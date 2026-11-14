package com.example.moci

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.moci.dictionary.EcdictDownloadState
import com.example.moci.dictionary.EcdictManifest
import com.example.moci.update.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateUiTest {
    @get:Rule val compose = createComposeRule()
    private val release = ReleaseInfo("v0.5", "更新说明：增加设置与后台下载", "Word-App-v0.5.apk", "https://github.com/lin04-24/Word-App/releases/download/v0.5/Word-App-v0.5.apk", 2_000_000, "a".repeat(64), null)

    @Test fun settingsToggleAndManualResultsAreVisible() {
        val state = mutableStateOf(UpdateUiState())
        val autoFillOnImport = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                UpdateSettingsScreen(state.value, {}, { state.value = state.value.copy(autoCheckEnabled = it) },
                    { state.value = state.value.copy(check = UpdateCheckState.Checking) }, {},
                    dictionaryState = EcdictDownloadState(
                        configured = true,
                        manifest = EcdictManifest("test", "https://dict.example.com/ecdict.sqlite", "a".repeat(64), 1000, 1),
                    ),
                    dictionaryAutoFillOnImport = autoFillOnImport.value,
                    onDictionaryAutoFillChange = { autoFillOnImport.value = it },
                )
            }
        }
        compose.onNodeWithText("当前版本：0.4").assertIsDisplayed()
        compose.onNodeWithContentDescription("TXT 导入自动填入词典释义开关").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("下载离线词典").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("启动时检查更新开关").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("检查更新").performClick()
        compose.onNodeWithText("检查中…").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(check = UpdateCheckState.Current) }
        compose.onNodeWithText("已是最新版本").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(check = UpdateCheckState.Error("GitHub 请求受限，请稍后重试")) }
        compose.onNodeWithText("GitHub 请求受限，请稍后重试").assertIsDisplayed()
    }

    @Test fun updateDialogShowsNotesProgressRetryAndInstallation() {
        val state = mutableStateOf(UpdateUiState(check = UpdateCheckState.Available(release), showDialog = true))
        var downloads = 0
        var installs = 0
        compose.setContent {
            MaterialTheme {
                UpdateDialog(state.value, {}, { downloads++ }, {}, { installs++ })
            }
        }
        compose.onNodeWithText("更新说明：增加设置与后台下载").assertIsDisplayed()
        compose.onNodeWithText("下载更新").performClick()
        assertEquals(1, downloads)
        compose.runOnIdle { state.value = state.value.copy(download = DownloadState.Running(release, 1_000_000, 2_000_000)) }
        compose.onNodeWithText("正在下载 50%", substring = true).assertIsDisplayed()
        compose.onNodeWithText("后台下载").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(download = DownloadState.Failed(release, "网络连接失败")) }
        compose.onNodeWithText("重新下载").performClick()
        assertEquals(2, downloads)
        compose.runOnIdle { state.value = state.value.copy(download = DownloadState.Ready(release, File("fixture.apk"))) }
        compose.onNodeWithText("下载完成，安装包校验通过").assertIsDisplayed()
        compose.onNodeWithText("立即安装").performClick()
        assertEquals(1, installs)
    }
}
