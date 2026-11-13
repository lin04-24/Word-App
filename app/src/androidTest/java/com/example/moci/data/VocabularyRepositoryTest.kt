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
    fun renameKeepsHistoryAndDeleteRemovesLinkedRecordsTransactionally() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "moci-edit-test-${System.nanoTime()}.db"
        try {
            val database = openDatabase(context, databaseName)
            val repository = VocabularyRepository(database)
            try {
                repository.saveWord(null, "苹果", "fruit")
                throw AssertionError("a manually entered word should use English spelling")
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("英文单词")) }
            repository.saveWord(null, "Apple", "蘋果")
            val inserted = database.wordDao().getByKey("apple")!!
            repository.recordAnswer(inserted, correct = false, elapsedMillis = 500)
            val originalLog = database.wordDao().getReviewLogs("apple").single()

            repository.saveWord("apple", "Pear", "梨")
            val renamed = database.wordDao().getByKey("pear")!!
            assertEquals(1, renamed.attempts)
            assertEquals(0, renamed.correctCount)
            assertEquals(listOf("pear"), database.wordDao().getReviewLogs("pear").map { it.normalizedWord })
            assertEquals("apple", originalLog.normalizedWord)

            try {
                repository.saveWord(null, "Pearl", "珍珠")
                repository.saveWord("pear", "Pearl", "重复")
                throw AssertionError("duplicate word must be rejected")
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("已存在")) }

            repository.resetProgress("pear")
            val reset = database.wordDao().getByKey("pear")!!
            assertEquals(0, reset.level)
            assertEquals(0L, reset.nextReviewAt)
            assertEquals(1, database.wordDao().getReviewLogs("pear").size)
            repository.deleteWords(listOf("pear"))
            assertEquals(null, database.wordDao().getByKey("pear"))
            assertTrue(database.wordDao().getReviewLogs("pear").isEmpty())
            database.close()
        } finally { context.deleteDatabase(databaseName) }
    }

    @Test
    fun validatedBackupRestoresCompleteSnapshotAndRejectedBackupLeavesDataUntouched() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "moci-backup-test-${System.nanoTime()}.db"
        try {
            val database = openDatabase(context, databaseName)
            val repository = VocabularyRepository(database)
            repository.saveWord(null, "Apple", "蘋果")
            repository.saveWord(null, "Book", "書")
            val apple = database.wordDao().getByKey("apple")!!
            repository.recordAnswer(apple, correct = true, elapsedMillis = 820)
            val backup = repository.backup(200)
            val expectedWords = backup.snapshot.words.associateBy { it.normalizedWord }
            val expectedLogs = backup.snapshot.logs

            repository.saveWord(null, "Pear", "梨")
            val invalid = backup.copy(snapshot = backup.snapshot.copy(logs = emptyList()))
            try {
                repository.restore(invalid)
                throw AssertionError("a backup with mismatched answer totals must be rejected")
            } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("累计数据")) }
            assertNotNull(database.wordDao().getByKey("pear"))

            repository.restore(BackupCodec.decode(BackupCodec.encode(backup)))
            assertEquals(expectedWords, database.wordDao().getAll().associateBy { it.normalizedWord })
            assertEquals(expectedLogs, database.wordDao().getAllLogs())
            assertEquals(null, database.wordDao().getByKey("pear"))
            repository.restore(VocabularyBackup(300, VocabularySnapshot()))
            assertTrue(database.wordDao().getAll().isEmpty())
            assertTrue(database.wordDao().getAllLogs().isEmpty())
            database.close()
        } finally { context.deleteDatabase(databaseName) }
    }

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
