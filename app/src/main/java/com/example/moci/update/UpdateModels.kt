package com.example.moci.update

import kotlinx.coroutines.flow.StateFlow
import java.io.File

class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ReleaseInfo(
    val tag: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
    val checksumUrl: String?,
)

interface UpdateRepository {
    suspend fun latest(): ReleaseInfo
    suspend fun checksum(release: ReleaseInfo): String
}

data class DownloadRecord(
    val id: Long,
    val release: ReleaseInfo,
    val sha256: String,
    val fileName: String,
)

interface UpdatePreferences {
    var autoCheckEnabled: Boolean
    fun readDownload(): DownloadRecord?
    fun saveDownload(record: DownloadRecord?)
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Preparing(val release: ReleaseInfo) : DownloadState
    data class Running(val release: ReleaseInfo, val bytes: Long, val total: Long, val paused: Boolean = false) : DownloadState
    data class Verifying(val release: ReleaseInfo) : DownloadState
    data class Ready(val release: ReleaseInfo, val file: File) : DownloadState
    data class Failed(val release: ReleaseInfo?, val message: String) : DownloadState
}

fun DownloadState.releaseOrNull(): ReleaseInfo? = when (this) {
    DownloadState.Idle -> null
    is DownloadState.Preparing -> release
    is DownloadState.Running -> release
    is DownloadState.Verifying -> release
    is DownloadState.Ready -> release
    is DownloadState.Failed -> release
}

fun DownloadState.isBusy(): Boolean = this is DownloadState.Preparing || this is DownloadState.Running || this is DownloadState.Verifying

interface UpdateDownloads {
    val state: StateFlow<DownloadState>
    suspend fun restore()
    suspend fun start(release: ReleaseInfo)
    suspend fun refresh()
    suspend fun cancel()
    suspend fun verifiedFile(): File?
}
