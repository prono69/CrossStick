package cross.stick.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cross.stick.conversion.ConversionEngine
import cross.stick.data.local.PreferencesManager
import cross.stick.data.model.TelegramStickerSet
import cross.stick.data.repository.StickerRepository
import cross.stick.whatsapp.WhatsAppIntentHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application)
    private val repository = StickerRepository(application)

    val botToken: StateFlow<String> = prefs.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val authorName: StateFlow<String> = prefs.authorName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val onboardingComplete: StateFlow<Boolean> = prefs.onboardingComplete.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _stickerSet = MutableStateFlow<TelegramStickerSet?>(null)
    val stickerSet: StateFlow<TelegramStickerSet?> = _stickerSet.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val _currentPackId = MutableStateFlow<String?>(null)
    val currentPackId: StateFlow<String?> = _currentPackId.asStateFlow()

    private val downloadedFilePaths = mutableListOf<File>()

    init {
        viewModelScope.launch {
            onboardingComplete.collect {
                _isReady.value = true
            }
        }
    }

    fun completeOnboarding(token: String, author: String) {
        viewModelScope.launch {
            prefs.saveBotToken(token)
            prefs.saveAuthorName(author)
            prefs.completeOnboarding()
        }
    }

    fun fetchStickerSet(link: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val packName = repository.extractPackName(link)
            repository.fetchStickerSet(packName).fold(
                onSuccess = { stickerSet ->
                    _stickerSet.value = stickerSet
                    // Start download immediately
                    downloadAllStickers(stickerSet)
                },
                onFailure = { e ->
                    _error.value = e.message ?: "Failed to fetch sticker set"
                }
            )
            _isLoading.value = false
        }
    }

    private fun downloadAllStickers(stickerSet: TelegramStickerSet) {
        viewModelScope.launch {
            _isDownloading.value = true
            _error.value = null
            downloadedFilePaths.clear()
            val packId = stickerSet.name.replace(" ", "_")
            stickerSet.stickers.forEachIndexed { index, sticker ->
                repository.downloadSticker(sticker.file_id, packId, index).fold(
                    onSuccess = { file -> downloadedFilePaths.add(file) },
                    onFailure = { /* skip failed downloads */ }
                )
            }
            _isDownloading.value = false
            convertAllStickers(packId)
        }
    }

    private fun convertAllStickers(packId: String) {
        viewModelScope.launch {
            _isConverting.value = true
            _currentPackId.value = packId
            val outputDir = File(getApplication<Application>().filesDir, "stickers/converted/$packId")
            if (!outputDir.exists()) outputDir.mkdirs()

            downloadedFilePaths.forEachIndexed { index, file ->
                ConversionEngine.convertToWhatsAppStatic(
                    inputFile = file,
                    outputDir = outputDir,
                    outputName = "sticker_$index.webp"
                )
            }
            // Create tray from first sticker
            if (downloadedFilePaths.isNotEmpty()) {
                ConversionEngine.createTrayFromFile(
                    inputFile = downloadedFilePaths[0],
                    outputDir = outputDir
                )
            }
            _isConverting.value = false
            _stickerSet.value = null
            addToWhatsApp(packId)
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

    fun importToTelegram(uris: List<Uri>, emojis: List<String>) {
        val context = getApplication<Application>()
        val intent = android.content.Intent("org.telegram.messenger.CREATE_STICKER_PACK").apply {
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            putStringArrayListExtra("STICKER_EMOJIS", ArrayList(emojis))
            type = "image/*"
        }
        context.startActivity(intent)
    }
}
