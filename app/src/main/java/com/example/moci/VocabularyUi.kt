package com.example.moci

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.moci.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatMoment(time: Long?, zone: ZoneId): String = time?.let {
    if (it == 0L) "立即" else Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
} ?: "暂无"

internal fun formatAccuracy(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f%%", it * 100) } ?: "—"

@Composable
internal fun LearningScreen(
    state: LearningUiState, goal: Int, busy: Boolean, onDue: () -> Unit, onWrong: () -> Unit,
    onImport: () -> Unit, onVocabulary: () -> Unit,
) {
    val learned = state.daily[state.today]?.wordCount ?: 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("看中文，写英文", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("今日学习目标", style = MaterialTheme.typography.titleMedium)
                Text("$learned / $goal 个", style = MaterialTheme.typography.headlineMedium)
                LinearProgressIndicator(progress = { (learned.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(if (learned >= goal) "今日目标已完成" else "同一个词每天只计一次，继续积累吧。")
            }
        }
        Text("待复习 ${state.dueCount} 个 · 当前错词 ${state.wrongKeys.size} 个")
        Button(onClick = onDue, enabled = !busy && !state.loading && state.dueCount > 0, modifier = Modifier.fillMaxWidth()) { Text("开始默写") }
        OutlinedButton(onClick = onWrong, enabled = !busy && !state.loading && state.wrongKeys.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("错词专项") }
        Text("词库 ${state.snapshot.words.size} 个 · 待补释义 ${state.snapshot.words.count { it.meaning.isBlank() }} 个")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImport, enabled = !busy) { Text("导入 TXT") }
            TextButton(onClick = onVocabulary) { Text("管理词库") }
        }
        if (state.snapshot.words.isEmpty() && !state.loading) Text("手动添加或导入 UTF-8 TXT，开始建立你的词库。")
    }
}

@Composable
internal fun VocabularyScreen(
    state: LearningUiState, busy: Boolean, onAdd: () -> Unit, onImport: () -> Unit, onExport: () -> Unit,
    onDetail: (String) -> Unit, onDelete: (List<String>) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(WordFilter.ALL.name) }
    var sortName by rememberSaveable { mutableStateOf(WordSort.ALPHABETICAL.name) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val filter = WordFilter.valueOf(filterName)
    val sort = WordSort.valueOf(sortName)
    val visible = remember(state.snapshot, query, filter, sort, state.now) {
        LearningStatistics.filterWords(state.snapshot, query, filter, sort, state.now)
    }
    val keys = visible.map { it.normalizedWord }.toSet()
    val selectedKeys = selected.toSet().intersect(keys)
    LaunchedEffect(keys) { selected = selectedKeys.toList() }
    fun toggle(key: String) { selected = (if (key in selectedKeys) selectedKeys - key else selectedKeys + key).toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(query, { query = it; selected = emptyList() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索英文或中文释义") }, singleLine = true)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAdd, enabled = !busy, modifier = Modifier.weight(1f)) { Text("添加单词") }
                OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导入 TXT") }
                OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f)) { Text("导出 TXT") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu("筛选：${filter.label}", WordFilter.entries.map { it.label }) { index -> filterName = WordFilter.entries[index].name; selected = emptyList() }
                ChoiceMenu("排序：${sort.label}", WordSort.entries.map { it.label }) { index -> sortName = WordSort.entries[index].name }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} 个单词")
                TextButton(onClick = { selecting = !selecting; selected = emptyList() }, enabled = !busy) { Text(if (selecting) "退出多选" else "批量管理") }
            }
            if (selecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selected = ArrayList(keys) }, enabled = !busy) { Text("全选当前结果") }
                    TextButton(onClick = { selected = emptyList() }, enabled = !busy) { Text("清空") }
                    TextButton(onClick = { onDelete(selectedKeys.toList()) }, enabled = !busy && selectedKeys.isNotEmpty()) { Text("删除所选 ${selectedKeys.size} 个") }
                }
            }
        }
        if (visible.isEmpty() && !state.loading) item { Text(if (state.snapshot.words.isEmpty()) "词库为空，添加或导入单词开始学习。" else "没有符合条件的单词。") }
        items(visible, key = { it.normalizedWord }) { word ->
            Card(onClick = { if (selecting) toggle(word.normalizedWord) else onDetail(word.normalizedWord) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(checked = word.normalizedWord in selectedKeys,
                        onCheckedChange = { toggle(word.normalizedWord) }, enabled = !busy)
                    Column(Modifier.weight(1f)) {
                        Text(word.word, style = MaterialTheme.typography.titleMedium)
                        Text(word.meaning.ifBlank { "待补释义" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${word.attempts} 次", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, options: List<String>, onChoice: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onChoice(index) }) }
        }
    }
}

@Composable
internal fun WordEditorDialog(editor: WordEditor, busy: Boolean, onChange: (String, String) -> Unit, onClose: () -> Unit, onSave: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(if (editor.oldKey == null) "添加单词" else "编辑单词") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(editor.word, { onChange(it, editor.meaning) }, label = { Text("英文单词") }, singleLine = true, enabled = !busy)
                OutlinedTextField(editor.meaning, { onChange(editor.word, it) }, label = { Text("中文释义（可暂空）") }, minLines = 2, enabled = !busy)
                Text("编辑默认保留复习安排和学习历史。", style = MaterialTheme.typography.bodySmall)
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } })
}

