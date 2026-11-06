package com.macedonga.visor.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.macedonga.visor.NotifyAppsKey
import com.macedonga.visor.R
import com.macedonga.visor.core.media.Albums
import com.macedonga.visor.core.media.wifiIsOn
import com.macedonga.visor.core.design.Visor
import com.macedonga.visor.core.settings.offeredOn
import com.macedonga.visor.features.featureSection
import com.macedonga.visor.gallery.AutoImport
import com.macedonga.visor.gallery.rememberPlacesAsk
import com.macedonga.visor.gallery.title
import com.macedonga.visor.ui.VisorIcons

/** One topic: Visor's own part of it first where it has one, then the glasses' settings by section. */
@Composable
fun TopicScreen(name: String, open: (NavKey) -> Unit, model: SettingsViewModel = hiltViewModel()) {
    val topic = Topic.valueOf(name)
    val known = model.settings.specs.keys
    val product = rememberProduct(model.settings, model.lastSeen)
    SettingsPage(stringResource(topic.title)) {
        when (topic) {
            Topic.NOTIFICATIONS -> item(key = "visor") {
                val on by model.notifyChoice.enabled.collectAsStateWithLifecycle()
                val muted by model.apps.muted.collectAsStateWithLifecycle()
                Rows(
                    { VisorToggleRow(model.toggles.byId("notifications")!!) },
                    if (on) row {
                        ValueRow(
                            stringResource(R.string.notify_apps_title),
                            if (muted.isEmpty()) stringResource(R.string.sum_notify_all) else pluralStringResource(R.plurals.sum_notify_muted, muted.size, muted.size),
                            onClick = { open(NotifyAppsKey) },
                        )
                    } else null,
                )
            }
            Topic.CAMERA -> item(key = "visor") { Rows({ VisorToggleRow(model.toggles.byId("phone_steady")!!) }) }
            Topic.PHOTOS -> item(key = "visor") { PhotosPart(model) }
            Topic.BAND -> item(key = "visor") { BandPart(model) }
            else -> Unit
        }
        // Hearing is nothing but features, so they need no heading of their own there.
        featureSection(topic, open, heading = topic != Topic.HEARING)
        for ((i, section) in topic.sections.withIndex()) {
            // Rows the Meta app keeps for other glasses are left to Advanced.
            val keys = section.second.filter { it in known && specOf(model.settings, it).offeredOn(product) }
            if (keys.isEmpty()) continue
            item(key = "section-$i") {
                Column {
                    val heading = section.first
                    if (heading != null) SectionLabel(stringResource(heading))
                    Rows(keys) { GlassesRow(it) }
                }
            }
        }
    }
}

/** How photos come over, the albums, and whether the phone notes where they were taken. */
@Composable
private fun PhotosPart(model: SettingsViewModel) {
    val context = LocalContext.current
    val mode by model.importChoice.mode.collectAsStateWithLifecycle()
    val (places, askPlaces) = rememberPlacesAsk()
    val wifi by rememberOnResume { wifiIsOn(context) }
    var renaming by remember { mutableStateOf<Album?>(null) }
    val photosAlbum by model.albums.photos.collectAsStateWithLifecycle()
    val videosAlbum by model.albums.videos.collectAsStateWithLifecycle()
    val moving by model.albums.moving.collectAsStateWithLifecycle()
    val together by model.albums.together.collectAsStateWithLifecycle()
    Column {
        Rows({
            PillsRow(
                stringResource(R.string.import_title), AutoImport.entries.map { it to stringResource(it.title) }, mode, model.importChoice::set,
                problem = stringResource(R.string.import_problem_wifi_off).takeIf { mode != AutoImport.OFF && !wifi },
            )
        })
        SectionLabel(stringResource(R.string.section_album))
        Rows(
            row { ToggleRow(stringResource(R.string.album_together), together, model.albums::setTogether) },
            row { ValueRow(albumTitle(Album.PHOTOS, together), photosAlbum, onClick = { renaming = Album.PHOTOS }, busy = moving > 0) },
            if (together) null else row { ValueRow(albumTitle(Album.VIDEOS, together), videosAlbum, onClick = { renaming = Album.VIDEOS }, busy = moving > 0) },
        )
        SectionLabel(stringResource(R.string.section_places))
        Rows({
            RowFrame(stringResource(R.string.places_title), onClick = null) {
                if (places) {
                    Icon(VisorIcons.Check, stringResource(R.string.access_allowed), Modifier.padding(start = 12.dp).size(20.dp), tint = Visor.colors.textSecondary)
                } else {
                    ActionPill(stringResource(R.string.allow), askPlaces, modifier = Modifier.padding(start = 12.dp))
                }
            }
        })
        renaming?.let { album ->
            TextSheet(
                albumTitle(album, together),
                if (album == Album.VIDEOS) videosAlbum else photosAlbum,
                onSave = if (album == Album.VIDEOS) model.albums::setVideos else model.albums::setPhotos,
                onDismiss = { renaming = null },
                hint = Albums.DEFAULT,
            )
        }
    }
}

private enum class Album { PHOTOS, VIDEOS }

@Composable
private fun albumTitle(album: Album, together: Boolean) = stringResource(
    when {
        album == Album.VIDEOS -> R.string.album_videos
        together -> R.string.album_both
        else -> R.string.album_photos
    },
)
