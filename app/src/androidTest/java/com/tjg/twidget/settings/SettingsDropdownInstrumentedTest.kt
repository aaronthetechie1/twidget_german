package com.tjg.twidget.settings

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDropdownInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun accountLongPressOpensCompactActionsWithoutDialogButtons() {
        val username = TwidgetStore.settings(context).username
        checkDropdown(SettingsPage.ACCOUNTS, "account_${username.lowercase()}", R.string.change_account_handle)
    }

    @Test fun appearanceFontLongPressOpensCheckedDropdownWithoutDialogButtons() {
        checkDropdown(SettingsPage.APPEARANCE, "settings_app_font", R.string.widget_font_google)
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun checkDropdown(page: SettingsPage, key: String, label: Int) {
        ActivityScenario.launch<SettingsCategoryActivity>(SettingsCategoryActivity.intent(context, page)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as SettingsCategoryPreferenceFragment
                fragment.scrollToPreference(key)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.preference_fragment_container)
                    as SettingsCategoryPreferenceFragment
                val adapter = fragment.listView.adapter as PreferenceGroupAdapter
                val row = fragment.listView.findViewHolderForAdapterPosition(adapter.getPreferenceAdapterPosition(key))!!.itemView
                assertTrue(longClickTarget(row)!!.performLongClick())
            }
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(root.findAccessibilityNodeInfosByText(context.getString(label)).isNotEmpty())
            assertTrue(root.findAccessibilityNodeInfosByText(context.getString(android.R.string.cancel)).isEmpty())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
    }

    private fun longClickTarget(view: View): View? {
        if (view.isLongClickable) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            longClickTarget(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
