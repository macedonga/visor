package com.macedonga.visor.ui

import android.content.Context
import android.content.pm.PackageManager

fun Context.allows(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
