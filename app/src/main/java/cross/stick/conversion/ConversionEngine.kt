package cross.stick.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.Log
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

object ConversionEngine {

    private const val TAG = "ConversionEngine"
    private const val MAX_STATIC_STICKER_BYTES = 100 * 1024
    private const val MAX_ANIMATED_STICKER_BYTES = 500 * 1024

    // ─── Static ───────────────────────────────────────────────────────────────

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

    fun convertToWhatsAppAnimated(
        inputFile: File,
        outputDir: File,
        outputName: String,
        tempDir: File
    ): Result<File> {
        return try {
            val lottieJson = decompressTgs(inputFile)
                ?: return Result.failure(Exception("Failed to decompress .tgs file"))

            val jsonFile = File(tempDir, "${inputFile.nameWithoutExtension}.json")
            jsonFile.writeText(lottieJson)

            val framesDir = File(tempDir, "frames_${inputFile.nameWithoutExtension}")
            framesDir.mkdirs()

            val frameCount = renderLottieFrames(lottieJson, framesDir)
            if (frameCount <= 0) {
                return Result.failure(Exception("Failed to render Lottie frames"))
            }

            val outFile = File(outputDir, outputName)
            val ffmpegResult = encodeFramesToAnimatedWebP(framesDir, outFile, frameCount)

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

    fun convertToWhatsAppVideo(
        inputFile: File,
        outputDir: File,
        outputName: String
    ): Result<File> {
        return try {
            val outFile = File(outputDir, outputName)

            // No quoted paths — FFmpeg Kit on Android is not a shell, quotes break path parsing
            val cmd = "-y " +
                    "-t 3 " +
                    "-i ${inputFile.absolutePath} " +
                    "-vf scale=512:512:force_original_aspect_ratio=decrease," +
                    "pad=512:512:(ow-iw)/2:(oh-ih)/2:color=0x00000000," +
                    "fps=fps=30 " +
                    "-vcodec libwebp " +
                    "-lossless 0 " +
                    "-q:v 70 " +
                    "-loop 0 " +
                    "-preset default " +
                    "-an " +
                    "-vsync 0 " +
                    outFile.absolutePath

            Log.d(TAG, "FFmpeg video cmd: $cmd")
            val session = FFmpegKit.execute(cmd)

            if (!ReturnCode.isSuccess(session.returnCode)) {
                Log.e(TAG, "FFmpeg failed: ${session.allLogsAsString}")
                return Result.failure(Exception("FFmpeg video conversion failed"))
            }

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
            // For .tgs/.webm files BitmapFactory returns null — fall back to blank tray
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)

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

    private fun decompressTgs(tgsFile: File): String? {
        return try {
            GZIPInputStream(tgsFile.inputStream()).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress .tgs", e)
            null
        }
    }

    private fun renderLottieFrames(lottieJson: String, framesDir: File): Int {
        return try {
            val result = LottieCompositionFactory.fromJsonStringSync(lottieJson, null)
            val composition = result.value ?: return 0

            val drawable = LottieDrawable().apply {
                setComposition(composition)
                setBounds(0, 0, 512, 512)
            }

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

    private fun encodeFramesToAnimatedWebP(
        framesDir: File,
        outFile: File,
        frameCount: Int
    ): Boolean {
        val cmd = "-y " +
                "-framerate 60 " +
                "-i ${framesDir.absolutePath}/frame_%04d.png " +
                "-vcodec libwebp " +
                "-lossless 0 " +
                "-q:v 70 " +
                "-loop 0 " +
                "-preset default " +
                "-an " +
                "-vsync 0 " +
                outFile.absolutePath

        Log.d(TAG, "FFmpeg animated cmd: $cmd")
        val session = FFmpegKit.execute(cmd)

        if (!ReturnCode.isSuccess(session.returnCode)) {
            Log.e(TAG, "FFmpeg frames encode failed: ${session.allLogsAsString}")
            return false
        }

        if (outFile.exists() && outFile.length() > MAX_ANIMATED_STICKER_BYTES) {
            return reencodeWithLowerQuality(outFile)
        }

        return outFile.exists() && outFile.length() > 0
    }

    private fun reencodeWithLowerQuality(file: File): Boolean {
        val tempFile = File(file.parent, "temp_${file.name}")
        file.copyTo(tempFile, overwrite = true)

        for (quality in listOf(50, 30, 10)) {
            val cmd = "-y " +
                    "-i ${tempFile.absolutePath} " +
                    "-vcodec libwebp " +
                    "-lossless 0 " +
                    "-q:v $quality " +
                    "-loop 0 " +
                    "-preset default " +
                    "-an " +
                    "-vsync 0 " +
                    file.absolutePath

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
