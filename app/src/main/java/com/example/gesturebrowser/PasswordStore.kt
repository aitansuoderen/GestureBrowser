package com.example.gesturebrowser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 保存的网站密码。密码用 Android Keystore 里的 AES-256-GCM 密钥加密后存本地，
 * 密钥不出系统安全区，App 数据被拷走也解不开。
 */
class PasswordStore(context: Context) {
    data class Login(val host: String, val user: String, val password: String, val time: Long)

    private val prefs = context.getSharedPreferences("passwords", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): Pair<String, String> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) to Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(iv: String, ct: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }.getOrNull()

    private fun raw(): JSONArray = JSONArray(prefs.getString("list", "[]"))

    fun all(): List<Login> {
        val arr = raw()
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val pw = decrypt(o.getString("iv"), o.getString("ct")) ?: return@mapNotNull null
            Login(o.getString("host"), o.optString("user"), pw, o.optLong("time"))
        }.sortedBy { it.host }
    }

    fun forHost(host: String): List<Login> = all().filter { it.host == host }.sortedByDescending { it.time }

    /** 已存过同样的用户名+密码返回 false */
    fun isSaved(host: String, user: String, password: String) =
        forHost(host).any { it.user == user && it.password == password }

    fun save(host: String, user: String, password: String) {
        val arr = raw()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!(o.getString("host") == host && o.optString("user") == user)) out.put(o)
        }
        val (iv, ct) = encrypt(password)
        out.put(JSONObject().put("host", host).put("user", user).put("iv", iv).put("ct", ct)
            .put("time", System.currentTimeMillis()))
        prefs.edit().putString("list", out.toString()).apply()
    }

    fun remove(host: String, user: String) {
        val arr = raw()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!(o.getString("host") == host && o.optString("user") == user)) out.put(o)
        }
        prefs.edit().putString("list", out.toString()).apply()
    }

    fun isNever(host: String) = prefs.getStringSet("never", emptySet())!!.contains(host)
    fun setNever(host: String) {
        val s = prefs.getStringSet("never", emptySet())!!.toMutableSet().apply { add(host) }
        prefs.edit().putStringSet("never", s).apply()
    }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) { prefs.edit().putBoolean("enabled", v).apply() }

    companion object { private const val ALIAS = "gesture_browser_pwd" }
}
