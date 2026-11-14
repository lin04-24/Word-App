package com.example.moci.dictionary

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class EcdictDownloadManagerTest {
    @Test
    fun downloadsVerifiesAndInstallsDictionaryThenQueriesOffline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "dictionary-test-${UUID.randomUUID()}").apply { mkdirs() }
        val preferencesName = "dictionary-test-${UUID.randomUUID()}"
        val fixture = File(directory, "fixture.sqlite")
        val destinationDirectory = File(directory, "installed")
        val destination = File(destinationDirectory, "ecdict.sqlite")
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Unconfined)
        val serverFailure = AtomicReference<Throwable?>(null)
        var server: ServerSocket? = null
        var serverThread: Thread? = null

        try {
            val database = SQLiteDatabase.openOrCreateDatabase(fixture, null)
            database.execSQL("CREATE TABLE stardict (word TEXT COLLATE NOCASE, translation TEXT)")
            database.execSQL("INSERT INTO stardict(word, translation) VALUES(?, ?)", arrayOf("see", "vt. 看见\nvi. 看"))
            database.execSQL("INSERT INTO stardict(word, translation) VALUES(?, ?)", arrayOf("book", "n. 书"))
            database.close()

            val bytes = fixture.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val localServer = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
            server = localServer
            val baseUrl = "http://127.0.0.1:${localServer.localPort}"
            serverThread = Thread {
                try {
                    localServer.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine() ?: error("Missing HTTP request")
                        check(request.startsWith("GET /ecdict.sqlite "))
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val output = socket.getOutputStream()
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.sqlite3\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                        output.flush()
                    }
                } catch (failure: Throwable) {
                    serverFailure.set(failure)
                } finally {
                    runCatching { localServer.close() }
                }
            }.apply { isDaemon = true; start() }

            val manager = EcdictDownloadManager(
                context,
                scope,
                preferencesName,
                destinationDirectory,
                EcdictManifest("test", "$baseUrl/ecdict.sqlite", hash, bytes.size.toLong(), 2),
            )
            assertNull(manager.state.value.installedVersion)

            manager.download()
            awaitIdle { manager.state.value.isBusy }

            assertNull(manager.state.value.error)
            assertEquals("test", manager.state.value.installedVersion)
            assertEquals(bytes.size.toLong(), destination.length())
            assertTrue(EcdictDictionary.atFile(destination).isInstalled())
            assertEquals("vt. 看见\nvi. 看", EcdictDictionary.atFile(destination).lookup("SEE"))
            assertEquals(mapOf("see" to "vt. 看见\nvi. 看", "book" to "n. 书"),
                EcdictDictionary.atFile(destination).lookupAll(listOf("see", "BOOK")))
            assertNull(serverFailure.get())
            serverThread.join(1000)
        } finally {
            runCatching { server?.close() }
            serverThread?.join(1000)
            supervisor.cancel()
            context.deleteSharedPreferences(preferencesName)
            directory.deleteRecursively()
        }
    }

    private suspend fun awaitIdle(isBusy: () -> Boolean) {
        withTimeout(15_000) {
            while (isBusy()) delay(25)
        }
    }
}
