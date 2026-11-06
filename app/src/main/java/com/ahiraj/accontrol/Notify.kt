package com.ahiraj.accontrol

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notify {
    const val ALERTS = "alerts"
    const val AUTO = "auto"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Mochi alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Offline, filter, too cold, on too long"
        })
        nm.createNotificationChannel(NotificationChannel(AUTO, "Automations", NotificationManager.IMPORTANCE_LOW).apply {
            description = "When schedules, sleep mode or arrive-home run"
        })
    }

    fun alert(ctx: Context, title: String, text: String) = post(ctx, ALERTS, title, text, title.hashCode())

    fun auto(ctx: Context, title: String, text: String) {
        if (Prefs.config(ctx).optBoolean("autoNotify", true)) post(ctx, AUTO, title, text, 77)
    }

    private fun post(ctx: Context, channel: String, title: String, text: String, id: Int) {
        channels(ctx)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setColor(0xFF5A9FD6.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}
