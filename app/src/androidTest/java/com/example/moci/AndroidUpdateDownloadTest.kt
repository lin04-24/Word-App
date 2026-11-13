package com.example.moci

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.moci.update.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidUpdateDownloadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(name: String = "valid") = InstrumentationRegistry.getInstrumentation().context.assets.open("update-$name.apk").use { it.readBytes() }
    private fun release(bytes: ByteArray, url: String) = ReleaseInfo("v0.3", "测试更新", "update-valid.apk", url, bytes.size.toLong(), sha(bytes), null)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun repository() = object : UpdateRepository {
        override suspend fun latest(): ReleaseInfo = error("No public API used in device fixtures")
        override suspend fun checksum(release: ReleaseInfo) = release.sha256!!
    }
    private fun controller(prefs: UpdatePreferences) = UpdateDownloadController(repository(), prefs, AndroidDownloadEngine(context),
        AndroidApkVerifier(context), File(context.getExternalFilesDir(null), "updates"), BuildConfig.VERSION_NAME)

    @Test fun systemDownloadRestoresAndVerifiesAnActualSignedApk() = runBlocking {
        val bytes = fixture()
        val gate = CountDownLatch(1)
        FixtureServer(bytes, gate).use { server ->
            val name = "update-device-${System.nanoTime()}"
            val prefs = AndroidUpdatePreferences(context, name)
            var download = controller(prefs)
            try {
                val release = release(bytes, server.url)
                download.start(release)
                assertTrue(download.state.value is DownloadState.Running)
                val id = prefs.readDownload()!!.id
                // New preferences/controller instances simulate reconstructing state after process death.
                val restored = AndroidUpdatePreferences(context, name)
                download = controller(restored)
                download.restore()
                assertEquals(id, restored.readDownload()!!.id)
                gate.countDown()
                awaitCompletion(download)
                assertTrue(download.state.value.toString(), download.state.value is DownloadState.Ready)
                val file = download.verifiedFile()!!
                val intent = updateInstallIntent(context, file)
                assertEquals("content", intent.data!!.scheme)
                assertEquals("${context.packageName}.updates", intent.data!!.authority)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertArrayEquals(bytes, context.contentResolver.openInputStream(intent.data!!)?.use { it.readBytes() })
                val nextProcess = controller(AndroidUpdatePreferences(context, name))
                nextProcess.restore()
                assertTrue(nextProcess.state.value is DownloadState.Ready)
            } finally {
                gate.countDown()
                download.cancel()
                context.deleteSharedPreferences(name)
            }
        }
    }

    @Test fun cancellationAndDigestFailureRemoveSystemTaskAndFile() = runBlocking {
        val bytes = fixture()
        FixtureServer(bytes).use { server ->
            val prefs = AndroidUpdatePreferences(context, "update-device-failure")
            val download = controller(prefs)
            try {
                download.start(release(bytes, server.url))
                download.cancel()
                assertNull(prefs.readDownload())
                assertEquals(DownloadState.Idle, download.state.value)
                download.start(release(bytes, server.url).copy(sha256 = "0".repeat(64)))
                awaitCompletion(download)
                assertTrue(download.state.value is DownloadState.Failed)
                assertNull(download.verifiedFile())
                assertNull(prefs.readDownload())
            } finally {
                download.cancel()
                context.deleteSharedPreferences("update-device-failure")
            }
        }
    }

    @Test fun realPackageInspectionRejectsWrongPackageSignerAndOldVersion() {
        val directory = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        for (name in listOf("wrong-package", "wrong-signer", "old-version")) {
            val bytes = fixture(name)
            val file = File(directory, "verify-$name.apk")
            try {
                file.writeBytes(bytes)
                assertThrows(UpdateException::class.java) { AndroidApkVerifier(context).verify(file, release(bytes, "https://example.test"), sha(bytes)) }
            } finally { file.delete() }
        }
    }

    private suspend fun awaitCompletion(controller: UpdateDownloads) {
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            controller.refresh()
            if (controller.state.value is DownloadState.Ready || controller.state.value is DownloadState.Failed) return
            delay(200)
        }
        fail("DownloadManager transfer did not finish: ${controller.state.value}")
    }
}

/** Loopback fixture server; no test assets or test releases are uploaded to GitHub. */
class FixtureServer(private val bytes: ByteArray, private val gate: CountDownLatch = CountDownLatch(0)) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}/update.apk"
    private val worker = thread(isDaemon = true, name = "update-fixture-http") {
        while (!server.isClosed) {
            try {
                server.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader()
                    while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break }
                    gate.await(15, TimeUnit.SECONDS)
                    client.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (_: Exception) { /* cancellation can close a connection before its response */ }
        }
    }
    override fun close() { gate.countDown(); server.close(); worker.join(1000) }
}
