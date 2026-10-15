// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [21, 35], application = Application::class)
class EmailsDictionaryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var file: File

    @Before fun setUp() {
        awaitSaves()
        resetMemory()
        val directory = temporaryFolder.newFolder("dictionary")
        file = File(directory, "emails_dictionary.tsv")
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
    }

    @After fun tearDown() {
        awaitSaves()
        resetMemory()
    }

    @Test fun loadsTheExistingTsvFormat() {
        file.writeText("Alice@Example.com\t4\ninvalid\t10\nbob@example.com\n")
        EmailsDictionary.init(context)
        assertEquals(listOf(
            EmailsDictionary.Entry("alice@example.com", 4),
            EmailsDictionary.Entry("bob@example.com", 1)
        ), EmailsDictionary.getAll())
    }

    @Test fun recordsAndPersistsNormalizedUsageCounts() {
        EmailsDictionary.init(context)
        EmailsDictionary.recordEmails(listOf(" Alice@Example.COM ", "alice@example.com", "invalid"))
        EmailsDictionary.recordEmail("bob@example.com")
        awaitSaves()
        resetMemory()
        EmailsDictionary.init(context)
        assertEquals(listOf(
            EmailsDictionary.Entry("alice@example.com", 2),
            EmailsDictionary.Entry("bob@example.com", 1)
        ), EmailsDictionary.getAll())
    }

    @Test fun interruptedReplacementKeepsTheLastCommittedDictionary() {
        file.writeText("saved@example.com\t7\n")
        // Simulate process death after a partial write, before finishWrite/failWrite.
        AtomicFile(file).startWrite().use { it.write("partial@example.com\t1".toByteArray()) }
        EmailsDictionary.init(context)
        assertEquals(listOf(EmailsDictionary.Entry("saved@example.com", 7)), EmailsDictionary.getAll())
        assertEquals("saved@example.com\t7\n", file.readText())
    }

    @Test @Config(sdk = [21])
    fun interruptedLegacyReplacementRecoversEvenWhenTheBaseFileIsMissing() {
        file.writeText("saved@example.com\t7\n")
        AtomicFile(file).startWrite().close()
        assertTrue(file.delete()) // API 21 kept the previous contents in its backup.
        EmailsDictionary.init(context)
        assertEquals(listOf(EmailsDictionary.Entry("saved@example.com", 7)), EmailsDictionary.getAll())
        assertEquals("saved@example.com\t7\n", file.readText())
    }

    @Test fun concurrentRecordingDoesNotLoseUsageIncrements() {
        EmailsDictionary.init(context)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val writes = (1..8).map {
                workers.submit { repeat(200) { EmailsDictionary.recordEmail("shared@example.com") } }
            }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        awaitSaves()
        resetMemory()
        EmailsDictionary.init(context)
        assertEquals(listOf(EmailsDictionary.Entry("shared@example.com", 1600)), EmailsDictionary.getAll())
    }

    private fun field(name: String) = EmailsDictionary::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun awaitSaves() {
        val executor = field("ioExecutor").get(null) as ExecutorService
        executor.submit {}.get(10, TimeUnit.SECONDS)
    }

    private fun resetMemory() {
        // Model a fresh process without adding reset hooks to the production singleton.
        (field("entries").get(null) as ConcurrentHashMap<*, *>).clear()
        field("loaded").setBoolean(null, false)
        field("appContext").set(null, null)
    }
}
