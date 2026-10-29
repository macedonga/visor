package com.macedonga.visor.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Starts or stops the Claude bridge through Termux's RUN_COMMAND service; Termux needs allow-external-apps on. */
object TermuxBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val FILES = "/data/data/com.termux/files"

    /**
     * Null when the request went to Termux, else why it did not. Start runs node as the command itself, because
     * anything the proot leaves running is killed when its command ends; stop is a pkill from Termux's side.
     */
    fun run(context: Context, start: Boolean): String? = runCatching {
        val (path, args) = if (start) {
            "$FILES/usr/bin/proot-distro" to arrayOf("login", "ubuntu", "--", "bash", "-c", "cd /root && exec node bridge.js >> bridge.log 2>&1")
        } else {
            "$FILES/usr/bin/pkill" to arrayOf("-f", "node bridge.js")
        }
        val intent = Intent("com.termux.RUN_COMMAND")
            .setComponent(ComponentName("com.termux", "com.termux.app.RunCommandService"))
            .putExtra("com.termux.RUN_COMMAND_PATH", path)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "$FILES/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        checkNotNull(context.startService(intent)) { "Termux is not installed" }
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }
}
