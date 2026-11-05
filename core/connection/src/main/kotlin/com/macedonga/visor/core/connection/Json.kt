package com.macedonga.visor.core.connection

import org.json.JSONArray
import org.json.JSONObject

/** The array's objects, in order; throws on an element that is not one. */
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

/** The array's elements as strings, in order. */
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
