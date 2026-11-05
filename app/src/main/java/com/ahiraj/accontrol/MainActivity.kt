package com.ahiraj.accontrol

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** Hosts the UI (assets/index.html) and bridges it to the MirAIe cloud client. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var client: MiraieClient

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#F4F0FB")
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        web = WebView(this)
        web.setBackgroundColor(bg)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        client = MiraieClient(Store(this)) { event, json -> emit(event, json) }
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
        if (::client.isInitialized) client.ensureConnected()
    }

    override fun onDestroy() {
        client.shutdown()
        web.destroy()
        super.onDestroy()
    }

    inner class Bridge {
        @JavascriptInterface fun hasSaved(): Boolean = client.hasSaved()
        @JavascriptInterface fun login(user: String, pass: String) = client.login(user, pass, save = true)
        @JavascriptInterface fun resume() = client.resume()
        @JavascriptInterface fun send(json: String) = client.send(json)
        @JavascriptInterface fun refresh() = client.refresh()
        @JavascriptInterface fun energy(deviceId: String) = client.energy(deviceId)
        @JavascriptInterface fun logout() = client.logout()
    }
}
