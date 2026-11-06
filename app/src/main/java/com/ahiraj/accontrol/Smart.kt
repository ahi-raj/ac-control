package com.ahiraj.accontrol

import android.content.Context
import org.json.JSONObject

/**
 * Background checks every ~15 minutes:
 *  offline alert · AC filter-clean flag · filter reminder by run-hours ·
 *  "on too long" (notify or turn off) · too-cold guard · outdoor weather.
 */
object Smart {
    fun check(ctx: Context) {
        if (!Store(ctx).hasCreds()) return
        val cfg = Prefs.config(ctx)
        val rules = cfg.optJSONObject("rules") ?: JSONObject()
        val st = Commander.fetchStatus(ctx)
        val c = Prefs.cache(ctx)
        val now = System.currentTimeMillis()

        val online = st.optString("onlineStatus", "true") != "false"
        val on = st.optString("ps") == "on" && online
        val delta = (now - c.optLong("lastCheck", now)).coerceIn(0L, 30 * 60_000L)
        c.put("lastCheck", now)

        if (on) {
            c.put("runMin", c.optDouble("runMin", 0.0) + delta / 60_000.0)
            if (c.optLong("onSince", 0L) == 0L) c.put("onSince", now)
        } else {
            c.put("onSince", 0L).put("longSent", false).put("coldSent", false)
        }

        // AC went offline
        if (rules.optBoolean("offline", true)) {
            if (!online && !c.optBoolean("offSent")) {
                Notify.alert(ctx, "Your AC is offline", "Mochi can't reach it. Check the Wi-Fi or power.")
                c.put("offSent", true)
            } else if (online) c.put("offSent", false)
        }

        // The AC itself says the filter needs cleaning
        if (st.optString("acfc") == "on") {
            if (!c.optBoolean("acfcSent")) {
                Notify.alert(ctx, "Time to clean the filter", "Your AC is asking for a filter clean.")
                c.put("acfcSent", true)
            }
        } else c.put("acfcSent", false)

        // Filter reminder by run-hours
        val fh = rules.optInt("filterHours", 300)
        if (fh > 0 && c.optDouble("runMin", 0.0) / 60.0 >= fh && !c.optBoolean("filterSent")) {
            Notify.alert(ctx, "Filter check due", "The AC has run about $fh hours since the last clean.")
            c.put("filterSent", true)
        }

        // On for too long
        val lh = rules.optInt("longHours", 0)
        val since = c.optLong("onSince", 0L)
        if (on && lh > 0 && since > 0 && now - since >= lh * 3_600_000L && !c.optBoolean("longSent")) {
            if (rules.optString("longAction", "notify") == "off") {
                Commander.send(ctx, JSONObject().put("ps", "off"))
                Notify.alert(ctx, "Mochi turned the AC off", "It had been on for $lh hours.")
            } else {
                Notify.alert(ctx, "AC on for $lh hours", "Tap to check on Mochi.")
            }
            c.put("longSent", true)
        }

        // Too cold
        val cold = rules.optJSONObject("cold")
        if (on && cold != null && cold.optBoolean("on")) {
            val room = MiraieApi.roomTemp(st.optString("rmtmp"), c.optString("fw"))
            val below = cold.optDouble("below", 21.0)
            if (room != null && room < below && !c.optBoolean("coldSent")) {
                if (cold.optBoolean("fix", false)) {
                    Commander.send(ctx, JSONObject().put("actmp", "25"))
                    Notify.alert(ctx, "Brrr! Mochi warmed things up", "Room was $room°C, so the AC is now set to 25°C.")
                } else {
                    Notify.alert(ctx, "Brrr! It's $room°C in your room", "Mochi is freezing. Maybe raise the temperature?")
                }
                c.put("coldSent", true)
            }
        }

        // Keep the newest status (Commander.send may have changed it) and save
        Prefs.cache(ctx).optJSONObject("status")?.let { c.put("status", it) }
        Prefs.saveCache(ctx, c)
        try { weather(ctx, force = false) } catch (_: Exception) {}
        Surfaces.refresh(ctx)
    }

    /** Outdoor weather for the saved home location (open-meteo, no key needed). */
    fun weather(ctx: Context, force: Boolean): JSONObject? {
        val geo = Prefs.config(ctx).optJSONObject("geo") ?: return null
        if (!geo.has("lat")) return null
        val c = Prefs.cache(ctx)
        val w = c.optJSONObject("weather")
        if (!force && w != null && System.currentTimeMillis() - w.optLong("at") < 60 * 60_000L) return w
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${geo.optDouble("lat")}&longitude=${geo.optDouble("lng")}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code&daily=temperature_2m_max&forecast_days=1&timezone=auto"
        val j = JSONObject(MiraieApi.http("GET", url, null, null))
        val cur = j.getJSONObject("current")
        val out = JSONObject()
            .put("t", cur.optDouble("temperature_2m"))
            .put("rh", cur.optDouble("relative_humidity_2m"))
            .put("code", cur.optInt("weather_code"))
            .put("max", j.optJSONObject("daily")?.optJSONArray("temperature_2m_max")?.optDouble(0) ?: cur.optDouble("temperature_2m"))
            .put("at", System.currentTimeMillis())
        Prefs.patchCache(ctx) { it.put("weather", out) }
        return out
    }

    /** Hot-day boost: when an automation turns the AC on and it's hot outside, start in Powerful. */
    fun applyHotBoost(ctx: Context, cmd: JSONObject) {
        val hot = Prefs.config(ctx).optJSONObject("hot") ?: return
        if (!hot.optBoolean("on") || cmd.optString("ps") != "on") return
        val w = try { weather(ctx, force = false) } catch (_: Exception) { null } ?: return
        if (w.optDouble("t", 0.0) >= hot.optDouble("above", 36.0)) {
            cmd.put("acpm", "on").put("acem", "off").put("cnv", 0)
        }
    }
}
