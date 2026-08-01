package com.tjg.twidget.brief

import android.content.Context
import com.tjg.twidget.data.SecureCredentialStore

object BriefSettingsStore {
    private const val PREFS = "twidget_brief_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun provider(context: Context): BriefProviderMode =
        BriefProviderMode.fromStorageId(prefs(context).getString(KEY_PROVIDER, null))

    fun setProvider(context: Context, provider: BriefProviderMode) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.storageId).apply()
    }

    fun cloudApiKey(context: Context): String =
        SecureCredentialStore.read(context, SecureCredentialStore.GEMINI_API_KEY)

    fun setCloudApiKey(context: Context, apiKey: String) {
        SecureCredentialStore.write(context, mapOf(SecureCredentialStore.GEMINI_API_KEY to apiKey))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
