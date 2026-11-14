package com.example.moci

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.moci.data.VocabularyDatabase
import com.example.moci.data.VocabularyRepository
import com.example.moci.update.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The host script confirms installation outside the target process, which Android replaces. */
class UpdateUpgradeSmokeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun prepareSystemInstaller() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeSmoke") == "true")
        val database = VocabularyDatabase.getInstance(context)
        val repository = VocabularyRepository(database)
        repository.importText("updatesmoke")
        repository.updateMeaning("updatesmoke", "覆盖更新保留词库")
        repository.recordAnswer(database.wordDao().getByKey("updatesmoke")!!, correct = true, elapsedMillis = 1234)
        AndroidUpdatePreferences(context).autoCheckEnabled = false
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("update-valid.apk").use { it.readBytes() }
        val file = File(context.getExternalFilesDir(null), "updates/upgrade-smoke.apk")
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val release = ReleaseInfo("v0.5", "测试覆盖升级", "update-valid.apk", "https://example.test/update.apk", bytes.size.toLong(), hash, null)
        AndroidApkVerifier(context).verify(file, release, hash)
        context.startActivity(updateInstallIntent(context, file).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("System installer should be visible", device.wait(Until.hasObject(By.text("Cancel")), 30_000))
    }

    @Test fun verifyUpgradeData() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("upgradeSmoke") == "true")
        val dao = VocabularyDatabase.getInstance(context).wordDao()
        val word = dao.getByKey("updatesmoke")
        assertNotNull(word)
        assertEquals("覆盖更新保留词库", word!!.meaning)
        assertTrue(word.level >= 1)
        assertTrue(word.attempts >= 1)
        assertTrue(dao.getReviewLogs("updatesmoke").any { it.elapsedMillis == 1234L && it.correct })
        assertFalse(AndroidUpdatePreferences(context).autoCheckEnabled)
        File(context.getExternalFilesDir(null), "updates/upgrade-smoke.apk").delete()
        Unit
    }
}
