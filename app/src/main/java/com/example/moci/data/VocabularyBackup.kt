package com.example.moci.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class VocabularyBackup(val exportedAt: Long, val snapshot: VocabularySnapshot)

/** Versioned interchange format; validation is completed before any database write. */
object BackupCodec {
    fun encode(backup: VocabularyBackup): String {
        validate(backup)
        return JSONObject().put("formatVersion", 1).put("exportedAt", backup.exportedAt)
            .put("words", JSONArray().apply {
                backup.snapshot.words.forEach { w ->
                    put(JSONObject().put("normalizedWord", w.normalizedWord).put("word", w.word)
                        .put("meaning", w.meaning).put("level", w.level).put("nextReviewAt", w.nextReviewAt)
                        .put("attempts", w.attempts).put("correctCount", w.correctCount)
                        .put("lastReviewedAt", w.lastReviewedAt ?: JSONObject.NULL))
                }
            }).put("reviewLogs", JSONArray().apply {
                backup.snapshot.logs.forEach { log ->
                    put(JSONObject().put("id", log.id).put("normalizedWord", log.normalizedWord)
                        .put("correct", log.correct).put("elapsedMillis", log.elapsedMillis).put("answeredAt", log.answeredAt))
                }
            }).toString(2)
    }

    fun decode(contents: String): VocabularyBackup {
        try {
            val root = JSONObject(contents.removePrefix("\uFEFF"))
            require(number(root, "formatVersion") == 1L) { "备份格式版本不支持" }
            val words = root.getJSONArray("words")
            val logs = root.getJSONArray("reviewLogs")
            val backup = VocabularyBackup(number(root, "exportedAt"), VocabularySnapshot(
                words = (0 until words.length()).map { index ->
                    val w = words.getJSONObject(index)
                    WordEntity(string(w, "normalizedWord"), string(w, "word"), string(w, "meaning"),
                        integer(w, "level"), number(w, "nextReviewAt"), integer(w, "attempts"), integer(w, "correctCount"),
                        if (w.has("lastReviewedAt") && w.isNull("lastReviewedAt")) null else number(w, "lastReviewedAt"))
                },
                logs = (0 until logs.length()).map { index ->
                    val log = logs.getJSONObject(index)
                    val correct = log.get("correct")
                    require(correct is Boolean) { "作答正误格式异常" }
                    ReviewLogEntity(number(log, "id"), string(log, "normalizedWord"), correct,
                        number(log, "elapsedMillis"), number(log, "answeredAt"))
                },
            ))
            validate(backup)
            return backup
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("备份文件损坏或字段格式异常", e)
        }
    }

    fun validate(backup: VocabularyBackup) {
        require(backup.exportedAt >= 0) { "备份时间异常" }
        val words = backup.snapshot.words
        val keys = words.map { it.normalizedWord }.toSet()
        require(keys.size == words.size) { "备份包含重复单词" }
        words.forEach { w ->
            require(w.word.isNotBlank() && w.word == w.word.trim() && '\n' !in w.word && '\r' !in w.word &&
                w.normalizedWord == w.word.lowercase(Locale.ROOT)) { "备份单词格式异常" }
            require(w.meaning == w.meaning.trim() && w.level >= 0 && w.level <= w.correctCount && w.nextReviewAt >= 0 &&
                w.attempts >= 0 && w.correctCount in 0..w.attempts && (w.lastReviewedAt == null || w.lastReviewedAt >= 0)) {
                "单词学习进度异常"
            }
        }
        val logs = backup.snapshot.logs
        require(logs.map { it.id }.toSet().size == logs.size && logs.all { it.id > 0 && it.id < Long.MAX_VALUE }) { "作答记录 ID 异常或重复" }
        require(logs.all { it.normalizedWord in keys && it.elapsedMillis >= 0 && it.answeredAt >= 0 }) { "作答记录关联或时间异常" }
        // Existing installations always persist these counters and logs in the same transaction.
        val byWord = logs.groupBy { it.normalizedWord }
        words.forEach { w ->
            val entries = byWord[w.normalizedWord].orEmpty()
            require(w.attempts == entries.size && w.correctCount == entries.count { it.correct } &&
                w.lastReviewedAt == entries.maxByOrNull { it.id }?.answeredAt) { "作答记录与单词累计数据不一致" }
        }
    }

    private fun string(json: JSONObject, key: String): String =
        (json.get(key) as? String) ?: throw IllegalArgumentException("字段 $key 应为文本")

    private fun number(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "字段 $key 应为整数" }
        return (value as Number).toLong()
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = number(json, key)
        require(value in 0..Int.MAX_VALUE.toLong()) { "字段 $key 超出范围" }
        return value.toInt()
    }
}
