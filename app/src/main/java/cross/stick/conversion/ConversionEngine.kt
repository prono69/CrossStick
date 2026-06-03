package cross.stick.conversion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import java.io.File
import java.io.FileOutputStream

object ConversionEngine {

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
            writeWebp(scaledBitmap, outFile)

            scaledBitmap.recycle()
            bitmap.recycle()

            if (outFile.exists() && outFile.length() > 0) {
                Result.success(outFile)
            } else {
                Result.failure(Exception("Output file empty"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun scaleTo512Transparent(original: Bitmap): Bitmap {
        val size = 512
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val ratio = minOf(
            size.toFloat() / original.width,
            size.toFloat() / original.height
        )
        val newW = (original.width * ratio).toInt()
        val newH = (original.height * ratio).toInt()
        val scaled = Bitmap.createScaledBitmap(original, newW, newH, true)
        val left = (size - newW) / 2
        val top = (size - newH) / 2
        canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), null)
        scaled.recycle()
        return result
    }

    private fun writeWebp(bitmap: Bitmap, output: File) {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        var quality = 80
        do {
            FileOutputStream(output).use { fos ->
                bitmap.compress(format, quality, fos)
            }
            quality -= 10
        } while (output.length() > 100 * 1024 && quality >= 30)
    }

    fun createTrayFromFile(inputFile: File, outputDir: File): File {
        val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
        val tray = Bitmap.createScaledBitmap(bitmap, 96, 96, true)
        val trayFile = File(outputDir, "tray.png")
        FileOutputStream(trayFile).use { fos ->
            tray.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        tray.recycle()
        bitmap.recycle()
        return trayFile
    }
}
