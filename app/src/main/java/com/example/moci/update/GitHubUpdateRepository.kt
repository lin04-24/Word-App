package com.example.moci.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import javax.net.ssl.HttpsURLConnection

data class UpdateResponse(val status: Int, val body: String)

fun interface UpdateHttpClient {
    suspend fun get(url: String): UpdateResponse
}

class HttpsUpdateClient : UpdateHttpClient {
    override suspend fun get(url: String): UpdateResponse = withContext(Dispatchers.IO) {
        try {
            var next = URL(url)
            repeat(6) { redirect ->
                if (next.protocol != "https") throw UpdateException("更新连接必须使用 HTTPS")
                val connection = (next.openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Moci-Android")
                    if (next.host == "api.github.com") {
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    }
                }
                try {
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        if (redirect == 5) throw UpdateException("更新服务器重定向次数过多")
                        val location = connection.getHeaderField("Location") ?: throw UpdateException("下载地址重定向异常")
                        next = URL(next, location)
                    } else {
                        val body = if (status == 200) {
                            connection.inputStream.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (output.size() <= 2 * 1024 * 1024) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                val bytes = output.toByteArray()
                                if (bytes.size > 2 * 1024 * 1024) throw UpdateException("更新说明超过大小限制")
                                bytes.toString(Charsets.UTF_8)
                            }
                        } else ""
                        return@withContext UpdateResponse(status, body)
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw UpdateException("更新地址异常")
        } catch (e: SocketTimeoutException) {
            throw UpdateException("检查更新超时，请稍后重试", e)
        } catch (e: java.io.IOException) {
            throw UpdateException("网络连接失败，请检查网络后重试", e)
        }
    }
}

class GitHubUpdateRepository(private val http: UpdateHttpClient = HttpsUpdateClient()) : UpdateRepository {
    override suspend fun latest(): ReleaseInfo = ReleaseParser.parse(response(LATEST_URL))

    override suspend fun checksum(release: ReleaseInfo): String = release.sha256
        ?: release.checksumUrl?.let { ChecksumParser.parse(response(it), release.assetName) }
        ?: throw UpdateException("发布者未提供 SHA-256 校验信息，请稍后重试")

    private suspend fun response(url: String): String {
        val result = http.get(url)
        return when (result.status) {
            200 -> result.body
            404 -> throw UpdateException("暂无正式版本或发布文件已移除")
            403, 429 -> throw UpdateException("GitHub 请求受限，请稍后重试")
            else -> throw UpdateException("更新服务器响应异常（HTTP ${result.status}）")
        }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/lin04-24/Word-App/releases/latest"
    }
}

object ReleaseParser {
    private const val DOWNLOAD_PREFIX = "https://github.com/lin04-24/Word-App/releases/download/"

    fun parse(body: String): ReleaseInfo {
        try {
            val json = JSONObject(body)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw UpdateException("暂无正式版本")
            val tag = json.getString("tag_name")
            SemanticVersion.parse(tag)
            val assets = json.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val preferred = "Word-App-v${tag.removePrefix("v").removePrefix("V")}.apk"
            val exact = apks.filter { it.optString("name") == preferred }
            val apk = when {
                exact.size == 1 -> exact.single()
                exact.isEmpty() && apks.size == 1 -> apks.single()
                apks.isEmpty() -> throw UpdateException("正式版本中没有 APK 安装包")
                else -> throw UpdateException("发布版本包含多个 APK，未找到明确匹配的安装包")
            }
            val checksumAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name") == "SHA256SUMS" }
            val checksumUrl = checksumAssets.singleOrNull()?.let { verifiedUrl(it.getString("browser_download_url")) }
            val digest = apk.optString("digest").removePrefix("sha256:")
                .takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase()
            return ReleaseInfo(
                tag = tag,
                notes = json.optString("body").takeUnless { it.isBlank() || it == "null" } ?: "本版本未提供更新说明",
                assetName = apk.getString("name"),
                downloadUrl = verifiedUrl(apk.getString("browser_download_url")),
                size = apk.getLong("size").also { if (it <= 0) throw UpdateException("安装包大小异常") },
                sha256 = digest,
                checksumUrl = checksumUrl,
            )
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("更新信息解析失败，请检查发布版本格式", e)
        }
    }

    private fun verifiedUrl(value: String): String {
        if (!value.startsWith(DOWNLOAD_PREFIX) || URL(value).protocol != "https") {
            throw UpdateException("发布文件下载地址不属于 Word-App 仓库")
        }
        return value
    }
}

object ChecksumParser {
    fun parse(text: String, assetName: String): String {
        val hashes = text.lineSequence().mapNotNull { line ->
            val match = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+)$").matchEntire(line.trim()) ?: return@mapNotNull null
            if (match.groupValues[2].removePrefix("./") == assetName) match.groupValues[1].lowercase() else null
        }.toSet()
        return hashes.singleOrNull() ?: throw UpdateException("SHA256SUMS 中未找到唯一匹配的安装包校验值")
    }
}
