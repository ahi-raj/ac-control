package com.ahiraj.accontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject

/** Hosts the UI (assets/index.html) and bridges it to the MirAIe client and background features. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var client: MiraieClient

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#BCD7EE")
        window.statusBarColor = bg
        window.navigationBarColor = Color.parseColor("#FFFBF0")
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        Notify.channels(this)
        Work.ensureMonitor(this)
        Scheduler.reschedule(this)
        Geo.sync(this)

        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        client = MiraieClient(applicationContext, Store(this)) { event, json -> emit(event, json) }
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    /** Sends an event to the page: window.onNative(event, payload). payload must be valid JSON. */
    private fun emit(event: String, json: String) {
        val js = "window.onNative && window.onNative(${JSONObject.quote(event)}, $json)"
        runOnUiThread { if (!isDestroyed) web.evaluateJavascript(js, null) }
    }

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized) {
            client.ensureConnected()
            emit("perms", perms().toString())
        }
    }

    override fun onDestroy() {
        client.shutdown()
        web.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        emit("perms", perms().toString())
        if (Geo.hasBackground(this)) Geo.sync(this)
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun perms(): JSONObject = JSONObject()
        .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        .put("fine", Geo.hasFine(this))
        .put("bg", Geo.hasBackground(this))
        .put("exact", Scheduler.canExact(this))
        .put("battery", getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName))
        .put("sdk", Build.VERSION.SDK_INT)

    @SuppressLint("MissingPermission")
    private fun locateHome() {
        if (!Geo.hasFine(this)) { emit("error", JSONObject.quote("Allow location first")); return }
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc == null) emit("error", JSONObject.quote("Couldn't get your location. Turn on GPS and try again."))
                else emit("home", JSONObject().put("lat", loc.latitude).put("lng", loc.longitude).toString())
            }
            .addOnFailureListener { emit("error", JSONObject.quote("Couldn't get your location")) }
    }

    inner class Bridge {
        @JavascriptInterface fun hasSaved(): Boolean = client.hasSaved()
        @JavascriptInterface fun login(user: String, pass: String) = client.login(user, pass, save = true)
        @JavascriptInterface fun resume() = client.resume()
        @JavascriptInterface fun send(json: String) = client.send(json)
        @JavascriptInterface fun refresh() = client.refresh()
        @JavascriptInterface fun energy(deviceId: String, grain: String, start: String, end: String, tag: String) =
            client.energy(deviceId, grain, start, end, tag)
        @JavascriptInterface fun weather() = client.weather()
        @JavascriptInterface fun logout() = client.logout()

        @JavascriptInterface fun getConfig(): String = Prefs.config(this@MainActivity).toString()
        @JavascriptInterface fun getCache(): String = Prefs.cache(this@MainActivity).toString()

        @JavascriptInterface fun saveConfig(json: String) {
            val ctx = this@MainActivity
            Prefs.saveConfig(ctx, JSONObject(json))
            Scheduler.reschedule(ctx)
            Geo.sync(ctx)
            Surfaces.refresh(ctx)
            emit("cache", Prefs.cache(ctx).toString())
        }

        @JavascriptInterface fun resetFilter() {
            Prefs.patchCache(this@MainActivity) { it.put("runMin", 0.0).put("filterSent", false) }
            emit("cache", Prefs.cache(this@MainActivity).toString())
        }

        @JavascriptInterface fun perms(): String = this@MainActivity.perms().toString()

        @JavascriptInterface fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface fun requestPerm(kind: String) = runOnUiThread {
            when (kind) {
                "notif" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
                "location" -> requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11)
                "background" -> if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 12)
            }
        }

        @SuppressLint("BatteryLife")
        @JavascriptInterface fun openSettings(kind: String) = runOnUiThread {
            try {
                val i = when (kind) {
                    "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    "exact" -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) else null
                    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                }
                if (i != null) startActivity(i)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        @JavascriptInterface fun setHomeHere() = runOnUiThread { locateHome() }
    }
}
