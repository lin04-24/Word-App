package com.example.moci.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

data class ImportSummary(val uniqueWords: Int, val addedWords: Int, val inputLines: Int) {
    val skippedWords: Int get() = inputLines - addedWords
}

class VocabularyRepository(private val database: VocabularyDatabase) {
    private val dao = database.wordDao()

    fun observeWords(): Flow<List<WordEntity>> = dao.observeAll()

    fun observeSnapshot(): Flow<VocabularySnapshot> = dao.observeEntries().map { entries ->
        VocabularySnapshot(entries.map { it.word }, entries.flatMap { it.logs })
    }

    suspend fun dueWords(now: Long): List<WordEntity> = dao.getDueWords(now)

    suspend fun practiceWords(mode: PracticeMode, now: Long): List<WordEntity> =
        if (mode == PracticeMode.DUE) dao.getDueWords(now) else dao.getWrongWords()

    suspend fun saveWord(oldKey: String?, word: String, meaning: String): String = database.withTransaction {
        val spelling = word.trim()
        require(spelling.any { it in 'A'..'Z' || it in 'a'..'z' } &&
            Regex("""[A-Za-z0-9]+(?:[ '\u2019.-][A-Za-z0-9]+)*""").matches(spelling)) { "请输入有效的英文单词或短语" }
        val key = spelling.lowercase(Locale.ROOT)
        require(key == oldKey || dao.getByKey(key) == null) { "该单词已存在" }
        if (oldKey == null) {
            dao.insertWord(WordEntity(key, spelling, meaning.trim()))
        } else {
            requireNotNull(dao.getByKey(oldKey)) { "该单词已被删除，请返回词库" }
            dao.rename(oldKey, key, spelling, meaning.trim())
            dao.renameLogs(oldKey, key)
        }
        key
    }

    suspend fun deleteWords(keys: List<String>) = database.withTransaction {
        // Stay below the SQLite variable limit even for large selected word lists.
        keys.distinct().chunked(500).forEach { batch ->
            dao.deleteLogs(batch)
            dao.deleteWords(batch)
        }
    }

    suspend fun resetProgress(key: String) = database.withTransaction {
        val word = requireNotNull(dao.getByKey(key)) { "单词已被删除" }
        dao.update(word.copy(level = 0, nextReviewAt = 0))
    }

    suspend fun backup(now: Long): VocabularyBackup = database.withTransaction {
        VocabularyBackup(now, VocabularySnapshot(dao.getAll(), dao.getAllLogs()))
    }

    suspend fun restore(backup: VocabularyBackup) {
        BackupCodec.validate(backup)
        database.withTransaction {
            dao.clearLogs()
            dao.clearWords()
            backup.snapshot.words.forEach { dao.insertWord(it) }
            dao.insertLogs(backup.snapshot.logs)
        }
    }

    suspend fun importText(contents: String): ImportSummary {
        val parsed = TxtImportParser.parse(contents)
        if (parsed.words.isEmpty()) return ImportSummary(uniqueWords = 0, addedWords = 0, inputLines = 0)
        val insertedIds = database.withTransaction { dao.insertAll(parsed.words) }
        return ImportSummary(
            uniqueWords = parsed.words.size,
            addedWords = insertedIds.count { it != -1L },
            inputLines = parsed.nonEmptyLineCount,
        )
    }

    suspend fun updateMeaning(key: String, meaning: String) {
        dao.updateMeaning(key, meaning.trim())
    }

    suspend fun recordAnswer(word: WordEntity, correct: Boolean, elapsedMillis: Long): WordEntity {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val current = requireNotNull(dao.getByKey(word.normalizedWord)) { "该单词已被删除" }
            val updated = ReviewScheduler.afterAnswer(current, correct, now)
            dao.update(updated)
            dao.insertReviewLog(
                ReviewLogEntity(
                    normalizedWord = word.normalizedWord,
                    correct = correct,
                    elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                    answeredAt = now,
                ),
            )
            updated
        }
    }
}
