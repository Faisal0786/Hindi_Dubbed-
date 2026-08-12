package com.hindi

import android.content.Context
import android.content.SharedPreferences

object AnimeCacheStorage {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(
            "AnimeCache",
            Context.MODE_PRIVATE
        )
    }

    fun save(key: String, value: String) {
        prefs.edit()
            .putString(key, value)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    fun load(
        key: String,
        maxAge: Long = 20L * 60L * 1000L
    ): String? {

        val time = prefs.getLong("${key}_time", 0L)

        if (time == 0L) return null

        if (System.currentTimeMillis() - time > maxAge) {
            prefs.edit()
                .remove(key)
                .remove("${key}_time")
                .apply()
            return null
        }

        return prefs.getString(key, null)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}