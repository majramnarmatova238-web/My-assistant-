package com.example.myassistant

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    fun name(c: Context): String = sp(c).getString("name", "Luxon") ?: "Luxon"

    fun base(c: Context): String =
        sp(c).getString("base", "http://127.0.0.1:3001/v1") ?: "http://127.0.0.1:3001/v1"

    fun key(c: Context): String = sp(c).getString("key", "") ?: ""

    fun save(c: Context, name: String, base: String, key: String) {
        sp(c).edit()
            .putString("name", name.trim().ifEmpty { "Luxon" })
            .putString("base", base.trim().ifEmpty { "http://127.0.0.1:3001/v1" })
            .putString("key", key.trim())
            .apply()
    }
}
