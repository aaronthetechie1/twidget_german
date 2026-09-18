package com.tjg.twidget.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.BuildConfig
import com.tjg.twidget.schedule.BufferOAuth
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the installed package, including manifest merging and alias expansion. */
@RunWith(AndroidJUnit4::class)
class PrototypeIsolationInstrumentedTest {
    @Test fun prototypeHasItsOwnLauncherProvidersAndDoesNotClaimStagingOAuth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.APPLICATION_ID.endsWith(".sesl9"))
        val pm = context.packageManager
        assertEquals("com.tjg.twidget.sesl9", context.packageName)
        assertEquals("Twidget SESL9", pm.getApplicationLabel(context.applicationInfo).toString())
        assertFalse(BuildConfig.IN_APP_UPDATES)
        assertFalse(BufferOAuth.isConfigured(context))

        val launcher = pm.getLaunchIntentForPackage(context.packageName)!!
        assertEquals(context.packageName, launcher.component!!.packageName)
        // Class names retain the code namespace even though the installed package changes.
        assertEquals("com.tjg.twidget.MainActivity", launcher.component!!.className)
        val alias = pm.getActivityInfo(launcher.component!!, 0)
        assertEquals("com.tjg.twidget.main.MainActivity", alias.targetActivity)
        assertEquals(context.packageName, pm.resolveContentProvider(
            "${context.packageName}.provider.routines.v3", 0,
        )!!.packageName)

        val callback = ComponentName(context, "com.tjg.twidget.schedule.BufferOAuthCallbackActivity")
        assertFalse(pm.getActivityInfo(callback, PackageManager.MATCH_DISABLED_COMPONENTS).enabled)
        val callbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("twidget://oauth/buffer"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        assertTrue(pm.queryIntentActivities(callbackIntent, 0).isEmpty())
    }
}
