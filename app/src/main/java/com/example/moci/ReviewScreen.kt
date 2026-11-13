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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(
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
                title = { Text("${if (session.mode == com.example.moci.data.PracticeMode.WRONG) "错词专项" else "默写"} · ${session.passed}/${session.initialCount}") },
                navigationIcon = { TextButton(onClick = onBack, enabled = !session.isSubmitting) { Text("返回") } },
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
