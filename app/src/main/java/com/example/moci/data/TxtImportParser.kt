package com.example.moci.data

import java.util.Locale

data class ParsedImport(val words: List<WordEntity>, val nonEmptyLineCount: Int)

object TxtImportParser {
    fun parse(contents: String, meanings: Map<String, String> = emptyMap()): ParsedImport {
        val unique = LinkedHashMap<String, String>()
        var nonEmptyLineCount = 0
        contents.removePrefix("\uFEFF").lineSequence().forEach { line ->
            val word = line.trim()
            if (word.isNotEmpty()) {
                nonEmptyLineCount += 1
                unique.putIfAbsent(word.lowercase(Locale.ROOT), word)
            }
        }
        return ParsedImport(
            words = unique.map { (key, word) ->
                WordEntity(normalizedWord = key, word = word, meaning = meanings[key]?.trim().orEmpty())
            },
            nonEmptyLineCount = nonEmptyLineCount,
        )
    }
}
