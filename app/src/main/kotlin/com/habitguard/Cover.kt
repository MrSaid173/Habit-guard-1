package com.habitguard

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager

/**
 * Black window of the accessibility service. TYPE_ACCESSIBILITY_OVERLAY sits above the
 * keyguard (the normal overlay and everything else we have does not), and the window stays
 * attached all the time: only alpha / touch flags change, which needs no redraw. So it is
 * already in the layer stack when the panel lights up and the lock screen is never seen.
 *
 * HIDDEN: fully transparent, touches pass through (normal use, AOD / ambient pulses)
 * ARMED:  black, not touchable (screen is off, waiting for a wake-up)
 * ACTIVE: black (+ countdown digits), eats touches
 * Main thread only.
 */
object Cover {
    const val HIDDEN = 0
    const val ARMED = 1
    const val ACTIVE = 2

    private const val BASE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private var wm: WindowManager? = null
    private var view: GuardView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var mode = HIDDEN
    private var digits = false

    val ready: Boolean get() = view != null

    /** `svc` must be the AccessibilityService itself: its WindowManager carries the overlay token. */
    fun attach(svc: Context) {
        detach()
        try {
            val m = svc.getSystemService(WindowManager::class.java)
            val v = GuardView(svc, false)
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            p.alpha = 0f
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.title = "HabitGuardCover"
            m.addView(v, p)
            wm = m
            view = v
            lp = p
            mode = HIDDEN
            digits = false
            Trace.t("cover attached")
        } catch (e: Throwable) {
            Log.w("HabitGuard", "cover attach failed", e)
            wm = null
            view = null
            lp = null
        }
    }

    fun detach() {
        val v = view
        if (v != null) {
            try {
                wm?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        wm = null
        view = null
        lp = null
        mode = HIDDEN
        digits = false
    }

    fun set(newMode: Int, countdown: Boolean = false) {
        val v = view ?: return
        val p = lp ?: return
        val d = newMode == ACTIVE && countdown
        if (newMode == mode && d == digits) return
        mode = newMode
        digits = d
        Trace.t("cover mode=$newMode digits=$d")
        when (newMode) {
            ACTIVE -> {
                p.alpha = 1f
                p.flags = BASE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                // black mode keeps the backlight at minimum, countdown mode must stay readable
                p.screenBrightness = if (countdown) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else 0f
            }
            ARMED -> {
                p.alpha = 1f
                p.flags = BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                p.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            else -> {
                p.alpha = 0f
                p.flags = BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                p.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        v.setCountdown(d)
        try {
            wm?.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.w("HabitGuard", "cover update failed", e)
        }
    }

    fun setSeconds(s: Int) {
        if (mode == ACTIVE) view?.setSeconds(s)
    }
}
