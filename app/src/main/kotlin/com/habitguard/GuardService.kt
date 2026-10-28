package com.habitguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import android.util.Log

class GuardService : Service() {
    // POSIX lock held for the whole life of the process: the root daemon probes it to
    // know that the screen_state file is trustworthy (a dead APK releases it)
    private var aliveFile: RandomAccessFile? = null
    private var aliveLock: FileLock? = null

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
        try { aliveLock?.release(); aliveFile?.close() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
