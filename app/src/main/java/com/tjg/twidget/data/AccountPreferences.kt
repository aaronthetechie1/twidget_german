package com.tjg.twidget.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Account stores share the migration monitor; queued writes resolve redirects at commit time. */
internal class AccountPreferences private constructor(
    private val context: Context,
    private val name: String,
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    private fun key(key: String): String {
        TwidgetStore.recoverAccountMigration(context)
        var resolved = key
        val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
        main.all.forEach { (alias, value) ->
            if (alias.startsWith(AccountHandleMigration.REDIRECT_PREFIX) && value is String) {
                AccountHandleMigration.companionKey(name, key, alias.removePrefix(AccountHandleMigration.REDIRECT_PREFIX), value.lowercase(java.util.Locale.US))
                    ?.let { resolved = it }
            }
        }
        return resolved
    }
    override fun getString(key: String, defValue: String?): String? = synchronized(TwidgetStore) { delegate.getString(key(key), defValue) }
    override fun getLong(key: String, defValue: Long): Long = synchronized(TwidgetStore) { delegate.getLong(key(key), defValue) }
    override fun getInt(key: String, defValue: Int): Int = synchronized(TwidgetStore) { delegate.getInt(key(key), defValue) }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(TwidgetStore) { delegate.getBoolean(key(key), defValue) }
    override fun getFloat(key: String, defValue: Float): Float = synchronized(TwidgetStore) { delegate.getFloat(key(key), defValue) }
    override fun contains(key: String): Boolean = synchronized(TwidgetStore) { delegate.contains(key(key)) }
    override fun edit(): SharedPreferences.Editor = Editor()

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        private fun flush(sync: Boolean): Boolean = synchronized(TwidgetStore) {
            TwidgetStore.recoverAccountMigration(context)
            val edit = delegate.edit()
            check(!clear) { "Account preferences must be cleared by account, not globally" }
            changes.forEach { (original, value) ->
                val target = key(original)
                if (value != null) {
                    val main = context.getSharedPreferences(TwidgetStore.PREFS, Context.MODE_PRIVATE)
                    require(main.all.none { (removedKey, removed) ->
                        removedKey.startsWith("account_removed_") && removed == true &&
                            AccountHandleMigration.companionKey(name, target, removedKey.removePrefix("account_removed_"), removedKey.removePrefix("account_removed_")) != null
                    }) { "The selected account has been removed." }
                }
                val rewritten = if (target != original && value is String && name in listOf("twidget_briefs", "twidget_analytics") && !target.startsWith("attempt:")) {
                    JSONObject(value).apply {
                        val owner = if (name == "twidget_briefs") "username" else "userName"
                        if (has(owner)) put(owner, TwidgetStore.canonicalAccount(context, optString(owner)))
                    }.toString()
                } else value
                if (rewritten == null) edit.remove(target) else AccountHandleMigration.put(edit, target, rewritten)
            }
            if (sync) edit.commit() else { edit.apply(); true }
        }
        override fun commit(): Boolean = flush(true)
        override fun apply() { flush(false) }
    }
    companion object {
        fun open(context: Context, name: String): SharedPreferences =
            AccountPreferences(context.applicationContext, name, context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}
