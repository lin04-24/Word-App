package com.example.moci

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.moci.data.PracticeMode
import com.example.moci.update.*
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { MociApp() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MociApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val updateViewModel: UpdateViewModel = viewModel(factory = remember { AndroidUpdateServices.factory(context) })
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val dictionaryState by viewModel.offlineDictionary.collectAsStateWithLifecycle()
    val state by viewModel.learning.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val goal by viewModel.dailyGoal.collectAsStateWithLifecycle()
    val dictionaryAutoFillOnImport by viewModel.dictionaryAutoFillOnImport.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val detailKey by viewModel.detailKey.collectAsStateWithLifecycle()
    val confirmation by viewModel.confirmation.collectAsStateWithLifecycle()
    val preview by viewModel.backupPreview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val appContext = context.applicationContext
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.readAndImport(appContext, it) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { viewModel.exportFile(appContext, it, false) }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportFile(appContext, it, true) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewBackup(appContext, it) }
    }
    val onImport = { import.launch(arrayOf("text/plain", "application/octet-stream")) }
    LaunchedEffect(message) {
        message?.let { viewModel.consumeMessage(); snackbar.showSnackbar(it) }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, viewModel) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { viewModel.refreshTime() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTime() }
        owner.lifecycle.addObserver(observer)
        onDispose { context.unregisterReceiver(receiver); owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(viewModel, settings) { if (!settings) while (true) { viewModel.refreshTime(); delay(30_000) } }

    BackHandler(enabled = !settings && session == null && (detailKey != null || tab != 0)) {
        if (detailKey != null) viewModel.showDetail(null) else tab = 0
    }
    val detail = state.snapshot.words.find { it.normalizedWord == detailKey }
    if (settings) {
        UpdateSettingsScreen(updateState, { settings = false }, updateViewModel::setAutoCheck,
            { updateViewModel.check() }, updateViewModel::openDialog,
            snackbarHost = { SnackbarHost(snackbar) },
            dictionaryState = dictionaryState,
            onDictionaryDownload = viewModel::downloadOfflineDictionary,
            onDictionaryCancel = viewModel::cancelOfflineDictionaryDownload,
            dictionaryAutoFillOnImport = dictionaryAutoFillOnImport,
            onDictionaryAutoFillChange = viewModel::setDictionaryAutoFillOnImport,
        ) {
            LearningSettings(goal, busy, viewModel::setDailyGoal,
                { backup.launch("默词备份-${state.today}.json") },
                { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
        }
    } else if (session != null) {
        BackHandler { if (session?.isSubmitting != true) viewModel.endReview() }
        ReviewScreen(session!!, snackbar, viewModel::endReview, viewModel::submitAnswer, viewModel::continueReview)
    } else {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text(if (detailKey != null) "单词详情" else listOf("默词", "我的词库", "学习统计")[tab]) },
                    navigationIcon = { if (detailKey != null) TextButton(onClick = { viewModel.showDetail(null) }) { Text("返回") } },
                    actions = { TextButton(onClick = { settings = true }, enabled = !busy) { Text("设置") } })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (detailKey == null) NavigationBar {
                    listOf("学习", "词库", "统计").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Text(listOf("学", "词", "图")[index]) }, label = { Text(label) })
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (busy || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    detail != null -> WordDetailScreen(detail, state.snapshot.logs.filter { it.normalizedWord == detailKey }, state.zone, busy,
                        { viewModel.openEditor(detail) }, { viewModel.requestDelete(listOf(detail.normalizedWord)) },
                        { viewModel.requestReset(detail.normalizedWord) })
                    detailKey != null -> Text(if (state.loading) "正在读取单词…" else "单词已移除，请返回词库", Modifier.padding(20.dp))
                    tab == 0 -> LearningScreen(state, goal, busy, { viewModel.beginReview(PracticeMode.DUE) },
                        { viewModel.beginReview(PracticeMode.WRONG) }, onImport, { tab = 1 })
                    tab == 1 -> VocabularyScreen(state, busy, { viewModel.openEditor() }, onImport,
                        { export.launch("默词词表-${state.today}.txt") }, viewModel::showDetail, viewModel::requestDelete)
                    else -> StatisticsScreen(state, goal)
                }
            }
        }
    }
    editor?.let {
        WordEditorDialog(it, busy, dictionaryState.isInstalled, viewModel::editDraft,
            viewModel::closeEditor, viewModel::saveEditor, viewModel::lookupEditorMeaning)
    }
    confirmation?.let { action ->
        AlertDialog(onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(if (action.reset) "重置复习安排？" else "删除 ${action.keys.size} 个单词？") },
            text = { Text(if (action.reset) "等级回到初始并立即进入待复习。作答历史和累计次数保留。"
                else "词条及其全部作答记录将一并删除，历史图表会重新统计。恢复需要使用已有备份。") },
            confirmButton = { TextButton(onClick = viewModel::confirmWordAction, enabled = !busy) { Text(if (busy) "处理中…" else "确认") } },
            dismissButton = { TextButton(onClick = viewModel::dismissConfirmation, enabled = !busy) { Text("取消") } })
    }
    preview?.let { data ->
        AlertDialog(onDismissRequest = viewModel::dismissBackup, title = { Text("覆盖恢复备份？") },
            text = { Text("备份时间：${formatMoment(data.exportedAt, state.zone)}\n${data.snapshot.words.size} 个单词 · ${data.snapshot.logs.size} 条作答记录\n\n确认后将整体替换本机词库、复习进度和作答记录。每日目标及更新设置保留。") },
            confirmButton = { TextButton(onClick = viewModel::restoreBackup, enabled = !busy) { Text(if (busy) "恢复中…" else "确认覆盖恢复") } },
            dismissButton = { TextButton(onClick = viewModel::dismissBackup, enabled = !busy) { Text("取消") } })
    }
    UpdateHost(updateViewModel, updateState,
        mayPrompt = session == null && editor == null && confirmation == null && preview == null && !busy)
}
