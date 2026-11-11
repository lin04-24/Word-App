package com.example.moci.data

import java.util.Locale

object ReviewScheduler {
    private const val HOUR = 60L * 60L * 1000L
    private const val DAY = 24L * HOUR
    private val baseIntervals = longArrayOf(12L * HOUR, DAY, 2L * DAY, 4L * DAY, 7L * DAY, 15L * DAY)

    fun isCorrect(expected: String, answer: String): Boolean =
        expected.trim().lowercase(Locale.ROOT) == answer.trim().lowercase(Locale.ROOT)

    fun nextIntervalMillis(level: Int): Long {
        val safeLevel = level.coerceAtLeast(0)
        return if (safeLevel < baseIntervals.size) {
            baseIntervals[safeLevel]
        } else {
            baseIntervals.last() + (safeLevel - (baseIntervals.size - 1)) * 3L * DAY
        }
    }

    fun afterAnswer(word: WordEntity, correct: Boolean, now: Long): WordEntity {
        val nextLevel = if (correct) word.level + 1 else (word.level - 1).coerceAtLeast(0)
        val nextReviewAt = if (correct) now + nextIntervalMillis(word.level.coerceAtLeast(0)) else now
        return word.copy(
            level = nextLevel,
            nextReviewAt = nextReviewAt,
            attempts = word.attempts + 1,
            correctCount = word.correctCount + if (correct) 1 else 0,
            lastReviewedAt = now,
        )
    }
}

object ReviewQueue {
    fun afterAnswer(queue: List<WordEntity>, updatedWord: WordEntity, correct: Boolean): List<WordEntity> {
        val remaining = queue.drop(1)
        return if (correct) remaining else remaining + updatedWord
    }
}
