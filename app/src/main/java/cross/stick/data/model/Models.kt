package cross.stick.data.model

data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T?,
    val description: String?
)

data class TelegramStickerSet(
    val name: String,
    val title: String,
    val is_animated: Boolean = false,
    val is_video: Boolean = false,
    val stickers: List<TelegramSticker>
)

data class TelegramSticker(
    val file_id: String,
    val file_unique_id: String,
    val width: Int = 512,
    val height: Int = 512,
    val is_animated: Boolean = false,
    val is_video: Boolean = false,
    val emoji: String? = ""
)

data class TelegramFile(
    val file_id: String,
    val file_unique_id: String,
    val file_size: Int? = 0,
    val file_path: String? = null
)
