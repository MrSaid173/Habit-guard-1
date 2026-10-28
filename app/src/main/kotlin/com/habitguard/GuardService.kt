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

class GuardService : Service() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> Guard.onScreenOn()
                Intent.ACTION_SCREEN_OFF -> Guard.onScreenOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Guard.init(applicationContext)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
