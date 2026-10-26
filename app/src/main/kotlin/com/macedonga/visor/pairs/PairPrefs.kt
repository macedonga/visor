package com.macedonga.visor.pairs

import android.content.Context
import android.content.SharedPreferences

/**
 * Visor's own notes about one pair, in a preferences file of its own (`<name>_<serial>`). The first pair to ask takes
 * what the shared file held from before Visor kept pairs apart; with no pair known yet, the shared file it is.
 */
fun pairPrefs(context: Context, name: String, serial: String?): SharedPreferences {
    val shared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    serial ?: return shared
    val own = context.getSharedPreferences("${name}_$serial", Context.MODE_PRIVATE)
    val old = shared.all
    if (old.isNotEmpty()) {
        if (own.all.isEmpty()) {
            val edit = own.edit()
            old.forEach { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> edit.putStringSet(key, value as Set<String>)
                }
            }
            edit.commit()
        }
        shared.edit().clear().commit()
    }
    return own
}
