package com.tjg.twidget.schedule

import android.content.Context
import com.tjg.twidget.core.AppExecutors

object ScheduleAccountCleanup {
    fun removeAccountSchedules(context: Context, username: String) {
        val appContext = context.applicationContext
        val store = ScheduleStore(appContext)
        // Include trash: removing an account must also release its retained local media.
        val posts = store.snapshot().filter {
            listOf(it.accountId.orEmpty(), it.accountUsername).any { account ->
                ScheduleAccountMapping.normalize(account) == ScheduleAccountMapping.normalize(username)
            }
        }
        val local = posts.filter { it.provider == ScheduleProvider.LOCAL_REMINDER }
        local.forEach { post ->
            LocalReminderScheduler(appContext).cancel(post.id)
            ScheduleNotificationHelper.cancel(appContext, post.id)
        }
        store.removeAll(local.mapTo(hashSetOf()) { it.id })
        val remote = posts.filter { it.provider == ScheduleProvider.BUFFER }
        if (remote.isNotEmpty()) AppExecutors.execute(onRejected = {
            store.removeAll(remote.mapTo(hashSetOf()) { it.id })
        }) {
            remote.forEach { post ->
                if (!post.remotePostId.isNullOrBlank()) runCatching { ScheduleCoordinator(appContext).cancel(post.id) }
            }
            store.removeAll(remote.mapTo(hashSetOf()) { it.id })
        }
    }
}
