package com.ahiraj.accontrol

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Work {
    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Send a command in the background. [boost] lets the hot-day rule add Powerful. */
    fun command(ctx: Context, cmd: JSONObject, label: String, boost: Boolean = false) =
        enqueue(ctx, Data.Builder().putString("cmd", cmd.toString()).putString("label", label).putBoolean("boost", boost).build())

    /** Run the plans that are due at [at]. */
    fun alarm(ctx: Context, at: Long) = enqueue(ctx, Data.Builder().putLong("at", at).build())

    private fun enqueue(ctx: Context, data: Data) {
        val req = OneTimeWorkRequest.Builder(CommandWorker::class.java)
            .setInputData(data)
            .setConstraints(net)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    fun ensureMonitor(ctx: Context) {
        val req = PeriodicWorkRequest.Builder(MonitorWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(net)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("monitor", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class CommandWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        return try {
            val at = inputData.getLong("at", 0L)
            if (at > 0L) {
                val evs = Scheduler.events(Prefs.config(ctx), at - 1000, at + 1000)
                if (evs.isEmpty()) return Result.success()
                val cmd = JSONObject()
                evs.forEach { e -> e.cmd.keys().forEach { k -> cmd.put(k, e.cmd.get(k)) } }
                Smart.applyHotBoost(ctx, cmd)
                Commander.send(ctx, cmd)
                Scheduler.dropFiredTimers(ctx, at)
                Notify.auto(ctx, evs.joinToString(" · ") { it.label }, Describe.cmd(cmd))
            } else {
                val cmd = JSONObject(inputData.getString("cmd") ?: "{}")
                if (inputData.getBoolean("boost", false)) Smart.applyHotBoost(ctx, cmd)
                Commander.send(ctx, cmd)
                val label = inputData.getString("label") ?: ""
                if (label.isNotEmpty()) Notify.auto(ctx, label, Describe.cmd(cmd))
            }
            Result.success()
        } catch (e: Commander.NotSignedIn) {
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else {
                Notify.alert(ctx, "Couldn't reach your AC", e.message ?: "Check your internet and try again.")
                Result.failure()
            }
        }
    }

    override fun getForegroundInfo(): ForegroundInfo {
        Notify.channels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, Notify.AUTO)
            .setSmallIcon(R.drawable.ic_stat_mochi)
            .setContentTitle("Mochi is talking to your AC…")
            .setOngoing(true)
            .build()
        return ForegroundInfo(4242, n)
    }
}

class MonitorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { Smart.check(applicationContext) } catch (_: Exception) {}
        return Result.success()
    }
}

/** Short human text for a command, used in notifications. */
object Describe {
    private val modes = mapOf("cool" to "Cool", "dry" to "Dry", "fan" to "Fan", "auto" to "Auto")
    private val fans = mapOf("auto" to "auto fan", "quiet" to "quiet fan", "low" to "low fan", "medium" to "medium fan", "high" to "high fan")

    fun cmd(c: JSONObject): String {
        if (c.optString("ps") == "off") return "AC turned off"
        val parts = mutableListOf<String>()
        if (c.optString("ps") == "on") parts += "On"
        modes[c.optString("acmd")]?.let { parts += it }
        c.optString("actmp").takeIf { it.isNotEmpty() }?.let { parts += "$it°C" }
        fans[c.optString("acfs")]?.let { parts += it }
        if (c.optString("acpm") == "on") parts += "Powerful"
        if (c.optString("acem") == "on") parts += "Eco"
        return if (parts.isEmpty()) "Settings updated" else parts.joinToString(" · ")
    }
}
