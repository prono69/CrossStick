package cross.stick.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

class StickerContentProvider : ContentProvider() {

    companion object {
        private const val PROVIDER_NAME = "cross.stick.stickercontentprovider"
        private const val METADATA = 1
        private const val METADATA_ID = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(PROVIDER_NAME, "metadata", METADATA)
            addURI(PROVIDER_NAME, "metadata/*", METADATA_ID)
            addURI(PROVIDER_NAME, "stickers/*", STICKERS)
            addURI(PROVIDER_NAME, "stickers_asset/*/*", STICKERS_ASSET)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            METADATA -> getMetadataCursor(null)
            METADATA_ID -> getMetadataCursor(uri.lastPathSegment)
            STICKERS -> getStickersCursor(uri.lastPathSegment)
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uriMatcher.match(uri) != STICKERS_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size < 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        val file = resolveFile(packId, fileName)
        return if (file.exists()) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else null
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun getMetadataCursor(packId: String?): Cursor? {
        val packsDir = File(context?.filesDir, "stickers/converted") ?: return null
        val columns = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "android_play_store_link",
            "ios_app_download_link",
            "sticker_pack_publisher_email",
            "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website",
            "sticker_pack_license_agreement_website",
            "image_data_version",
            "avoid_cache",
            "animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)

        packsDir.listFiles()?.filter { it.isDirectory }?.forEach { packDir ->
            if (packId != null && packDir.name != packId) return@forEach
            val stickerCount = packDir.listFiles { f -> f.extension == "webp" }?.size ?: 0
            if (stickerCount < 3) return@forEach
            cursor.addRow(arrayOf(
                packDir.name,        // identifier
                packDir.name,        // name
                "CrossStick User",   // publisher
                "tray",              // tray image file name (tray.png)
                "",                  // play store link
                "",                  // ios link
                "",                  // email
                "",                  // website
                "",                  // privacy
                "",                  // license
                "1",                 // image_data_version
                0,                   // avoid_cache
                0                    // animated_sticker_pack (0 = static)
            ))
        }
        return cursor
    }

    private fun getStickersCursor(packId: String?): Cursor? {
        val packDir = File(context?.filesDir, "stickers/converted/$packId")
        if (!packDir.exists()) return null
        val columns = arrayOf("sticker_file_name", "sticker_emoji")
        val cursor = MatrixCursor(columns)
        packDir.listFiles()?.filter { it.extension == "webp" }?.forEach { file ->
            cursor.addRow(arrayOf(file.name, "😀"))
        }
        return cursor
    }

    private fun resolveFile(packId: String?, fileName: String?): File {
        val convertedDir = File(context?.filesDir, "stickers/converted") ?: return File("/")
        if (fileName == "tray") {
            return File(convertedDir, "$packId/tray.png")
        }
        return File(convertedDir, "$packId/$fileName")
    }
}
