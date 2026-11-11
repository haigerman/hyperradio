package io.github.haigerman.hyperradio.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PlayerPhase {
    /** Nichts ausgewählt bzw. gestoppt */
    IDLE,
    /** Erste Verbindung zum Sender, Puffer wird gefüllt */
    CONNECTING,
    /** Wiedergabe war schon gelaufen, Puffer ist leergelaufen und wird neu gefüllt */
    BUFFERING,
    PLAYING,
    PAUSED,
    /** Verbindung abgerissen – neuer Versuch läuft bzw. ist geplant */
    RECONNECTING,
    /** Kein Netz – sobald wieder eins da ist, geht es automatisch weiter */
    WAITING_FOR_NETWORK,
    /** Alle Versuche gescheitert */
    ERROR,
}

data class PlaybackStatus(
    val stationId: String? = null,
    val phase: PlayerPhase = PlayerPhase.IDLE,
    /** Aktueller Titel aus den Stream-Metadaten (ICY), falls der Sender welche schickt */
    val nowPlaying: String? = null,
    /** Wie viel Audio schon im Voraus geladen ist */
    val bufferedMs: Long = 0,
)

/** Zentrale, app-weite Quelle für Sender und Wiedergabestatus – geteilt von UI und Dienst. */
object RadioRepository {
    private val _stations = MutableStateFlow(Presets.all)
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /** Zugeklappte Kategorien (ids) */
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        prefs = p
        _stations.value = arrange(applyOrder(Presets.all + decodeCustom(p.getString(KEY_CUSTOM, null)), decodeOrder(p.getString(KEY_ORDER, null))))
        _collapsed.value = p.getStringSet(KEY_COLLAPSED, emptySet())!!.toSet()
        _status.update { it.copy(stationId = lastStationId) }
    }

    fun find(id: String?): Station? = id?.let { i -> _stations.value.firstOrNull { it.id == i } }

    var lastStationId: String?
        get() = prefs?.getString(KEY_LAST, null)?.takeIf { find(it) != null }
        set(value) {
            prefs?.edit()?.putString(KEY_LAST, value)?.apply()
        }

    fun addCustom(name: String, url: String, category: Category = Category.OTHER): Station {
        val s = customStation(UUID.randomUUID().toString(), name, url, category)
        _stations.update { arrange(it + s) }
        saveCustom()
        return s
    }

    fun updateCustom(id: String, name: String, url: String, category: Category) {
        _stations.update { list ->
            val old = list.firstOrNull { it.id == id }
            val updated = list.map { if (it.id == id && it.custom) customStation(id, name, url, category) else it }
            // in eine andere Kategorie verschoben → dort ans Ende
            if (old != null && old.category != category) arrange(updated.filterNot { it.id == id } + updated.first { it.id == id })
            else arrange(updated)
        }
        saveCustom()
        saveOrder()
    }

    fun toggleCategory(category: Category) {
        _collapsed.update { if (category.id in it) it - category.id else it + category.id }
        prefs?.edit()?.putStringSet(KEY_COLLAPSED, _collapsed.value)?.apply()
    }

    fun removeCustom(id: String) {
        _stations.update { list -> list.filterNot { it.id == id && it.custom } }
        saveCustom()
    }

    /** Neue Reihenfolge der Sender (per Ziehen in der Liste); gilt auch für Android Auto. */
    fun setOrder(ids: List<String>) {
        _stations.update { arrange(applyOrder(it, ids)) }
        saveOrder()
    }

    private fun saveOrder() {
        prefs?.edit()?.putString(KEY_ORDER, JSONArray(_stations.value.map { it.id }).toString())?.apply()
    }

    /** Nach Kategorien gruppiert; innerhalb einer Kategorie bleibt die Reihenfolge erhalten. */
    internal fun arrange(list: List<Station>): List<Station> = list.sortedBy { it.category.ordinal }

    fun publishStatus(status: PlaybackStatus) {
        _status.value = status
    }

    private fun saveCustom() {
        prefs?.edit()?.putString(KEY_CUSTOM, encodeCustom(_stations.value.filter { it.custom }))?.apply()
    }

    private const val KEY_CUSTOM = "custom_stations"
    private const val KEY_LAST = "last_station"
    private const val KEY_ORDER = "station_order"
    private const val KEY_COLLAPSED = "collapsed_categories"
    private const val CUSTOM_PREFIX = "custom-"

    internal fun customStation(id: String, name: String, url: String, category: Category = Category.OTHER): Station {
        val cleanUrl = url.trim()
        val fullId = if (id.startsWith(CUSTOM_PREFIX)) id else CUSTOM_PREFIX + id
        return Station(
            id = fullId,
            name = name.trim(),
            urls = listOf(cleanUrl),
            subtitle = hostOf(cleanUrl),
            emoji = "📻",
            custom = true,
            category = category,
        )
    }

    /**
     * Sortiert nach der gespeicherten Reihenfolge. Sender, die darin noch nicht vorkommen:
     * neu eingebaute kommen an ihren vorgesehenen Platz (ihre Position in [stations]) – steht
     * hinter ihnen kein bereits bekannter eingebauter Sender, ganz ans Ende; neu hinzugefügte
     * eigene Sender ans Ende.
     */
    internal fun applyOrder(stations: List<Station>, order: List<String>): List<Station> {
        if (order.isEmpty()) return stations
        val pos = order.withIndex().associate { (i, id) -> id to i }
        val (known, unknown) = stations.partition { it.id in pos }
        val result = known.sortedBy { pos.getValue(it.id) }.toMutableList()
        unknown.filterNot { it.custom }.forEach { s ->
            val i = stations.indexOf(s)
            val lastPreset = stations.drop(i + 1).none { !it.custom && it.id in pos }
            result.add(if (lastPreset) result.size else i.coerceAtMost(result.size), s)
        }
        result += unknown.filter { it.custom }
        return result
    }

    private fun decodeOrder(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList() else JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }
    } catch (_: Exception) {
        emptyList()
    }

    internal fun encodeCustom(list: List<Station>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.urls.first()).put("category", it.category.id)) }
    }.toString()

    internal fun decodeCustom(json: String?): List<Station> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url")
                val name = o.optString("name")
                if (name.isBlank() || !isValidStreamUrl(url)) null
                else customStation(o.optString("id", UUID.randomUUID().toString()), name, url, Category.fromId(o.optString("category", "")))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
