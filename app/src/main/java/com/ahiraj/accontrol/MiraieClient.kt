package com.ahiraj.accontrol

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Talks to Panasonic MirAIe cloud: HTTPS login + REST, then MQTT (TLS 8883) for live control.
 * Unofficial: endpoints come from the open-source miraie-ac library and may change.
 */
class MiraieClient(
    private val store: Store,
    private val emit: (event: String, json: String) -> Unit
) {
    private companion object {
        const val CLIENT_ID = "PBcMcfG19njNCL8AOgvRzIC8AjQa"
        const val SCOPE = "an_14214235325"
        const val LOGIN_URL = "https://auth.miraie.in/simplifi/v1/userManagement/login"
        const val HOMES_URL = "https://app.miraie.in/simplifi/v1/homeManagement/homes"
        const val DETAILS_URL = "https://app.miraie.in/simplifi/v1/deviceManagement/devices/deviceId/"
        const val STATUS_URL = "https://app.miraie.in/simplifi/v1/deviceManagement/devices/%s/mobile/status"
        const val ENERGY_URL = "https://app.miraie.in/simplifi/v1/powerConsumption/devices/%s?grain=Daily&startDate=%s&endDate=%s"
        const val BROKER = "ssl://mqtt.miraie.in:8883"
    }

    private data class Dev(val id: String, val name: String, val base: String, var fw: String = "", var model: String = "")

    private val io = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var token: String? = null
    @Volatile private var homeId: String? = null
    @Volatile private var mqtt: MqttClient? = null
    @Volatile private var reconnecting = false
    private var devices = listOf<Dev>()

    fun hasSaved(): Boolean = store.user != null && store.pass != null

    fun resume() {
        val u = store.user; val p = store.pass
        if (u != null && p != null) login(u, p, save = false)
    }

    fun login(user: String, pass: String, save: Boolean) = io.execute {
        try {
            emit("phase", q("Signing in"))
            authenticate(user, pass)
            if (save) store.save(user, pass)
            emit("phase", q("Finding your AC"))
            loadHome()
            emit("devices", JSONArray(devices.map {
                JSONObject().put("id", it.id).put("name", it.name).put("fw", it.fw).put("model", it.model)
            }).toString())
            devices.forEach { pullStatus(it) }
            emit("phase", q("Connecting live"))
            connectMqtt()
        } catch (e: Exception) {
            emit("error", q(friendly(e)))
        }
    }

    fun refresh() = io.execute { try { devices.forEach { pullStatus(it) } } catch (e: Exception) { emit("error", q(friendly(e))) } }

    fun ensureConnected() {
        if (token != null && mqtt?.isConnected != true) scheduleReconnect(0)
    }

    /** json = {"deviceId": "...", <MirAIe keys e.g. "ps":"on", "actmp":"24"> } */
    fun send(json: String) = io.execute {
        try {
            val cmd = JSONObject(json)
            val dev = devices.firstOrNull { it.id == cmd.optString("deviceId") } ?: devices.firstOrNull()
                ?: throw IOException("No AC found on this account")
            cmd.remove("deviceId")
            val payload = JSONObject().put("ki", 1).put("cnt", "an").put("sid", "1")
            cmd.keys().forEach { k -> payload.put(k, cmd.get(k)) }
            val c = mqtt
            if (c == null || !c.isConnected) {
                emit("error", q("Not connected yet. Retrying…"))
                scheduleReconnect(0)
                return@execute
            }
            c.publish("${dev.base}/control", MqttMessage(payload.toString().toByteArray()).apply { qos = 0 })
            emit("sent", payload.toString())
        } catch (e: Exception) {
            emit("error", q(friendly(e)))
        }
    }

    fun energy(deviceId: String) = io.execute {
        try {
            val f = SimpleDateFormat("ddMMyyyy", Locale.US)
            val end = Calendar.getInstance()
            val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -6) }
            val txt = http("GET", ENERGY_URL.format(deviceId, f.format(start.time), f.format(end.time)))
            val out = JSONObject().put("deviceId", deviceId)
            if (txt.trim().startsWith("[")) out.put("days", JSONArray(txt)) else out.put("days", JSONArray())
            emit("energy", out.toString())
        } catch (e: Exception) {
            emit("energy", JSONObject().put("deviceId", deviceId).put("days", JSONArray()).toString())
        }
    }

    fun logout() = io.execute {
        try { mqtt?.disconnectForcibly(500) } catch (_: Exception) {}
        mqtt = null; token = null; homeId = null; devices = listOf()
        store.clear()
        emit("loggedOut", "true")
    }

    fun shutdown() {
        try { mqtt?.disconnectForcibly(300) } catch (_: Exception) {}
        io.shutdownNow()
    }

    // ---- internals ----

    private fun authenticate(user: String, pass: String) {
        val body = JSONObject().put("clientId", CLIENT_ID).put("password", pass).put("scope", SCOPE)
        if (user.contains("@")) body.put("email", user.trim()) else body.put("mobile", user.trim())
        val res = try {
            JSONObject(http("POST", LOGIN_URL, body, auth = false))
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 4") == true) throw IOException("Wrong email/mobile or password")
            throw e
        }
        token = res.getString("accessToken")
    }

    private fun loadHome() {
        val homes = JSONArray(http("GET", HOMES_URL))
        if (homes.length() == 0) throw IOException("No home found on this MirAIe account")
        val home = homes.getJSONObject(0)
        homeId = home.getString("homeId")
        val list = mutableListOf<Dev>()
        val spaces = home.optJSONArray("spaces") ?: JSONArray()
        for (i in 0 until spaces.length()) {
            val ds = spaces.getJSONObject(i).optJSONArray("devices") ?: continue
            for (j in 0 until ds.length()) {
                val d = ds.getJSONObject(j)
                list += Dev(d.getString("deviceId"), d.optString("deviceName", "AC"), d.getJSONArray("topic").getString(0))
            }
        }
        if (list.isEmpty()) throw IOException("No AC found on this account")
        try {
            val details = JSONArray(http("GET", DETAILS_URL + list.joinToString(",") { it.id }))
            for (i in 0 until details.length()) {
                val dd = details.getJSONObject(i)
                list.firstOrNull { it.id == dd.optString("deviceId") }?.let {
                    it.fw = dd.optString("firmwareVersion"); it.model = dd.optString("modelName")
                }
            }
        } catch (_: Exception) { /* details are optional */ }
        devices = list
    }

    private fun pullStatus(d: Dev) {
        val s = JSONObject(http("GET", STATUS_URL.format(d.id)))
        s.put("deviceId", d.id)
        emit("status", s.toString())
    }

    private fun connectMqtt() {
        try { mqtt?.disconnectForcibly(300) } catch (_: Exception) {}
        val c = MqttClient(BROKER, "acctl-" + Random.nextInt(1_000_000), MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = homeId
            password = (token ?: "").toCharArray()
            isCleanSession = true
            connectionTimeout = 20
            keepAliveInterval = 60
            isAutomaticReconnect = false
            socketFactory = SSLSocketFactory.getDefault()
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {
                emit("conn", "false")
                scheduleReconnect(5)
            }
            override fun messageArrived(topic: String, message: MqttMessage) = onMessage(topic, String(message.payload))
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        c.connect(opts)
        devices.forEach {
            c.subscribe("${it.base}/status", 0)
            c.subscribe("${it.base}/connectionStatus", 0)
        }
        mqtt = c
        emit("conn", "true")
    }

    private fun onMessage(topic: String, payload: String) {
        val dev = devices.firstOrNull { topic.startsWith(it.base + "/") } ?: return
        try {
            val o = JSONObject(payload).put("deviceId", dev.id)
            when {
                topic.endsWith("/status") -> emit("status", o.toString())
                topic.endsWith("/connectionStatus") -> emit("online", o.toString())
            }
        } catch (_: Exception) {}
    }

    /** Token may have expired, so log in again before reconnecting MQTT. */
    private fun scheduleReconnect(delaySec: Long) {
        if (reconnecting) return
        reconnecting = true
        try {
            io.schedule({
                reconnecting = false
                val u = store.user; val p = store.pass
                try {
                    if (u != null && p != null) authenticate(u, p)
                    if (devices.isEmpty()) loadHome()
                    connectMqtt()
                    devices.forEach { pullStatus(it) }
                } catch (e: Exception) {
                    emit("conn", "false")
                    scheduleReconnect(15)
                }
            }, delaySec, TimeUnit.SECONDS)
        } catch (_: Exception) { reconnecting = false }
    }

    private fun http(method: String, url: String, body: JSONObject? = null, auth: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            if (auth) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "MirAIe server is not responding"
        else -> e.message ?: "Something went wrong"
    }

    private fun q(s: String) = JSONObject.quote(s)
}
