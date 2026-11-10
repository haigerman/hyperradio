package io.github.haigerman.hyperradio

import io.github.haigerman.hyperradio.spotify.MixBuilder
import io.github.haigerman.hyperradio.spotify.Pkce
import io.github.haigerman.hyperradio.spotify.SpotifyArtist
import io.github.haigerman.hyperradio.spotify.SpotifyConfig
import io.github.haigerman.hyperradio.spotify.SpotifyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpotifyTest {

    @Test
    fun pkceChallengeMatchesRfc7636() {
        // Prüfwerte aus RFC 7636, Anhang B
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val v = Pkce.newVerifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun authorizeUrlContainsEverything() {
        val url = Pkce.authorizeUrl("abc", SpotifyConfig.REDIRECT_URI, listOf("a-b", "c"), "verifier", "st")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("client_id=abc"))
        assertTrue(url.contains("redirect_uri=hyperradio%3A%2F%2Fspotify-callback"))
        assertTrue(url.contains("scope=a-b%20c"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=st"))
    }

    @Test
    fun mixIsFairAndAvoidsRepeats() {
        val pool = mapOf(
            "big" to (1..80).map { "spotify:track:big$it" },
            "small" to (1..10).map { "spotify:track:small$it" },
            "mid" to (1..30).map { "spotify:track:mid$it" },
        )
        repeat(20) { seed ->
            val mix = MixBuilder.build(pool, 100, Random(seed))
            assertEquals(100, mix.size)
            assertEquals(mix.size, mix.toSet().size)
            // in den ersten 30 Titeln kommt jeder Künstler gleich oft dran
            val first30 = mix.take(30).groupingBy { it.substringAfter("track:").takeWhile { c -> c.isLetter() } }.eachCount()
            assertEquals(mapOf("big" to 10, "small" to 10, "mid" to 10), first30)
            // nie zweimal derselbe Künstler direkt hintereinander, solange es Alternativen gibt
            val artists = mix.take(30).map { it.substringAfter("track:").takeWhile { c -> c.isLetter() } }
            assertTrue(artists.zipWithNext().none { (a, b) -> a == b })
        }
        assertEquals(emptyList<String>(), MixBuilder.build(emptyMap(), 100))
        assertEquals(3, MixBuilder.build(mapOf("x" to listOf("1", "2", "2", "3")), 100).size)
    }

    @Test
    fun selectedArtistsRoundTrip() {
        val list = listOf(SpotifyArtist("1", "AC/DC"), SpotifyArtist("2", "Nirvana"))
        assertEquals(list, SpotifyRepository.decodeArtists(SpotifyRepository.encodeArtists(list)))
        assertEquals(emptyList<SpotifyArtist>(), SpotifyRepository.decodeArtists("kaputt"))
    }
}
