package com.habitguard

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline for diagnostics (logcat drops our lines on some ROMs). Costs one stat() per call
 * unless `files/trace_on` exists; sync_config.sh creates it when /data/adb/habitguard/trace exists.
 */
object Trace {
    private const val MAX_BYTES = 48_000L
    private var dir: File? = null

    fun init(ctx: Context) {
        dir = ctx.filesDir
    }

    fun t(msg: String) {
        val d = dir ?: return
        try {
            if (!File(d, "trace_on").exists()) return
            val f = File(d, "trace.log")
            if (f.length() > MAX_BYTES) f.writeText("")
            val wall = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$wall ${SystemClock.elapsedRealtime()} $msg\n")
        } catch (_: Throwable) {
        }
    }
}
