package cross.stick.data.repository

import android.content.Context
import android.util.Log
import cross.stick.conversion.ConversionEngine
import cross.stick.data.local.PreferencesManager
import cross.stick.data.model.TelegramResponse
import cross.stick.data.model.TelegramSticker
import cross.stick.data.model.TelegramStickerSet
import cross.stick.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

class StickerRepository(context: Context) {
    private val prefs = PreferencesManager(context)
    private val filesDir = context.filesDir

    suspend fun fetchStickerSet(packName: String): Result<TelegramStickerSet> =
        withContext(Dispatchers.IO) {
            try {
                if (packName.isBlank()) return@withContext Result.failure(Exception("Enter a valid Telegram sticker pack link."))
                val token = getToken() ?: return@withContext Result.failure(Exception("Bot token is missing. Add it from Settings first."))
                val url = "https://api.telegram.org/bot$token/getStickerSet?name=$packName"
                Log.d("CrossStick", "Fetching sticker set: $packName")
                val response = RetrofitClient.api.getStickerSet(url)
                if (response.ok && response.result != null) {
                    Result.success(response.result)
                } else {
                    Result.failure(Exception(response.toUserMessage("Could not fetch the sticker pack.")))
                }
            } catch (e: Exception) {
                Log.e("CrossStick", "Fetch error", e)
                Result.failure(Exception(e.toUserMessage()))
            }
        }

    suspend fun downloadSticker(fileId: String, packId: String, index: Int): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken() ?: return@withContext Result.failure(Exception("Bot token is missing. Add it from Settings first."))
                val getFileUrl = "https://api.telegram.org/bot$token/getFile?file_id=$fileId"
                val fileResponse = RetrofitClient.api.getFile(getFileUrl)
                if (!fileResponse.ok) {
                    return@withContext Result.failure(Exception(fileResponse.toUserMessage("Could not prepare sticker download.")))
                }

                val filePath = fileResponse.result?.file_path
                    ?: return@withContext Result.failure(Exception("Telegram did not return a file path for this sticker."))
                val ext = filePath.substringAfterLast('.', "webp")
                val downloadUrl = "https://api.telegram.org/file/bot$token/$filePath"
                Log.d("CrossStick", "Downloading sticker ${index + 1}")
                val responseBody = RetrofitClient.api.downloadFile(downloadUrl)
                val rawDir = File(filesDir, "stickers/raw/$packId")
                if (!rawDir.exists()) rawDir.mkdirs()
                val outFile = File(rawDir, "sticker_$index.$ext")
                FileOutputStream(outFile).use { fos ->
                    responseBody.byteStream().copyTo(fos)
                }
                Log.d("CrossStick", "Downloaded sticker ${index + 1}: ${outFile.length()} bytes")
                Result.success(outFile)
            } catch (e: Exception) {
                Log.e("CrossStick", "Download error for sticker $index", e)
                Result.failure(Exception(e.toUserMessage()))
            }
        }

    /**
     * Downloads and converts a sticker to WhatsApp-compatible WebP format.
     * Automatically handles static (.webp), animated (.tgs), and video (.webm) stickers.
     *
     * @param sticker    The TelegramSticker object (contains type flags)
     * @param packId     Unique ID for the sticker pack (used for directory naming)
     * @param index      Index of the sticker in the pack
     * @param outputDir  Directory where the converted .webp should be saved
     * @return           Result with the converted File on success
     */
    suspend fun downloadAndConvertSticker(
        sticker: TelegramSticker,
        packId: String,
        index: Int,
        outputDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        // Step 1: Download raw file from Telegram
        val downloadResult = downloadSticker(sticker.file_id, packId, index)
        if (downloadResult.isFailure) return@withContext downloadResult

        val rawFile = downloadResult.getOrThrow()
        val outputName = "sticker_$index.webp"
        val tempDir = File(filesDir, "stickers/temp/$packId")
        if (!tempDir.exists()) tempDir.mkdirs()
        if (!outputDir.exists()) outputDir.mkdirs()

        // Step 2: Convert based on sticker type
        val conversionResult = when {
            sticker.is_video -> {
                // .webm VP9 video sticker → animated WebP
                Log.d("CrossStick", "Converting video sticker $index (.webm → animated .webp)")
                ConversionEngine.convertToWhatsAppVideo(rawFile, outputDir, outputName)
            }
            sticker.is_animated -> {
                // .tgs Lottie sticker → animated WebP
                Log.d("CrossStick", "Converting animated sticker $index (.tgs → animated .webp)")
                ConversionEngine.convertToWhatsAppAnimated(rawFile, outputDir, outputName, tempDir)
            }
            else -> {
                // .webp static sticker → scaled/validated WebP
                Log.d("CrossStick", "Converting static sticker $index (.webp → .webp)")
                ConversionEngine.convertToWhatsAppStatic(rawFile, outputDir, outputName)
            }
        }

        // Cleanup raw file after conversion
        rawFile.delete()
        tempDir.deleteRecursively()

        conversionResult
    }

    fun extractPackName(input: String): String {
        return input
            .trim()
            .removePrefix("https://t.me/addstickers/")
            .removePrefix("http://t.me/addstickers/")
            .removePrefix("t.me/addstickers/")
            .removePrefix("@")
            .trimEnd('/')
            .substringBefore('?')
            .substringBefore('#')
    }

    private suspend fun getToken(): String? {
        val token = prefs.botToken.first()
        return token.ifBlank { null }
    }

    private fun <T> TelegramResponse<T>.toUserMessage(fallback: String): String {
        parameters?.retry_after?.let { seconds ->
            return "Telegram rate limit hit. Try again in $seconds seconds."
        }
        return when (error_code) {
            400 -> "Telegram could not find this sticker pack. Check the link or pack name."
            401 -> "Telegram bot token is invalid. Update the token in Settings."
            404 -> "Telegram file was not found. Try importing the pack again."
            429 -> "Telegram rate limit hit. Wait a bit and try again."
            else -> description?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private fun Exception.toUserMessage(): String {
        return when (this) {
            is HttpException -> when (code()) {
                401 -> "Telegram bot token is invalid. Update the token in Settings."
                404 -> "Telegram endpoint or file was not found."
                429 -> "Telegram rate limit hit. Wait a bit and try again."
                else -> "Telegram request failed with HTTP ${code()}."
            }
            is SocketTimeoutException -> "Network timed out while talking to Telegram. Check your connection and try again."
            is IOException -> "Network error. Check your connection and try again."
            else -> message ?: "Unexpected Telegram error."
        }
    }
}
