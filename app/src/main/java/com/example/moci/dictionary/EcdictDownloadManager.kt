package com.example.moci.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.example.moci.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class EcdictDownloadState(
    val configured: Boolean,
    val installedVersion: String? = null,
    val installedSha256: String? = null,
    val installedEntryCount: Int = 0,
    val installedSizeBytes: Long = 0,
    val manifest: EcdictManifest? = null,
    val downloading: Boolean = false,
    val verifying: Boolean = false,
    val bytesDownloaded: Long = 0,
    val error: String? = null,
) {
    val isInstalled: Boolean get() = installedVersion != null
    val isBusy: Boolean get() = downloading || verifying
    val updateAvailable: Boolean
        get() = isInstalled && manifest != null && manifest.sha256 != installedSha256
}

/** Installs a user-requested, SHA-256-verified ECDICT database without putting it in the APK. */
class EcdictDownloadManager(
    context: Context,
    private val scope: CoroutineScope,
    private val preferencesName: String = "offline_dictionary",
    storageDirectory: File? = null,
    private val release: EcdictManifest = EcdictManifest.current(),
) {
    private val appContext = context.applicationContext
    private val dictionaryDirectory = storageDirectory ?: File(appContext.filesDir, "dictionary")
    private val destination = File(dictionaryDirectory, DATABASE_FILENAME)
    private val preferences = appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val configured = runCatching { release.validate(allowLocalHttp = BuildConfig.DEBUG) }.isSuccess
    private val mutableState = MutableStateFlow(readInstalledState().copy(
        configured = configured,
        manifest = release.takeIf { configured },
        error = if (configured) null else "词典地址或完整性信息尚未配置",
    ))
    val state: StateFlow<EcdictDownloadState> = mutableState
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var operation: Job? = null

    fun download() {
        if (state.value.isBusy) return
        if (!configured) {
            mutableState.value = state.value.copy(error = "词典地址或完整性信息尚未配置")
            return
        }
        val manifest = state.value.manifest
        if (manifest == null) {
            mutableState.value = state.value.copy(error = "词典地址或完整性信息尚未配置")
            return
        }
        mutableState.value = state.value.copy(downloading = true, bytesDownloaded = 0, error = null)
        operation = scope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    install(manifest) { received ->
                        mutableState.value = state.value.copy(bytesDownloaded = received)
                    }
                }
                mutableState.value = state.value.copy(
                    installedVersion = installed.version,
                    installedSha256 = installed.sha256,
                    installedEntryCount = installed.entryCount,
                    installedSizeBytes = installed.sizeBytes,
                    downloading = false,
                    verifying = false,
                    bytesDownloaded = installed.sizeBytes,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                mutableState.value = state.value.copy(downloading = false, verifying = false, bytesDownloaded = 0)
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(
                    downloading = false,
                    verifying = false,
                    bytesDownloaded = 0,
                    error = error.localizedMessage ?: "下载词典失败，请重试",
                )
            }
        }
    }

    fun cancel() {
        val active = operation ?: return
        if (!active.isActive) return
        active.cancel()
        activeConnection?.disconnect()
        operation = null
        mutableState.value = state.value.copy(downloading = false, verifying = false, bytesDownloaded = 0)
    }

    private fun readInstalledState(): EcdictDownloadState {
        val installed = destination.isFile && destination.length() > 0 && runCatching {
            destination.inputStream().use { input ->
                val magic = ByteArray(SQLITE_HEADER.size)
                input.read(magic) == magic.size && magic.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(false)
        return EcdictDownloadState(
            configured = configured,
            installedVersion = if (installed) preferences.getString(KEY_VERSION, null) else null,
            installedSha256 = if (installed) preferences.getString(KEY_SHA256, null) else null,
            installedEntryCount = if (installed) preferences.getInt(KEY_ENTRY_COUNT, 0) else 0,
            installedSizeBytes = if (installed) destination.length() else 0,
            manifest = release.takeIf { configured },
        )
    }

    private suspend fun install(manifest: EcdictManifest, onProgress: (Long) -> Unit): EcdictManifest {
        dictionaryDirectory.mkdirs()
        val requiredBytes = manifest.sizeBytes + if (destination.isFile) destination.length() else 0L
        val freeBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        require(freeBytes > requiredBytes + MINIMUM_TEMP_SPACE) { "手机存储空间不足，请至少预留 ${formatBytes(requiredBytes + MINIMUM_TEMP_SPACE)}" }

        val temporary = File(dictionaryDirectory, "$DATABASE_FILENAME.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = openConnection(manifest.downloadUrl)
        activeConnection = connection
        try {
            connection.connectTimeout = CONNECTION_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.sqlite3, application/octet-stream")
            val status = connection.responseCode
            require(status in 200..299) { "词典服务器返回 HTTP $status" }
            val advertisedSize = connection.contentLengthLong
            require(advertisedSize == -1L || advertisedSize == manifest.sizeBytes) { "服务器返回的词典大小与清单不一致" }

            var received = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= manifest.sizeBytes) { "下载文件大于词典清单所列大小" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(received)
                    }
                    output.fd.sync()
                }
            }
            require(received == manifest.sizeBytes) { "词典下载不完整，请重试" }
            val actualHash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            require(actualHash.equals(manifest.sha256, ignoreCase = true)) { "词典 SHA-256 校验失败，请重试" }

            mutableState.value = state.value.copy(downloading = false, verifying = true)
            verifyDatabase(temporary, manifest.entryCount)
            coroutineContext.ensureActive()
            try {
                java.nio.file.Files.move(
                    temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(temporary.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            check(preferences.edit()
                .putString(KEY_VERSION, manifest.version)
                .putString(KEY_SHA256, manifest.sha256)
                .putInt(KEY_ENTRY_COUNT, manifest.entryCount)
                .putLong(KEY_SIZE, manifest.sizeBytes)
                .commit()) { "词典已下载，但安装状态保存失败，请重新打开设置" }
            return manifest
        } finally {
            connection.disconnect()
            if (activeConnection === connection) activeConnection = null
            temporary.delete()
        }
    }

    private fun verifyDatabase(file: File, expectedEntryCount: Int) {
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "词典数据库完整性校验失败" }
            }
            database.rawQuery("SELECT COUNT(*) FROM stardict", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getInt(0) == expectedEntryCount) { "词典词条数与清单不一致" }
            }
            database.rawQuery("SELECT translation FROM stardict WHERE word=? COLLATE NOCASE LIMIT 1", arrayOf("see")).use { cursor ->
                check(cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank()) { "词典中缺少示例查询条目" }
            }
        } finally {
            database.close()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as? HttpURLConnection) ?: error("词典服务器连接类型不受支持")

    companion object {
        private const val DATABASE_FILENAME = "ecdict.sqlite"
        private const val CONNECTION_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 45_000
        private const val MINIMUM_TEMP_SPACE = 16 * 1024 * 1024L
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private const val KEY_VERSION = "version"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_ENTRY_COUNT = "entry_count"
        private const val KEY_SIZE = "size_bytes"

        internal fun formatBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
