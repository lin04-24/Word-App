package com.example.moci.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VocabularyRepositoryTest {
    @Test
    fun importsAreIdempotentMissingMeaningsStayOutOfReviewAndRecordsPersist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "moci-test-${System.nanoTime()}.db"
        try {
            var database = openDatabase(context, databaseName)
            var repository = VocabularyRepository(database)

            val firstImport = repository.importText("apple\nAPPLE\npear\n")
            assertEquals(2, firstImport.uniqueWords)
            assertEquals(2, firstImport.addedWords)
            assertTrue(repository.dueWords(System.currentTimeMillis()).isEmpty())

            repository.updateMeaning("apple", "苹果")
            val apple = database.wordDao().getByKey("apple")!!
            val mastered = ReviewScheduler.afterAnswer(apple, correct = true, now = 10_000L)
            database.wordDao().update(mastered)
            repository.recordAnswer(mastered, correct = true, elapsedMillis = 850L)

            val secondImport = repository.importText("Apple\npear\nplum\n")
            assertEquals(3, secondImport.uniqueWords)
            assertEquals(1, secondImport.addedWords)
            assertEquals("苹果", database.wordDao().getByKey("apple")?.meaning)
            assertEquals(mastered.level + 1, database.wordDao().getByKey("apple")?.level)
            assertFalse(repository.dueWords(System.currentTimeMillis()).any { it.normalizedWord == "pear" })

            database.close()
            database = openDatabase(context, databaseName)
            repository = VocabularyRepository(database)
            val persistedApple = database.wordDao().getByKey("apple")
            assertNotNull(persistedApple)
            assertEquals("苹果", persistedApple?.meaning)
            assertEquals(1, database.wordDao().getReviewLogs("apple").size)
            assertEquals(850L, database.wordDao().getReviewLogs("apple").single().elapsedMillis)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun openDatabase(context: Context, name: String): VocabularyDatabase =
        Room.databaseBuilder(context, VocabularyDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
}
