package com.example.moci

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.moci.data.ReviewQueue
import com.example.moci.data.ReviewScheduler
import com.example.moci.data.VocabularyDatabase
import com.example.moci.data.VocabularyRepository
import com.example.moci.data.WordEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

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
) {
    val current: WordEntity? get() = queue.firstOrNull()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = VocabularyRepository(VocabularyDatabase.getInstance(application))

    val words: StateFlow<List<WordEntity>> = repository.observeWords().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _session = kotlinx.coroutines.flow.MutableStateFlow<ReviewSession?>(null)
    val session: StateFlow<ReviewSession?> = _session

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun readAndImport(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        InputStreamReader(input, Charsets.UTF_8).readText()
                    } ?: error("无法读取所选文件")
                }
            }.onSuccess(::importText)
                .onFailure { _message.value = "文件读取失败：${it.localizedMessage ?: "请检查 TXT 文件"}" }
        }
    }

    fun importText(contents: String) {
        viewModelScope.launch {
            runCatching { repository.importText(contents) }
                .onSuccess { summary ->
                    _message.value = when {
                        summary.uniqueWords == 0 -> "文件中没有可导入的单词"
                        summary.addedWords == 0 -> "没有新单词，已跳过 ${summary.skippedWords} 个重复项"
                        else -> "已导入 ${summary.addedWords} 个新词，跳过 ${summary.skippedWords} 个重复项"
                    }
                }
                .onFailure { _message.value = "导入失败：${it.localizedMessage ?: "请检查 TXT 文件"}" }
        }
    }

    fun saveMeaning(key: String, meaning: String) {
        viewModelScope.launch {
            runCatching { repository.updateMeaning(key, meaning) }
                .onSuccess { _message.value = if (meaning.isBlank()) "已清空释义" else "释义已保存" }
                .onFailure { _message.value = "保存失败：${it.localizedMessage ?: "请重试"}" }
        }
    }

    fun beginReview() {
        viewModelScope.launch {
            runCatching { repository.dueWords(System.currentTimeMillis()) }
                .onSuccess { dueWords ->
                    if (dueWords.isEmpty()) {
                        _message.value = "当前没有待复习单词"
                    } else {
                        _session.value = ReviewSession(
                            queue = dueWords,
                            initialCount = dueWords.size,
                            promptStartedAt = SystemClock.elapsedRealtime(),
                        )
                    }
                }
                .onFailure { _message.value = "读取复习单词失败：${it.localizedMessage ?: "请重试"}" }
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
