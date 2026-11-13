package com.example.moci.update

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryUpdatePreferences : UpdatePreferences {
    override var autoCheckEnabled = true
    var record: DownloadRecord? = null
    override fun readDownload() = record
    override fun saveDownload(record: DownloadRecord?) { this.record = record }
}

class FakeRepository : UpdateRepository {
    var calls = 0
    var release = sampleRelease()
    var failure: Exception? = null
    var waitForLatest: (suspend () -> Unit)? = null
    override suspend fun latest(): ReleaseInfo { calls++; waitForLatest?.invoke(); failure?.let { throw it }; return release }
    override suspend fun checksum(release: ReleaseInfo): String { failure?.let { throw it }; return release.sha256!! }
}

class UpdateDownloadControllerTest {
    @get:Rule val temp = TemporaryFolder()
    private val preferences = MemoryUpdatePreferences()
    private val repository = FakeRepository()
    private var validations = 0
    private var invalid = false
    private val engine = object : DownloadEngine {
        var starts = 0
        var snapshot = TransferSnapshot(TransferStatus.RUNNING, 20, 100)
        var lastFile: File? = null
        override fun enqueue(release: ReleaseInfo, file: File): Long { starts++; lastFile = file; file.writeText("fixture"); return starts.toLong() }
        override fun query(id: Long) = snapshot
        override fun remove(id: Long) { lastFile?.delete() }
    }
    private fun controller(version: String = "0.2") = UpdateDownloadController(repository, preferences, engine,
        PackageVerifier { file, _, _ -> validations++; if (invalid || !file.exists()) throw UpdateException("校验失败") }, temp.root, version)

    @Test fun avoidsDuplicateDownloadsAndResumesAfterControllerRecreation() = runBlocking {
        val first = controller()
        listOf(async { first.start(sampleRelease()) }, async { first.start(sampleRelease()) }).forEach { it.await() }
        assertEquals(1, engine.starts)
        val recreated = controller()
        recreated.restore()
        assertEquals(20L, (recreated.state.value as DownloadState.Running).bytes)
        engine.snapshot = TransferSnapshot(TransferStatus.COMPLETE)
        recreated.refresh()
        assertTrue(recreated.state.value is DownloadState.Ready)
        recreated.refresh()
        assertEquals(1, validations)
        controller().restore()
        assertEquals(2, validations)
    }

    @Test fun cancelRemovesTaskRecordAndPartialFileThenAllowsRetry() = runBlocking {
        val controller = controller()
        controller.start(sampleRelease())
        val file = engine.lastFile!!
        controller.cancel()
        assertEquals(DownloadState.Idle, controller.state.value)
        assertNull(preferences.record)
        assertFalse(file.exists())
        controller.start(sampleRelease())
        assertEquals(2, engine.starts)
    }

    @Test fun verificationFailureDeletesFileAndPreventsInstallation() = runBlocking {
        val controller = controller()
        controller.start(sampleRelease())
        invalid = true
        engine.snapshot = TransferSnapshot(TransferStatus.COMPLETE)
        controller.refresh()
        assertTrue(controller.state.value is DownloadState.Failed)
        assertNull(controller.verifiedFile())
        assertNull(preferences.record)
        assertFalse(engine.lastFile!!.exists())
    }

    @Test fun installationRechecksReadyFileInsteadOfTrustingEarlierValidation() = runBlocking {
        val controller = controller()
        controller.start(sampleRelease())
        engine.snapshot = TransferSnapshot(TransferStatus.COMPLETE)
        controller.refresh()
        invalid = true
        assertNull(controller.verifiedFile())
        assertTrue(controller.state.value is DownloadState.Failed)
    }

    @Test fun failedMissingAndPausedTasksHaveRecoverableStates() = runBlocking {
        for (status in listOf(TransferStatus.FAILED, TransferStatus.MISSING)) {
            val controller = controller()
            controller.start(sampleRelease())
            engine.snapshot = TransferSnapshot(status)
            controller.refresh()
            assertTrue(controller.state.value is DownloadState.Failed)
            assertNull(preferences.record)
        }
        val controller = controller()
        controller.start(sampleRelease())
        engine.snapshot = TransferSnapshot(TransferStatus.PAUSED, 10, -1)
        controller.refresh()
        assertTrue((controller.state.value as DownloadState.Running).paused)
    }

    @Test fun upgradeCleansOldDownloadAndChecksumErrorsDoNotStartTransfers() = runBlocking {
        controller().start(sampleRelease())
        val upgraded = controller("0.3")
        upgraded.restore()
        assertEquals(DownloadState.Idle, upgraded.state.value)
        assertNull(preferences.record)
        repository.failure = UpdateException("校验信息缺失")
        controller().start(sampleRelease())
        assertEquals(1, engine.starts)
    }

    @Test fun packagePolicyRejectsWrongPackageSignerVersionAndTag() {
        val installed = PackageIdentity("com.example.moci", "0.2", 2, setOf("signer"))
        val good = installed.copy(versionName = "0.3", versionCode = 3)
        UpdatePackagePolicy.verify(installed, good, "v0.3")
        listOf(good.copy(packageName = "other"), good.copy(signers = setOf("other")), good.copy(signers = emptySet()),
            good.copy(versionCode = 2), good.copy(versionName = "0.1"), good.copy(versionName = "0.4")).forEach { candidate ->
            assertThrows(UpdateException::class.java) { UpdatePackagePolicy.verify(installed, candidate, "v0.3") }
        }
    }
}
