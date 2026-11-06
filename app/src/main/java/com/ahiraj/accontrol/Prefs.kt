package com.ahiraj.accontrol

import android.content.Context
import org.json.JSONObject

/**
 * Plain (non-secret) app state.
 *  config = what the user set up: schedules, sleep curve, timers, geofence, rules, tariff, favourites.
 *  cache  = what the app learned: last AC status, device info, run-hours, weather, alert flags.
 */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ac_state", Context.MODE_PRIVATE)

    @Synchronized fun config(ctx: Context): JSONObject = read(ctx, "config")
    @Synchronized fun saveConfig(ctx: Context, j: JSONObject) { sp(ctx).edit().putString("config", j.toString()).commit() }

    @Synchronized fun cache(ctx: Context): JSONObject = read(ctx, "cache")
    @Synchronized fun saveCache(ctx: Context, j: JSONObject) { sp(ctx).edit().putString("cache", j.toString()).commit() }

    /** Merge AC status keys (from a command or a status message) into the cached status. */
    @Synchronized fun mergeStatus(ctx: Context, s: JSONObject) {
        val c = cache(ctx)
        val st = c.optJSONObject("status") ?: JSONObject()
        s.keys().forEach { k -> if (k != "deviceId") st.put(k, s.get(k)) }
        c.put("status", st).put("statusAt", System.currentTimeMillis())
        saveCache(ctx, c)
    }

    /** Update a few cache keys without clobbering the rest. */
    @Synchronized fun patchCache(ctx: Context, block: (JSONObject) -> Unit) {
        val c = cache(ctx)
        block(c)
        saveCache(ctx, c)
    }

    private fun read(ctx: Context, key: String): JSONObject = try {
        JSONObject(sp(ctx).getString(key, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }
}
