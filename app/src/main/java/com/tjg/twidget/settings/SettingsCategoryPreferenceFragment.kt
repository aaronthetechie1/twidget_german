package com.tjg.twidget.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.BuildConfig
import com.tjg.twidget.analytics.AnalyticsImportActivity
import com.tjg.twidget.data.TwidgetSettings
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.schedule.ScheduleProvider
import com.tjg.twidget.schedule.ScheduleSettingsStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.startAddAccountActivity
import com.tjg.twidget.ui.startSettingsSubActivity
import com.tjg.twidget.widget.RefreshWorker
import com.tjg.twidget.widget.TwidgetBriefWidget
import com.tjg.twidget.widget.TwidgetWidget
import com.tjg.twidget.widget.WidgetOpacityControl
import dev.oneuiproject.oneui.preference.LayoutPreference

import dev.oneuiproject.oneui.widget.CardItemView
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.appcompat.app.AlertDialog
import com.tjg.twidget.schedule.BufferOAuth
import dev.oneuiproject.oneui.widget.RadioItemView
import dev.oneuiproject.oneui.widget.RadioItemViewGroup
import dev.oneuiproject.oneui.preference.HorizontalRadioPreference
import com.tjg.twidget.data.TwidgetWidgetSettings
import com.tjg.twidget.ui.AppAppearance
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.widget.BottomTipView

