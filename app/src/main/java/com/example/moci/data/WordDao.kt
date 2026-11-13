package com.example.moci.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import androidx.room.Embedded
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow

data class WordWithLogs(
    @Embedded val word: WordEntity,
    @Relation(parentColumn = "normalizedWord", entityColumn = "normalizedWord")
    val logs: List<ReviewLogEntity>,
)

@Dao
interface WordDao {
    @Transaction
    @Query("SELECT * FROM words ORDER BY word COLLATE NOCASE")
    fun observeEntries(): Flow<List<WordWithLogs>>

    @Query("SELECT * FROM words ORDER BY word COLLATE NOCASE")
    suspend fun getAll(): List<WordEntity>

    @Query("SELECT * FROM review_logs ORDER BY answeredAt, id")
    suspend fun getAllLogs(): List<ReviewLogEntity>

    @Query("SELECT * FROM words WHERE meaning <> '' AND (SELECT correct FROM review_logs WHERE review_logs.normalizedWord = words.normalizedWord ORDER BY id DESC LIMIT 1) = 0 ORDER BY word COLLATE NOCASE")
    suspend fun getWrongWords(): List<WordEntity>

    @Insert
    suspend fun insertWord(word: WordEntity)

    @Insert
    suspend fun insertLogs(logs: List<ReviewLogEntity>)

    @Query("UPDATE words SET normalizedWord = :newKey, word = :word, meaning = :meaning WHERE normalizedWord = :oldKey")
    suspend fun rename(oldKey: String, newKey: String, word: String, meaning: String)

    @Query("UPDATE review_logs SET normalizedWord = :newKey WHERE normalizedWord = :oldKey")
    suspend fun renameLogs(oldKey: String, newKey: String)

    @Query("DELETE FROM review_logs WHERE normalizedWord IN (:keys)")
    suspend fun deleteLogs(keys: List<String>)

    @Query("DELETE FROM words WHERE normalizedWord IN (:keys)")
    suspend fun deleteWords(keys: List<String>)

    @Query("DELETE FROM review_logs")
    suspend fun clearLogs()

    @Query("DELETE FROM words")
    suspend fun clearWords()
    @Query("SELECT * FROM words ORDER BY word COLLATE NOCASE")
    fun observeAll(): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE meaning <> '' AND nextReviewAt <= :now ORDER BY nextReviewAt, word COLLATE NOCASE")
    suspend fun getDueWords(now: Long): List<WordEntity>

    @Query("SELECT * FROM words WHERE normalizedWord = :key LIMIT 1")
    suspend fun getByKey(key: String): WordEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(words: List<WordEntity>): List<Long>

    @Update
    suspend fun update(word: WordEntity)

    @Query("UPDATE words SET meaning = :meaning WHERE normalizedWord = :key")
    suspend fun updateMeaning(key: String, meaning: String)

    @Insert
    suspend fun insertReviewLog(log: ReviewLogEntity)

    @Query("SELECT * FROM review_logs WHERE normalizedWord = :key ORDER BY answeredAt, id")
    suspend fun getReviewLogs(key: String): List<ReviewLogEntity>
}
