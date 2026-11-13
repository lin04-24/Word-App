package com.example.moci.update

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val preferences = MemoryUpdatePreferences()
    private val repository = FakeRepository()
    private val downloads = object : UpdateDownloads {
        override val state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        var starts = 0
        override suspend fun restore() = Unit
        override suspend fun refresh() = Unit
        override suspend fun start(release: ReleaseInfo) { starts++; state.value = DownloadState.Running(release, 0, 100) }
        override suspend fun cancel() { state.value = DownloadState.Idle }
        override suspend fun verifiedFile(): File? = null
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    private fun model(gate: StartupCheckGate = StartupCheckGate(), key: String = "main") =
        UpdateViewModel(repository, downloads, preferences, gate, "0.2").also { store.put(key, it) }

    @Test fun checksOncePerProcessAndDisabledPreferenceSurvivesNewViewModel() = runTest(dispatcher) {
        val gate = StartupCheckGate()
        val model = model(gate)
        advanceUntilIdle()
        assertEquals(1, repository.calls)
        assertTrue(model.state.value.showDialog)
        model.dismissDialog()
        model(gate, "recreated")
        advanceUntilIdle()
        assertEquals(1, repository.calls)
        model.setAutoCheck(false)
        val fresh = model(StartupCheckGate(), "fresh")
        advanceUntilIdle()
        assertFalse(fresh.state.value.autoCheckEnabled)
        assertEquals(1, repository.calls)
        fresh.check()
        advanceUntilIdle()
        assertEquals(2, repository.calls)
        assertTrue(fresh.state.value.showDialog)
    }

    @Test fun duplicateChecksShareAnActiveRequestAndFailuresAreQuietAtStartup() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.waitForLatest = { gate.await() }
        repository.failure = UpdateException("GitHub 请求受限")
        val model = model()
        model.check(); model.check()
        runCurrent()
        assertEquals(1, repository.calls)
        assertEquals(UpdateCheckState.Checking, model.state.value.check)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.state.value.check is UpdateCheckState.Error)
        assertFalse(model.state.value.showDialog)
        repository.failure = null
        model.check()
        advanceUntilIdle()
        assertTrue(model.state.value.showDialog)
    }

    @Test fun equalOlderAndBuildMetadataVersionsDoNotPrompt() = runTest(dispatcher) {
        preferences.autoCheckEnabled = false
        val model = model()
        for (tag in listOf("v0.2", "0.2.0+build.1", "v0.1", "0.2.0-rc.1")) {
            repository.release = sampleRelease().copy(tag = tag)
            model.check()
            advanceUntilIdle()
            assertEquals(UpdateCheckState.Current, model.state.value.check)
            assertFalse(model.state.value.showDialog)
        }
    }

    @Test fun onlyUserStartsDownloadAndBackgroundingDisarmsAutomaticInstallation() = runTest(dispatcher) {
        val model = model()
        advanceUntilIdle()
        assertEquals(0, downloads.starts)
        model.setForeground(true)
        model.startDownload(); model.startDownload()
        runCurrent()
        assertEquals(1, downloads.starts)
        assertTrue(model.state.value.autoInstall)
        model.setForeground(false)
        downloads.state.value = DownloadState.Ready(sampleRelease(), File("fixture.apk"))
        runCurrent()
        assertFalse(model.state.value.autoInstall)
        model.openDialog()
        model.cancelDownload()
        advanceUntilIdle()
        assertEquals(DownloadState.Idle, model.state.value.download)
    }
}
