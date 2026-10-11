package com.tjg.twidget.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountMutationInstrumentedTest {
    @Test fun concurrentAddsAndLateResponsesCannotUndoRemovalOrNewerStats() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accounts = listOf("audit_storage_one", "audit_storage_two")
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.invokeAll(accounts.map { account -> java.util.concurrent.Callable { TwidgetStore.addAccount(context, account) } })
                .forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(TwidgetStore.accounts(context).containsAll(accounts))
            val account = accounts.first()
            val generation = TwidgetStore.accountGeneration(context, account)
            val now = System.currentTimeMillis()
            val newer = ProfileStats("Audit", account, 200, 0, 0, 0, syncedAt = now, accountGeneration = generation)
            TwidgetStore.saveStats(context, newer)
            TwidgetStore.saveStats(context, newer.copy(followersCount = 100, syncedAt = now - 1))
            assertEquals(200L, TwidgetStore.currentStats(context, account).followersCount)
            TwidgetStore.removeAccount(context, account)
            TwidgetStore.saveStats(context, newer.copy(syncedAt = now + 1))
            assertFalse(TwidgetStore.accounts(context).contains(account))
            TwidgetStore.addAccount(context, account)
            TwidgetStore.saveStats(context, newer.copy(syncedAt = now + 2))
            assertNotEquals(200L, TwidgetStore.currentStats(context, account).followersCount)
            TwidgetStore.saveStats(context, newer.copy(followersCount = 300, syncedAt = now + 3,
                accountGeneration = TwidgetStore.accountGeneration(context, account)))
            assertEquals(300L, TwidgetStore.currentStats(context, account).followersCount)
        } finally { pool.shutdownNow(); accounts.forEach { TwidgetStore.removeAccount(context, it) } }
    }
}
