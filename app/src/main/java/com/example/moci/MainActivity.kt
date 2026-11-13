package com.example.moci

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.moci.data.WordEntity
import com.example.moci.update.AndroidUpdateServices
import com.example.moci.update.UpdateHost
import com.example.moci.update.UpdateSettingsScreen
import com.example.moci.update.UpdateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MociApp()
                }
            }
        }
    }
}

@Composable
private fun MociApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val updateViewModel: UpdateViewModel = viewModel(factory = remember { AndroidUpdateServices.factory(context) })
    val updateState by updateViewModel.state.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val words by viewModel.words.collectAsState()
    val session by viewModel.session.collectAsState()
    val message by viewModel.message.collectAsState()
    var editingWord by remember { mutableStateOf<WordEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.readAndImport(context, uri)
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message!!)
            viewModel.consumeMessage()
        }
    }

    if (showSettings) {
        UpdateSettingsScreen(
            state = updateState,
            onBack = { showSettings = false },
            onAutoCheckChanged = updateViewModel::setAutoCheck,
            onCheck = { updateViewModel.check() },
            onOpenUpdate = updateViewModel::openDialog,
        )
    } else if (session != null) {
        ReviewScreen(
            session = session!!,
            snackbarHostState = snackbarHostState,
            onBack = viewModel::endReview,
            onSubmit = viewModel::submitAnswer,
            onContinue = viewModel::continueReview,
        )
    } else {
        HomeScreen(
            words = words,
            snackbarHostState = snackbarHostState,
            onImport = { importLauncher.launch(arrayOf("text/plain", "application/octet-stream")) },
            onStart = viewModel::beginReview,
            onEditWord = { editingWord = it },
            onSettings = { showSettings = true },
        )
    }

    UpdateHost(updateViewModel, updateState, mayPrompt = session == null && editingWord == null)

    editingWord?.let { word ->
        MeaningDialog(
            word = word,
            onDismiss = { editingWord = null },
            onSave = { meaning ->
                viewModel.saveMeaning(word.normalizedWord, meaning)
                editingWord = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    words: List<WordEntity>,
    snackbarHostState: SnackbarHostState,
    onImport: () -> Unit,
    onStart: () -> Unit,
    onEditWord: (WordEntity) -> Unit,
    onSettings: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val dueCount = words.count { it.meaning.isNotBlank() && it.nextReviewAt <= now }
    val missingMeaning = words.count { it.meaning.isBlank() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("默词") }, actions = { TextButton(onClick = onSettings) { Text("设置") } }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("看中文，写英文", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("待复习", style = MaterialTheme.typography.titleMedium)
                    Text("$dueCount", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("词库 ${words.size} 个 · 待补释义 $missingMeaning 个")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, enabled = dueCount > 0, modifier = Modifier.weight(1f)) {
                    Text("开始默写")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Text("导入 TXT")
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("我的词库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (words.isEmpty()) {
                Text("导入 UTF-8 TXT 开始建立词库。")
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(words, key = { it.normalizedWord }) { word ->
                        Card(onClick = { onEditWord(word) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(word.word, fontWeight = FontWeight.Medium)
                                    Text(
                                        word.meaning.ifBlank { "点击补充中文释义" },
                                        color = if (word.meaning.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(if (word.meaning.isBlank()) "补释义" else "${word.attempts} 次")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewScreen(
    session: ReviewSession,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onContinue: () -> Unit,
) {
    var answer by rememberSaveable(session.current?.normalizedWord, session.answer != null) { mutableStateOf("") }
    val current = session.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("默写 · ${session.passed}/${session.initialCount}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            when {
                session.answer != null -> {
                    val result = session.answer
                    Spacer(Modifier.height(36.dp))
                    Text(if (result.correct) "回答正确" else "再记一下", style = MaterialTheme.typography.headlineMedium, color = if (result.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(18.dp))
                    Text("正确答案：${result.expectedWord}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!result.correct) {
                        Spacer(Modifier.height(8.dp))
                        Text("你的答案：${result.enteredAnswer.ifBlank { "（未填写）" }}")
                    }
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = onContinue) {
                        Text(if (session.queue.isEmpty()) "完成本轮" else "继续")
                    }
                }
                current == null -> {
                    Text("本轮完成", style = MaterialTheme.typography.headlineMedium)
                    Text("答对 ${session.passed} 个 · 错误重练 ${session.mistakes} 次")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onBack) { Text("返回词库") }
                }
                else -> {
                    Text("${session.passed + 1} / ${session.initialCount} · 错误重练 ${session.mistakes} 次", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(40.dp))
                    Text(current.meaning, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(28.dp))
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("默写英文单词") },
                        singleLine = true,
                        enabled = !session.isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (answer.isNotBlank()) onSubmit(answer) }),
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onSubmit(answer) },
                        enabled = answer.isNotBlank() && !session.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (session.isSubmitting) "保存中…" else "检查答案")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { onSubmit("") },
                        enabled = !session.isSubmitting,
                    ) {
                        Text("想不起来")
                    }
                    if (session.isSubmitting) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在保存作答…")
                    }
                }
            }
        }
    }
}

@Composable
private fun MeaningDialog(
    word: WordEntity,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var meaning by rememberSaveable(word.normalizedWord) { mutableStateOf(word.meaning) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(word.word) },
        text = {
            OutlinedTextField(
                value = meaning,
                onValueChange = { meaning = it },
                label = { Text("中文释义") },
                placeholder = { Text("输入一个或多个释义") },
                minLines = 2,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(meaning) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