class SettingsCategoryPreferenceFragment : InsetPreferenceFragment() {
    private lateinit var settings: TwidgetSettings
    private val page get() = SettingsCategoryActivity.page(arguments?.getString(SettingsCategoryActivity.EXTRA_PAGE))

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TwidgetStore.PREFS
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        settings = TwidgetStore.settings(context)
        val screen = preferenceManager.createPreferenceScreen(context)
        when (page) {
            SettingsPage.ACCOUNTS -> accounts(screen)
            SettingsPage.DATA -> data(screen)
            SettingsPage.APPEARANCE -> appearance(screen)
            SettingsPage.SCHEDULING -> scheduling(screen)
        }
        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun data(screen: androidx.preference.PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.analytics))
        screen.addPreference(ListPreference(context).apply {
            key = "data_source_pref"
            title = getString(R.string.active_source)
            dialogTitle = getString(R.string.active_source)
            summary = dataSourceTitle(settings.dataSource)
            entries = arrayOf(
                getString(R.string.source_fxtwitter),
                getString(R.string.source_default),
                getString(R.string.source_self_hosted),
                getString(R.string.source_x_api),
                getString(R.string.source_twitterapis),
            )
            entryValues = arrayOf(
                TwidgetStore.DATA_SOURCE_FXTWITTER,
                TwidgetStore.DATA_SOURCE_DEFAULT,
                TwidgetStore.DATA_SOURCE_SELF_HOSTED,
                TwidgetStore.DATA_SOURCE_X_API,
                TwidgetStore.DATA_SOURCE_TWITTERAPIS,
            )
            value = settings.dataSource
            setOnPreferenceChangeListener { pref, value ->
                val source = value as String
                save(settings.copy(dataSource = source))
                pref.summary = dataSourceTitle(source)
                true
            }
        })
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "share_history_pref"
            title = getString(R.string.share_history)
            summary = getString(R.string.share_history_summary)
            isChecked = settings.shareHistory
            setOnPreferenceChangeListener { _, value ->
                save(settings.copy(shareHistory = value as Boolean))
                true
            }
        })

        screen.addPreference(category(R.string.settings_alternate_sources))
        listOf(
            TwidgetStore.DATA_SOURCE_SELF_HOSTED to R.string.settings_self_hosted_bridge,
            TwidgetStore.DATA_SOURCE_TWITTERAPIS to R.string.source_twitterapis,
            TwidgetStore.DATA_SOURCE_X_API to R.string.source_x_api,
        ).forEach { (source, label) ->
            screen.addPreference(Preference(context).apply {
                key = "source_$source"
                setTitle(label)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(
                        Intent(context, SettingsAdvancedActivity::class.java)
                            .putExtra(SettingsAdvancedActivity.EXTRA_SOURCE, source),
                    )
                    true
                }
            })
        }
        screen.addPreference(category(R.string.refresh))
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "refresh_on_launch_pref"
            title = getString(R.string.refresh_on_launch)
            isChecked = settings.refreshOnLaunch
            setOnPreferenceChangeListener { _, value ->
                save(settings.copy(refreshOnLaunch = value as Boolean))
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "refresh_interval_pref"
            title = getString(R.string.refresh_interval)
            text = settings.refreshIntervalMinutes.toString()
            summary = getString(R.string.refresh_interval_value, settings.refreshIntervalMinutes)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { pref, value ->
                val minutes = (value as String).toIntOrNull()?.coerceIn(15, 240) ?: 15
                save(settings.copy(refreshIntervalMinutes = minutes))
                RefreshWorker.schedule(requireContext())
                (pref as EditTextPreference).summary = getString(R.string.refresh_interval_value, minutes)
                true
            }
        })


        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "clear_cached_stats"
            title = SpannableString(getString(R.string.clear_cache)).apply {
                setSpan(ForegroundColorSpan(context.getColor(R.color.metric_red)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setOnPreferenceClickListener {
                TwidgetStore.clearCachedStats(context)
                TwidgetWidget.updateAll(context)
                true
            }
        })
    }

    private fun accounts(screen: PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.settings_main_account))
        screen.addPreference(account(settings.username, true))
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "buffer_settings"
            setTitle(R.string.schedule_provider_buffer)
            setIcon(R.drawable.ic_buffer)
            summary = getString(if (BufferOAuth.isConnected(context)) R.string.settings_connected else R.string.status_not_configured)
            setOnPreferenceClickListener {
                requireActivity().startSettingsSubActivity(Intent(context, SettingsScheduleActivity::class.java))
                true
            }
        })
        screen.addPreference(Preference(context).apply {
            key = "analytics_import"
            setTitle(R.string.settings_analytics_import)
            setIcon(R.drawable.ic_settings_download)
            summary = getString(if (TwidgetStore.currentStats(context, settings.username).history.any { it.imported })
                R.string.settings_imported else R.string.settings_import_pending)
            setOnPreferenceClickListener { beginAnalyticsImport(settings.username); true }
        })
        val others = TwidgetStore.accounts(context).filter {
            it.isNotBlank() && !it.equals(settings.username, true)
        }.distinctBy { it.lowercase() }
        if (others.isNotEmpty()) {
            screen.addPreference(category(R.string.settings_other_accounts))
            others.forEach { screen.addPreference(account(it, false)) }
        }
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "add_account"
            setTitle(R.string.add_account)
            setIcon(R.drawable.ic_settings_add)
            setOnPreferenceClickListener { requireActivity().startAddAccountActivity(); true }
        })
    }

    private fun account(username: String, isDefault: Boolean): Preference {
        val context = requireContext()
        val stats = TwidgetStore.currentStats(context, username)
        val row = CardItemView(context).apply {
            minimumHeight = dp(85)
            gravity = android.view.Gravity.CENTER_VERTICAL
            title = stats.fullName.ifBlank { username }
            summary = getString(R.string.account_handle, username.trimStart('@'))
            iconSize = dp(34)
            icon = context.getDrawable(R.drawable.avatar_twidget)
            ProfileImageLoader.loadInto(context, getIconImageView(), stats.profileImage)
            setOnClickListener {
                if (isDefault) showAccountActions(username, true) else {
                    save(settings.copy(username = username))
                    buildScreen()
                }
            }
        }
        fun attachLongClick(view: View) {
            view.setOnLongClickListener { showAccountActions(username, isDefault); true }
            if (view is ViewGroup) for (i in 0 until view.childCount) attachLongClick(view.getChildAt(i))
        }
        attachLongClick(row)
        return LayoutPreference(context, row).apply {
            key = "account_${username.lowercase()}"
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
        }
    }

    private fun showAccountActions(username: String, isDefault: Boolean) {
        val actions = accountPopupActions(isDefault)
        val labels = actions.map { action -> getString(when (action) {
            AccountPopupAction.SET_DEFAULT -> R.string.set_as_default
            AccountPopupAction.IMPORT_ANALYTICS -> R.string.import_x_analytics
            AccountPopupAction.DELETE -> R.string.delete
        }) }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.account_handle, username.trimStart('@')))
            .setItems(labels) { _, index ->
                when (actions[index]) {
                    AccountPopupAction.SET_DEFAULT -> {
                        save(settings.copy(username = username))
                        buildScreen()
                    }
                    AccountPopupAction.IMPORT_ANALYTICS -> beginAnalyticsImport(username)
                    AccountPopupAction.DELETE -> {
                        if (TwidgetStore.accounts(requireContext()).size <= 1) {
                            Toast.makeText(requireContext(), R.string.cannot_delete_last_account, Toast.LENGTH_SHORT).show()
                        } else {
                            TwidgetStore.removeAccount(requireContext(), username)
                            TwidgetWidget.updateAll(requireContext())
                            TwidgetBriefWidget.updateAll(requireContext())
                            buildScreen()
                        }
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun beginAnalyticsImport(username: String) {
        startActivity(Intent(requireContext(), AnalyticsImportActivity::class.java)
            .putExtra(AnalyticsImportActivity.EXTRA_USERNAME, username))
    }

    private fun scheduling(screen: PreferenceScreen) {
        val context = requireContext()
        screen.addPreference(category(R.string.scheduling_method))
        val methods = listOf(ScheduleProvider.BUFFER, ScheduleProvider.LOCAL_REMINDER)
        val group = RadioItemViewGroup(context)
        methods.forEachIndexed { index, method ->
            group.addView(RadioItemView(context).apply {
                id = View.generateViewId()
                title = providerLabel(method)
                showTopDivider = index > 0
                isChecked = ScheduleSettingsStore.defaultProvider(context) == method
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val selected = methods.firstOrNull { method ->
                group.getChildAt(methods.indexOf(method)).id == checkedId
            } ?: return@setOnCheckedChangeListener
            ScheduleSettingsStore.setDefaultProvider(context, selected)
        }
        screen.addPreference(LayoutPreference(context, group).apply { key = "schedule_default_method_pref" })
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(Preference(context).apply {
            key = "schedule_buffer_connection"
            val connected = BufferOAuth.isConnected(context)
            title = if (connected) SpannableString(getString(R.string.schedule_disconnect_buffer)).apply {
                setSpan(ForegroundColorSpan(context.getColor(R.color.metric_red)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else getString(R.string.login_to_buffer)
            setOnPreferenceClickListener {
                if (connected) {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.schedule_disconnect_buffer)
                        .setMessage(R.string.schedule_disconnect_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.schedule_disconnect_buffer) { _, _ ->
                            ScheduleSettingsStore.clearBuffer(context)
                            buildScreen()
                        }.show()
                } else {
                    requireActivity().startSettingsSubActivity(Intent(context, SettingsScheduleActivity::class.java))
                }
                true
            }
        })
    }

    private fun appearance(screen: PreferenceScreen) {
        val context = requireContext()
        // Inflate the library component so its image and entry arrays use its supported XML API.
        setPreferencesFromResource(R.xml.settings_appearance_theme, null)
        val themeScreen = preferenceScreen
        val picker = themeScreen.findPreference<HorizontalRadioPreference>("settings_theme")!!
        val followSystem = themeScreen.findPreference<SwitchPreferenceCompat>("settings_theme_system")!!
        themeScreen.removePreference(picker)
        themeScreen.removePreference(followSystem)
        picker.isPersistent = false
        val mode = AppAppearance.mode(context)
        picker.value = if (mode == AppCompatDelegate.MODE_NIGHT_YES) "dark" else if (mode == AppCompatDelegate.MODE_NIGHT_NO) "light"
            else if (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        picker.isEnabled = mode != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        picker.setOnPreferenceChangeListener { _, value ->
            AppAppearance.setMode(context, if (value == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            true
        }
        followSystem.isPersistent = false
        followSystem.isChecked = mode == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        followSystem.setOnPreferenceChangeListener { _, value ->
            AppAppearance.setMode(context, if (value == true) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else if (picker.value == "dark") AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            listView.post { if (isAdded) buildScreen() }
            true
        }
        // Detached XML preferences retain their old order; let the destination assign it.
        picker.order = Preference.DEFAULT_ORDER
        followSystem.order = Preference.DEFAULT_ORDER
        screen.addPreference(picker)
        screen.addPreference(followSystem)
        screen.addPreference(InsetPreferenceCategory(context))
        screen.addPreference(ListPreference(context).apply {
            key = "settings_app_font"
            isPersistent = false
            setTitle(R.string.settings_app_font)
            setDialogTitle(R.string.settings_app_font)
            entries = arrayOf(getString(R.string.settings_app_font_default),
                getString(R.string.widget_font_google), getString(R.string.widget_font_system))
            entryValues = AppAppearance.Font.entries.map { it.value }.toTypedArray()
            value = AppAppearance.font(context).value
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, value ->
                val font = AppAppearance.Font.entries.first { it.value == value }
                if (font != AppAppearance.font(context)) {
                    AppAppearance.setFont(context, font)
                    listView.post { if (isAdded) requireActivity().recreate() }
                }
                true
            }
        })
        if (BuildConfig.FLAVOR == "github" && TwidgetFonts.hasSystemOneUiSans) {
            screen.addPreference(InsetPreferenceCategory(context).apply {
                key = "settings_app_font_inset"
            })
            val tip = BottomTipView(context).apply {
                setTitle(R.string.settings_font_tip_title)
                setSummary(R.string.settings_font_tip_summary)
                setLink(R.string.settings_font_tip_link) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/fahadalijaved/SamFonts")))
                }
            }
            screen.addPreference(LayoutPreference(context, tip).apply {
                key = "settings_app_font_tip"
                isSelectable = false
                setAllowDividerAbove(false)
                setAllowDividerBelow(false)
            })
        }
        screen.addPreference(category(R.string.settings_widget_defaults))
        var defaults = TwidgetStore.widgetSettings(context)
        fun update(next: TwidgetWidgetSettings) {
            defaults = next
            TwidgetStore.saveWidgetSettings(context, 0, next)
            TwidgetWidget.updateAll(context)
            TwidgetBriefWidget.updateAll(context)
        }
        val opacity = layoutInflater.inflate(R.layout.widget_opacity_control, null)
        WidgetOpacityControl.bind(opacity, defaults.tintAlpha) { alpha ->
            update(defaults.copy(tintAlpha = alpha))
        }
        screen.addPreference(LayoutPreference(context, opacity).apply {
            key = "settings_widget_opacity"
            isSelectable = false
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
        })
        fun choice(keyName: String, titleRes: Int, values: Array<String>, labels: Array<String>, selected: String, changed: (String) -> Unit) {
            screen.addPreference(ListPreference(context).apply {
                key = keyName
                isPersistent = false
                setTitle(titleRes)
                dialogTitle = getString(titleRes)
                entryValues = values
                entries = labels
                value = selected
                summary = labels[values.indexOf(selected).coerceAtLeast(0)]
                setOnPreferenceChangeListener { pref, newValue ->
                    val value = newValue as String
                    changed(value)
                    pref.summary = labels[values.indexOf(value).coerceAtLeast(0)]
                    true
                }
            })
        }
        choice("settings_widget_colours", R.string.widget_tint,
            arrayOf(TwidgetStore.COLOR_MODE_SYSTEM, TwidgetStore.COLOR_MODE_LIGHT, TwidgetStore.COLOR_MODE_DARK),
            arrayOf(getString(R.string.widget_tint_system), getString(R.string.widget_tint_light), getString(R.string.widget_tint_dark)), defaults.colorMode) {
            update(defaults.copy(colorMode = it, tintColor = if (it == TwidgetStore.COLOR_MODE_DARK) 0x00000000 else 0x00FFFFFF))
        }
        choice("settings_widget_font", R.string.widget_font,
            arrayOf(TwidgetStore.FONT_SYSTEM, TwidgetStore.FONT_ONE_UI_SANS, TwidgetStore.FONT_GOOGLE_SANS_FLEX),
            arrayOf(
                getString(R.string.widget_font_system),
                getString(R.string.widget_font_one_ui),
                getString(R.string.widget_font_google),
            ), defaults.fontFamily) {
            update(defaults.copy(fontFamily = it))
        }
        val logoValues = arrayOf(TwidgetStore.LOGO_X, TwidgetStore.LOGO_TWITTER)
        val logoLabels = arrayOf(getString(R.string.widget_logo_x), getString(R.string.widget_logo_twitter))
        val logoRow = CardItemView(context).apply { title = getString(R.string.widget_logo_style) }
        fun displayLogo(value: String) {
            logoRow.summary = logoLabels[logoValues.indexOf(value).coerceAtLeast(0)]
            logoRow.getEndImageView().setImageResource(
                if (value == TwidgetStore.LOGO_TWITTER) R.drawable.ic_settings_twitter else R.drawable.ic_logo_x,
            )
        }
        displayLogo(defaults.logo)
        val logoPreference = LayoutPreference(context, logoRow).apply {
            key = "settings_widget_logo"
            setTitle(R.string.widget_logo_style)
            setAllowDividerAbove(true)
            setAllowDividerBelow(true)
            setOnPreferenceChangeListener { _, value ->
                val logo = value as String
                update(defaults.copy(logo = logo))
                displayLogo(logo)
                true
            }
        }
        val openLogoPicker = {
            AlertDialog.Builder(context)
                .setTitle(R.string.widget_logo_style)
                .setSingleChoiceItems(logoLabels, logoValues.indexOf(defaults.logo).coerceAtLeast(0)) { dialog, index ->
                    logoPreference.callChangeListener(logoValues[index])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        logoRow.setOnClickListener { openLogoPicker() }
        logoPreference.setOnPreferenceClickListener { openLogoPicker(); true }
        screen.addPreference(logoPreference)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun category(titleRes: Int): PreferenceCategory = PreferenceCategory(requireContext()).apply {
        if (titleRes != 0) setTitle(titleRes)
        isIconSpaceReserved = false
    }

    private fun save(next: TwidgetSettings) {
        settings = next
        TwidgetStore.saveSettings(requireContext(), next)
        TwidgetWidget.updateAll(requireContext())
    }

    private fun dataSourceTitle(source: String): String = when (source) {
        TwidgetStore.DATA_SOURCE_FXTWITTER -> getString(R.string.source_fxtwitter)
        TwidgetStore.DATA_SOURCE_SELF_HOSTED -> getString(R.string.source_self_hosted)
        TwidgetStore.DATA_SOURCE_X_API -> getString(R.string.source_x_api)
        TwidgetStore.DATA_SOURCE_TWITTERAPIS -> getString(R.string.source_twitterapis)
        else -> getString(R.string.source_default)
    }

    private fun providerLabel(provider: ScheduleProvider): String = getString(
        if (provider == ScheduleProvider.BUFFER) {
            R.string.schedule_provider_buffer
        } else {
            R.string.schedule_provider_local
        }
    )

}
