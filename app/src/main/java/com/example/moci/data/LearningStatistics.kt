package com.example.moci.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class VocabularySnapshot(
    val words: List<WordEntity> = emptyList(),
    val logs: List<ReviewLogEntity> = emptyList(),
)

enum class PracticeMode { DUE, WRONG }
enum class WordFilter(val label: String) {
    ALL("全部"), MISSING("待补释义"), DUE("待复习"), WRONG("错词"), MASTERED("已掌握"),
}
enum class WordSort(val label: String) {
    ALPHABETICAL("英文升序"), REVIEW_TIME("复习时间升序"), MISTAKES("错误次数降序"),
}

data class DayStatistics(val date: LocalDate, val wordCount: Int, val attempts: Int, val correct: Int) {
    val accuracy: Double? get() = if (attempts == 0) null else correct.toDouble() / attempts
}

data class MasteryDistribution(val missing: Int, val unlearned: Int, val learning: Int, val mastered: Int)

object LearningStatistics {
    fun latestLogs(logs: List<ReviewLogEntity>): Map<String, ReviewLogEntity> =
        logs.groupBy { it.normalizedWord }.mapValues { (_, entries) ->
            entries.maxBy { it.id }
        }

    fun wrongKeys(snapshot: VocabularySnapshot): Set<String> {
        val latest = latestLogs(snapshot.logs)
        return snapshot.words.filter { it.meaning.isNotBlank() && latest[it.normalizedWord]?.correct == false }
            .mapTo(mutableSetOf()) { it.normalizedWord }
    }

    fun daily(logs: List<ReviewLogEntity>, zone: ZoneId): Map<LocalDate, DayStatistics> =
        logs.groupBy { Instant.ofEpochMilli(it.answeredAt).atZone(zone).toLocalDate() }
            .mapValues { (day, entries) ->
                DayStatistics(day, entries.map { it.normalizedWord }.distinct().size, entries.size, entries.count { it.correct })
            }

    fun days(daily: Map<LocalDate, DayStatistics>, today: LocalDate, count: Int): List<DayStatistics> =
        (count - 1 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            daily[day] ?: DayStatistics(day, 0, 0, 0)
        }

    fun mastery(words: List<WordEntity>) = MasteryDistribution(
        missing = words.count { it.meaning.isBlank() },
        unlearned = words.count { it.meaning.isNotBlank() && it.attempts == 0 },
        learning = words.count { it.meaning.isNotBlank() && it.attempts > 0 && it.level < 6 },
        mastered = words.count { it.meaning.isNotBlank() && it.attempts > 0 && it.level >= 6 },
    )

    fun filterWords(
        snapshot: VocabularySnapshot, query: String, filter: WordFilter, sort: WordSort, now: Long,
    ): List<WordEntity> {
        val wrong = wrongKeys(snapshot)
        val search = query.trim()
        val matching = snapshot.words.filter { word ->
            (word.word.contains(search, ignoreCase = true) || word.meaning.contains(search, ignoreCase = true)) &&
                when (filter) {
                    WordFilter.ALL -> true
                    WordFilter.MISSING -> word.meaning.isBlank()
                    WordFilter.DUE -> word.meaning.isNotBlank() && word.nextReviewAt <= now
                    WordFilter.WRONG -> word.normalizedWord in wrong
                    WordFilter.MASTERED -> word.meaning.isNotBlank() && word.attempts > 0 && word.level >= 6
                }
        }
        val alphabetical = compareBy<WordEntity> { it.word.lowercase(Locale.ROOT) }
        return matching.sortedWith(when (sort) {
            WordSort.ALPHABETICAL -> alphabetical
            WordSort.REVIEW_TIME -> compareBy<WordEntity> { it.nextReviewAt }.then(alphabetical)
            WordSort.MISTAKES -> compareByDescending<WordEntity> { it.attempts - it.correctCount }.then(alphabetical)
        })
    }
}
