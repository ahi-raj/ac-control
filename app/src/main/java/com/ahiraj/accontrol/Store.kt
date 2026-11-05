package com.ahiraj.accontrol

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** MirAIe login, kept encrypted on this phone only (Android Keystore). */
class Store(ctx: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "miraie_secure",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        ctx,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val user: String? get() = prefs.getString("user", null)
    val pass: String? get() = prefs.getString("pass", null)

    fun save(user: String, pass: String) {
        prefs.edit().putString("user", user).putString("pass", pass).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
