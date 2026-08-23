package com.caniko.cenix

import android.content.SharedPreferences

interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
}

class MemoryStore : KeyValueStore {
    private val bools = mutableMapOf<String, Boolean>()
    private val ints = mutableMapOf<String, Int>()
    private val longs = mutableMapOf<String, Long>()

    override fun getBoolean(key: String, default: Boolean) = bools[key] ?: default
    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun getLong(key: String, default: Long) = longs[key] ?: default
    override fun putBoolean(key: String, value: Boolean) { bools[key] = value }
    override fun putInt(key: String, value: Int) { ints[key] = value }
    override fun putLong(key: String, value: Long) { longs[key] = value }
}

class PrefStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
}
