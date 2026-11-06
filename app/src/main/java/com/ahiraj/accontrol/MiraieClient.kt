package com.ahiraj.accontrol

import android.content.Context
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Live connection while the app is open: login, status, and MQTT for instant updates. */
class MiraieClient(
    private val ctx: Context,
    private val store: Store,
    private val emit: (event: String, json: String) -> Unit
) {
    private val io = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var session: MiraieApi.Session? = null
    @Volatile private var mqtt: MqttClient? = null
    @Volatile private var reconnecting = false

    fun hasSaved(): Boolean = store.hasCreds()

    fun resume() {
        val u = store.user; val p = store.pass
        if (u != null && p != null) login(u, p, save = false)
    }

    fun login(user: String, pass: String, save: Boolean) = io.execute {
        try {
            emit("phase", q("Signing in"))
            val token = MiraieApi.login(user, pass)
            if (save) store.save(user, pass)
            Commander.reset()
            emit("phase", q("Finding your AC"))
            val s = MiraieApi.session(token)
            session = s
            Commander.rememberDevice(ctx, s)
            emit("devices", JSONArray(s.devices.map {
                JSONObject().put("id", it.id).put("name", it.name).put("fw", it.fw).put("model", it.model)
            }).toString())
            s.devices.forEach { pullStatus(s, it) }
            emit("phase", q("Connecting live"))
            connectMqtt()
        } catch (e: Exception) {
            emit("error", q(friendly(e)))
        }
    }

    fun refresh() = io.execute {
        val s = session ?: return@execute
        try { s.devices.forEach { pullStatus(s, it) } } catch (e: Exception) { emit("error", q(friendly(e))) }
    }

    fun ensureConnected() {
        if (session != null && mqtt?.isConnected != true) scheduleReconnect(0)
    }

    /** json = {"deviceId": "...", <MirAIe keys e.g. "ps":"on", "actmp":"24">} */
    fun send(json: String) = io.execute {
        try {
            val cmd = JSONObject(json)
            val s = session ?: throw IOException("Not connected yet")
            val dev = s.devices.firstOrNull { it.id == cmd.optString("deviceId") } ?: s.devices.first()
            cmd.remove("deviceId")
            val c = mqtt
            if (c == null || !c.isConnected) {
                emit("error", q("Not connected yet. Retrying…"))
                scheduleReconnect(0)
                return@execute
            }
            val payload = MiraieApi.payload(cmd)
            c.publish("${dev.base}/control", MqttMessage(payload.toString().toByteArray()).apply { qos = 0 })
            if (isCurrent(dev.id)) { Prefs.mergeStatus(ctx, cmd); Surfaces.refresh(ctx) }
            emit("sent", payload.toString())
        } catch (e: Exception) {
            emit("error", q(friendly(e)))
        }
    }

    /** grain = "Daily" (ddMMyyyy) or "Monthly" (MMyyyy). Result comes back tagged. */
    fun energy(deviceId: String, grain: String, start: String, end: String, tag: String) = io.execute {
        val out = JSONObject().put("deviceId", deviceId).put("tag", tag).put("grain", grain)
        try {
            val s = session ?: throw IOException("Not connected yet")
            out.put("items", MiraieApi.energy(s.token, deviceId, grain, start, end))
        } catch (e: Exception) {
            out.put("items", JSONArray())
        }
        emit("energy", out.toString())
    }

    fun weather() = io.execute {
        try { Smart.weather(ctx, force = true)?.let { emit("weather", it.toString()) } } catch (_: Exception) {}
    }

    fun logout() = io.execute {
        try { mqtt?.disconnectForcibly(500) } catch (_: Exception) {}
        mqtt = null; session = null
        store.clear()
        Commander.reset()
        emit("loggedOut", "true")
    }

    fun shutdown() {
        try { mqtt?.disconnectForcibly(300) } catch (_: Exception) {}
        io.shutdownNow()
    }

    // ---- internals ----

    private fun isCurrent(id: String): Boolean {
        val cur = Prefs.config(ctx).optString("deviceId").ifEmpty { Prefs.cache(ctx).optString("deviceId") }
        return cur.isEmpty() || cur == id
    }

    private fun pullStatus(s: MiraieApi.Session, d: MiraieApi.Device) {
        val st = MiraieApi.status(s.token, d.id)
        if (isCurrent(d.id)) { Prefs.mergeStatus(ctx, st); Surfaces.refresh(ctx) }
        emit("status", st.put("deviceId", d.id).toString())
    }

    private fun connectMqtt() {
        val s = session ?: return
        try { mqtt?.disconnectForcibly(300) } catch (_: Exception) {}
        val c = MiraieApi.newClient()
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                emit("conn", "false")
                scheduleReconnect(5)
            }
            override fun messageArrived(topic: String, message: MqttMessage) = onMessage(topic, String(message.payload))
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        c.connect(MiraieApi.options(s))
        s.devices.forEach {
            c.subscribe("${it.base}/status", 0)
            c.subscribe("${it.base}/connectionStatus", 0)
        }
        mqtt = c
        emit("conn", "true")
    }

    private fun onMessage(topic: String, payload: String) {
        val s = session ?: return
        val dev = s.devices.firstOrNull { topic.startsWith(it.base + "/") } ?: return
        try {
            val o = JSONObject(payload)
            if (topic.endsWith("/status")) {
                if (isCurrent(dev.id)) { Prefs.mergeStatus(ctx, o); Surfaces.refresh(ctx) }
                emit("status", o.put("deviceId", dev.id).toString())
            } else if (topic.endsWith("/connectionStatus")) {
                emit("online", o.put("deviceId", dev.id).toString())
            }
        } catch (_: Exception) {}
    }

    /** The token may have expired, so log in again before reconnecting. */
    private fun scheduleReconnect(delaySec: Long) {
        if (reconnecting) return
        reconnecting = true
        try {
            io.schedule({
                reconnecting = false
                val u = store.user; val p = store.pass
                try {
                    if (u != null && p != null) session = MiraieApi.session(MiraieApi.login(u, p))
                    connectMqtt()
                    session?.let { s -> s.devices.forEach { pullStatus(s, it) } }
                } catch (e: Exception) {
                    emit("conn", "false")
                    scheduleReconnect(15)
                }
            }, delaySec, TimeUnit.SECONDS)
        } catch (_: Exception) { reconnecting = false }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "MirAIe server is not responding"
        else -> e.message ?: "Something went wrong"
    }

    private fun q(s: String) = JSONObject.quote(s)
}
