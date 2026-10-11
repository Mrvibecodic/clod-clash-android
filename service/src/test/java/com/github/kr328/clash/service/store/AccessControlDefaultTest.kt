package com.github.kr328.clash.service.store

import android.content.SharedPreferences
import com.github.kr328.clash.service.model.AccessControlMode
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessControlDefaultTest {
    private class Prefs(vararg initial: Pair<String, Any>) : SharedPreferences {
        val values = mutableMapOf(*initial)

        override fun getAll(): Map<String, *> = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.keys.forEach { pending[it] = null } }
            override fun commit(): Boolean {
                apply()

                return true
            }
            override fun apply() {
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            }
        }
    }

    private fun Prefs.mode() = values[KEY_ACCESS_CONTROL_MODE]

    private fun Prefs.packages() = values[KEY_ACCESS_CONTROL_PACKAGES]

    @Test
    fun `новая установка получает «Разрешить выбранные» с пустым списком`() {
        val prefs = Prefs()

        migrateAccessControl(prefs)

        assertEquals(AccessControlMode.AcceptSelected.name, prefs.mode())
        assertEquals(emptySet<String>(), prefs.packages())
    }

    @Test
    fun `«Разрешить все» сбрасывается вместе с оставшимся списком`() {
        val prefs = Prefs(
            KEY_ACCESS_CONTROL_MODE to AccessControlMode.AcceptAll.name,
            KEY_ACCESS_CONTROL_PACKAGES to setOf("app.left"),
        )

        migrateAccessControl(prefs)

        assertEquals(AccessControlMode.AcceptSelected.name, prefs.mode())
        assertEquals(emptySet<String>(), prefs.packages())
    }

    @Test
    fun `выбранные режимы и их списки не меняются`() {
        for (mode in listOf(AccessControlMode.AcceptSelected, AccessControlMode.DenySelected)) {
            val prefs = Prefs(
                KEY_ACCESS_CONTROL_MODE to mode.name,
                KEY_ACCESS_CONTROL_PACKAGES to setOf("app.picked"),
            )

            migrateAccessControl(prefs)

            assertEquals(mode.name, prefs.mode())
            assertEquals(setOf("app.picked"), prefs.packages())
        }
    }

    @Test
    fun `«Разрешить все», выбранное после сброса, остаётся`() {
        val prefs = Prefs()

        migrateAccessControl(prefs)

        prefs.values[KEY_ACCESS_CONTROL_MODE] = AccessControlMode.AcceptAll.name
        prefs.values[KEY_ACCESS_CONTROL_PACKAGES] = setOf("app.picked")

        migrateAccessControl(prefs)

        assertEquals(AccessControlMode.AcceptAll.name, prefs.mode())
        assertEquals(setOf("app.picked"), prefs.packages())
    }
}
