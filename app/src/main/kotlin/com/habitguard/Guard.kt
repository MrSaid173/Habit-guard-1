package com.habitguard

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager

/** Core of stage 1: on SCREEN_ON cover the screen for N seconds. */
object Guard {
    private const val TICK_MS = 200L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    var config = DelayConfig()
        private set
    var active = false
        private set

    private var startedAt = 0L
    private var bypassUntil = 0L
    private var overlay: GuardView? = null
    private var activity: GuardActivity? = null
    private var activityView: GuardView? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        config = DelayConfig.load(app)
    }

    fun onScreenOn() {
        config = DelayConfig.load(app)
        if (!config.enabled || active) return
        if (SystemClock.elapsedRealtime() < bypassUntil) return
        if (shouldYield()) return
        start()
    }

    fun onScreenOff() {
        if (active) dismiss()
    }

    /** Triple volume-up on the guard screen: no delay for config.bypassMinutes. */
    fun emergency() {
        bypassUntil = SystemClock.elapsedRealtime() + config.bypassMinutes * 60_000L
        dismiss()
    }

    fun attach(a: GuardActivity, v: GuardView) {
        activity = a
        activityView = v
    }

    fun detach(a: GuardActivity) {
        if (activity === a) {
            activity = null
            activityView = null
        }
    }

    private fun start() {
        active = true
        startedAt = SystemClock.elapsedRealtime()
        addOverlay() // first: it is instant and also lets us start the activity from background
        try {
            app.startActivity(
                Intent(app, GuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (_: Throwable) {
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val pm = app.getSystemService(PowerManager::class.java)
            val left = config.seconds * 1000L - (SystemClock.elapsedRealtime() - startedAt)
            if (!pm.isInteractive || left <= 0 || shouldYield()) {
                dismiss()
                return
            }
            val s = ((left + 999) / 1000).toInt()
            overlay?.setSeconds(s)
            activityView?.setSeconds(s)
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun dismiss() {
        active = false
        handler.removeCallbacks(tick)
        overlay?.let {
            try {
                app.getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Throwable) {
            }
        }
        overlay = null
        activity?.finish()
    }

    private fun addOverlay() {
        val v = GuardView(app, config.countdown)
        v.setSeconds(config.seconds)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        lp.screenBrightness = 0f
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            app.getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
        } catch (_: Throwable) {
        }
    }

    /** True when the screen was lit by something we must not delay (call, alarm, ...). */
    private fun shouldYield(): Boolean {
        val am = app.getSystemService(AudioManager::class.java)
        // ringtone / in-call / VoIP call / call screening
        if (config.skipCall && am.mode != AudioManager.MODE_NORMAL) return true
        if (config.skipAlarm) {
            try {
                if (am.activePlaybackConfigurations.any {
                        it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
                    }) return true
            } catch (_: Throwable) {
            }
        }
        if (config.skipCharging) {
            if (app.getSystemService(BatteryManager::class.java).isCharging) return true
        }
        return false
    }
}