@Composable
internal fun WordDetailScreen(word: WordEntity, logs: List<ReviewLogEntity>, zone: ZoneId, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit, onReset: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(word.word, style = MaterialTheme.typography.headlineMedium)
            Text(word.meaning.ifBlank { "待补释义" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("练习 ${word.attempts} 次 · 正确率 ${formatAccuracy(if (word.attempts == 0) null else word.correctCount.toDouble() / word.attempts)}")
            Text("复习等级 ${word.level}")
            Text("最近复习：${formatMoment(word.lastReviewedAt, zone)}")
            Text("下次复习：${if (word.meaning.isBlank()) "补充释义后参与复习" else formatMoment(word.nextReviewAt, zone)}")
            Row {
                TextButton(onClick = onEdit, enabled = !busy) { Text("编辑") }
                TextButton(onClick = onReset, enabled = !busy) { Text("重置进度") }
                TextButton(onClick = onDelete, enabled = !busy) { Text("删除单词") }
            }
            Text("作答历史", style = MaterialTheme.typography.titleLarge)
        }
        if (logs.isEmpty()) item { Text("暂无作答记录") }
        items(logs.sortedWith(compareByDescending<ReviewLogEntity> { it.answeredAt }.thenByDescending { it.id }), key = { it.id }) { log ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (log.correct) "回答正确" else "回答错误", color = if (log.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("${formatMoment(log.answeredAt, zone)} · 耗时 ${String.format(Locale.ROOT, "%.1f", log.elapsedMillis / 1000.0)} 秒")
                }
            }
        }
    }
}

@Composable
internal fun LearningSettings(goal: Int, busy: Boolean, onGoal: (String) -> Boolean, onBackup: () -> Unit, onRestore: () -> Unit) {
    var value by rememberSaveable(goal) { mutableStateOf(goal.toString()) }
    var error by rememberSaveable { mutableStateOf(false) }
    Text("学习设置", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(value, { value = it; error = false }, label = { Text("每日目标（不同单词数）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !busy,
        isError = error, supportingText = { Text(if (error) "请输入正整数" else "同词每天只计一次，修改后当日生效。") })
    var saved by remember { mutableStateOf(false) }
    Button(onClick = { val success = onGoal(value); error = !success; saved = success }, enabled = !busy) { Text("保存每日目标") }
    if (saved) Text("每日目标已保存")
    Text("数据备份", style = MaterialTheme.typography.titleLarge)
    Text("完整备份包含词条、释义、复习进度及作答记录。覆盖恢复前可预览内容。")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onBackup, enabled = !busy) { Text("导出完整备份") }
        OutlinedButton(onClick = onRestore, enabled = !busy) { Text("恢复备份") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    HorizontalDivider()
}
