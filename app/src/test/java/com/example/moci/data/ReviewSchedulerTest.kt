package com.example.moci.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSchedulerTest {
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    @Test
    fun usesReferenceIntervalsAndAddsThreeDaysAfterFifteenDays() {
        assertEquals(12L * hour, ReviewScheduler.nextIntervalMillis(0))
        assertEquals(day, ReviewScheduler.nextIntervalMillis(1))
        assertEquals(2L * day, ReviewScheduler.nextIntervalMillis(2))
        assertEquals(4L * day, ReviewScheduler.nextIntervalMillis(3))
        assertEquals(7L * day, ReviewScheduler.nextIntervalMillis(4))
        assertEquals(15L * day, ReviewScheduler.nextIntervalMillis(5))
        assertEquals(18L * day, ReviewScheduler.nextIntervalMillis(6))
        assertEquals(21L * day, ReviewScheduler.nextIntervalMillis(7))
    }

    @Test
    fun comparesIgnoringCaseAndOuterWhitespaceOnly() {
        assertTrue(ReviewScheduler.isCorrect("Apple", " apple "))
        assertFalse(ReviewScheduler.isCorrect("apple", "apples"))
        assertFalse(ReviewScheduler.isCorrect("ice cream", "icecream"))
    }

    @Test
    fun correctSchedulesCurrentLevelThenAdvancesAndWrongMovesBackImmediately() {
        val fresh = WordEntity(normalizedWord = "apple", word = "apple", meaning = "苹果")
        val firstCorrect = ReviewScheduler.afterAnswer(fresh, correct = true, now = 1_000L)

        assertEquals(1, firstCorrect.level)
        assertEquals(1_000L + 12L * hour, firstCorrect.nextReviewAt)
        assertEquals(1, firstCorrect.attempts)
        assertEquals(1, firstCorrect.correctCount)

        val wrongAtLevelThree = firstCorrect.copy(level = 3)
        val retried = ReviewScheduler.afterAnswer(wrongAtLevelThree, correct = false, now = 2_000L)
        assertEquals(2, retried.level)
        assertEquals(2_000L, retried.nextReviewAt)
        assertEquals(2, retried.attempts)
        assertEquals(1, retried.correctCount)

        val wrongAtFloor = ReviewScheduler.afterAnswer(fresh, correct = false, now = 3_000L)
        assertEquals(0, wrongAtFloor.level)
    }

    @Test
    fun wrongWordMovesToQueueEndAndLeavesAfterCorrectAnswer() {
        val apple = WordEntity(normalizedWord = "apple", word = "apple", meaning = "苹果")
        val book = WordEntity(normalizedWord = "book", word = "book", meaning = "书")
        val cat = WordEntity(normalizedWord = "cat", word = "cat", meaning = "猫")
        val queue = listOf(apple, book, cat)
        val afterWrong = apple.copy(level = 0, attempts = 1)

        assertEquals(listOf(book, cat, afterWrong), ReviewQueue.afterAnswer(queue, afterWrong, correct = false))
        assertEquals(listOf(book, cat), ReviewQueue.afterAnswer(queue, apple, correct = true))
    }
}
