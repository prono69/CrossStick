package cross.stick.conversion

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ConversionEngine {

    private const val MAX_STATIC_STICKER_BYTES = 100 * 1024

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

    private fun tryWriteTrayPng(tray: Bitmap, output: File): Boolean {
        FileOutputStream(output).use { fos -> tray.compress(Bitmap.CompressFormat.PNG, 100, fos) }
        if (output.length() in 1..(50 * 1024)) return true
        output.delete()
        return false
    }

    fun validateTray(file: File): Boolean {
        if (!file.exists() || file.length() !in 1..(50 * 1024)) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth == 96 && opts.outHeight == 96
    }
}
