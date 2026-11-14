package com.example.moci

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.example.moci.dictionary.EcdictDictionary
import com.example.moci.dictionary.EcdictDownloadManager
import com.example.moci.dictionary.EcdictDownloadState
import com.example.moci.data.ReviewQueue
import com.example.moci.data.ReviewScheduler
import com.example.moci.data.VocabularyDatabase
import com.example.moci.data.VocabularyRepository
import com.example.moci.data.WordEntity
import com.example.moci.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.time.LocalDate
import java.time.ZoneId

data class ReviewAnswer(
    val expectedWord: String,
    val enteredAnswer: String,
    val correct: Boolean,
)

data class ReviewSession(
    val queue: List<WordEntity>,
    val initialCount: Int,
    val passed: Int = 0,
    val mistakes: Int = 0,
    val promptStartedAt: Long = SystemClock.elapsedRealtime(),
    val answer: ReviewAnswer? = null,
    val isSubmitting: Boolean = false,
    val mode: PracticeMode = PracticeMode.DUE,
) {
    val current: WordEntity? get() = queue.firstOrNull()
}

data class LearningUiState(
    val snapshot: VocabularySnapshot = VocabularySnapshot(),
    val daily: Map<LocalDate, DayStatistics> = emptyMap(),
    val wrongKeys: Set<String> = emptySet(),
    val now: Long = System.currentTimeMillis(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val loading: Boolean = true,
) {
    val today: LocalDate get() = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val dueCount: Int get() = snapshot.words.count { it.meaning.isNotBlank() && it.nextReviewAt <= now }
}

data class WordEditor(val oldKey: String?, val word: String, val meaning: String, val error: String? = null)
data class WordConfirmation(val reset: Boolean, val keys: List<String>)

class MainViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repository = VocabularyRepository(VocabularyDatabase.getInstance(application))
    private val dictionary = EcdictDictionary(application)
    private val dictionaryDownloads = EcdictDownloadManager(application, viewModelScope)
    val offlineDictionary: StateFlow<EcdictDownloadState> = dictionaryDownloads.state
    private val clock = MutableStateFlow(System.currentTimeMillis() to ZoneId.systemDefault())
    val learning = combine(repository.observeSnapshot(), clock) { snapshot, time ->
        LearningUiState(snapshot, LearningStatistics.daily(snapshot.logs, time.second),
            LearningStatistics.wrongKeys(snapshot), time.first, time.second, loading = false)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearningUiState())

    private val preferences = application.getSharedPreferences("learning", Context.MODE_PRIVATE)
    private val _dailyGoal = MutableStateFlow(preferences.getInt("daily_goal", 20).coerceAtLeast(1))
    val dailyGoal: StateFlow<Int> = _dailyGoal
    private val _dictionaryAutoFillOnImport = MutableStateFlow(preferences.getBoolean("dictionary_autofill_on_import", true))
    val dictionaryAutoFillOnImport: StateFlow<Boolean> = _dictionaryAutoFillOnImport
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _editor = MutableStateFlow<WordEditor?>(if (savedState.get<Boolean>("editor_open") == true)
        WordEditor(savedState["editor_key"], savedState["editor_word"] ?: "", savedState["editor_meaning"] ?: "") else null)
    val editor: StateFlow<WordEditor?> = _editor
    val detailKey = savedState.getStateFlow<String?>("detail_key", null)
    private val _confirmation = MutableStateFlow<WordConfirmation?>(null)
    val confirmation: StateFlow<WordConfirmation?> = _confirmation
    private val _backupPreview = MutableStateFlow<VocabularyBackup?>(null)
    val backupPreview: StateFlow<VocabularyBackup?> = _backupPreview

    fun refreshTime() { clock.value = System.currentTimeMillis() to ZoneId.systemDefault() }
    fun downloadOfflineDictionary() { dictionaryDownloads.download() }
    fun cancelOfflineDictionaryDownload() { dictionaryDownloads.cancel() }
    fun showDetail(key: String?) { savedState["detail_key"] = key }
    fun openEditor(word: WordEntity? = null) { setEditor(WordEditor(word?.normalizedWord, word?.word ?: "", word?.meaning ?: "")) }
    fun closeEditor() { if (!_busy.value) setEditor(null) }
    fun editDraft(word: String, meaning: String) { _editor.value?.let { setEditor(it.copy(word = word, meaning = meaning, error = null)) } }
    private fun setEditor(editor: WordEditor?) {
        savedState["editor_open"] = editor != null
        savedState["editor_key"] = editor?.oldKey
        savedState["editor_word"] = editor?.word
        savedState["editor_meaning"] = editor?.meaning
        _editor.value = editor
    }

    fun saveEditor() {
        val draft = _editor.value ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val key = repository.saveWord(draft.oldKey, draft.word, draft.meaning)
                if (detailKey.value == draft.oldKey && draft.oldKey != null) showDetail(key)
                setEditor(null)
                _message.value = "单词已保存"
            } catch (e: Exception) {
                setEditor(draft.copy(error = e.localizedMessage ?: "保存失败，请重试"))
            } finally { _busy.value = false }
        }
    }

    fun lookupEditorMeaning() {
        val draft = _editor.value ?: return
        if (draft.word.isBlank()) return
        launchOperation {
            val meaning = withContext(Dispatchers.IO) { dictionary.lookup(draft.word) }
            val current = _editor.value ?: return@launchOperation
            if (!current.word.equals(draft.word, ignoreCase = true)) return@launchOperation
            if (meaning.isNullOrBlank()) {
                _message.value = if (dictionary.isInstalled()) "词典中没有找到这个单词" else "请先在设置中下载离线词典"
            } else {
                setEditor(current.copy(meaning = meaning, error = null))
                _message.value = "已填入词典释义，可继续编辑"
            }
        }
    }

    fun requestDelete(keys: List<String>) { if (!_busy.value && keys.isNotEmpty()) _confirmation.value = WordConfirmation(false, keys.distinct()) }
    fun requestReset(key: String) { if (!_busy.value) _confirmation.value = WordConfirmation(true, listOf(key)) }
    fun dismissConfirmation() { if (!_busy.value) _confirmation.value = null }
    fun confirmWordAction() {
        val action = _confirmation.value ?: return
        launchOperation {
            if (action.reset) repository.resetProgress(action.keys.single()) else {
                repository.deleteWords(action.keys)
                if (detailKey.value in action.keys) showDetail(null)
            }
            _confirmation.value = null
            _message.value = if (action.reset) "复习安排已重置，学习历史已保留" else "已删除 ${action.keys.size} 个单词及关联记录"
        }
    }

    fun setDailyGoal(value: String): Boolean {
        val goal = value.toIntOrNull()?.takeIf { it > 0 } ?: return false
        preferences.edit().putInt("daily_goal", goal).apply()
        _dailyGoal.value = goal
        return true
    }

    fun setDictionaryAutoFillOnImport(enabled: Boolean) {
        preferences.edit().putBoolean("dictionary_autofill_on_import", enabled).apply()
        _dictionaryAutoFillOnImport.value = enabled
    }

    fun exportFile(context: Context, uri: Uri, fullBackup: Boolean) = launchOperation {
        withContext(Dispatchers.IO) {
            val backup = repository.backup(System.currentTimeMillis())
            val text = if (fullBackup) BackupCodec.encode(backup) else backup.snapshot.words.joinToString("\n") { it.word }
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "文件打开失败" }
                .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        _message.value = if (fullBackup) "完整备份已保存" else "词表已导出"
    }

    fun previewBackup(context: Context, uri: Uri) = launchOperation {
        _backupPreview.value = withContext(Dispatchers.IO) {
            val text = requireNotNull(context.contentResolver.openInputStream(uri)) { "文件打开失败" }
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            BackupCodec.decode(text)
        }
    }

    fun dismissBackup() { if (!_busy.value) _backupPreview.value = null }
    fun restoreBackup() {
        val backup = _backupPreview.value ?: return
        launchOperation {
            withContext(Dispatchers.IO) { repository.restore(backup) }
            _backupPreview.value = null
            showDetail(null)
            refreshTime()
            _message.value = "备份已恢复"
        }
    }

    private fun launchOperation(block: suspend () -> Unit) {
        if (_busy.value || _session.value != null) return
        _busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) { _message.value = e.localizedMessage ?: "操作失败，请重试" }
            finally { _busy.value = false }
        }
    }

    val words: StateFlow<List<WordEntity>> = repository.observeWords().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _session = kotlinx.coroutines.flow.MutableStateFlow<ReviewSession?>(null)
    val session: StateFlow<ReviewSession?> = _session

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun readAndImport(context: Context, uri: Uri) = launchOperation {
        val contents = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        InputStreamReader(input, Charsets.UTF_8).readText()
                    } ?: error("文件读取失败")
        }
        performImport(contents)
    }

    fun importText(contents: String) = launchOperation { performImport(contents) }
    private suspend fun performImport(contents: String) {
                    val importedWords = TxtImportParser.parse(contents).words
                    val suggestedMeanings = if (_dictionaryAutoFillOnImport.value) {
                        withContext(Dispatchers.IO) { dictionary.lookupAll(importedWords.map { it.word }) }
                    } else emptyMap()
                    val summary = repository.importText(contents, suggestedMeanings)
                    _message.value = when {
                        summary.uniqueWords == 0 -> "文件中没有可导入的单词"
                        summary.addedWords == 0 -> "没有新单词，已跳过 ${summary.skippedWords} 个重复项"
                        else -> "已导入 ${summary.addedWords} 个新词，跳过 ${summary.skippedWords} 个重复项"
                    }
    }

    fun saveMeaning(key: String, meaning: String) {
        viewModelScope.launch {
            runCatching { repository.updateMeaning(key, meaning) }
                .onSuccess { _message.value = if (meaning.isBlank()) "已清空释义" else "释义已保存" }
                .onFailure { _message.value = "保存失败：${it.localizedMessage ?: "请重试"}" }
        }
    }

    fun beginReview(mode: PracticeMode = PracticeMode.DUE) {
        if (_busy.value || _session.value != null) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { repository.practiceWords(mode, System.currentTimeMillis()) }
                .onSuccess { dueWords ->
                    if (dueWords.isEmpty()) {
                        _message.value = if (mode == PracticeMode.DUE) "当前没有待复习单词" else "当前没有错词"
                    } else {
                        _session.value = ReviewSession(
                            queue = dueWords,
                            initialCount = dueWords.size,
                            mode = mode,
                            promptStartedAt = SystemClock.elapsedRealtime(),
                        )
                    }
                }
                .onFailure { _message.value = "读取复习单词失败：${it.localizedMessage ?: "请重试"}" }
            _busy.value = false
        }
    }

    fun submitAnswer(answer: String) {
        val session = _session.value ?: return
        val word = session.current ?: return
        if (session.answer != null || session.isSubmitting) return

        val correct = ReviewScheduler.isCorrect(word.word, answer)
        _session.value = session.copy(isSubmitting = true)
        viewModelScope.launch {
            runCatching {
                repository.recordAnswer(
                    word = word,
                    correct = correct,
                    elapsedMillis = SystemClock.elapsedRealtime() - session.promptStartedAt,
                )
            }.onSuccess { updated ->
                if (_session.value == null) return@onSuccess
                _session.value = session.copy(
                    queue = ReviewQueue.afterAnswer(session.queue, updated, correct),
                    passed = session.passed + if (correct) 1 else 0,
                    mistakes = session.mistakes + if (correct) 0 else 1,
                    answer = ReviewAnswer(word.word, answer.trim(), correct),
                    isSubmitting = false,
                )
            }.onFailure {
                _session.value = session.copy(isSubmitting = false)
                _message.value = "保存作答失败：${it.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun continueReview() {
        val current = _session.value ?: return
        if (current.queue.isEmpty()) {
            _session.value = null
        } else {
            _session.value = current.copy(
                promptStartedAt = SystemClock.elapsedRealtime(),
                answer = null,
                isSubmitting = false,
            )
        }
    }

    fun endReview() {
        _session.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }
}
