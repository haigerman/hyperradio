package io.github.haigerman.hyperradio

import io.github.haigerman.hyperradio.data.Presets
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.data.hostOf
import io.github.haigerman.hyperradio.data.isValidStreamUrl
import io.github.haigerman.hyperradio.playback.StreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTest {

    @Test
    fun presetsAreValid() {
        assertEquals(6, Presets.all.size)
        assertEquals(Presets.all.size, Presets.all.map { it.id }.toSet().size)
        Presets.all.forEach { s ->
            assertTrue(s.urls.isNotEmpty())
            s.urls.forEach { assertTrue("${s.name}: $it", isValidStreamUrl(it)) }
        }
    }

    @Test
    fun urlValidation() {
        assertTrue(isValidStreamUrl("https://example.com/stream"))
        assertTrue(isValidStreamUrl("  http://1.2.3.4:8000/live.mp3 "))
        assertFalse(isValidStreamUrl("example.com/stream"))
        assertFalse(isValidStreamUrl("ftp://example.com/x"))
        assertFalse(isValidStreamUrl(""))
        assertEquals("stream.example.com", hostOf("https://www.stream.example.com/x"))
    }

    @Test
    fun customStationsRoundTrip() {
        val a = RadioRepository.customStation("1", " Mein Sender ", " https://example.com/a.mp3 ")
        val b = RadioRepository.customStation("2", "Zwei", "http://example.org/b.pls")
        val decoded = RadioRepository.decodeCustom(RadioRepository.encodeCustom(listOf(a, b)))
        assertEquals(listOf(a, b), decoded)
        assertEquals("Mein Sender", decoded[0].name)
        assertEquals("https://example.com/a.mp3", decoded[0].urls.single())
        assertTrue(decoded.all { it.custom })
        assertEquals(emptyList<Any>(), RadioRepository.decodeCustom("kaputt"))
    }

    @Test
    fun playlistParsing() {
        assertTrue(StreamResolver.looksLikePlaylist("http://x.de/live.m3u?foo=1"))
        assertTrue(StreamResolver.looksLikePlaylist("http://x.de/LIVE.PLS"))
        assertFalse(StreamResolver.looksLikePlaylist("http://x.de/live.m3u8"))
        assertTrue(StreamResolver.isHls("http://x.de/live.m3u8"))

        val m3u = "#EXTM3U\n#EXTINF:-1,Radio\n\nhttp://stream.x.de/live.mp3\nhttp://backup\n"
        assertEquals("http://stream.x.de/live.mp3", StreamResolver.parsePlaylist(m3u))
        val pls = "[playlist]\nNumberOfEntries=1\nFile1=https://stream.x.de/a.aac\nTitle1=Radio\n"
        assertEquals("https://stream.x.de/a.aac", StreamResolver.parsePlaylist(pls))
        assertNull(StreamResolver.parsePlaylist("<html>nope</html>"))
    }
}

class StationOrderTest {
    private fun st(id: String) = io.github.haigerman.hyperradio.data.Station(id, id, listOf("http://x/$id"), "", "📻")

    @Test
    fun appliesSavedOrderAndAppendsNewStations() {
        val stations = listOf(st("a"), st("b"), st("c"), st("new"))
        val ordered = RadioRepository.applyOrder(stations, listOf("c", "a", "gone", "b"))
        assertEquals(listOf("c", "a", "b", "new"), ordered.map { it.id })
        assertEquals(stations, RadioRepository.applyOrder(stations, emptyList()))
    }

    @Test
    fun newPresetLandsAtItsPlaceEvenWithCustomOrder() {
        val custom = RadioRepository.customStation("1", "Mein", "http://x/m")
        val stations = listOf(st("kexp"), st("a"), st("b"), st("c"), custom)
        // gespeicherte Reihenfolge aus der Zeit vor dem neuen Sender
        val ordered = RadioRepository.applyOrder(stations, listOf("c", custom.id, "a", "b"))
        assertEquals(listOf("kexp", "c", custom.id, "a", "b"), ordered.map { it.id })
    }
}
