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

    private fun assertTrue(condition: Boolean) = org.junit.Assert.assertTrue(condition)
}
