package io.github.haigerman.hyperradio.spotify

import kotlin.random.Random

/**
 * Stellt die Zufallswiedergabe zusammen: Titel aller gewählten Künstler gemischt, wobei jeder
 * Künstler ungefähr gleich oft drankommt (auch wenn einer viel mehr Titel hat als ein anderer)
 * und möglichst nie zwei Titel desselben Künstlers direkt hintereinander laufen.
 */
object MixBuilder {

    fun build(tracksByArtist: Map<String, List<String>>, maxTracks: Int, random: Random = Random.Default): List<String> {
        val queues = tracksByArtist
            .mapValues { (_, uris) -> uris.distinct().shuffled(random).toMutableList() }
            .filterValues { it.isNotEmpty() }
            .toMutableMap()
        val result = ArrayList<String>(maxTracks)
        val used = HashSet<String>()
        var previous: String? = null
        while (result.size < maxTracks && queues.isNotEmpty()) {
            // "Runde": jeder Künstler einmal, in zufälliger Reihenfolge
            val round = queues.keys.shuffled(random).toMutableList()
            if (round.size > 1 && round.first() == previous) round.add(round.removeAt(0))
            for (artist in round) {
                if (result.size >= maxTracks) break
                val q = queues[artist] ?: continue
                var uri: String? = null
                while (q.isNotEmpty() && uri == null) uri = q.removeAt(0).takeIf { used.add(it) }
                if (q.isEmpty()) queues.remove(artist)
                if (uri != null) {
                    result += uri
                    previous = artist
                }
            }
        }
        return result
    }
}
