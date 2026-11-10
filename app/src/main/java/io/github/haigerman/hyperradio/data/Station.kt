package io.github.haigerman.hyperradio.data

import java.net.URI

/**
 * Ein Radiosender. [urls] enthält die Stream-Adresse(n) in der Reihenfolge, in der sie
 * probiert werden: fällt die erste aus, wechselt die Wiedergabe automatisch zur nächsten.
 */
data class Station(
    val id: String,
    val name: String,
    val urls: List<String>,
    val subtitle: String,
    val emoji: String,
    val custom: Boolean = false,
)

/** Fest eingebaute Sender. */
object Presets {
    val all: List<Station> = listOf(
        Station(
            id = "bob-live",
            name = "Radio Bob Live",
            subtitle = "RADIO BOB! · Rock",
            emoji = "🤘",
            urls = listOf(
                "https://streams.radiobob.de/bob-live/mp3-192/mediaplayer",
                "https://streams.radiobob.de/bob-live/mp3-128/mediaplayer",
                "http://streams.radiobob.de/bob-live/mp3-192/mediaplayer",
            ),
        ),
        Station(
            id = "bob-grunge",
            name = "Radio Bob Grunge",
            subtitle = "RADIO BOB! · Grunge",
            emoji = "🎸",
            urls = listOf(
                "https://streams.radiobob.de/bob-grunge/mp3-192/mediaplayer",
                "https://streams.radiobob.de/bob-grunge/mp3-128/mediaplayer",
                "http://streams.radiobob.de/bob-grunge/mp3-192/mediaplayer",
            ),
        ),
        Station(
            id = "bayern1",
            name = "Bayern 1",
            subtitle = "Bayerischer Rundfunk · Oberbayern",
            emoji = "🏔️",
            urls = listOf(
                "https://streams.br.de/bayern1obb_2.m3u",
                "https://dispatcher.rndfnk.com/br/br1/obb/mp3/high",
                "https://dispatcher.rndfnk.com/br/br1/obb/mp3/mid",
                "http://dispatcher.rndfnk.com/br/br1/obb/mp3/mid",
            ),
        ),
        Station(
            id = "virgin",
            name = "Virgin Radio",
            subtitle = "Virgin Radio Switzerland · Rock",
            emoji = "🎧",
            urls = listOf(
                "https://stream.streambase.ch/vrock/mp3-192/direct/",
                "https://stream.streambase.ch/vrock/mp3-128/direct/",
                "http://stream.streambase.ch/vrock/mp3-192/direct/",
            ),
        ),
    )
}

/** Gültige Stream-Adresse? (nur http/https mit Host) */
fun isValidStreamUrl(url: String): Boolean = try {
    val u = URI(url.trim())
    (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
} catch (_: Exception) {
    false
}

/** Hostname für die Unterzeile eigener Sender, z. B. "stream.example.com". */
fun hostOf(url: String): String = try {
    URI(url.trim()).host?.removePrefix("www.") ?: url
} catch (_: Exception) {
    url
}
