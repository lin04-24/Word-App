package com.example.moci.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "review_logs")
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val normalizedWord: String,
    val correct: Boolean,
    val elapsedMillis: Long,
    val answeredAt: Long,
)
