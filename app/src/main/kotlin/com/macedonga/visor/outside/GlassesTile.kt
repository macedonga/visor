package com.macedonga.visor.outside

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.macedonga.visor.MainActivity
import com.macedonga.visor.R
import com.macedonga.visor.connection.GlassesService
import com.macedonga.visor.home.moodWords
import com.macedonga.visor.stage.Mood
import com.macedonga.visor.ui.allows
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings: how the glasses are and their battery. A tap reconnects them, or opens Visor. */
@AndroidEntryPoint
class GlassesTile : TileService() {

    @Inject
    lateinit var outside: Outside

    private var watching: Job? = null

    override fun onStartListening() {
        watching?.cancel()
        watching = outside.scope.launch(Dispatchers.Main) { outside.glimpse.collect(::show) }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
    }

    override fun onClick() {
        val glimpse = outside.glimpse.value
        when {
            !allows(Manifest.permission.BLUETOOTH_CONNECT) || glimpse.mood == Mood.ABSENT || glimpse.here -> open(null)
            // Over Wi-Fi Bluetooth is paused on purpose: show what is happening instead of reconnecting.
            glimpse.mood == Mood.RECORDING -> open(MainActivity.OPEN_LIVE)
            glimpse.mood == Mood.IMPORTING -> open(MainActivity.OPEN_GALLERY)
            // The tap lets the service start from the background; if the system says no all the same, the app starts it.
            else -> {
                outside.retrying()
                runCatching { GlassesService.reconnect(this) }.onFailure { open(null) }
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form runs only before Android 14
    private fun open(where: String?) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        where?.let { intent.putExtra(MainActivity.EXTRA_OPEN, it) }
        val go = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, TILE_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun(go) else go()
    }

    private fun show(glimpse: Glimpse) {
        val tile = qsTile ?: return
        val words = getString(moodWords(glimpse.mood))
        tile.state = if (glimpse.here || glimpse.mood == Mood.RECORDING || glimpse.mood == Mood.IMPORTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = glimpse.glasses?.let { getString(R.string.tile_subtitle, words, it) } ?: words
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_visor)
        tile.updateTile()
    }

    companion object {
        private const val TILE_REQUEST = 40

        /** Have the system bind the tile so it shows the change, whether or not the shade is open. */
        fun ask(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, GlassesTile::class.java)) }
        }
    }
}
