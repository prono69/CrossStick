package cross.stick.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cross.stick.conversion.ConversionEngine
import cross.stick.data.local.PreferencesManager
import cross.stick.data.model.TelegramStickerSet
import cross.stick.data.repository.StickerRepository
import cross.stick.whatsapp.WhatsAppIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SavedPack(
    val id: String,
    val name: String,
    val stickerCount: Int,
    val path: File
)

sealed class ImportPhase {
    object Idle : ImportPhase()
    object Fetching : ImportPhase()
    data class Downloading(val current: Int, val total: Int) : ImportPhase()
    data class Converting(val current: Int, val total: Int) : ImportPhase()
    object Done : ImportPhase()
    data class Failed(val error: String) : ImportPhase()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repository = StickerRepository(application)

    val botToken: StateFlow<String> = prefs.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val authorName: StateFlow<String> = prefs.authorName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val onboardingComplete: StateFlow<Boolean> = prefs.onboardingComplete.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _phase = MutableStateFlow<ImportPhase>(ImportPhase.Idle)
    val phase: StateFlow<ImportPhase> = _phase.asStateFlow()

    private val _currentPackId = MutableStateFlow<String?>(null)
    val currentPackId: StateFlow<String?> = _currentPackId.asStateFlow()

    private val _convertedPackId = MutableStateFlow<String?>(null)
    val convertedPackId: StateFlow<String?> = _convertedPackId.asStateFlow()

    private val _downloadedFiles = MutableStateFlow<List<File>>(emptyList())
    val downloadedFilePaths: List<File> get() = _downloadedFiles.value

    private val _savedPacks = MutableStateFlow<List<SavedPack>>(emptyList())
    val savedPacks: StateFlow<List<SavedPack>> = _savedPacks.asStateFlow()

    init {
        viewModelScope.launch {
            onboardingComplete.collect { _isReady.value = true }
        }
        loadSavedPacks()
    }

    private fun loadSavedPacks() {
        val context = getApplication<Application>()
        val packsDir = File(context.filesDir, "stickers/converted")
        if (!packsDir.exists()) return
        _savedPacks.value = packsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { packDir ->
                val stickerCount = packDir.listFiles { f -> f.extension == "webp" }?.size ?: 0
                SavedPack(id = packDir.name, name = packDir.name, stickerCount = stickerCount, path = packDir)
            } ?: emptyList()
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
        }
    }

    fun fetchStickerSet(link: String) {
        viewModelScope.launch {
            _phase.value = ImportPhase.Fetching
            _downloadedFiles.value = emptyList()
            val packName = repository.extractPackName(link)
            val result = repository.fetchStickerSet(packName)
            result.fold(
                onSuccess = { stickerSet ->
                    _currentPackId.value = stickerSet.name.replace(" ", "_")
                    downloadAndConvertAll(stickerSet)
                },
                onFailure = { e ->
                    _phase.value = ImportPhase.Failed(e.message ?: "Could not fetch stickers")
                }
            )
        }
    }

    private suspend fun downloadAndConvertAll(stickerSet: TelegramStickerSet) {
        val packId = stickerSet.name.replace(" ", "_")
        val total = stickerSet.stickers.size
        val files = mutableListOf<File>()
        val outputDir = File(getApplication<Application>().filesDir, "stickers/converted/$packId")
        if (!outputDir.exists()) outputDir.mkdirs()

        stickerSet.stickers.forEachIndexed { index, sticker ->
            if (sticker.is_animated || sticker.is_video) {
                _phase.value = ImportPhase.Downloading(index + 1, total)
                return@forEachIndexed
            }

            _phase.value = ImportPhase.Downloading(index + 1, total)
            repository.downloadSticker(sticker.file_id, packId, index).fold(
                onSuccess = { file ->
                    files.add(file)
                    withContext(Dispatchers.Default) {
                        ConversionEngine.convertToWhatsAppStatic(
                            inputFile = file,
                            outputDir = outputDir,
                            outputName = "sticker_$index.webp"
                        )
                    }
                    _phase.value = ImportPhase.Converting(index + 1, total)
                },
                onFailure = { e -> Log.e("CrossStick", "Failed sticker $index: ${e.message}") }
            )
        }

        _downloadedFiles.value = files
        if (files.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                ConversionEngine.createTrayFromFile(files[0], outputDir)
            }
        }

        _phase.value = ImportPhase.Done
        _convertedPackId.value = packId
        loadSavedPacks()

        // Only add if enough stickers
        if (files.size >= 3) {
            addToWhatsApp(packId)
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), "Need at least 3 stickers for WhatsApp", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun addToWhatsApp(packId: String) {
        val context = getApplication<Application>()
        WhatsAppIntentHelper.addStickerPackToWhatsApp(
            context = context,
            packId = packId,
            packName = packId,
            authority = "cross.stick.stickercontentprovider"
        )
    }

    fun resetPhase() {
        _phase.value = ImportPhase.Idle
    }

    fun importToTelegram(uris: List<Uri>, emojis: List<String>) {
        val context = getApplication<Application>()
        try {
            val intent = Intent("org.telegram.messenger.CREATE_STICKER_PACK").apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putStringArrayListExtra("STICKER_EMOJIS", ArrayList(emojis))
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Toast.makeText(context, "Opening Telegram...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Telegram is not installed or doesn't support sticker import", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("CrossStick", "Telegram import failed", e)
            Toast.makeText(context, "Failed to open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
