package cross.stick.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.BaseColumns
import java.io.File

class StickerContentProvider : ContentProvider() {

    companion object {
        private const val METADATA = 1
        private const val METADATA_ID = 2
        private const val STICKERS = 3
        private const val STICKER_ASSET = 4
        private const val TRAY_ASSET = 5

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI("${getAuthorityStatic()}", "metadata", METADATA)
            addURI("${getAuthorityStatic()}", "metadata/*", METADATA_ID)
            addURI("${getAuthorityStatic()}", "stickers/*", STICKERS)
            addURI("${getAuthorityStatic()}", "stickers_asset/*/*", STICKER_ASSET)
            addURI("${getAuthorityStatic()}", "stickers_asset/*/*", TRAY_ASSET)
        }

        private fun getAuthorityStatic(): String = "cross.stick.stickercontentprovider"
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
            METADATA -> getMetadataCursor()
            METADATA_ID -> getMetadataForIdCursor(uri.lastPathSegment)
            STICKERS -> getStickersCursor(uri.lastPathSegment)
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return when (uriMatcher.match(uri)) {
            STICKER_ASSET, TRAY_ASSET -> {
                val segments = uri.pathSegments
                val packId = segments[1]
                val fileName = segments[2]
                val file = getFileForPack(packId, fileName)
                if (file.exists()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            }
            else -> null
        }
    }

    private fun getMetadataCursor(): Cursor? {
        val packsDir = File(context?.filesDir, "stickers/converted")
        if (!packsDir.exists()) return null

        val columns = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "sticker_pack_size",
            "animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)
        packsDir.listFiles()?.forEach { packDir ->
            if (packDir.isDirectory) {
                val stickerFiles = packDir.listFiles { f -> f.extension == "webp" }
                cursor.addRow(arrayOf(
                    packDir.name,
                    packDir.name,
                    "CrossStick User",
                    "tray",
                    stickerFiles?.size ?: 0,
                    false
                ))
            }
        }
        return cursor
    }

    private fun getMetadataForIdCursor(packId: String?): Cursor? {
        val packsDir = File(context?.filesDir, "stickers/converted")
        val packDir = File(packsDir, packId ?: return null)
        if (!packDir.exists()) return null

        val stickerFiles = packDir.listFiles { f -> f.extension == "webp" }
        val columns = arrayOf(
            "sticker_pack_identifier",
            "sticker_pack_name",
            "sticker_pack_publisher",
            "sticker_pack_icon",
            "sticker_pack_size",
            "animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf(
            packId,
            packId,
            "CrossStick User",
            "tray",
            stickerFiles?.size ?: 0,
            false
        ))
        return cursor
    }

    private fun getStickersCursor(packId: String?): Cursor? {
        val packsDir = File(context?.filesDir, "stickers/converted")
        val packDir = File(packsDir, packId ?: return null)
        if (!packDir.exists()) return null

        val columns = arrayOf("sticker_file_name")
        val cursor = MatrixCursor(columns)
        packDir.listFiles()?.forEach { file ->
            if (file.extension == "webp") {
                cursor.addRow(arrayOf(file.name))
            }
        }
        return cursor
    }

    private fun getFileForPack(packId: String?, fileName: String?): File {
        val packsDir = File(context?.filesDir, "stickers/converted")
        return File(File(packsDir, packId ?: ""), fileName ?: "missing.webp")
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
