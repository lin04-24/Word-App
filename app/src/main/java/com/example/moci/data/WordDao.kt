package com.example.moci.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
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

    @Query("SELECT * FROM review_logs WHERE normalizedWord = :key ORDER BY answeredAt")
    suspend fun getReviewLogs(key: String): List<ReviewLogEntity>
}
