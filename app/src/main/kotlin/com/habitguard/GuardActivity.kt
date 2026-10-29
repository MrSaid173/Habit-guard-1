package com.habitguard

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/** Same black screen but as an activity: shows over the keyguard and receives key events. */
class GuardActivity : Activity() {
    private var taps = 0
    private var lastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("HabitGuard", "activity created, guard active=${Guard.active}")
        if (!Guard.active) {
            finish()
            return
        }
        setShowWhenLocked(true)
        val view = GuardView(this, Guard.config.countdown)
        view.setSeconds(Guard.config.seconds)
        setContentView(view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // pure black mode: dim the backlight; countdown mode: leave brightness alone so digits are readable
        if (!Guard.config.countdown) {
            window.attributes = window.attributes.also { it.screenBrightness = 0f }
        }
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        Guard.attach(this, view)
    }

    override fun onResume() {
        super.onResume()
        Trace.t("activity resumed")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = SystemClock.elapsedRealtime()
            taps = if (now - lastTap < 1500) taps + 1 else 1
            lastTap = now
            if (taps >= 3) Guard.emergency()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // swallowed while the delay is active
    }

    override fun onDestroy() {
        Guard.detach(this)
        super.onDestroy()
    }
}
