package cross.stick.data.repository

import android.content.Context
import cross.stick.data.local.PreferencesManager
import cross.stick.data.model.TelegramFile
import cross.stick.data.model.TelegramSticker
import cross.stick.data.model.TelegramStickerSet
import cross.stick.data.remote.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class StickerRepository(context: Context) {
    private val prefs = PreferencesManager(context)
    private val filesDir = context.filesDir

    suspend fun fetchStickerSet(packName: String): Result<TelegramStickerSet> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken() ?: return@withContext Result.failure(Exception("Bot token not set"))
                val url = "https://api.telegram.org/bot$token/getStickerSet?name=$packName"
                val response = RetrofitClient.api.getStickerSet(url)
                if (response.ok && response.result != null) {
                    Result.success(response.result)
                } else {
                    Result.failure(Exception(response.description ?: "Unknown error"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun downloadSticker(fileId: String, packId: String, index: Int): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val token = getToken() ?: return@withContext Result.failure(Exception("Bot token not set"))
                // Get file path
                val getFileUrl = "https://api.telegram.org/bot$token/getFile?file_id=$fileId"
                val fileResponse = RetrofitClient.api.getFile(getFileUrl)
                val filePath = fileResponse.result?.file_path
                    ?: return@withContext Result.failure(Exception("Could not get file path"))
                // Determine extension
                val ext = filePath.substringAfterLast('.', "webp")
                val downloadUrl = "https://api.telegram.org/file/bot$token/$filePath"
                val responseBody = RetrofitClient.api.downloadFile(downloadUrl)
                // Save to raw directory
                val rawDir = File(filesDir, "stickers/raw/$packId")
                if (!rawDir.exists()) rawDir.mkdirs()
                val outFile = File(rawDir, "sticker_$index.$ext")
                FileOutputStream(outFile).use { fos ->
                    responseBody.byteStream().copyTo(fos)
                }
                Result.success(outFile)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun extractPackName(input: String): String {
        return input
            .replace("https://t.me/addstickers/", "")
            .replace("t.me/addstickers/", "")
            .trimEnd('/')
    }

    private suspend fun getToken(): String? {
        var token = ""
        prefs.botToken.collect { token = it }
        return token.ifBlank { null }
    }
}
