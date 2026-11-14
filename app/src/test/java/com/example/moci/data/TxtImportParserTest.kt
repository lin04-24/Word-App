package com.example.moci.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtImportParserTest {
    @Test
    fun trimsLinesSkipsBlanksAndDeduplicatesIgnoringCase() {
        val parsed = TxtImportParser.parse("\uFEFFapple\n\n Banana \napple\nBANANA\r\n")

        assertEquals(listOf("apple", "Banana"), parsed.words.map { it.word })
        assertEquals(listOf("apple", "banana"), parsed.words.map { it.normalizedWord })
        assertEquals(4, parsed.nonEmptyLineCount)
        assertTrue(parsed.words.all { it.meaning.isEmpty() && it.nextReviewAt == 0L })
    }

    @Test
    fun usesDictionaryMeaningsForMatchingWordsAndLeavesMissingWordsBlank() {
        val parsed = TxtImportParser.parse(
            "SEE\nunknown\nsee\n",
            mapOf("see" to "vt. 看见\nvi. 看"),
        )

        assertEquals(listOf("SEE", "unknown"), parsed.words.map { it.word })
        assertEquals(listOf("vt. 看见\nvi. 看", ""), parsed.words.map { it.meaning })
    }

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
