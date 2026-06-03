package cross.stick.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cross.stick.conversion.ConversionEngine
import cross.stick.data.export.TelegramStickerExporter
import cross.stick.data.importer.UniversalStickerPack
import cross.stick.data.local.PackStorage
import cross.stick.data.local.PreferencesManager
import cross.stick.data.local.Sticker
import cross.stick.data.local.StickerPack
import cross.stick.data.model.TelegramStickerSet
import cross.stick.data.repository.StickerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val MAX_WHATSAPP_STICKERS = 60
private const val MIN_WHATSAPP_STICKERS = 3
private const val TELEGRAM_CREATE_STICKER_PACK_ACTION = "org.telegram.messenger.CREATE_STICKER_PACK"
private const val TELEGRAM_STICKER_EMOJIS_EXTRA = "STICKER_EMOJIS"
private const val TELEGRAM_IMPORTER_EXTRA = "IMPORTER"

data class SavedPack(val id: String, val name: String, val stickerCount: Int, val path: File)
data class PreviewSticker(val file: File, val emoji: String = "😀")

sealed class ImportPhase {
    object Idle : ImportPhase()
    object Fetching : ImportPhase()
    data class Downloading(val current: Int, val total: Int) : ImportPhase()
    object PreviewReady : ImportPhase()
    data class Converting(val current: Int, val total: Int) : ImportPhase()
    data class Done(val packId: String) : ImportPhase()
    data class Failed(val error: String) : ImportPhase()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repository = StickerRepository(application)
    private val packStorage = PackStorage(application)

    val botToken: StateFlow<String> = prefs.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val authorName: StateFlow<String> = prefs.authorName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val onboardingComplete: StateFlow<Boolean> = prefs.onboardingComplete.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _phase = MutableStateFlow<ImportPhase>(ImportPhase.Idle)
    val phase: StateFlow<ImportPhase> = _phase.asStateFlow()

    private val _currentPackId = MutableStateFlow<String?>(null)
    val currentPackId: StateFlow<String?> = _currentPackId.asStateFlow()

    private val _previewStickers = MutableStateFlow<List<PreviewSticker>>(emptyList())
    val previewStickers: StateFlow<List<PreviewSticker>> = _previewStickers.asStateFlow()

    private val _savedPacks = MutableStateFlow<List<SavedPack>>(emptyList())
    val savedPacks: StateFlow<List<SavedPack>> = _savedPacks.asStateFlow()

    private var lastStickerSetTitle: String? = null

    init {
        viewModelScope.launch { onboardingComplete.collect { _isReady.value = true } }
        loadSavedPacks()
    }

    private fun loadSavedPacks() {
        val packs = packStorage.loadPacks()
        _savedPacks.value = packs.map { pack ->
            SavedPack(
                id = pack.identifier,
                name = pack.name,
                stickerCount = pack.stickers.size,
                path = File(getApplication<Application>().filesDir, "stickers/converted/${pack.identifier}")
            )
        }
    }

    fun completeOnboarding(token: String, author: String) {
        viewModelScope.launch {
            prefs.saveBotToken(token)
            prefs.saveAuthorName(author)
            prefs.completeOnboarding()
        }
    }

    fun updateSettings(token: String, author: String) {
        viewModelScope.launch {
            prefs.saveBotToken(token)
            prefs.saveAuthorName(author)
            loadSavedPacks()
        }
    }

    fun fetchStickerSet(link: String) {
        viewModelScope.launch {
            _phase.value = ImportPhase.Fetching
            _previewStickers.value = emptyList()
            val packName = repository.extractPackName(link)
            val result = repository.fetchStickerSet(packName)
            result.fold(
                onSuccess = { stickerSet ->
                    lastStickerSetTitle = stickerSet.title
                    _currentPackId.value = stickerSet.name.sanitizePackId()
                    downloadForPreview(stickerSet, _currentPackId.value!!)
                },
                onFailure = { e -> _phase.value = ImportPhase.Failed(e.message ?: "Could not fetch stickers") }
            )
        }
    }

