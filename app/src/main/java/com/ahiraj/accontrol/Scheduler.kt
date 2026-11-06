package com.ahiraj.accontrol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Turns the user's plans into one alarm at a time:
 *  - schedules: [{id,on,name,time:"HH:MM",days:[0..6 Sun..Sat],cmd:{...}}]
 *  - sleep: {on,start,end,days,temps:[..],quiet,dark,offAtEnd}  (temps spread evenly from start to end)
 *  - timers: [{at,cmd,label}]  (one-shot, e.g. "off in 2h")
 */
object Scheduler {
    class Ev(val at: Long, val cmd: JSONObject, val label: String)

    private const val DAY = 86_400_000L

    fun events(cfg: JSONObject, from: Long, to: Long): List<Ev> {
        val out = mutableListOf<Ev>()

        cfg.optJSONArray("schedules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (!s.optBoolean("on", true)) continue
                val (h, m) = hm(s.optString("time", "07:00"))
                val days = ints(s.optJSONArray("days"))
                for (d in -1..8) {
                    val c = dayAt(from, d, h, m)
                    if (days.isNotEmpty() && (c.get(Calendar.DAY_OF_WEEK) - 1) !in days) continue
                    val t = c.timeInMillis
                    if (t in from..to) out += Ev(t, s.optJSONObject("cmd") ?: JSONObject(), s.optString("name", "Schedule"))
                }
            }
        }

        cfg.optJSONObject("sleep")?.let { sl ->
            if (!sl.optBoolean("on")) return@let
            val (h, m) = hm(sl.optString("start", "23:00"))
            val (eh, em) = hm(sl.optString("end", "07:00"))
            val days = ints(sl.optJSONArray("days"))
            val temps = sl.optJSONArray("temps") ?: JSONArray().put(24).put(25).put(26).put(27)
            val n = temps.length().coerceAtLeast(1)
            for (d in -2..8) {
                val c = dayAt(from, d, h, m)
                if (days.isNotEmpty() && (c.get(Calendar.DAY_OF_WEEK) - 1) !in days) continue
                val start = c.timeInMillis
                var end = dayAt(from, d, eh, em).timeInMillis
                if (end <= start) end += DAY
                val span = end - start
                for (k in 0 until n) {
                    val t = start + span * k / n
                    val cmd = JSONObject().put("actmp", temps.optInt(k, 26).toString())
                    if (k == 0) {
                        cmd.put("ps", "on").put("acmd", "cool")
                        if (sl.optBoolean("quiet", true)) cmd.put("acfs", "quiet")
                        if (sl.optBoolean("dark", true)) cmd.put("acdc", "off")
                    }
                    if (t in from..to) out += Ev(t, cmd, if (k == 0) "Sleep mode started" else "Sleep curve")
                }
                if (sl.optBoolean("offAtEnd", true) && end in from..to) {
                    out += Ev(end, JSONObject().put("ps", "off"), "Good morning! AC off")
                }
            }
        }

        cfg.optJSONArray("timers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val at = t.optLong("at")
                if (at in from..to) out += Ev(at, t.optJSONObject("cmd") ?: JSONObject().put("ps", "off"), t.optString("label", "Timer"))
            }
        }
        return out.sortedBy { it.at }
    }

    /** Set the alarm for the first event strictly after [after]. */
    fun reschedule(ctx: Context, after: Long = System.currentTimeMillis()) {
        val cfg = Prefs.config(ctx)
        val from = maxOf(after + 1, System.currentTimeMillis() - 30_000L)
        val next = events(cfg, from, from + 9 * DAY).firstOrNull()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, AlarmReceiver::class.java).putExtra("at", next?.at ?: 0L)
        val pi = PendingIntent.getBroadcast(ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        Prefs.patchCache(ctx) { c ->
            if (next == null) c.remove("next") else c.put("next", JSONObject().put("at", next.at).put("label", next.label))
        }
        if (next == null) return
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, pi)
        }
    }

    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Remove one-shot timers that have now fired. */
    fun dropFiredTimers(ctx: Context, at: Long) {
        val cfg = Prefs.config(ctx)
        val arr = cfg.optJSONArray("timers") ?: return
        val keep = JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optLong("at") > at + 1000) keep.put(t)
        }
        cfg.put("timers", keep)
        Prefs.saveConfig(ctx, cfg)
    }

    private fun hm(s: String): Pair<Int, Int> {
        val p = s.split(":")
        return Pair(p.getOrNull(0)?.toIntOrNull() ?: 0, p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun ints(a: JSONArray?): Set<Int> {
        if (a == null) return emptySet()
        return (0 until a.length()).map { a.optInt(it) }.toSet()
    }

    private fun dayAt(base: Long, offsetDays: Int, h: Int, m: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = base
        add(Calendar.DAY_OF_YEAR, offsetDays)
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}
