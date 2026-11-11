package com.example.moci.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey val normalizedWord: String,
    val word: String,
    val meaning: String = "",
    val level: Int = 0,
    val nextReviewAt: Long = 0L,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val lastReviewedAt: Long? = null,
)
