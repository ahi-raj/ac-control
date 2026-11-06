package com.ahiraj.accontrol

import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * Blocking calls to the Panasonic MirAIe cloud (HTTPS + MQTT over TLS).
 * Unofficial: endpoints come from the open-source miraie-ac library and may change.
 */
object MiraieApi {
    private const val CLIENT_ID = "PBcMcfG19njNCL8AOgvRzIC8AjQa"
    private const val SCOPE = "an_14214235325"
    private const val LOGIN_URL = "https://auth.miraie.in/simplifi/v1/userManagement/login"
    private const val HOMES_URL = "https://app.miraie.in/simplifi/v1/homeManagement/homes"
    private const val DETAILS_URL = "https://app.miraie.in/simplifi/v1/deviceManagement/devices/deviceId/"
    private const val STATUS_URL = "https://app.miraie.in/simplifi/v1/deviceManagement/devices/%s/mobile/status"
    private const val ENERGY_URL = "https://app.miraie.in/simplifi/v1/powerConsumption/devices/%s?grain=%s&startDate=%s&endDate=%s"
    const val BROKER = "ssl://mqtt.miraie.in:8883"

    class Device(val id: String, val name: String, val base: String) {
        var fw: String = ""
        var model: String = ""
    }

    class Session(val token: String, val homeId: String, val devices: List<Device>)

    class AuthException(msg: String) : IOException(msg)

    fun login(user: String, pass: String): String {
        val body = JSONObject().put("clientId", CLIENT_ID).put("password", pass).put("scope", SCOPE)
        if (user.contains("@")) body.put("email", user.trim()) else body.put("mobile", user.trim())
        val res = try {
            JSONObject(http("POST", LOGIN_URL, body, null))
        } catch (e: IOException) {
            if (e.message?.startsWith("HTTP 4") == true) throw AuthException("Wrong email/mobile or password")
            throw e
        }
        return res.getString("accessToken")
    }

    fun session(token: String): Session {
        val homes = JSONArray(http("GET", HOMES_URL, null, token))
        if (homes.length() == 0) throw IOException("No home found on this MirAIe account")
        val home = homes.getJSONObject(0)
        val list = mutableListOf<Device>()
        val spaces = home.optJSONArray("spaces") ?: JSONArray()
        for (i in 0 until spaces.length()) {
            val ds = spaces.getJSONObject(i).optJSONArray("devices") ?: continue
            for (j in 0 until ds.length()) {
                val d = ds.getJSONObject(j)
                list += Device(d.getString("deviceId"), d.optString("deviceName", "AC"), d.getJSONArray("topic").getString(0))
            }
        }
        if (list.isEmpty()) throw IOException("No AC found on this account")
        try {
            val details = JSONArray(http("GET", DETAILS_URL + list.joinToString(",") { it.id }, null, token))
            for (i in 0 until details.length()) {
                val dd = details.getJSONObject(i)
                list.firstOrNull { it.id == dd.optString("deviceId") }?.let {
                    it.fw = dd.optString("firmwareVersion")
                    it.model = dd.optString("modelName")
                }
            }
        } catch (_: Exception) { /* optional */ }
        return Session(token, home.getString("homeId"), list)
    }

    fun status(token: String, deviceId: String): JSONObject =
        JSONObject(http("GET", STATUS_URL.format(deviceId), null, token))

    /** grain: Daily (dates ddMMyyyy, key "day") or Monthly (dates MMyyyy, key "month"). */
    fun energy(token: String, deviceId: String, grain: String, start: String, end: String): JSONArray {
        val txt = http("GET", ENERGY_URL.format(deviceId, grain, start, end), null, token).trim()
        return if (txt.startsWith("[")) JSONArray(txt) else JSONArray()
    }

    /** Full MQTT payload for a command such as {"ps":"on","actmp":"24"}. */
    fun payload(cmd: JSONObject): JSONObject {
        val p = JSONObject().put("ki", 1).put("cnt", "an").put("sid", "1")
        cmd.keys().forEach { k -> p.put(k, cmd.get(k)) }
        return p
    }

    fun newClient(): MqttClient = MqttClient(BROKER, "acctl-" + Random.nextInt(1_000_000), MemoryPersistence())

    fun options(s: Session): MqttConnectOptions = MqttConnectOptions().apply {
        userName = s.homeId
        password = s.token.toCharArray()
        isCleanSession = true
        connectionTimeout = 20
        keepAliveInterval = 60
        isAutomaticReconnect = false
        socketFactory = SSLSocketFactory.getDefault()
    }

    /** Connects, sends one command and disconnects. Used by schedules, widget and alerts. */
    fun publishOnce(s: Session, d: Device, cmd: JSONObject) {
        val c = newClient()
        try {
            c.connect(options(s))
            c.publish("${d.base}/control", MqttMessage(payload(cmd).toString().toByteArray()).apply { qos = 0 })
            Thread.sleep(400)
        } finally {
            try { c.disconnect(2000) } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }

    fun http(method: String, url: String, body: JSONObject?, token: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw AuthException("HTTP 401")
            if (code !in 200..299) throw IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    /** Room temperature; newer firmware packs it as "<fraction/256>.<whole>". */
    fun roomTemp(v: String?, fw: String?): Double? {
        if (v.isNullOrEmpty()) return null
        val parts = (fw ?: "").replace(Regex("[^\\d.]"), "").split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val packed = parts.size >= 2 && (major > 3 || (major == 3 && minor >= 2))
        if (packed && v.contains(".")) {
            val p = v.substringBefore(".").toDoubleOrNull() ?: return null
            val t = v.substringAfter(".").toDoubleOrNull() ?: return null
            if (t <= 0) return null
            return Math.round((t + p / 256.0) * 100) / 100.0
        }
        return v.toDoubleOrNull()
    }
}
