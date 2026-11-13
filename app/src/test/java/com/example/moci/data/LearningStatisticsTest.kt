package com.example.moci.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LearningStatisticsTest {
    @Test
    fun countsDistinctWordsByLocalDayAndKeepsAllAttemptsForAccuracy() {
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2025, 1, 2)
        val base = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val logs = listOf(
            ReviewLogEntity(1, "apple", true, 10, base),
            ReviewLogEntity(2, "apple", false, 10, base + 1000),
            ReviewLogEntity(3, "book", true, 10, base + 2000),
            ReviewLogEntity(4, "cat", false, 10, base + 86_400_000),
        )
        val daily = LearningStatistics.daily(logs, zone)
        val result = daily.getValue(day)
        assertEquals(2, result.wordCount)
        assertEquals(3, result.attempts)
        assertEquals(.666, result.accuracy!!, .001)
        assertEquals(1, daily.getValue(day.plusDays(1)).wordCount)
        assertEquals(7, LearningStatistics.days(daily, day.plusDays(1), 7).size)
    }

    @Test
    fun filtersByLatestAnswerAndSortsByHistoricalMistakes() {
        val apple = WordEntity("apple", "apple", "苹果", attempts = 3, correctCount = 1)
        val book = WordEntity("book", "book", "书", attempts = 2, correctCount = 1)
        val logs = listOf(ReviewLogEntity(1, "apple", false, 10, 100), ReviewLogEntity(2, "apple", true, 10, 200),
            ReviewLogEntity(3, "book", true, 10, 100), ReviewLogEntity(4, "book", false, 10, 200))
        val snapshot = VocabularySnapshot(listOf(apple, book), logs)
        assertEquals(setOf("book"), LearningStatistics.wrongKeys(snapshot))
        assertFalse("apple" in LearningStatistics.wrongKeys(snapshot))
        assertEquals(listOf("apple", "book"), LearningStatistics.filterWords(snapshot, "", WordFilter.ALL,
            WordSort.MISTAKES, 300).map { it.normalizedWord })
        assertEquals(listOf("book"), LearningStatistics.filterWords(snapshot, "书", WordFilter.WRONG,
            WordSort.ALPHABETICAL, 300).map { it.normalizedWord })
    }

    @Test
    fun masteryAndBackupValidationPreserveCompleteLearningData() {
        val word = WordEntity("apple", "Apple", "苹果", level = 1, nextReviewAt = 50,
            attempts = 1, correctCount = 1, lastReviewedAt = 40)
        val backup = VocabularyBackup(100, VocabularySnapshot(listOf(word), listOf(ReviewLogEntity(1, "apple", true, 20, 40))))
        val decoded = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, decoded)
        assertEquals(MasteryDistribution(0, 0, 1, 0), LearningStatistics.mastery(listOf(word)))
        val broken = backup.copy(snapshot = backup.snapshot.copy(logs = emptyList()))
        try {
            BackupCodec.validate(broken)
            throw AssertionError("backup with inconsistent counters should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("累计数据"))
        }
        try {
            BackupCodec.decode("{\"formatVersion\":2}")
            throw AssertionError("unsupported backup version should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本不支持"))
        }
    }
}
