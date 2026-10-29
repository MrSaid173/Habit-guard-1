package com.habitguard

import android.content.Context
import org.json.JSONObject
import java.io.File

data class DelayConfig(
    val enabled: Boolean = false,
    val seconds: Int = 15,
    val countdown: Boolean = false,
    val trueSleep: Boolean = false,
    val skipCall: Boolean = false,
    val skipAlarm: Boolean = false,
    val skipCharging: Boolean = false,
    val bypassMinutes: Int = 5,
    val cover: Boolean = true // black accessibility window above the lock screen (see Cover)
) {
    companion object {
        /** Config is synced here by module/scripts/sync_config.sh */
        fun load(ctx: Context): DelayConfig {
            return try {
                val f = File(ctx.filesDir, "config.json")
                if (!f.exists()) return DelayConfig()
                val j = JSONObject(f.readText()).optJSONObject("screen_delay") ?: return DelayConfig()
                val skip = HashSet<String>()
                val arr = j.optJSONArray("skip_on")
                if (arr != null) {
                    for (i in 0 until arr.length()) skip.add(arr.optString(i))
                }
                DelayConfig(
                    enabled = j.optBoolean("enabled", false),
                    seconds = j.optInt("seconds", 15).coerceIn(1, 300),
                    countdown = j.optString("mode", "black") == "countdown",
                    trueSleep = j.optBoolean("true_sleep", false),
                    skipCall = "call" in skip,
                    skipAlarm = "alarm" in skip,
                    skipCharging = "charging" in skip,
                    bypassMinutes = j.optInt("emergency_bypass_min", 5).coerceIn(1, 60),
                    cover = j.optBoolean("cover", true)
                )
            } catch (_: Throwable) {
                DelayConfig()
            }
        }
    }
}