    private suspend fun downloadForPreview(stickerSet: TelegramStickerSet, packId: String) {
        val staticStickers = stickerSet.stickers.filterNot { it.is_animated || it.is_video }.take(MAX_WHATSAPP_STICKERS)
        if (staticStickers.size < MIN_WHATSAPP_STICKERS) {
            _phase.value = ImportPhase.Failed("This pack has fewer than 3 static stickers.")
            return
        }
        val files = mutableListOf<PreviewSticker>()
        val rawDir = File(getApplication<Application>().filesDir, "stickers/raw/$packId")
        if (rawDir.exists()) rawDir.deleteRecursively()
        rawDir.mkdirs()
        staticStickers.forEachIndexed { index, sticker ->
            _phase.value = ImportPhase.Downloading(index + 1, staticStickers.size)
            repository.downloadSticker(sticker.file_id, packId, index).fold(
                onSuccess = { file -> files.add(PreviewSticker(file = file, emoji = sticker.emoji?.takeIf { it.isNotBlank() } ?: "😀")) },
                onFailure = { Log.e("CrossStick", "Failed sticker $index: ${it.message}") }
            )
        }
        _previewStickers.value = files
        _phase.value = if (files.size >= MIN_WHATSAPP_STICKERS) ImportPhase.PreviewReady
        else ImportPhase.Failed("Only ${files.size} stickers downloaded.")
    }

    fun removePreviewSticker(index: Int) {
        val current = _previewStickers.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        _previewStickers.value = current
    }

