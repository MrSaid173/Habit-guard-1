package com.habitguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import android.util.Log

class GuardService : Service() {
    // POSIX lock held for the whole life of the process: the root daemon probes it to
    // know that the screen_state file is trustworthy (a dead APK releases it)
    private var aliveFile: RandomAccessFile? = null
    private var aliveLock: FileLock? = null

    private val main = Handler(Looper.getMainLooper())

    // The root daemon drops `power_wake` just before letting a power-key press wake the phone.
    // Watching it raises the guard BEFORE the screen lights (Guard.onPowerWake was never wired up).
    private var wakeObserver: FileObserver? = null

    // Panel-on event of the default display: arrives well before ACTION_SCREEN_ON, which
    // is what lets a double-tap / lift wake-up show the countdown without a lock-screen flash.
    private var lastDisplayState = Display.STATE_UNKNOWN
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val st = getSystemService(DisplayManager::class.java)
                .getDisplay(displayId)?.state ?: return
            if (st == lastDisplayState) return
            lastDisplayState = st
            if (st == Display.STATE_ON) Guard.onDisplayOn() else Guard.onDisplayOff()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    writeState(true)
                    Guard.onScreenOn()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    writeState(false)
                    Guard.onScreenOff()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Guard.init(applicationContext)
        holdAliveLock()
        Log.i("HabitGuard", "service created")
        writeState(getSystemService(PowerManager::class.java).isInteractive)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("guard", "Habit Guard", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            1,
            Notification.Builder(this, "guard")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("Habit Guard")
                .build()
        )
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        watchPowerWake()
        watchDisplay()
    }

    private fun watchPowerWake() {
        try {
            val obs = object : FileObserver(filesDir, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    // FileObserver calls back on its own thread; Guard works on the main one
                    if (path == "power_wake") main.post { Guard.onPowerWake() }
                }
            }
            obs.startWatching()
            wakeObserver = obs
        } catch (e: Throwable) {
            Log.w("HabitGuard", "power_wake watch failed", e)
        }
    }

    private fun watchDisplay() {
        try {
            val dm = getSystemService(DisplayManager::class.java)
            lastDisplayState = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
            dm.registerDisplayListener(displayListener, main)
        } catch (e: Throwable) {
            Log.w("HabitGuard", "display listener failed", e)
        }
    }

    private fun holdAliveLock() {
        try {
            val f = RandomAccessFile(File(filesDir, "alive"), "rw")
            aliveLock = f.channel.tryLock()
            aliveFile = f // keep the reference: closing the file drops the lock
        } catch (e: Throwable) {
            Log.w("HabitGuard", "alive lock failed", e)
        }
    }

    /** The root daemon (hgd) reads this to know whether the screen is on. */
    private fun writeState(on: Boolean) {
        try {
            File(filesDir, "screen_state").writeText(if (on) "1" else "0")
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        wakeObserver?.stopWatching()
        try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) } catch (_: Throwable) {}
        try { aliveLock?.release(); aliveFile?.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
