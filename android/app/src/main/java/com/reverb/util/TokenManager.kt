package com.reverb.util

import android.content.Context

object TokenManager {
    private const val PREFS_NAME = "reverb_prefs"
    private const val KEY_TOKEN = "server_token"
    private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var token = prefs.getString(KEY_TOKEN, null)
        if (token == null) {
            token = (1..6).map { CHARS.random() }.joinToString("")
            prefs.edit().putString(KEY_TOKEN, token).apply()
        }
        return token
    }

    fun resetToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = (1..6).map { CHARS.random() }.joinToString("")
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }
}
