package com.example.moci.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.moci.BuildConfig
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsScreen(
    state: UpdateUiState,
    onBack: () -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onOpenUpdate: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    learningContent: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("设置") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
    }, snackbarHost = snackbarHost) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            learningContent()
            Text("应用更新", style = MaterialTheme.typography.titleLarge)
            Text("当前版本：${BuildConfig.VERSION_NAME}")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启动时检查更新")
                    Text("仅检查版本，下载前会征求确认", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.autoCheckEnabled, onCheckedChange = onAutoCheckChanged,
                    modifier = Modifier.semantics { contentDescription = "启动时检查更新开关" })
            }
            Button(onClick = onCheck, enabled = state.check != UpdateCheckState.Checking && !state.download.isBusy()) {
                Text(if (state.check == UpdateCheckState.Checking) "检查中…" else "检查更新")
            }
            Text(when (val check = state.check) {
                UpdateCheckState.Idle -> "从 GitHub Releases 获取正式版本"
                UpdateCheckState.Checking -> "正在检查最新版本…"
                UpdateCheckState.Current -> "已是最新版本"
                is UpdateCheckState.Available -> "发现新版本 ${check.release.tag}"
                is UpdateCheckState.Error -> check.message
            })
            if (state.release != null) {
                DownloadStatus(state.download)
                TextButton(onClick = onOpenUpdate) {
                    Text(if (state.download is DownloadState.Ready) "立即安装" else if (state.download.isBusy()) "查看下载进度" else "查看更新")
                }
            }
            Text("检查更新和下载仅连接 GitHub 及其下载服务，不上传词库或作答记录。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun UpdateDialog(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    installing: Boolean = false,
) {
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新至 ${release.tag}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("安装包 ${formatUpdateSize(release.size)}")
                Text(release.notes, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
                DownloadStatus(state.download)
                state.installMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when (state.download) {
                is DownloadState.Ready -> TextButton(onClick = onInstall, enabled = !installing) { Text(if (installing) "准备安装…" else "立即安装") }
                is DownloadState.Preparing, is DownloadState.Verifying -> Text("请稍候…", Modifier.padding(12.dp))
                is DownloadState.Running -> TextButton(onClick = onCancel) { Text("取消下载") }
                is DownloadState.Failed -> TextButton(onClick = onDownload) { Text("重新下载") }
                DownloadState.Idle -> TextButton(onClick = onDownload) { Text("下载更新") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (state.download.isBusy()) "后台下载" else "稍后") } },
    )
}

@Composable
private fun DownloadStatus(download: DownloadState) {
    when (download) {
        DownloadState.Idle -> Unit
        is DownloadState.Preparing -> Text("正在准备下载…")
        is DownloadState.Verifying -> Text("正在校验安装包…")
        is DownloadState.Ready -> Text("下载完成，安装包校验通过")
        is DownloadState.Failed -> Text(download.message, color = MaterialTheme.colorScheme.error)
        is DownloadState.Running -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (download.total > 0) {
                    val progress = (download.bytes.toFloat() / download.total).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${if (download.paused) "等待网络或下载服务" else "正在下载"} ${(progress * 100).toInt()}% · ${formatUpdateSize(download.bytes)} / ${formatUpdateSize(download.total)}")
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("${if (download.paused) "等待网络或下载服务" else "正在下载"} · ${formatUpdateSize(download.bytes)}")
                }
            }
        }
    }
}

private fun formatUpdateSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes.coerceAtLeast(0) / 1024.0 / 1024.0)

/** All activity launches stay in the UI; background completion only changes persisted download state. */
@Composable
fun UpdateHost(viewModel: UpdateViewModel, state: UpdateUiState, mayPrompt: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var installing by remember { mutableStateOf(false) }
    var pendingPermission by rememberSaveable { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.installMessage("安装已返回；若尚未安装，可再次点击“立即安装”")
    }
    val launchInstall: () -> Unit = {
        if (!installing) {
            installing = true
            scope.launch {
                try {
                    viewModel.installerFile()?.let { installer.launch(updateInstallIntent(context, it)) }
                } catch (e: Exception) {
                    viewModel.installMessage("打开系统安装界面失败：${e.localizedMessage ?: "请重试"}")
                } finally {
                    installing = false
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingPermission) {
            pendingPermission = false
            if (context.packageManager.canRequestPackageInstalls()) launchInstall()
            else viewModel.installMessage("安装授权尚未开启，安装包已保留，可再次点击“立即安装”")
        }
    }
    val requestInstall: () -> Unit = {
        viewModel.consumeAutoInstall()
        viewModel.installMessage(null)
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstall()
        } else {
            try {
                pendingPermission = true
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (e: Exception) {
                pendingPermission = false
                viewModel.installMessage("请在系统设置中允许默词安装应用后重试")
            }
        }
    }

    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; viewModel.setForeground(true) }
                Lifecycle.Event.ON_PAUSE -> { foreground = false; viewModel.setForeground(false) }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        viewModel.setForeground(foreground)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.setForeground(false) }
    }

    LaunchedEffect(state.download, state.autoInstall, state.showDialog, foreground, mayPrompt) {
        if (state.download is DownloadState.Ready && state.autoInstall && state.showDialog && foreground && mayPrompt) requestInstall()
    }
    if (state.showDialog && mayPrompt) {
        UpdateDialog(state, viewModel::dismissDialog, viewModel::startDownload, viewModel::cancelDownload, requestInstall, installing)
    }
}
