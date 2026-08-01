package com.tjg.twidget.settings

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.tjg.twidget.R
import com.tjg.twidget.brief.BriefProviderMode
import com.tjg.twidget.brief.BriefSettingsStore
import com.tjg.twidget.brief.BriefStore
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.InsetPreferenceFragment
import com.tjg.twidget.ui.startSettingsSubActivity
import dev.oneuiproject.oneui.preference.LayoutPreference

class BriefSettingsPreferenceFragment : InsetPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {
        val context = requireContext()
        val account = TwidgetStore.settings(context).username
        val screen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = "brief_enabled_pref"
            title = enabledTitle(BriefSettingsStore.enabled(context))
            isChecked = BriefSettingsStore.enabled(context)
            setOnPreferenceChangeListener { preference, value ->
                val enabled = value as Boolean
                BriefSettingsStore.setEnabled(context, enabled)
                preference.title = enabledTitle(enabled)
                true
            }
        })

        screen.addPreference(spacerCategory())
        screen.addPreference(LayoutPreference(
            context,
            LayoutInflater.from(context).inflate(R.layout.preference_brief_intro, null, false),
        ).apply {
            key = "brief_intro"
            isSelectable = false
        })

        screen.addPreference(spacerCategory())
        val provider = BriefSettingsStore.provider(context)
        screen.addPreference(ListPreference(context).apply {
            key = "brief_provider_pref"
            title = getString(R.string.brief_provider)
            entries = arrayOf(
                getString(R.string.brief_provider_auto),
                getString(R.string.brief_provider_local),
                getString(R.string.brief_provider_cloud),
            )
            entryValues = BriefProviderMode.entries.map { it.storageId }.toTypedArray()
            value = provider.storageId
            summary = providerLabel(provider)
            setOnPreferenceChangeListener { preference, newValue ->
                val selected = BriefProviderMode.fromStorageId(newValue as String)
                BriefSettingsStore.setProvider(context, selected)
                BriefStore.resetAi(context, account)
                preference.summary = providerLabel(selected)
                listView.post { buildScreen() }
                true
            }
        })
        screen.addPreference(EditTextPreference(context).apply {
            key = "brief_cloud_api_key_pref"
            title = getString(R.string.brief_ai_studio_key)
            isPersistent = false
            isVisible = provider != BriefProviderMode.LOCAL
            val current = BriefSettingsStore.cloudApiKey(context)
            text = current
            summary = if (current.isBlank()) null else getString(R.string.brief_cloud_key_configured)
            setOnBindEditTextListener { editor ->
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                editor.setSelectAllOnFocus(true)
            }
            setOnPreferenceChangeListener { preference, value ->
                val key = (value as String).trim()
                BriefSettingsStore.setCloudApiKey(context, key)
                BriefStore.resetAi(context, account)
                preference.summary = if (key.isBlank()) null else {
                    getString(R.string.brief_cloud_key_configured)
                }
                true
            }
        })

        if (TwidgetStore.debugMenuUnlocked(context)) {
            screen.addPreference(spacerCategory())
            screen.addPreference(Preference(context).apply {
                key = "brief_debug_pref"
                title = getString(R.string.brief_settings_debug)
                setOnPreferenceClickListener {
                    requireActivity().startSettingsSubActivity(
                        Intent(context, BriefDebugActivity::class.java),
                    )
                    true
                }
            })
        }

        screen.addBottomInset()
        preferenceScreen = screen
    }

    private fun enabledTitle(enabled: Boolean): String = getString(
        if (enabled) R.string.brief_settings_on else R.string.brief_settings_off,
    )

    private fun providerLabel(provider: BriefProviderMode): String = getString(
        when (provider) {
            BriefProviderMode.AUTO -> R.string.brief_provider_auto_short
            BriefProviderMode.LOCAL -> R.string.brief_provider_local
            BriefProviderMode.CLOUD -> R.string.brief_provider_cloud
        },
    )

    private fun spacerCategory() = PreferenceCategory(requireContext()).apply {
        isIconSpaceReserved = false
    }
}
