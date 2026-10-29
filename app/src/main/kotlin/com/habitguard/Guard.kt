package com.habitguard

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import java.io.File

/** Core of stage 1: on SCREEN_ON cover the screen for N seconds. */
object Guard {
    private const val TICK_MS = 200L
    private const val TAG = "HabitGuard"
    private const val RELAUNCH_MS = 1500L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var app: Context

    var config = DelayConfig()
        private set
    var active = false
        private set

    private var startedAt = 0L
    private var bypassUntil = 0L
    private var lastLaunch = 0L
    private var wakeGraceUntil = 0L // power-key wake in flight: screen is not on yet / stale OFF broadcast
    private var authorized = false // this screen-on session was allowed by the daemon
    private var earlyUntil = 0L // guard was raised from the display-on event: the SCREEN_ON broadcast of the same wake-up is a duplicate
    private var earlyGuard = false // the running guard was started from the display-on event
    private var overlay: GuardView? = null
    private var lastLockAt = -10_000L // last "lock now" for an unexpected wake-up
    private var lockVerifyLeft = 0 // remaining "is it really locked?" checks after an unexpected wake-up
    private var released = false // the delay of the current wake-up is over (or exempt): the wall may be down
    private var activity: GuardActivity? = null
    private var activityView: GuardView? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        config = DelayConfig.load(app)
        Trace.init(app)
    }

    fun onScreenOn() {
        config = DelayConfig.load(app)
        handler.removeCallbacks(sleepCheck)
        Log.i(TAG, "SCREEN_ON enabled=${config.enabled} seconds=${config.seconds} active=$active")
        if (SystemClock.elapsedRealtime() < earlyUntil) {
            Log.i(TAG, "skip: guard already raised from display-on")
            return
        }
        // wake-up already delayed by the root daemon (true sleep): do not delay twice
        if (consumeMark("pass", 5000L)) {
            authorized = true
            release("pass mark")
            Log.i(TAG, "skip: wake-up was delayed by daemon")
            return
        }
        if (authorized) {
            release("authorized")
            Log.i(TAG, "skip: session already allowed")
            return
        }
        if (!config.enabled || active) return
        if (SystemClock.elapsedRealtime() < bypassUntil) {
            release("emergency bypass")
            Log.i(TAG, "skip: emergency bypass")
            return
        }
        if (shouldYield()) {
            release("call/alarm/charging")
            Log.i(TAG, "skip: call/alarm/charging")
            return
        }
        // countdown + true sleep: the daemon let this power-key wake-up through, guard it
        if (consumeMark("power_wake", 5000L)) {
            start()
            return
        }
        // black-screen true sleep: the screen must not be lit by anything but the power key.
        // countdown mode: every wake-up (fingerprint, double tap...) just gets the countdown
        if (config.trueSleep && !config.countdown) {
            val pm = app.getSystemService(PowerManager::class.java)
            if (!pm.isInteractive) {
                Log.i(TAG, "skip: screen already back to sleep")
                return
            }
            released = false
            syncWall("unexpected wake-up (broadcast)")
            if (requestSleep()) {
                blackOut()
                lockDevice()
                return
            }
        }
        start()
    }

    /**
     * The daemon dropped `power_wake` just before letting a power press wake the phone
     * (countdown + true sleep). Raise the guard now, before the screen is lit, so the
     * lock screen / home never flashes. SCREEN_ON may arrive late or be coalesced with a
     * quick off->on, so we cannot wait for it.
     */
    fun onPowerWake() {
        if (!consumeMark("power_wake", 5000L)) return
        config = DelayConfig.load(app)
        if (!config.enabled || active) return
        if (SystemClock.elapsedRealtime() < bypassUntil) return
        if (shouldYield()) return
        authorized = false
        handler.removeCallbacks(sleepCheck)
        wakeGraceUntil = SystemClock.elapsedRealtime() + 2000L
        Log.i(TAG, "power wake: guard raised before screen-on")
        Trace.t("power wake")
        start()
    }

    /**
     * Display state change (DisplayListener in GuardService); `prev` is the state before it.
     * It arrives long before ACTION_SCREEN_ON (0.5-1.5 s late), so the guard is raised from here;
     * the Cover (accessibility window above the lock screen) is armed while the display is off.
     */
    fun onDisplayState(st: Int, prev: Int) {
        config = DelayConfig.load(app)
        Trace.t("display $prev->$st active=$active")
        if (st == Display.STATE_ON) {
            onDisplayOn(prev)
            return
        }
        rearmIfOff("display $st")
        if (earlyGuard && active) dismiss("display off") else refreshCover()
    }

    /** The accessibility service (or GuardService) came up: bring the cover in line with the current state. */
    fun onCoverReady() {
        config = DelayConfig.load(app)
        // (re)start of the service: a lit screen with no delay running holds nothing back
        if (displayState() == Display.STATE_ON && !active) released = true
        syncWall("init")
        refreshCover()
    }

    /** The daemon dropped `pass`: its delayed wake-up is about to light the screen, so lower the wall first. */
    fun onPassMark() {
        val f = File(app.filesDir, "pass")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 5000L) return
        config = DelayConfig.load(app)
        release("pass mark seen")
    }

    private fun onDisplayOn(prev: Int) {
        // safety valve: whatever is decided below, a cover must never outlive the wake-up
        handler.removeCallbacks(coverWatchdog)
        handler.postDelayed(coverWatchdog, 1500L)
        if (active) return
        val pm = app.getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return // ambient pulse (STATE_ON while dozing): not a wake-up
        if (prev != Display.STATE_OFF && prev != Display.STATE_DOZE && prev != Display.STATE_DOZE_SUSPEND) return
        // black-screen true sleep: nothing but the daemon's delayed power press may light the panel.
        // Ask for sleep from the display-on event: it comes long before ACTION_SCREEN_ON, so
        // lift / double tap / face unlock get much less time before the panel goes dark again.
        if (config.enabled && config.trueSleep && !config.countdown) {
            earlySleep()
            return
        }
        if (!guardsWakeups() || authorized) {
            Cover.set(Cover.HIDDEN)
            release("display on: not guarded")
            return
        }
        if (File(app.filesDir, "pass").exists() ||
            SystemClock.elapsedRealtime() < bypassUntil || shouldYield()
        ) {
            Cover.set(Cover.HIDDEN)
            release("display on: pass/bypass/yield")
            Trace.t("display on: not guarded (pass/bypass/yield)")
            return
        }
        consumeMark("power_wake", 5000L) // same wake-up as a power press: do not leave the mark behind
        handler.removeCallbacks(sleepCheck)
        val now = SystemClock.elapsedRealtime()
        wakeGraceUntil = now + 2000L // a late OFF broadcast of the previous press
        earlyUntil = now + 3000L
        earlyGuard = true
        Log.i(TAG, "display on: guard raised before SCREEN_ON")
        start()
    }

    /**
     * Display-on in black true-sleep mode: same checks as the broadcast path, but earlier.
     * A wake-up nobody could veto (fingerprint, face, notification...) gets the black cover AND the
     * sleep request at the same moment: the cover is already in the layer stack (armed while the
     * screen was off), so the lock screen / face-unlock UI is never visible while KEY_SLEEP is on its way.
     */
    private fun earlySleep() {
        if (authorized) {
            Cover.set(Cover.HIDDEN)
            release("early: authorized")
            return
        }
        if (SystemClock.elapsedRealtime() < bypassUntil || shouldYield()) {
            Cover.set(Cover.HIDDEN)
            release("early: bypass/yield")
            return
        }
        val pass = File(app.filesDir, "pass")
        if (pass.exists() && System.currentTimeMillis() - pass.lastModified() < 5000) {
            authorized = true // the delayed wake-up of the daemon; the SCREEN_ON broadcast consumes the file
            lockVerifyLeft = 0
            Cover.set(Cover.HIDDEN)
            release("early: delayed by daemon")
            Trace.t("display on: delayed by daemon")
            return
        }
        Trace.t("display on: unexpected, cover + lock + sleep request")
        released = false
        syncWall("unexpected wake-up")
        blackOut()
        lockDevice()
        requestSleep()
    }

    /**
     * Lock the keyguard at once, in parallel with the sleep request. Without it a biometric that
     * succeeded behind the cover (face / fingerprint) leaves the phone unlocked until the system's
     * "lock after screen timeout". The accessibility lock action also sends the phone to sleep.
     * display-on and SCREEN_ON of the same wake-up both get here: once per wake-up is enough.
     */
    private fun lockDevice() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockAt < 3000L) return
        lastLockAt = now
        val ok = CoverService.lockNow()
        Trace.t("lock now: $ok")
        Log.i(TAG, "unexpected wake-up: lock now -> $ok")
        // a biometric that succeeded at the same moment can undo the lock: check, and lock again
        lockVerifyLeft = 8
        handler.removeCallbacks(lockVerify)
        handler.postDelayed(lockVerify, 250L)
    }

    /** Re-locks until the keyguard reports "locked" (or the attempts run out: Smart Lock, no PIN...). */
    private val lockVerify = object : Runnable {
        override fun run() {
            if (lockVerifyLeft <= 0 || authorized) return
            val km = app.getSystemService(KeyguardManager::class.java)
            if (!km.isDeviceSecure || km.isDeviceLocked) {
                lockVerifyLeft = 0
                Trace.t("lock verified")
                return
            }
            lockVerifyLeft--
            val ok = CoverService.lockNow()
            Trace.t("lock verify: still open, lock again -> $ok ($lockVerifyLeft left)")
            handler.postDelayed(this, 300L)
        }
    }

    /** Black, touch-eating cover (backlight at minimum) for a wake-up that is being put back to sleep. */
    private fun blackOut() {
        if (config.cover) Cover.set(Cover.ACTIVE, false)
    }

    /** Wake-ups are delayed by the guard (everything except black-screen true sleep, where the daemon does it). */
    private fun guardsWakeups() = config.enabled && (config.countdown || !config.trueSleep)

    private fun coverEligible() =
        config.cover && config.enabled && SystemClock.elapsedRealtime() >= bypassUntil && !shouldYield()

    private fun displayState(): Int =
        app.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN

    /** Black while the panel is off (so it is already in place on wake-up), transparent otherwise. */
    private fun refreshCover() {
        if (!Cover.ready) return
        val st = displayState()
        val asleep = st == Display.STATE_OFF || st == Display.STATE_DOZE_SUSPEND
        Cover.set(if (asleep && coverEligible()) Cover.ARMED else Cover.HIDDEN)
    }

    private val coverWatchdog = Runnable {
        if (!active && displayState() == Display.STATE_ON) {
            Trace.t("watchdog: cover released")
            Cover.set(Cover.HIDDEN)
        }
    }

    /**
     * True if mark file `name` exists and is younger than maxAgeMs; the mark is consumed either way.
     * The mark is CLAIMED with an atomic rename first (exists() + delete() was a check-then-act
     * race: a fresh mark the daemon wrote in between got deleted unseen). Only one claimant can win,
     * and a mark written after the rename is a new file that stays for its own consumer.
     */
    private fun consumeMark(name: String, maxAgeMs: Long): Boolean {
        val f = File(app.filesDir, name)
        val claimed = File(app.filesDir, "$name.claimed")
        if (!f.renameTo(claimed)) {
            // no mark, or somebody else just took it -- or this ROM / SELinux refuses the rename:
            // then fall back to the old check-then-delete rather than lose a valid mark
            if (!f.exists()) return false
            val fresh = System.currentTimeMillis() - f.lastModified() < maxAgeMs
            f.delete()
            return fresh
        }
        val fresh = System.currentTimeMillis() - claimed.lastModified() < maxAgeMs
        claimed.delete()
        return fresh
    }

    fun onScreenOff() {
        authorized = false
        handler.removeCallbacks(sleepCheck)
        rearmIfOff("screen off")
        // a late OFF broadcast of the previous press must not kill a guard raised for the new one
        if (active && SystemClock.elapsedRealtime() < wakeGraceUntil) return
        if (active) dismiss("screen off") else refreshCover()
    }

    /** Ask the root daemon to put the phone back to sleep (it watches this file). */
    private fun requestSleep(): Boolean {
        return try {
            File(app.filesDir, "sleep_req").writeText(System.currentTimeMillis().toString())
            handler.postDelayed(sleepCheck, 1500L)
            Log.i(TAG, "unexpected wake-up: asked daemon to sleep")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "sleep request failed", e)
            false
        }
    }

    /** Daemon did not react (not running / no root helper): fall back to the overlay. */
    private val sleepCheck = Runnable {
        val pm = app.getSystemService(PowerManager::class.java)
        if (pm.isInteractive && !active && !authorized && config.enabled && !shouldYield()) {
            Log.w(TAG, "screen still on after sleep request: overlay fallback")
            start()
        }
    }

    /** Triple volume-up on the guard screen: no delay for config.bypassMinutes. */
    fun emergency() {
        bypassUntil = SystemClock.elapsedRealtime() + config.bypassMinutes * 60_000L
        dismiss("emergency")
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
        released = false
        syncWall("delay start")
        Trace.t("start")
        Cover.set(Cover.ACTIVE, config.countdown)
        Cover.setSeconds(config.seconds)
        addOverlay() // it also lets us start the activity from background
        launchActivity()
        Log.i(TAG, "start: delay ${config.seconds}s")
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun launchActivity() {
        lastLaunch = SystemClock.elapsedRealtime()
        try {
            app.startActivity(
                Intent(app, GuardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "startActivity failed", e)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val pm = app.getSystemService(PowerManager::class.java)
            val left = config.seconds * 1000L - (SystemClock.elapsedRealtime() - startedAt)
            val why = when {
                !pm.isInteractive && SystemClock.elapsedRealtime() >= wakeGraceUntil -> "not interactive"
                left <= 0 -> "time is up"
                shouldYield() -> "call/alarm/charging"
                else -> null
            }
            if (why != null) {
                dismiss(why)
                return
            }
            // the activity (the only thing that shows above the lock screen) died or never started
            if (activity == null && SystemClock.elapsedRealtime() - lastLaunch > RELAUNCH_MS) {
                Log.i(TAG, "activity missing: relaunch")
                launchActivity()
            }
            val s = ((left + 999) / 1000).toInt()
            Cover.setSeconds(s)
            overlay?.setSeconds(s)
            activityView?.setSeconds(s)
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun dismiss(reason: String = "unknown") {
        Log.i(TAG, "dismiss: $reason")
        Trace.t("dismiss: $reason")
        active = false
        earlyGuard = false
        // the wall comes down only when the delay is really over; a switch-off keeps it up
        released = reason != "screen off" && reason != "display off" && reason != "not interactive"
        syncWall("dismiss: $reason")
        handler.removeCallbacks(tick)
        overlay?.let {
            try {
                app.getSystemService(WindowManager::class.java).removeView(it)
            } catch (_: Throwable) {
            }
        }
        overlay = null
        activity?.finish()
        refreshCover() // display on: transparent; display off (screen-off dismiss): black again
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
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        if (!config.countdown) lp.screenBrightness = 0f
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        try {
            app.getSystemService(WindowManager::class.java).addView(v, lp)
            overlay = v
            Log.i(TAG, "overlay added")
        } catch (e: Throwable) {
            Log.w(TAG, "addView failed", e)
        }
    }

    private fun release(why: String) {
        released = true
        syncWall(why)
    }

    /** Panel really is not on (a late event of an earlier switch-off must not re-arm a live screen). */
    private fun rearmIfOff(why: String) {
        if (displayState() == Display.STATE_ON) return
        released = false
        syncWall(why)
    }

    /**
     * "Hard wall": while the phone is asleep or a delay is running, the lock screen takes no face,
     * fingerprint or Smart Lock (DevicePolicyManager keyguard features, see Wall). The cover only
     * hides the screen, it cannot stop a biometric that succeeds behind it. PIN / password still work.
     * Up on every switch-off and at the start of a delay, down the moment the delay is over.
     */
    private fun syncWall(why: String) {
        if (!config.hardWall) {
            Wall.retire(app)
            return
        }
        val want = config.enabled && !released && SystemClock.elapsedRealtime() >= bypassUntil
        Trace.t("wall sync ($why): want=$want released=$released")
        Wall.set(app, want)
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