    fun addPreviewUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val packId = _currentPackId.value ?: "manual_${System.currentTimeMillis()}"
            _currentPackId.value = packId
            val copied = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { index, uri -> copyUriToRawPreview(uri, packId, index) }
            }
            if (copied.isNotEmpty()) {
                _previewStickers.value = (_previewStickers.value + copied).take(MAX_WHATSAPP_STICKERS)
                _phase.value = ImportPhase.PreviewReady
            }
        }
    }

    private fun copyUriToRawPreview(uri: Uri, packId: String, index: Int): PreviewSticker? {
        val context = getApplication<Application>()
        return try {
            val rawDir = File(context.filesDir, "stickers/raw/$packId")
            if (!rawDir.exists()) rawDir.mkdirs()
            val outFile = File(rawDir, "added_${System.currentTimeMillis()}_$index.webp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            } ?: return null
            PreviewSticker(outFile, "😀")
        } catch (e: Exception) { Log.e("CrossStick", "Could not copy selected sticker", e); null }
    }

    fun convertPreviewToWhatsApp() {
        viewModelScope.launch {
            val packId = _currentPackId.value ?: "pack_${System.currentTimeMillis()}"
            val selected = _previewStickers.value.take(MAX_WHATSAPP_STICKERS)
            if (selected.size < MIN_WHATSAPP_STICKERS) {
                _phase.value = ImportPhase.Failed("Select at least 3 stickers before converting.")
                return@launch
            }
            val outputDir = File(getApplication<Application>().filesDir, "stickers/converted/$packId")
            withContext(Dispatchers.IO) {
                if (outputDir.exists()) outputDir.deleteRecursively()
                outputDir.mkdirs()
            }

            val validStickers = mutableListOf<Sticker>()
            selected.forEachIndexed { index, ps ->
                _phase.value = ImportPhase.Converting(index + 1, selected.size)
                val result = withContext(Dispatchers.Default) {
                    ConversionEngine.convertToWhatsAppStatic(
                        inputFile = ps.file,
                        outputDir = outputDir,
                        outputName = "sticker_${index.toString().padStart(2, '0')}.webp"
                    )
                }
                result.fold(
                    onSuccess = { file ->
                        validStickers.add(Sticker(
                            imageFileName = file.name,
                            emojis = listOf(ps.emoji.ifBlank { "😀" }),
                            accessibilityText = "Sticker ${index + 1}",
                            rawFilePath = ps.file.absolutePath,
                            convertedFilePath = file.absolutePath
                        ))
                    },
                    onFailure = { Log.e("CrossStick", "Conversion failed for sticker $index: ${it.message}") }
                )
            }

            if (validStickers.size < MIN_WHATSAPP_STICKERS) {
                _phase.value = ImportPhase.Failed("Only ${validStickers.size} valid stickers were created. WhatsApp requires at least 3.")
                return@launch
            }

            val trayResult = withContext(Dispatchers.Default) {
                ConversionEngine.createTrayFromFile(File(validStickers.first().rawFilePath), outputDir)
            }

            if (trayResult.isFailure || !ConversionEngine.validateTray(File(outputDir, "tray.png"))) {
                _phase.value = ImportPhase.Failed("Tray icon could not be generated under 50 KB.")
                return@launch
            }

            val pack = StickerPack(
                identifier = packId,
                name = lastStickerSetTitle?.take(128) ?: packId.take(128),
                publisher = authorName.value.ifBlank { "CrossStick User" }.take(128),
                trayImageFile = "tray.png",
                stickers = validStickers.take(MAX_WHATSAPP_STICKERS),
                imageDataVersion = System.currentTimeMillis().toString(),
                animatedStickerPack = false
            )

            packStorage.savePack(pack)
            _phase.value = ImportPhase.Done(packId)
            _previewStickers.value = emptyList()
            loadSavedPacks()
        }
    }

    fun getWhatsAppIntent(packId: String): Intent {
        return Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", packId)
            putExtra("sticker_pack_authority", "cross.stick.stickercontentprovider")
            putExtra("sticker_pack_name", packId.take(128))
            setPackage("com.whatsapp")
        }
    }

    fun validatePackForWhatsApp(packId: String): List<String> {
        val errors = mutableListOf<String>()
        val resolver = getApplication<Application>().contentResolver
        val authority = "cross.stick.stickercontentprovider"
        resolver.query(Uri.parse("content://$authority/metadata/$packId"), null, null, null, null).use { c ->
            if (c == null || !c.moveToFirst()) errors += "Provider metadata row missing for $packId"
        }
        resolver.query(Uri.parse("content://$authority/stickers/$packId"), arrayOf("sticker_file_name"), null, null, null).use { c ->
            if (c == null) errors += "Provider stickers cursor is null"
            else {
                val names = mutableListOf<String>()
                val col = c.getColumnIndexOrThrow("sticker_file_name")
                while (c.moveToNext()) names += c.getString(col)
                if (names.size !in 3..60) errors += "Sticker count must be 3..60, got ${names.size}"
                names.forEach { name ->
                    try {
                        resolver.openAssetFileDescriptor(Uri.parse("content://$authority/stickers_asset/$packId/$name"), "r")?.close()
                    } catch (e: Exception) { errors += "Sticker $name cannot be opened: ${e.message}" }
                }
            }
        }
        try {
            resolver.openAssetFileDescriptor(Uri.parse("content://$authority/stickers_asset/$packId/tray.png"), "r")?.close()
        } catch (e: Exception) { errors += "Tray cannot be opened: ${e.message}" }
        return errors
    }

    fun exportToTelegram(packs: List<UniversalStickerPack>) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val exporter = TelegramStickerExporter(context)

            for (pack in packs) {
                Toast.makeText(context, "Exporting '${pack.title}' to Telegram...", Toast.LENGTH_SHORT).show()

                exporter.exportPack(pack).fold(
                    onSuccess = { url ->
                        Toast.makeText(context, "Pack exported! $url", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    fun resetPhase() { _phase.value = ImportPhase.Idle }

    fun importToTelegram(uris: List<Uri>, emojis: List<String>) {
        val context = getApplication<Application>()
        if (uris.isEmpty()) { Toast.makeText(context, "Select at least one WebP sticker", Toast.LENGTH_SHORT).show(); return }
        val normalizedEmojis = if (emojis.size == uris.size) emojis else List(uris.size) { "😀" }
        val baseIntent = Intent(TELEGRAM_CREATE_STICKER_PACK_ACTION).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putStringArrayListExtra(TELEGRAM_STICKER_EMOJIS_EXTRA, ArrayList(normalizedEmojis))
            putExtra(TELEGRAM_IMPORTER_EXTRA, context.packageName)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val handlers = context.packageManager.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)
            handlers.forEach { ri -> uris.forEach { uri -> context.grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            val targetPackage = handlers.map { it.activityInfo.packageName }.firstOrNull { it == "org.telegram.messenger" } ?: handlers.firstOrNull()?.activityInfo?.packageName
            val launchIntent = Intent(baseIntent).apply { targetPackage?.let { setPackage(it) } }
            context.startActivity(launchIntent)
            Toast.makeText(context, "Opening Telegram...", Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Telegram is not installed or does not support sticker import.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(context, "Failed to open Telegram: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun String.sanitizePackId(): String {
        return replace(Regex("[^A-Za-z0-9_. -]"), "_").trim().ifBlank { "pack_${System.currentTimeMillis()}" }.take(120)
    }
}
