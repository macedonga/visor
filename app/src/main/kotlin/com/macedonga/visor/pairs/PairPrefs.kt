package com.macedonga.visor.pairs

import android.content.Context
import android.content.SharedPreferences

/** Visor's notes on one pair, in `<name>_<serial>`; the first pair to ask takes the shared file's keys, a null serial gets the shared file. */
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
