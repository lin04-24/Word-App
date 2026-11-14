package com.example.moci.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EcdictManifestTest {
    private val hash = "a".repeat(64)

    @Test
    fun validatesPinnedPublicDownloadMetadata() {
        val manifest = EcdictManifest(
            version = "2026.11",
            downloadUrl = "https://dict.example.com/ecdict.sqlite",
            sha256 = hash,
            sizeBytes = 1000,
            entryCount = 770611,
        )
        manifest.validate()

        assertEquals("2026.11", manifest.version)
        assertEquals("https://dict.example.com/ecdict.sqlite", manifest.downloadUrl)
        assertEquals(hash, manifest.sha256)
        assertEquals(1000L, manifest.sizeBytes)
        assertEquals(770611, manifest.entryCount)
    }

    @Test
    fun rejectsInvalidHashesSizesAndCrossHostDownloads() {
        listOf(
            EcdictManifest("1", "https://dict.example.com/ecdict.sqlite", "abc", 1000, 1),
            EcdictManifest("1", "https://dict.example.com/ecdict.sqlite", hash, 1_000_000_001L, 1),
            EcdictManifest("1", "http://dict.example.com/ecdict.sqlite", hash, 1000, 1),
        ).forEach { manifest -> assertThrows(IllegalArgumentException::class.java) { manifest.validate() } }
    }
}
