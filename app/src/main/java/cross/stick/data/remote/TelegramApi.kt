package cross.stick.data.remote

import cross.stick.data.model.TelegramFile
import cross.stick.data.model.TelegramResponse
import cross.stick.data.model.TelegramStickerSet
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

interface TelegramApi {
    @GET
    suspend fun getStickerSet(@Url url: String): TelegramResponse<TelegramStickerSet>

    @GET
    suspend fun getFile(@Url url: String): TelegramResponse<TelegramFile>

    @Streaming
    @GET
    suspend fun downloadFile(@Url url: String): ResponseBody
}
