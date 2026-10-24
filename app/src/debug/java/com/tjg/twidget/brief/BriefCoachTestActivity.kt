package com.tjg.twidget.brief

import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Full-screen, target-UID host for instrumented on-device inference. Never included in release builds. */
class BriefCoachTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "Twidget AI coaching test" })
    }
}
