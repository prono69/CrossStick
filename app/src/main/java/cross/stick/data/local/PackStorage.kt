package cross.stick.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String,
    val stickers: List<Sticker>,
    val publisherEmail: String = "",
    val publisherWebsite: String = "",
    val privacyPolicyWebsite: String = "",
    val licenseAgreementWebsite: String = "",
    val imageDataVersion: String = "1",
    val avoidCache: Boolean = false,
    val animatedStickerPack: Boolean = false
)

data class Sticker(
    val imageFileName: String,
    val emojis: List<String> = listOf("😀"),
    val accessibilityText: String = "",
    val rawFilePath: String = "",
    val convertedFilePath: String = ""
)

class PackStorage(private val context: Context) {
    private val gson = Gson()
    private val packsDir get() = File(context.filesDir, "sticker_packs").also { it.mkdirs() }
    private val indexFile get() = File(packsDir, "packs_index.json")

    fun loadPacks(): List<StickerPack> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val json = indexFile.readText()
            val type = object : TypeToken<List<StickerPack>>() {}.type
            gson.fromJson<List<StickerPack>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePacks(packs: List<StickerPack>) {
        indexFile.writeText(gson.toJson(packs))
    }

    fun savePack(pack: StickerPack) {
        val current = loadPacks().toMutableList()
        val idx = current.indexOfFirst { it.identifier == pack.identifier }
        if (idx != -1) current[idx] = pack else current.add(pack)
        savePacks(current)
    }

    fun deletePack(identifier: String) {
        val current = loadPacks().filter { it.identifier != identifier }
        savePacks(current)
        File(context.filesDir, "stickers/converted/$identifier").deleteRecursively()
        File(context.filesDir, "stickers/raw/$identifier").deleteRecursively()
    }

    fun clearAll() {
        File(context.filesDir, "sticker_packs").deleteRecursively()
        File(context.filesDir, "stickers").deleteRecursively()
    }
}
