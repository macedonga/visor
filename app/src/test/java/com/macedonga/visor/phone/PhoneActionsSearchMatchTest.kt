package com.macedonga.visor.phone

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneActionsSearchMatchTest {
    @Test
    fun chooseMediaSearchMatch_prefers_exact_title_and_artist() {
        val exact = mediaItem("song-2", "Thunderstruck", "AC/DC")
        val fuzzy = mediaItem("song-1", "Thunderstruck", "The Best of Rock")
        val other = mediaItem("song-3", "Back in Black", "AC/DC")

        val chosen = chooseMediaSearchMatch(
            "Thunderstruck by AC/DC",
            listOf(fuzzy, exact, other),
        )

        assertEquals("song-2", chosen?.mediaId)
    }

    private fun mediaItem(mediaId: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
        )
}
