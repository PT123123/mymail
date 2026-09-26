package com.wmail.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 密码存 EncryptedSharedPreferences(Android Keystore),绝不进数据库。 */
class SecretStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wmail_secrets",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getPassword(accountId: String): String? = prefs.getString("pw_$accountId", null)

    fun setPassword(accountId: String, password: String) {
        prefs.edit().putString("pw_$accountId", password).apply()
    }

    fun remove(accountId: String) {
        prefs.edit().remove("pw_$accountId").apply()
    }
}
