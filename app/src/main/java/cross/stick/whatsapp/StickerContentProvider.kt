package cross.stick.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import cross.stick.data.local.PackStorage
import cross.stick.data.local.PreferencesManager
import java.io.File

class StickerContentProvider : ContentProvider() {

    companion object {
        const val PROVIDER_NAME = "cross.stick.stickercontentprovider"
        private const val TAG = "CrossStickProvider"

        private const val METADATA_CODE = 1
        private const val METADATA_SINGLE_CODE = 2
        private const val STICKERS_CODE = 3
        private const val STICKERS_ASSET_CODE = 4

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(PROVIDER_NAME, "metadata", METADATA_CODE)
            addURI(PROVIDER_NAME, "metadata/*", METADATA_SINGLE_CODE)
            addURI(PROVIDER_NAME, "stickers/*", STICKERS_CODE)
            addURI(PROVIDER_NAME, "stickers_asset/*/*", STICKERS_ASSET_CODE)
        }
    }

    private lateinit var storage: PackStorage
    private var publisherName: String = "CrossStick User"

    override fun onCreate(): Boolean {
        storage = PackStorage(requireNotNull(context))
        publisherName = context
            ?.getSharedPreferences(PreferencesManager.SYNC_PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(PreferencesManager.SYNC_AUTHOR_NAME, "CrossStick User")
            ?.takeIf { it.isNotBlank() }
            ?.take(128)
            ?: "CrossStick User"
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? {
        return when (matcher.match(uri)) {
            METADATA_CODE -> getMetadataCursor(uri, null)
            METADATA_SINGLE_CODE -> getMetadataCursor(uri, uri.lastPathSegment)
            STICKERS_CODE -> getStickersCursor(uri, uri.lastPathSegment)
            else -> { Log.w(TAG, "Unknown query: $uri"); null }
        }
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != STICKERS_ASSET_CODE || !mode.contains("r")) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        val file = resolveFile(packId, fileName)
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: Exception) {
            Log.e(TAG, "Asset open failed: $uri", e)
            null
        }
    }

    override fun openFile(uri: Uri, mode: String) = openAssetFile(uri, mode)?.parcelFileDescriptor

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        METADATA_CODE -> "vnd.android.cursor.dir/vnd.$PROVIDER_NAME.metadata"
        METADATA_SINGLE_CODE -> "vnd.android.cursor.item/vnd.$PROVIDER_NAME.metadata"
        STICKERS_CODE -> "vnd.android.cursor.dir/vnd.$PROVIDER_NAME.stickers"
        STICKERS_ASSET_CODE -> if (uri.lastPathSegment == "tray.png") "image/png" else "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, sa: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0

    private fun getMetadataCursor(uri: Uri, identifier: String?): Cursor {
        val columns = arrayOf(
            "sticker_pack_identifier","sticker_pack_name","sticker_pack_publisher",
            "sticker_pack_icon","android_play_store_link","ios_app_download_link",
            "sticker_pack_publisher_email","sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website","sticker_pack_license_agreement_website",
            "image_data_version","whatsapp_will_not_cache_stickers","animated_sticker_pack"
        )
        val cursor = MatrixCursor(columns)
        storage.loadPacks()
            .filter { identifier == null || it.identifier == identifier }
            .forEach { pack ->
                cursor.addRow(arrayOf(
                    pack.identifier,
                    pack.name.take(128),
                    pack.publisher.take(128),
                    pack.trayImageFile,
                    "", "", pack.publisherEmail.take(128),
                    pack.publisherWebsite.take(128),
                    pack.privacyPolicyWebsite.take(128),
                    pack.licenseAgreementWebsite.take(128),
                    pack.imageDataVersion,
                    if (pack.avoidCache) 1 else 0,
                    if (pack.animatedStickerPack) 1 else 0
                ))
            }
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    private fun getStickersCursor(uri: Uri, identifier: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name","sticker_emoji","sticker_accessibility_text"))
        val pack = storage.loadPacks().firstOrNull { it.identifier == identifier } ?: return cursor
        pack.stickers.forEachIndexed { idx, st ->
            cursor.addRow(arrayOf(
                st.imageFileName,
                st.emojis.take(3).joinToString(",") { it },
                st.accessibilityText.ifBlank { "Sticker ${idx + 1}" }
            ))
        }
        context?.contentResolver?.let { cursor.setNotificationUri(it, uri) }
        return cursor
    }

    private fun resolveFile(packId: String, fileName: String): File {
        return File(File(requireNotNull(context).filesDir, "stickers/converted"), "$packId/$fileName")
    }
}
