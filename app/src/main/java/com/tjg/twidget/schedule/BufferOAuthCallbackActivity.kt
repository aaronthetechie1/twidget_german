package com.tjg.twidget.schedule

import com.tjg.twidget.core.UiMessages
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.tjg.twidget.core.AppExecutors

class BufferOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callback = intent?.data
        if (callback == null) {
            finish()
            return
        }
        AppExecutors.execute(onRejected = { finish() }) {
            val result = BufferOAuth.exchangeCallback(this, callback)
            runOnUiThread {
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { getString(com.tjg.twidget.R.string.l10n_buffer_connected_message) },
                        onFailure = { UiMessages.resolve(this, it.message, com.tjg.twidget.R.string.l10n_error_buffer_login) },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        }
    }
}
