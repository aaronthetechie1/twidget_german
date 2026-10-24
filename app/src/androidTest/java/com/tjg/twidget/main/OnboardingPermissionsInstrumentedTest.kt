package com.tjg.twidget.main

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import org.junit.Assert.*
import org.junit.Test

class OnboardingPermissionsInstrumentedTest {
    @Test fun skipMovesToProfileWithoutChangingPermissionGrants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun grants(): Pair<Int, Boolean> = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) to
            (Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
        val before = grants()
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.primary_button).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.step_permissions).visibility)
                val skip = activity.findViewById<TextView>(R.id.secondary_button)
                assertEquals(View.VISIBLE, skip.visibility)
                assertEquals(activity.getString(R.string.skip), skip.text.toString())
                skip.performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.step_profile).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.step_permissions).visibility)
            }
        }
        assertEquals(before, grants())
    }
}
