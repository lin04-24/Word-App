package com.example.moci.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

data class ImportSummary(val uniqueWords: Int, val addedWords: Int, val inputLines: Int) {
    val skippedWords: Int get() = inputLines - addedWords
}

class VocabularyRepository(private val database: VocabularyDatabase) {
    private val dao = database.wordDao()

    fun observeWords(): Flow<List<WordEntity>> = dao.observeAll()

    suspend fun dueWords(now: Long): List<WordEntity> = dao.getDueWords(now)

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
        val updated = ReviewScheduler.afterAnswer(word, correct, now)
        database.withTransaction {
            dao.update(updated)
            dao.insertReviewLog(
                ReviewLogEntity(
                    normalizedWord = word.normalizedWord,
                    correct = correct,
                    elapsedMillis = elapsedMillis.coerceAtLeast(0L),
                    answeredAt = now,
                ),
            )
        }
        return updated
    }
}
