package com.ahiraj.accontrol

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast
import org.json.JSONObject

/** Keeps the home-screen widget and Quick Settings tile in sync with the AC. */
object Surfaces {
    fun refresh(ctx: Context) {
        try { AcWidget.updateAll(ctx) } catch (_: Exception) {}
        try { TileService.requestListeningState(ctx, ComponentName(ctx, AcTile::class.java)) } catch (_: Exception) {}
    }

    fun status(ctx: Context): JSONObject = Prefs.cache(ctx).optJSONObject("status") ?: JSONObject()
    fun isOn(st: JSONObject) = st.optString("ps") == "on"
    fun setTemp(st: JSONObject) = st.optString("actmp", "24").toDoubleOrNull()?.toInt() ?: 24
    fun modeName(st: JSONObject) = when (st.optString("acmd")) {
        "dry" -> "Dry"; "fan" -> "Fan"; "auto" -> "Auto"; else -> "Cool"
    }

    /** Toggle power or nudge the temperature, updating the screen right away. */
    fun quick(ctx: Context, action: String) {
        if (!Store(ctx).hasCreds()) {
            Toast.makeText(ctx, "Open AC Control and sign in first", Toast.LENGTH_SHORT).show()
            return
        }
        val st = status(ctx)
        val cmd = when (action) {
            "power" -> JSONObject().put("ps", if (isOn(st)) "off" else "on")
            "up" -> JSONObject().put("actmp", (setTemp(st) + 1).coerceAtMost(30).toString())
            "down" -> JSONObject().put("actmp", (setTemp(st) - 1).coerceAtLeast(16).toString())
            "on" -> JSONObject().put("ps", "on")
            "off" -> JSONObject().put("ps", "off")
            "chill" -> JSONObject().put("ps", "on").put("acmd", "cool").put("actmp", "20").put("acpm", "on").put("acem", "off").put("cnv", 0)
            "bedtime" -> JSONObject().put("ps", "on").put("acmd", "cool").put("actmp", "26").put("acfs", "quiet").put("acdc", "off")
            else -> return
        }
        Prefs.mergeStatus(ctx, cmd)
        refresh(ctx)
        Work.command(ctx, cmd, "")
    }
}

class AcWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, views(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACT_POWER -> Surfaces.quick(ctx, "power")
            ACT_UP -> Surfaces.quick(ctx, "up")
            ACT_DOWN -> Surfaces.quick(ctx, "down")
        }
    }

    companion object {
        const val ACT_POWER = "com.ahiraj.accontrol.W_POWER"
        const val ACT_UP = "com.ahiraj.accontrol.W_UP"
        const val ACT_DOWN = "com.ahiraj.accontrol.W_DOWN"

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, AcWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views(ctx))
        }

        private fun bc(ctx: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, code, Intent(ctx, AcWidget::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun views(ctx: Context): RemoteViews {
            val c = Prefs.cache(ctx)
            val st = c.optJSONObject("status") ?: JSONObject()
            val on = Surfaces.isOn(st)
            val set = Surfaces.setTemp(st)
            val room = MiraieApi.roomTemp(st.optString("rmtmp"), c.optString("fw"))
            val rv = RemoteViews(ctx.packageName, R.layout.widget_ac)
            rv.setTextViewText(R.id.w_temp, if (on) "$set°" else "Off")
            val sub = StringBuilder(if (on) Surfaces.modeName(st) else "Mochi is napping")
            if (room != null) sub.append(" · Room ").append(String.format("%.1f", room)).append("°")
            rv.setTextViewText(R.id.w_sub, sub.toString())
            rv.setImageViewResource(R.id.w_mochi, when {
                !on -> R.drawable.mochi_sleep
                set <= 21 -> R.drawable.mochi_cold
                else -> R.drawable.mochi_happy
            })
            rv.setInt(R.id.w_power, "setBackgroundResource", if (on) R.drawable.w_btn_on else R.drawable.w_btn)
            rv.setInt(R.id.w_power, "setColorFilter", if (on) Color.WHITE else Color.parseColor("#3D3229"))
            rv.setOnClickPendingIntent(R.id.w_power, bc(ctx, ACT_POWER, 21))
            rv.setOnClickPendingIntent(R.id.w_up, bc(ctx, ACT_UP, 22))
            rv.setOnClickPendingIntent(R.id.w_down, bc(ctx, ACT_DOWN, 23))
            rv.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(ctx, 24, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            return rv
        }
    }
}

class AcTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        Surfaces.quick(this, "power")
        paint()
    }

    private fun paint() {
        val t = qsTile ?: return
        val st = Surfaces.status(this)
        val on = Surfaces.isOn(st)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Mochi AC"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "${Surfaces.setTemp(st)}° · ${Surfaces.modeName(st)}" else "Off"
        t.updateTile()
    }
}

/** Long-press app icon shortcuts: AC on, AC off, Quick chill, Bedtime. */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind") ?: ""
        val label = mapOf("on" to "AC on", "off" to "AC off", "chill" to "Quick chill!", "bedtime" to "Bedtime mode")[kind]
        if (label != null) {
            Surfaces.quick(this, kind)
            if (Store(this).hasCreds()) Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
