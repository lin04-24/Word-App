package com.example.moci.update

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateParsingTest {
    @Test fun comparesNumericSegmentsAndNormalizesHistoricalTags() {
        assertEquals(SemanticVersion.parse("0.2.0"), SemanticVersion.parse("v0.2"))
        assertTrue(SemanticVersion.parse("0.10") > SemanticVersion.parse("0.9.9"))
        assertTrue(SemanticVersion.parse("2.0.0") > SemanticVersion.parse("1.99.99"))
        assertTrue(SemanticVersion.parse("99999999999999999999.0") > SemanticVersion.parse("2.0"))
        assertEquals(SemanticVersion.parse("1.0+build.02"), SemanticVersion.parse("1.0.0+other"))
    }

    @Test fun implementsSemverPrereleaseOrdering() {
        val order = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        order.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", SemanticVersion.parse(a) < SemanticVersion.parse(b)) }
    }

    @Test fun rejectsInvalidVersions() {
        listOf("", "1", "1.02", "01.2.0", "1.0.0.0", "1.0.0-01", "1.0-", "1.0+", "release-1.0", "1.0.0-a..b").forEach {
            assertThrows("invalid $it", Exception::class.java) { SemanticVersion.parse(it) }
        }
    }

    @Test fun selectsNamedApkOverOtherAssetsAndReadsReleaseNotes() {
        val parsed = ReleaseParser.parse(releaseJson(listOf(asset("SHA256SUMS"), asset("other.apk"), asset("Word-App-v0.3.apk", "sha256:${"a".repeat(64)}"))))
        assertEquals("Word-App-v0.3.apk", parsed.assetName)
        assertEquals("更新说明\n第二行", parsed.notes)
        assertEquals("a".repeat(64), parsed.sha256)
        assertTrue(parsed.checksumUrl!!.endsWith("/SHA256SUMS"))
    }

    @Test fun acceptsSingleApkAndEmptyNotesWithoutDigest() {
        val json = JSONObject(releaseJson(listOf(asset("moci.apk")))).put("body", JSONObject.NULL)
        val parsed = ReleaseParser.parse(json.toString())
        assertEquals("moci.apk", parsed.assetName)
        assertEquals("本版本未提供更新说明", parsed.notes)
        assertNull(parsed.sha256)
    }

    @Test fun rejectsAmbiguousMissingDraftOrUntrustedAssets() {
        assertThrows(UpdateException::class.java) { ReleaseParser.parse(releaseJson(listOf(asset("a.apk"), asset("b.apk")))) }
        assertThrows(UpdateException::class.java) { ReleaseParser.parse(releaseJson(listOf(asset("readme.txt")))) }
        assertThrows(UpdateException::class.java) { ReleaseParser.parse(JSONObject(releaseJson(listOf(asset("a.apk")))).put("draft", true).toString()) }
        assertThrows(UpdateException::class.java) { ReleaseParser.parse(JSONObject(releaseJson(listOf(asset("a.apk")))).put("prerelease", true).toString()) }
        val bad = asset("a.apk").put("browser_download_url", "https://example.com/a.apk")
        assertThrows(UpdateException::class.java) { ReleaseParser.parse(releaseJson(listOf(bad))) }
        assertThrows(UpdateException::class.java) { ReleaseParser.parse("<html>invalid</html>") }
    }

    @Test fun readsChecksumForExactFileAndRejectsConflicts() {
        val hash = "a".repeat(64)
        assertEquals(hash, ChecksumParser.parse("${"b".repeat(64)}  other.apk\r\n${hash.uppercase()}  *./moci.apk\r\n", "moci.apk"))
        assertThrows(UpdateException::class.java) { ChecksumParser.parse("$hash  other.apk", "moci.apk") }
        assertThrows(UpdateException::class.java) { ChecksumParser.parse("$hash  moci.apk\n${"b".repeat(64)}  moci.apk", "moci.apk") }
    }

    @Test fun repositoryUsesLatestEndpointAndFallsBackToChecksums() = runBlocking {
        val calls = mutableListOf<String>()
        val json = releaseJson(listOf(asset("a.apk"), asset("SHA256SUMS")))
        val repository = GitHubUpdateRepository { url ->
            calls += url
            UpdateResponse(200, if (url == GitHubUpdateRepository.LATEST_URL) json else "${"b".repeat(64)}  a.apk")
        }
        val release = repository.latest()
        assertEquals("b".repeat(64), repository.checksum(release))
        assertEquals(GitHubUpdateRepository.LATEST_URL, calls.first())
        assertTrue(calls.last().endsWith("/SHA256SUMS"))
    }

    @Test fun repositoryDifferentiatesHttpErrorsAndMissingChecksums() = runBlocking {
        for ((status, expected) in listOf(404 to "暂无正式版本", 403 to "请求受限", 429 to "请求受限", 500 to "HTTP 500")) {
            val error = runCatching { GitHubUpdateRepository { UpdateResponse(status, "") }.latest() }.exceptionOrNull()
            assertTrue(error!!.message!!.contains(expected))
        }
        val repository = GitHubUpdateRepository { error("digest should avoid a network request") }
        val release = sampleRelease()
        assertEquals(release.sha256, repository.checksum(release))
        assertTrue(runCatching { repository.checksum(release.copy(sha256 = null)) }.exceptionOrNull() is UpdateException)
    }

    private fun asset(name: String, digest: String? = null): JSONObject = JSONObject()
        .put("name", name).put("size", 100)
        .put("browser_download_url", "https://github.com/lin04-24/Word-App/releases/download/v0.3/$name")
        .apply { digest?.let { put("digest", it) } }

    private fun releaseJson(assets: List<JSONObject>): String = JSONObject()
        .put("tag_name", "v0.3").put("body", "更新说明\n第二行")
        .put("draft", false).put("prerelease", false).put("assets", JSONArray(assets)).toString()
}

fun sampleRelease() = ReleaseInfo("v0.3", "更新说明", "Word-App-v0.3.apk", "https://github.com/lin04-24/Word-App/releases/download/v0.3/Word-App-v0.3.apk", 100, "a".repeat(64), null)
