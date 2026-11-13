package com.example.moci

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.moci.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateInstallFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val store = ViewModelStore()
    private val file = File(context.getExternalFilesDir(null), "updates/install-fixture.apk")

    @After fun cleanUp() {
        device.pressBack()
        compose.runOnUiThread { store.clear() }
        file.delete()
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES default")
    }

    @Test fun permissionDenialGrantAndInstallerCancellationKeepTheDownload() {
        device.executeShellCommand("appops set ${context.packageName} REQUEST_INSTALL_PACKAGES default")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("update-valid.apk").use { it.readBytes() }
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = ReleaseInfo("v0.3", "安装权限流程测试", "update-valid.apk", "https://example.test/update.apk", bytes.size.toLong(), sha, null)
        val downloads = object : UpdateDownloads {
            override val state = MutableStateFlow<DownloadState>(DownloadState.Ready(release, file))
            override suspend fun restore() = Unit
            override suspend fun refresh() = Unit
            override suspend fun start(release: ReleaseInfo) = Unit
            override suspend fun cancel() = Unit
            override suspend fun verifiedFile(): File = withContext(Dispatchers.IO) {
                AndroidApkVerifier(context).verify(file, release, sha)
                file
            }
        }
        val preferences = object : UpdatePreferences {
            override var autoCheckEnabled = false
            override fun readDownload(): DownloadRecord? = null
            override fun saveDownload(record: DownloadRecord?) = Unit
        }
        val repository = object : UpdateRepository {
            override suspend fun latest() = release
            override suspend fun checksum(release: ReleaseInfo) = sha
        }
        lateinit var model: UpdateViewModel
        compose.runOnUiThread {
            model = UpdateViewModel(repository, downloads, preferences, StartupCheckGate(), "0.2")
            store.put("update-test", model)
            model.openDialog()
        }
        compose.setContent {
            val state by model.state.collectAsState()
            MaterialTheme { UpdateHost(model, state, mayPrompt = true) }
        }
        compose.onNodeWithText("立即安装").performClick()
        assertTrue("Unknown-source settings should open", device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 30_000))
        device.pressBack()
        compose.onNodeWithText("安装授权尚未开启", substring = true).assertIsDisplayed()
        assertTrue(file.exists())

        compose.onNodeWithText("立即安装").performClick()
        val permissionSwitch = device.wait(Until.findObject(By.checkable(true)), 30_000)
        assertNotNull("Install-source switch should be present", permissionSwitch)
        if (!permissionSwitch.isChecked) permissionSwitch.click()
        device.pressBack()
        val cancel = device.wait(Until.findObject(By.text("Cancel")), 30_000)
        assertNotNull("System APK installer should open after granting permission", cancel)
        cancel.click()
        compose.onNodeWithText("立即安装").assertIsDisplayed()
        assertTrue(file.exists())
    }
}
