package cn.toside.music.nativeapp.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object StoreHelper {
    private lateinit var sp: SharedPreferences
    private var initFinish = false

    // Keep the existing cache file so installed versions retain their settings and last page.
    private const val SP_NAME = "app-settings"

    @Synchronized fun init(context: Context) {
        if (initFinish) return
        sp = context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        initFinish = true
    }

    fun getSP(): SharedPreferences = sp

    fun putInt(key: String, value: Int) = sp.edit { putInt(key, value) }
    fun getInt(key: String, defaultValue: Int = 0) = sp.getInt(key, defaultValue)

    fun putDouble(key: String, value: Double) = sp.edit { putString(key, value.toString()) }
    fun getDouble(key: String, defaultValue: Double = 0.0): Double =
        sp.getString(key, null)?.toDoubleOrNull() ?: defaultValue

    fun putString(key: String, value: String) = sp.edit { putString(key, value) }
    fun getString(key: String, defaultValue: String = "") = sp.getString(key, defaultValue) ?: defaultValue

    fun putBool(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }
    fun getBool(key: String, defaultValue: Boolean = false) = sp.getBoolean(key, defaultValue)

    fun putFloat(key: String, value: Float) = sp.edit { putFloat(key, value) }
    fun getFloat(key: String, defaultValue: Float = 0f) = sp.getFloat(key, defaultValue)

    fun putStringSet(key: String, value: Set<String>) = sp.edit { putStringSet(key, value.toSet()) }
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()) =
        sp.getStringSet(key, defaultValue)?.toSet() ?: defaultValue

    fun remove(key: String) = sp.edit { remove(key) }
    fun clear() = sp.edit { clear() }
}
