package cross.stick.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

object ConversionEngine {

    private const val TAG = "ConversionEngine"
    private const val MAX_STATIC_STICKER_BYTES = 100 * 1024
    private const val MAX_ANIMATED_STICKER_BYTES = 500 * 1024

    // ─── Static ──────────────────────────────────────────────────────────────

    fun convertToWhatsAppStatic(
        inputFile: File,
        outputDir: File,
        outputName: String
    ): Result<File> {
        return try {
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: return Result.failure(Exception("Cannot decode bitmap"))

            val scaledBitmap = scaleTo512Transparent(bitmap)
            val outFile = File(outputDir, outputName)
            val success = writeWebp(scaledBitmap, outFile)

            scaledBitmap.recycle()
            bitmap.recycle()

            if (success && validateStaticSticker(outFile)) {
                Result.success(outFile)
            } else {
                outFile.delete()
                Result.failure(Exception("Sticker validation failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun validateStaticSticker(file: File): Boolean {
        if (!file.exists() || file.length() !in 1..MAX_STATIC_STICKER_BYTES) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth == 512 && opts.outHeight == 512
    }

    // ─── Animated (.tgs → animated WebP) ─────────────────────────────────────

    /**
     * Converts a Telegram animated sticker (.tgs = gzipped Lottie JSON)
     * to an animated WebP suitable for WhatsApp.
     *
     * Flow: .tgs → decompress → .json → FFmpeg (via lottie render workaround)
     * Since FFmpeg can't render Lottie directly, we first decompress .tgs to
     * a raw .json, render frames using LottieDrawable off-screen, save as
     * a PNG sequence, then encode to animated WebP with FFmpeg.
     */
    fun convertToWhatsAppAnimated(
        inputFile: File,
        outputDir: File,
        outputName: String,
        tempDir: File
    ): Result<File> {
        return try {
            // Step 1: Decompress .tgs → .json
            val lottieJson = decompressTgs(inputFile)
                ?: return Result.failure(Exception("Failed to decompress .tgs file"))

            val jsonFile = File(tempDir, "${inputFile.nameWithoutExtension}.json")
            jsonFile.writeText(lottieJson)

            // Step 2: Render Lottie frames to PNG sequence
            val framesDir = File(tempDir, "frames_${inputFile.nameWithoutExtension}")
            framesDir.mkdirs()

            val frameCount = renderLottieFrames(lottieJson, framesDir)
            if (frameCount <= 0) {
                return Result.failure(Exception("Failed to render Lottie frames"))
            }

            // Step 3: Encode PNG sequence → animated WebP via FFmpeg
            val outFile = File(outputDir, outputName)
            val ffmpegResult = encodeFramesToAnimatedWebP(framesDir, outFile, frameCount)

            // Cleanup temp frames
            framesDir.deleteRecursively()
            jsonFile.delete()

            if (ffmpegResult && validateAnimatedSticker(outFile)) {
                Result.success(outFile)
            } else {
                outFile.delete()
                Result.failure(Exception("Animated sticker conversion or validation failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Animated conversion error", e)
            Result.failure(e)
        }
    }

    // ─── Video (.webm → animated WebP) ───────────────────────────────────────

    /**
     * Converts a Telegram video sticker (.webm VP9)
     * to an animated WebP suitable for WhatsApp.
     *
     * WhatsApp limits: 512x512, max 3 seconds, max 100 frames, max 500KB.
     */
    fun convertToWhatsAppVideo(
        inputFile: File,
        outputDir: File,
        outputName: String
    ): Result<File> {
        return try {
            val outFile = File(outputDir, outputName)

            // FFmpeg: scale to 512x512, limit to 3s, 30fps max, encode as animated WebP
            val cmd = buildString {
                append("-y ")                                   // overwrite output
                append("-t 3 ")                                 // max 3 seconds
                append("-i \"${inputFile.absolutePath}\" ")
                append("-vf \"scale=512:512:force_original_aspect_ratio=decrease,")
                append("pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000,")
                append("fps=fps=30\" ")
                append("-vcodec libwebp ")
                append("-lossless 0 ")
                append("-q:v 70 ")
                append("-loop 0 ")                             // loop forever
                append("-preset default ")
                append("-an ")                                  // no audio
                append("-vsync 0 ")
                append("\"${outFile.absolutePath}\"")
            }

            Log.d(TAG, "FFmpeg video cmd: $cmd")
            val session = FFmpegKit.execute(cmd)

            if (!ReturnCode.isSuccess(session.returnCode)) {
                Log.e(TAG, "FFmpeg failed: ${session.allLogsAsString}")
                return Result.failure(Exception("FFmpeg video conversion failed"))
            }

            // If output is still too large, re-encode with lower quality
            if (outFile.exists() && outFile.length() > MAX_ANIMATED_STICKER_BYTES) {
                val reencoded = reencodeWithLowerQuality(outFile)
                if (!reencoded) {
                    outFile.delete()
                    return Result.failure(Exception("Animated sticker too large even after compression"))
                }
            }

            if (validateAnimatedSticker(outFile)) {
                Result.success(outFile)
            } else {
                outFile.delete()
                Result.failure(Exception("Video sticker validation failed"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video conversion error", e)
            Result.failure(e)
        }
    }

    fun validateAnimatedSticker(file: File): Boolean {
        return file.exists() && file.length() in 1..MAX_ANIMATED_STICKER_BYTES
    }

    // ─── Tray ─────────────────────────────────────────────────────────────────

    fun createTrayFromFile(inputFile: File, outputDir: File): Result<File> {
        return try {
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: return Result.failure(Exception("Cannot decode tray source"))
            val tray = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
            val trayFile = File(outputDir, "tray.png")
            if (!tryWriteTrayPng(tray, trayFile)) {
                tray.recycle()
                bitmap.recycle()
                return Result.failure(Exception("Tray too large"))
            }
            tray.recycle()
            bitmap.recycle()
            Result.success(trayFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun validateTray(file: File): Boolean {
        if (!file.exists() || file.length() !in 1..(50 * 1024)) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth == 96 && opts.outHeight == 96
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun scaleTo512Transparent(original: Bitmap): Bitmap {
        val size = 512
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val ratio = minOf(size.toFloat() / original.width, size.toFloat() / original.height)
        val newW = (original.width * ratio).toInt().coerceAtLeast(1)
        val newH = (original.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(original, newW, newH, true)
        val left = (size - newW) / 2
        val top = (size - newH) / 2
        canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
        scaled.recycle()
        return result
    }

    private fun writeWebp(bitmap: Bitmap, output: File): Boolean {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        var quality = 80
        while (quality >= 10) {
            FileOutputStream(output).use { fos -> bitmap.compress(format, quality, fos) }
            if (output.length() in 1..MAX_STATIC_STICKER_BYTES) return true
            quality -= 10
        }
        output.delete()
        return false
    }

    private fun tryWriteTrayPng(tray: Bitmap, output: File): Boolean {
        FileOutputStream(output).use { fos -> tray.compress(Bitmap.CompressFormat.PNG, 100, fos) }
        if (output.length() in 1..(50 * 1024)) return true
        output.delete()
        return false
    }

    /**
     * Decompresses a .tgs file (gzip) and returns Lottie JSON string.
     */
    private fun decompressTgs(tgsFile: File): String? {
        return try {
            GZIPInputStream(tgsFile.inputStream()).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress .tgs", e)
            null
        }
    }

    /**
     * Renders Lottie JSON frames to PNG files in framesDir.
     * Uses LottieDrawable to draw each frame off-screen onto a Canvas.
     * Returns the number of frames rendered.
     */
    private fun renderLottieFrames(lottieJson: String, framesDir: File): Int {
        return try {
            val composition = com.airbnb.lottie.LottieComposition.Factory
                .fromJsonSync(android.content.res.Resources.getSystem(), lottieJson)
                ?: return 0

            val drawable = com.airbnb.lottie.LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, 512, 512)
            }

            // WhatsApp cap: 100 frames max, 3 seconds max
            val totalFrames = composition.endFrame.toInt()
            val frameStep = if (totalFrames > 100) totalFrames / 100 else 1
            var frameCount = 0

            var frame = 0
            while (frame <= totalFrames && frameCount < 100) {
                drawable.frame = frame
                val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.draw(canvas)

                val frameFile = File(framesDir, "frame_%04d.png".format(frameCount))
                FileOutputStream(frameFile).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                bmp.recycle()
                frameCount++
                frame += frameStep
            }

            Log.d(TAG, "Rendered $frameCount Lottie frames")
            frameCount
        } catch (e: Exception) {
            Log.e(TAG, "Lottie render error", e)
            0
        }
    }

    /**
     * Encodes a PNG frame sequence into an animated WebP using FFmpeg.
     */
    private fun encodeFramesToAnimatedWebP(
        framesDir: File,
        outFile: File,
        frameCount: Int
    ): Boolean {
        // Default to 60fps total for smooth animation (Lottie is usually 60fps)
        val cmd = buildString {
            append("-y ")
            append("-framerate 60 ")
            append("-i \"${framesDir.absolutePath}/frame_%04d.png\" ")
            append("-vcodec libwebp ")
            append("-lossless 0 ")
            append("-q:v 70 ")
            append("-loop 0 ")
            append("-preset default ")
            append("-an ")
            append("-vsync 0 ")
            append("\"${outFile.absolutePath}\"")
        }

        Log.d(TAG, "FFmpeg animated cmd: $cmd")
        val session = FFmpegKit.execute(cmd)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e(TAG, "FFmpeg frames encode failed: ${session.allLogsAsString}")
            return false
        }

        // Re-encode at lower quality if too large
        if (outFile.exists() && outFile.length() > MAX_ANIMATED_STICKER_BYTES) {
            return reencodeWithLowerQuality(outFile)
        }

        return outFile.exists() && outFile.length() > 0
    }

    /**
     * Re-encodes an existing animated WebP at lower quality to meet the 500KB limit.
     */
    private fun reencodeWithLowerQuality(file: File): Boolean {
        val tempFile = File(file.parent, "temp_${file.name}")
        file.copyTo(tempFile, overwrite = true)

        for (quality in listOf(50, 30, 10)) {
            val cmd = buildString {
                append("-y ")
                append("-i \"${tempFile.absolutePath}\" ")
                append("-vcodec libwebp ")
                append("-lossless 0 ")
                append("-q:v $quality ")
                append("-loop 0 ")
                append("-preset default ")
                append("-an ")
                append("-vsync 0 ")
                append("\"${file.absolutePath}\"")
            }
            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode) &&
                file.exists() && file.length() in 1..MAX_ANIMATED_STICKER_BYTES
            ) {
                tempFile.delete()
                Log.d(TAG, "Re-encoded at quality $quality → ${file.length()} bytes")
                return true
            }
        }

        tempFile.delete()
        return false
    }
}
