package com.ahiraj.accontrol

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONObject

/** Fires when a schedule / sleep step / timer is due. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val at = intent.getLongExtra("at", 0L)
        if (at > 0L) Work.alarm(ctx, at)
        Scheduler.reschedule(ctx, if (at > 0L) at else System.currentTimeMillis())
    }
}

/** Alarms and geofences are cleared on reboot/update, so set them up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.reschedule(ctx)
        Geo.sync(ctx)
        Work.ensureMonitor(ctx)
    }
}

/** Arrive home → turn on with the chosen settings. Leave → optionally turn off. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val g = Prefs.config(ctx).optJSONObject("geo") ?: return
        if (!g.optBoolean("on")) return
        when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                val cmd = JSONObject((g.optJSONObject("arrive") ?: JSONObject()).toString()).put("ps", "on")
                Work.command(ctx, cmd, "Welcome home! Mochi cooled your room", boost = true)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                if (g.optBoolean("leaveOff")) Work.command(ctx, JSONObject().put("ps", "off"), "Bye! Mochi turned the AC off")
            }
        }
    }
}

object Geo {
    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 7, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
    )

    fun hasFine(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = hasFine(ctx) && (Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    fun sync(ctx: Context) {
        try {
            val client = LocationServices.getGeofencingClient(ctx)
            client.removeGeofences(listOf("home")).addOnCompleteListener {
                val g = Prefs.config(ctx).optJSONObject("geo") ?: return@addOnCompleteListener
                if (!g.optBoolean("on") || !g.has("lat") || !hasBackground(ctx)) return@addOnCompleteListener
                val fence = Geofence.Builder()
                    .setRequestId("home")
                    .setCircularRegion(g.optDouble("lat"), g.optDouble("lng"), g.optDouble("radius", 1000.0).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
                val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
                try { client.addGeofences(req, pending(ctx)) } catch (_: SecurityException) {}
            }
        } catch (_: Exception) {}
    }
}
