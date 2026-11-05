package com.macedonga.visor.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/** The clipboard's text, if it holds any. */
fun paste(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

/** [text] on the clipboard; [sensitive] keeps it out of the clipboard preview. */
fun copy(context: Context, text: String, label: String = "", sensitive: Boolean = false) {
    val clip = ClipData.newPlainText(label, text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** The share sheet for plain [text]. */
fun shareText(context: Context, text: String) {
    val chooser = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
