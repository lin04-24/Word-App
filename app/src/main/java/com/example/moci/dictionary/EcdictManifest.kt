package com.example.moci.dictionary

import com.example.moci.BuildConfig
import java.net.URI

/** Integrity metadata for the separately hosted SQLite dictionary. */
data class EcdictManifest(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val entryCount: Int,
) {
    fun validate(allowLocalHttp: Boolean = false) {
        require(version.isNotBlank() && version.length <= 80 && version.none(Char::isISOControl)) {
            "词典版本信息无效"
        }
        val uri = try { URI(downloadUrl) } catch (e: Exception) {
            throw IllegalArgumentException("词典下载地址无效", e)
        }
        val loopbackHttp = uri.scheme.equals("http", ignoreCase = true) && allowLocalHttp && isLoopback(uri.host)
        require((uri.scheme.equals("https", ignoreCase = true) || loopbackHttp) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
            "词典服务须使用有效的 HTTPS 地址"
        }
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "词典 SHA-256 信息无效" }
        require(sizeBytes in 1..MAX_DICTIONARY_BYTES) { "词典文件大小信息无效" }
        require(entryCount in 1..MAX_DICTIONARY_ENTRIES) { "词条数量信息无效" }
    }

    companion object {
        private const val MAX_DICTIONARY_BYTES = 1_000_000_000L
        private const val MAX_DICTIONARY_ENTRIES = 10_000_000

        fun current() = EcdictManifest(
            version = BuildConfig.ECDICT_VERSION,
            downloadUrl = BuildConfig.ECDICT_DOWNLOAD_URL,
            sha256 = BuildConfig.ECDICT_SHA256,
            sizeBytes = BuildConfig.ECDICT_SIZE_BYTES,
            entryCount = BuildConfig.ECDICT_ENTRY_COUNT,
        )

        private fun isLoopback(host: String?): Boolean =
            host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
    }
}
