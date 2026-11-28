package com.locket.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCaptionSourceTest {
    @Test fun normalizesSpotifyTrackOnly() {
        val id = "0123456789ABCDEFGHIJKL"
        val url = "https://open.spotify.com/track/$id"
        assertEquals(url, SpotifyCaptionSource.canonicalTrack("$url?si=tracking"))
        assertEquals(url, SpotifyCaptionSource.canonicalTrack("spotify:track:$id"))
        assertNull(SpotifyCaptionSource.canonicalTrack("https://open.spotify.com/album/$id"))
        assertNull(SpotifyCaptionSource.canonicalTrack("https://open.spotify.com.example.org/track/$id"))
    }

    @Test fun serializesActualMetadataWithoutInventingOptionalFields() {
        val music = CaptionMusic("https://open.spotify.com/track/0123456789ABCDEFGHIJKL", "Title", "Artist", null)
        val overlay = music.overlay()
        assertEquals("caption:music", overlay.get("overlay_id").asString)
        val payload = overlay.getAsJsonObject("data").getAsJsonObject("payload")
        assertEquals(music.spotifyUrl, payload.get("spotify_url").asString)
        assertEquals("Artist", payload.get("artist").asString)
        assertNull(payload.get("isrc"))
        assertNull(payload.get("preview_url"))
    }
}
