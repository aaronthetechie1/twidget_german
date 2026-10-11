package com.tjg.twidget.schedule

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleTransactionsInstrumentedTest {
    @Test fun batchUsesOneCommitReadsArePureAndFailedCommitRetainsMedia() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val real = context.getSharedPreferences("audit-schedule-test", Context.MODE_PRIVATE)
        real.edit().clear().commit()
        var commits = 0
        var fail = false
        val preferences = object : SharedPreferences by real {
            override fun edit(): SharedPreferences.Editor {
                val editor = real.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { editor.putString(key, value) }
                    override fun commit(): Boolean {
                        commits++
                        return if (fail) false else editor.commit()
                    }
                }
            }
        }
        val store = ScheduleStore(context, preferences)
        val directory = File(context.filesDir, "scheduled-media").apply { mkdirs() }
        val media = File(directory, "audit-test.png").apply { writeBytes(byteArrayOf(1)) }
        val uri = "content://${context.packageName}.update_files/scheduled_media/${media.name}"
        try {
            val posts = (1..10).map { ScheduledPost(id = "audit-$it", provider = ScheduleProvider.LOCAL_REMINDER,
                accountUsername = "audit", scheduledAt = null, thread = listOf(ScheduleThreadItem(text = "test", media = listOf(LocalUriMedia(uri))))) }
            store.transaction { it.addAll(posts) }
            assertEquals(1, commits)
            store.setPinned(posts.mapTo(hashSetOf()) { it.id }, true)
            assertEquals(2, commits)
            assertTrue(store.list().all { it.pinned })
            repeat(5) { store.snapshot(); store.list(); store.listTrash() }
            assertEquals(2, commits)
            fail = true
            assertTrue(runCatching { store.removeAll(posts.mapTo(hashSetOf()) { it.id }) }.isFailure)
            assertTrue(media.exists())
            assertEquals(10, store.list().size)
            fail = false
            store.removeAll(posts.mapTo(hashSetOf()) { it.id })
            assertFalse(media.exists())
            assertTrue(store.list().isEmpty())
        } finally { media.delete(); real.edit().clear().commit() }
    }
}
