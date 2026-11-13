package com.example.moci.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class TransferStatus { WAITING, RUNNING, PAUSED, COMPLETE, FAILED, MISSING }
data class TransferSnapshot(val status: TransferStatus, val bytes: Long = 0, val total: Long = -1, val reason: String = "")

interface DownloadEngine {
    fun enqueue(release: ReleaseInfo, file: File): Long
    fun query(id: Long): TransferSnapshot
    fun remove(id: Long)
}

fun interface PackageVerifier {
    fun verify(file: File, release: ReleaseInfo, expectedSha256: String)
}

/** DownloadManager owns the transfer; this controller can be recreated after process death. */
class UpdateDownloadController(
    private val repository: UpdateRepository,
    private val preferences: UpdatePreferences,
    private val engine: DownloadEngine,
    private val verifier: PackageVerifier,
    private val directory: File,
    private val currentVersion: String,
) : UpdateDownloads {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    override val state: StateFlow<DownloadState> = mutableState
    private var validatedId: Long? = null

    override suspend fun restore() = refresh()

    override suspend fun start(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.value.isBusy()) return@withLock
            if (state.value is DownloadState.Ready && state.value.releaseOrNull() == release) return@withLock
            try {
                clearRecord()
                mutableState.value = DownloadState.Preparing(release)
                val hash = repository.checksum(release)
                if (!hash.matches(Regex("[a-fA-F0-9]{64}"))) throw UpdateException("安装包校验值格式错误")
                if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException("更新目录创建失败，请检查存储空间")
                val file = File(directory, "update-${UUID.randomUUID()}.apk")
                val id = engine.enqueue(release, file)
                try {
                    preferences.saveDownload(DownloadRecord(id, release, hash, file.name))
                } catch (e: Exception) {
                    engine.remove(id)
                    file.delete()
                    throw e
                }
                mutableState.value = DownloadState.Running(release, 0, release.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = DownloadState.Failed(release, e.message ?: "开始下载失败，请重试")
            }
        }
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            var release: ReleaseInfo? = null
            try {
                val record = preferences.readDownload() ?: return@withLock
                release = record.release
                if (SemanticVersion.parse(record.release.tag) <= SemanticVersion.parse(currentVersion)) {
                    clearRecord()
                    mutableState.value = DownloadState.Idle
                    return@withLock
                }
                val snapshot = engine.query(record.id)
                when (snapshot.status) {
                    TransferStatus.WAITING, TransferStatus.RUNNING, TransferStatus.PAUSED -> {
                        mutableState.value = DownloadState.Running(record.release, snapshot.bytes, snapshot.total, snapshot.status != TransferStatus.RUNNING)
                    }
                    TransferStatus.COMPLETE -> {
                        val file = resolveFile(record.fileName)
                        if (validatedId != record.id || !file.isFile) {
                            mutableState.value = DownloadState.Verifying(record.release)
                            verifier.verify(file, record.release, record.sha256)
                            validatedId = record.id
                        }
                        mutableState.value = DownloadState.Ready(record.release, file)
                    }
                    TransferStatus.FAILED, TransferStatus.MISSING -> {
                        throw UpdateException(snapshot.reason.ifBlank { "下载失败或任务已移除，请重新下载" })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { clearRecord() }
                mutableState.value = DownloadState.Failed(release, e.message ?: "安装包校验失败，请重新下载")
            }
        }
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                clearRecord()
                mutableState.value = DownloadState.Idle
            } catch (e: Exception) {
                mutableState.value = DownloadState.Failed(state.value.releaseOrNull(), e.message ?: "取消下载失败，请重试")
            }
        }
    }

    override suspend fun verifiedFile(): File? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ready = state.value as? DownloadState.Ready ?: return@withLock null
            try {
                val record = preferences.readDownload() ?: throw UpdateException("下载记录已丢失，请重新下载")
                mutableState.value = DownloadState.Verifying(record.release)
                val file = resolveFile(record.fileName)
                verifier.verify(file, record.release, record.sha256)
                mutableState.value = DownloadState.Ready(record.release, file)
                file
            } catch (e: Exception) {
                runCatching { clearRecord() }
                mutableState.value = DownloadState.Failed(ready.release, e.message ?: "安装包校验失败，请重新下载")
                null
            }
        }
    }

    private fun clearRecord() {
        val record = preferences.readDownload()
        if (record != null) {
            engine.remove(record.id)
            resolveFile(record.fileName).delete()
        }
        preferences.saveDownload(null)
        validatedId = null
    }

    private fun resolveFile(name: String): File {
        if (File(name).name != name || !name.endsWith(".apk")) throw UpdateException("安装包保存路径异常")
        val file = File(directory, name).canonicalFile
        if (file.parentFile != directory.canonicalFile) throw UpdateException("安装包保存路径异常")
        return file
    }
}
