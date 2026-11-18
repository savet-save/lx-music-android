package cn.toside.music.nativeapp.utils

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.toside.music.nativeapp.NativeApplication
import cn.toside.music.nativeapp.data.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoreHelperTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val legacy get() = context.getSharedPreferences("app-settings", 0)
    private fun cleanup(keys: List<String>) { legacy.edit().apply { keys.forEach { remove(it) } }.commit() }

    @Test fun applicationInitializesTheCacheAndReadsExistingValuesWithoutMigration() {
        assertTrue(context.applicationContext is NativeApplication)
        val key = "store-test-${System.nanoTime()}"
        try {
            check(legacy.edit().putString(key, "legacy").commit())
            assertEquals("legacy", StoreHelper.getString(key))
            StoreHelper.putString(key, "updated")
            assertEquals("updated", legacy.getString(key, null))
            StoreHelper.init(context)
            assertEquals("updated", StoreHelper.getString(key))
            StoreHelper.remove(key)
            assertEquals("missing", StoreHelper.getString(key, "missing"))
        } finally { cleanup(listOf(key)) }
    }

    @Test fun existingNativeTypesAndDoubleTextRemainCompatible() {
        val prefix = "store-types-${System.nanoTime()}"
        val keys = listOf("int", "float", "bool", "string", "double").map { "$prefix-$it" }
        try {
            check(legacy.edit().putInt(keys[0], 42).putFloat(keys[1], 1.25f).putBoolean(keys[2], true)
                .putString(keys[3], "原有设置").putString(keys[4], "1.23456789012345").commit())
            assertEquals(42, StoreHelper.getInt(keys[0]))
            assertEquals(1.25f, StoreHelper.getFloat(keys[1]), 0f)
            assertTrue(StoreHelper.getBool(keys[2]))
            assertEquals("原有设置", StoreHelper.getString(keys[3]))
            assertEquals(1.23456789012345, StoreHelper.getDouble(keys[4]), 0.0)
            StoreHelper.putInt(keys[0], 21); StoreHelper.putFloat(keys[1], .75f)
            StoreHelper.putBool(keys[2], false); StoreHelper.putDouble(keys[4], 2.718281828459045)
            assertEquals(21, legacy.getInt(keys[0], 0)); assertEquals(.75f, legacy.getFloat(keys[1], 0f), 0f)
            assertFalse(legacy.getBoolean(keys[2], true)); assertEquals("2.718281828459045", legacy.getString(keys[4], null))
        } finally { cleanup(keys) }
    }

    @Test fun stringSetInputsAndReturnedSnapshotsCannotMutateStoredValues() {
        val key = "store-set-${System.nanoTime()}"
        try {
            val values = linkedSetOf("first", "second")
            StoreHelper.putStringSet(key, values)
            values.add("outside")
            val snapshot = StoreHelper.getStringSet(key)
            runCatching { (snapshot as MutableSet<String>).add("mutated") }
            assertEquals(setOf("first", "second"), StoreHelper.getStringSet(key))
            assertEquals(setOf("first", "second"), legacy.getStringSet(key, null))
        } finally { cleanup(listOf(key)) }
    }

    @Test fun settingsKeepNativeNotificationsAndExplicitlyScopedContextsStayIndependent() {
        val key = "store-settings-${System.nanoTime()}"
        val name = "$key-scoped"
        var changes = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed -> if (changed == key) changes++ }
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(value: String, mode: Int) = super.getSharedPreferences(if (value == "app-settings") name else value, mode)
        }
        try {
            instrumentation.runOnMainSync {
                legacy.registerOnSharedPreferenceChangeListener(listener)
                AppPreferences(context).set(key, "dark")
                assertEquals("dark", StoreHelper.getString(key)); assertEquals(1, changes)
                legacy.edit().putString(key, "light").apply()
                assertEquals("light", AppPreferences(context).string(key, "")); assertEquals(2, changes)
                legacy.unregisterOnSharedPreferenceChangeListener(listener)
                AppPreferences(scoped).set(key, false)
                assertFalse(AppPreferences(scoped).bool(key, true))
                assertEquals("light", StoreHelper.getString(key))
                assertTrue(AppPreferences(context).bool(key, true))
            }
        } finally { legacy.unregisterOnSharedPreferenceChangeListener(listener); cleanup(listOf(key)); context.deleteSharedPreferences(name) }
    }
}
