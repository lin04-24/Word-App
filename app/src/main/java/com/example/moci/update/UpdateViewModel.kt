package com.example.moci.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class StartupCheckGate {
    private val checked = AtomicBoolean(false)
    fun claim(): Boolean = checked.compareAndSet(false, true)
}

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object Current : UpdateCheckState
    data class Available(val release: ReleaseInfo) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

data class UpdateUiState(
    val autoCheckEnabled: Boolean = true,
    val check: UpdateCheckState = UpdateCheckState.Idle,
    val download: DownloadState = DownloadState.Idle,
    val showDialog: Boolean = false,
    val autoInstall: Boolean = false,
    val installMessage: String? = null,
) {
    val release: ReleaseInfo? get() = download.releaseOrNull() ?: (check as? UpdateCheckState.Available)?.release
}

class UpdateViewModel(
    private val repository: UpdateRepository,
    private val downloads: UpdateDownloads,
    private val preferences: UpdatePreferences,
    startupGate: StartupCheckGate,
    private val currentVersion: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState(autoCheckEnabled = preferences.autoCheckEnabled))
    val state: StateFlow<UpdateUiState> = mutableState
    private var foreground = false
    private var polling: Job? = null
    private var downloading: Job? = null

    init {
        viewModelScope.launch { downloads.state.collect { download -> mutableState.update { it.copy(download = download) } } }
        viewModelScope.launch { downloads.restore() }
        if (startupGate.claim() && preferences.autoCheckEnabled) check(manual = false)
    }

    fun setAutoCheck(enabled: Boolean) {
        preferences.autoCheckEnabled = enabled
        mutableState.update { it.copy(autoCheckEnabled = enabled) }
    }

    fun check(manual: Boolean = true) {
        if (state.value.check == UpdateCheckState.Checking || state.value.download.isBusy()) return
        mutableState.update { it.copy(check = UpdateCheckState.Checking, installMessage = null) }
        viewModelScope.launch {
            try {
                val release = repository.latest()
                if (SemanticVersion.parse(release.tag) > SemanticVersion.parse(currentVersion)) {
                    mutableState.update { it.copy(check = UpdateCheckState.Available(release), showDialog = manual || it.autoCheckEnabled) }
                } else {
                    mutableState.update { it.copy(check = UpdateCheckState.Current) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(check = UpdateCheckState.Error(e.message ?: "检查更新失败，请重试")) }
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            polling?.cancel()
            polling = null
            consumeAutoInstall()
        } else {
            polling = viewModelScope.launch {
                while (isActive) {
                    downloads.refresh()
                    delay(1000)
                }
            }
        }
    }

    fun openDialog() { mutableState.update { it.copy(showDialog = true, autoInstall = false) } }
    fun dismissDialog() { mutableState.update { it.copy(showDialog = false, autoInstall = false) } }
    fun consumeAutoInstall() { mutableState.update { it.copy(autoInstall = false) } }
    fun installMessage(message: String?) { mutableState.update { it.copy(installMessage = message) } }

    fun startDownload() {
        if (downloading?.isActive == true || state.value.download.isBusy()) return
        val release = state.value.release ?: return
        mutableState.update { it.copy(showDialog = true, autoInstall = foreground, installMessage = null) }
        downloading = viewModelScope.launch { downloads.start(release) }
    }

    fun cancelDownload() {
        consumeAutoInstall()
        viewModelScope.launch { downloads.cancel() }
    }

    suspend fun installerFile(): File? {
        consumeAutoInstall()
        return downloads.verifiedFile()
    }
}
