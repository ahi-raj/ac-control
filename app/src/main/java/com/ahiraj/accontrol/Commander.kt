package com.ahiraj.accontrol

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/** Sends commands and reads status from the background (no screen open). */
object Commander {
    class NotSignedIn : IOException("Not signed in")

    private var cached: MiraieApi.Session? = null
    private var cachedAt = 0L

    @Synchronized fun reset() { cached = null; cachedAt = 0L }

    @Synchronized fun session(ctx: Context, fresh: Boolean = false): MiraieApi.Session {
        val now = System.currentTimeMillis()
        val c = cached
        if (!fresh && c != null && now - cachedAt < 30 * 60_000L) return c
        val st = Store(ctx)
        val u = st.user ?: throw NotSignedIn()
        val p = st.pass ?: throw NotSignedIn()
        val s = MiraieApi.session(MiraieApi.login(u, p))
        cached = s
        cachedAt = now
        rememberDevice(ctx, s)
        return s
    }

    fun device(ctx: Context, s: MiraieApi.Session): MiraieApi.Device {
        val id = Prefs.config(ctx).optString("deviceId")
        return s.devices.firstOrNull { it.id == id } ?: s.devices.first()
    }

    fun rememberDevice(ctx: Context, s: MiraieApi.Session) {
        val id = Prefs.config(ctx).optString("deviceId")
        val d = s.devices.firstOrNull { it.id == id } ?: s.devices.first()
        Prefs.patchCache(ctx) { c ->
            c.put("deviceId", d.id).put("name", d.name).put("fw", d.fw).put("model", d.model)
        }
    }

    /** Send one command; retries once with a fresh login if the old token stopped working. */
    fun send(ctx: Context, cmd: JSONObject) {
        try {
            val s = session(ctx)
            MiraieApi.publishOnce(s, device(ctx, s), cmd)
        } catch (e: NotSignedIn) {
            throw e
        } catch (e: Exception) {
            val s = session(ctx, fresh = true)
            MiraieApi.publishOnce(s, device(ctx, s), cmd)
        }
        Prefs.mergeStatus(ctx, cmd)
        Surfaces.refresh(ctx)
    }

    fun fetchStatus(ctx: Context): JSONObject {
        val st = try {
            val s = session(ctx)
            MiraieApi.status(s.token, device(ctx, s).id)
        } catch (e: NotSignedIn) {
            throw e
        } catch (e: Exception) {
            val s = session(ctx, fresh = true)
            MiraieApi.status(s.token, device(ctx, s).id)
        }
        Prefs.mergeStatus(ctx, st)
        return st
    }
}
