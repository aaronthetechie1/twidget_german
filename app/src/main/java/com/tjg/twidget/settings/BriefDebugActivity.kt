package com.tjg.twidget.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefDebugLog
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BriefDebugActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TwidgetStore.debugMenuUnlocked(this)) { finish(); return }
        val chrome = layoutInflater.inflate(R.layout.activity_preference_screen, null) as ToolbarLayout
        // Configure the native compact bar before EdgeToEdgeActivity installs its SESL9 bridge.
        chrome.isExpandable = false
        setContentView(chrome)
        applyPreferenceScreenInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(BriefDebugPage.from(intent.getStringExtra(BriefDebugPage.EXTRA)).title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, BriefDebugFragment().apply {
                    arguments = Bundle().apply { putString(BriefDebugPage.EXTRA, intent.getStringExtra(BriefDebugPage.EXTRA)) }
                })
                .commit()
        }
    }
}

class BriefDebugLogActivity : FoldablePopOverActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preference_screen)
        applyPreferenceScreenInsets(findViewById(R.id.preference_toolbar_layout))
        findViewById<ToolbarLayout>(R.id.preference_toolbar_layout).apply {
            setTitle(getString(R.string.brief_debug_log_title))
            setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference_fragment_container, BriefDebugLogFragment())
                .commit()
        }
    }
}

class BriefDebugFragment : BriefDebugOptionsFragment()

class BriefDebugLogFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        val log = BriefDebugLog.entries(context)

        if (log.isEmpty()) {
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_log_empty"
                title = getString(R.string.brief_debug_log_empty)
                isSelectable = false
            })
        } else {
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_clear_log"
                title = SpannableString(getString(R.string.clear_log)).apply {
                    setSpan(
                        ForegroundColorSpan(context.getColor(R.color.metric_red)),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                setOnPreferenceClickListener {
                    BriefDebugLog.clear(context)
                    buildScreen()
                    true
                }
            })
            log.forEachIndexed { index, entry ->
                screen.addPreference(Preference(context).apply {
                    key = "brief_debug_log_$index"
                    title = "${entry.event} · @${entry.username}"
                    summary = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
                        .format(Date(entry.timestamp))
                    setOnPreferenceClickListener {
                        showDebugText(context, entry.event, entry.report, copyable = true)
                        true
                    }
                })
            }
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }
}

internal fun showDebugText(
    context: Context,
    title: String,
    value: String,
    copyable: Boolean = false,
) {
    val textView = TextView(context).apply {
        text = value
        typeface = android.graphics.Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.oneui_text_primary))
        setTextIsSelectable(true)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }
    AlertDialog.Builder(context)
        .setTitle(title)
        .setView(ScrollView(context).apply { addView(textView) })
        .setNegativeButton(R.string.done, null)
        .apply {
            if (copyable) setPositiveButton(R.string.copy) { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, value))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        .show()
}
