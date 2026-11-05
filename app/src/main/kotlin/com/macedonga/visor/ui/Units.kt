package com.macedonga.visor.ui

import android.content.Context
import android.text.format.Formatter
import androidx.core.text.util.LocalePreferences
import androidx.core.text.util.LocalePreferences.TemperatureUnit

/** [bytes] in the phone's language: "14 MB", "1.2 GB". */
fun sizeWords(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** [celsius] in the phone's temperature unit: its regional preference, else what its region uses. */
fun temperatureWords(celsius: Double, decimals: Int = 0): String {
    val pattern = "%.${decimals}f"
    return when (LocalePreferences.getTemperatureUnit()) {
        TemperatureUnit.FAHRENHEIT -> pattern.format(celsius * 9 / 5 + 32) + "°F"
        TemperatureUnit.KELVIN -> pattern.format(celsius + 273.15) + " K"
        else -> pattern.format(celsius) + "°C"
    }
}
