package com.example.moci.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.moci.BuildConfig
import java.io.File
import java.security.MessageDigest

class AndroidDownloadEngine(private val context: Context) : DownloadEngine {
    private val manager = context.getSystemService(DownloadManager::class.java)

    override fun enqueue(release: ReleaseInfo, file: File): Long {
        if (context.getExternalFilesDir(null) == null) throw UpdateException("应用存储位置暂不可用，请稍后重试")
        val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
            .setTitle("默词 ${release.tag}")
            .setDescription("正在下载更新安装包")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        return manager.enqueue(request)
    }

    override fun query(id: Long): TransferSnapshot {
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return TransferSnapshot(TransferStatus.MISSING)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val mapped = when (status) {
                DownloadManager.STATUS_PENDING -> TransferStatus.WAITING
                DownloadManager.STATUS_RUNNING -> TransferStatus.RUNNING
                DownloadManager.STATUS_PAUSED -> TransferStatus.PAUSED
                DownloadManager.STATUS_SUCCESSFUL -> TransferStatus.COMPLETE
                else -> TransferStatus.FAILED
            }
            val message = when (reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重试"
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "安装包存储位置暂不可用，请重试"
                DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "下载连接失败，请检查网络后重试"
                else -> "下载失败（$reason），请重试"
            }
            return TransferSnapshot(mapped,
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), message)
        }
    }

    override fun remove(id: Long) { manager.remove(id) }
}

data class PackageIdentity(val packageName: String, val versionName: String, val versionCode: Long, val signers: Set<String>)

object UpdatePackagePolicy {
    fun verify(installed: PackageIdentity, candidate: PackageIdentity, tag: String) {
        if (candidate.packageName != installed.packageName) throw UpdateException("安装包包名不匹配，请重新下载")
        if (candidate.signers.isEmpty() || installed.signers.isEmpty() || candidate.signers != installed.signers) {
            throw UpdateException("安装包签名与当前应用不一致，请使用同一发布证书")
        }
        if (candidate.versionCode <= installed.versionCode || SemanticVersion.parse(candidate.versionName) <= SemanticVersion.parse(installed.versionName)) {
            throw UpdateException("安装包版本未高于当前版本")
        }
        if (SemanticVersion.parse(candidate.versionName) != SemanticVersion.parse(tag)) {
            throw UpdateException("安装包版本与发布版本不一致")
        }
    }
}

class AndroidApkVerifier(private val context: Context) : PackageVerifier {
    @Suppress("DEPRECATION")
    override fun verify(file: File, release: ReleaseInfo, expectedSha256: String) {
        if (!file.isFile || file.length() != release.size) throw UpdateException("安装包大小不匹配，请重新下载")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) throw UpdateException("安装包 SHA-256 校验失败，请重新下载")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw UpdateException("下载文件不是有效的 APK 安装包")
        UpdatePackagePolicy.verify(identity(installed), identity(archive), release.tag)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): PackageIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet() ?: emptySet()
        return PackageIdentity(info.packageName, info.versionName ?: "", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), signers)
    }
}

fun updateInstallIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

object AndroidUpdateServices {
    private val startupGate = StartupCheckGate()
    @Volatile private var downloads: UpdateDownloads? = null

    fun factory(context: Context): ViewModelProvider.Factory {
        val app = context.applicationContext
        val preferences = AndroidUpdatePreferences(app)
        val repository = GitHubUpdateRepository()
        val controller = downloads ?: synchronized(this) {
            downloads ?: UpdateDownloadController(
                repository, preferences, AndroidDownloadEngine(app), AndroidApkVerifier(app),
                File(app.getExternalFilesDir(null) ?: app.filesDir, "updates"), BuildConfig.VERSION_NAME,
            ).also { downloads = it }
        }
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(repository, controller, preferences, startupGate, BuildConfig.VERSION_NAME) as T
        }
    }
}
