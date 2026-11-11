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

/** Ein gelöschter Sender – für "Rückgängig". */
data class DeletedStation(val station: Station, val position: Int)

/** Zentrale, app-weite Quelle für Sender, Kategorien und Wiedergabestatus – geteilt von UI und Dienst. */
object RadioRepository {
    private val _stations = MutableStateFlow(arrange(Presets.all, Category.defaults))
    /** Alle sichtbaren Sender, nach Kategorien gruppiert, innerhalb in der gewählten Reihenfolge */
    val stations: StateFlow<List<Station>> = _stations.asStateFlow()

    private val _categories = MutableStateFlow(Category.defaults)
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    /** Zugeklappte Kategorien (ids) */
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    /** Anzahl gelöschter eingebauter Sender (lassen sich wiederherstellen) */
    private val _hiddenCount = MutableStateFlow(0)
    val hiddenCount: StateFlow<Int> = _hiddenCount.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private var prefs: SharedPreferences? = null

    // Quellen, aus denen die Senderliste gebaut wird
    private var customs: List<Station> = emptyList()
    private var overrides: Map<String, String> = emptyMap() // eingebauter Sender → andere Kategorie
    private var hidden: Set<String> = emptySet() // gelöschte eingebaute Sender
    private var lastDeleted: DeletedStation? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("radio", Context.MODE_PRIVATE)
        prefs = p
        _categories.value = decodeCategories(p.getString(KEY_CATEGORIES, null))
        customs = decodeCustom(p.getString(KEY_CUSTOM, null))
        overrides = decodeOverrides(p.getString(KEY_OVERRIDES, null))
        hidden = p.getStringSet(KEY_HIDDEN, emptySet())!!.toSet()
        _collapsed.value = p.getStringSet(KEY_COLLAPSED, emptySet())!!.toSet()
        rebuild(decodeOrder(p.getString(KEY_ORDER, null)))
        _status.update { it.copy(stationId = lastStationId) }
    }

    fun find(id: String?): Station? = id?.let { i -> _stations.value.firstOrNull { it.id == i } }

    fun category(id: String?): Category =
        _categories.value.firstOrNull { it.id == id } ?: _categories.value.first { it.isFallback }

    var lastStationId: String?
        get() = prefs?.getString(KEY_LAST, null)?.takeIf { find(it) != null }
        set(value) {
            prefs?.edit()?.putString(KEY_LAST, value)?.apply()
        }

    // ------------------------------------------------------------------ Sender

    fun addCustom(name: String, url: String, categoryId: String = Category.OTHER_ID): Station {
        val s = customStation(UUID.randomUUID().toString(), name, url, categoryId)
        customs = customs + s
        rebuild(currentOrder() + s.id)
        saveAll()
        return s
    }

    fun updateCustom(id: String, name: String, url: String, categoryId: String) {
        val old = customs.firstOrNull { it.id == id } ?: return
        customs = customs.map { if (it.id == id) customStation(id, name, url, categoryId) else it }
        // in eine andere Kategorie → dort ans Ende
        rebuild(if (old.categoryId != categoryId) currentOrder() - id + id else currentOrder())
        saveAll()
    }

    /** Verschiebt einen (eingebauten oder eigenen) Sender in eine andere Kategorie – dort ans Ende. */
    fun setStationCategory(id: String, categoryId: String) {
        val station = find(id) ?: return
        if (station.categoryId == categoryId) return
        if (station.custom) {
            customs = customs.map { if (it.id == id) it.copy(categoryId = categoryId) else it }
        } else {
            val default = Presets.all.firstOrNull { it.id == id }?.categoryId
            overrides = if (categoryId == default) overrides - id else overrides + (id to categoryId)
        }
        rebuild(currentOrder() - id + id)
        saveAll()
    }

    /** Löscht einen Sender (eingebaute lassen sich wiederherstellen). Rückgängig mit [undoDelete]. */
    fun deleteStation(id: String): DeletedStation? {
        val station = find(id) ?: return null
        val deleted = DeletedStation(station, currentOrder().indexOf(id))
        if (station.custom) customs = customs.filterNot { it.id == id } else hidden = hidden + id
        lastDeleted = deleted
        rebuild(currentOrder() - id)
        saveAll()
        return deleted
    }

    fun undoDelete() {
        val d = lastDeleted ?: return
        lastDeleted = null
        if (d.station.custom) customs = customs + d.station else hidden = hidden - d.station.id
        val order = currentOrder().toMutableList().apply { add(d.position.coerceIn(0, size), d.station.id) }
        rebuild(order)
        saveAll()
    }

    /** Holt alle gelöschten eingebauten Sender zurück (an ihren ursprünglichen Platz). */
    fun restoreHiddenPresets() {
        if (hidden.isEmpty()) return
        hidden = emptySet()
        rebuild()
        saveAll()
    }

    /** Neue Reihenfolge der Sender (per Ziehen in der Liste); gilt auch für Android Auto. */
    fun setOrder(ids: List<String>) {
        rebuild(ids)
        saveAll()
    }

    // ------------------------------------------------------------------ Kategorien

    fun toggleCategory(categoryId: String) {
        _collapsed.update { if (categoryId in it) it - categoryId else it + categoryId }
        prefs?.edit()?.putStringSet(KEY_COLLAPSED, _collapsed.value)?.apply()
    }

    fun expand(categoryId: String) {
        if (categoryId in _collapsed.value) toggleCategory(categoryId)
    }

    /** Neue Kategorie – kommt vor "Übrige". */
    fun addCategory(title: String, emoji: String): Category {
        val c = Category("c-" + UUID.randomUUID().toString().take(8), title.trim(), emoji)
        _categories.update { list ->
            val i = list.indexOfFirst { it.isFallback }.let { if (it < 0) list.size else it }
            list.toMutableList().apply { add(i, c) }
        }
        rebuild()
        saveAll()
        return c
    }

    fun updateCategory(id: String, title: String, emoji: String) {
        _categories.update { list -> list.map { if (it.id == id) it.copy(title = title.trim(), emoji = emoji) else it } }
        rebuild()
        saveAll()
    }

    /** Kategorie um [delta] Plätze nach oben (-1) oder unten (+1) schieben. */
    fun moveCategory(id: String, delta: Int) {
        _categories.update { list ->
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
        }
        rebuild()
        saveAll()
    }

    /** Löscht eine Kategorie; ihre Sender wandern nach "Übrige". */
    fun deleteCategory(id: String) {
        val cat = _categories.value.firstOrNull { it.id == id } ?: return
        if (cat.isFallback) return
        val other = Category.OTHER_ID
        customs = customs.map { if (it.categoryId == id) it.copy(categoryId = other) else it }
        overrides = overrides.mapValues { (_, c) -> if (c == id) other else c }
        Presets.all.filter { it.categoryId == id && it.id !in overrides }.forEach { overrides = overrides + (it.id to other) }
        _categories.update { list -> list.filterNot { it.id == id } }
        _collapsed.update { it - id }
        rebuild()
        saveAll()
    }

    fun publishStatus(status: PlaybackStatus) {
        _status.value = status
    }

    // ------------------------------------------------------------------ intern

    private fun currentOrder(): List<String> = _stations.value.map { it.id }

    /** Baut die sichtbare Senderliste aus Voreinstellungen, eigenen Sendern und Einstellungen. */
    private fun rebuild(order: List<String> = currentOrder()) {
        val cats = _categories.value
        val known = cats.map { it.id }.toSet()
        val presets = Presets.all.filter { it.id !in hidden }.map { p -> p.copy(categoryId = overrides[p.id] ?: p.categoryId) }
        val all = (presets + customs).map { if (it.categoryId in known) it else it.copy(categoryId = Category.OTHER_ID) }
        _stations.value = arrange(applyOrder(all, order), cats)
        _hiddenCount.value = hidden.size
    }

    private fun saveAll() {
        prefs?.edit()
            ?.putString(KEY_CUSTOM, encodeCustom(customs))
            ?.putString(KEY_ORDER, JSONArray(currentOrder()).toString())
            ?.putString(KEY_CATEGORIES, encodeCategories(_categories.value))
            ?.putString(KEY_OVERRIDES, JSONObject(overrides as Map<*, *>).toString())
            ?.putStringSet(KEY_HIDDEN, hidden)
            ?.apply()
    }

    private const val KEY_CUSTOM = "custom_stations"
    private const val KEY_LAST = "last_station"
    private const val KEY_ORDER = "station_order"
    private const val KEY_COLLAPSED = "collapsed_categories"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_OVERRIDES = "station_categories"
    private const val KEY_HIDDEN = "hidden_presets"
    private const val CUSTOM_PREFIX = "custom-"

    internal fun customStation(id: String, name: String, url: String, categoryId: String = Category.OTHER_ID): Station {
        val cleanUrl = url.trim()
        val fullId = if (id.startsWith(CUSTOM_PREFIX)) id else CUSTOM_PREFIX + id
        return Station(
            id = fullId,
            name = name.trim(),
            urls = listOf(cleanUrl),
            subtitle = hostOf(cleanUrl),
            emoji = "📻",
            custom = true,
            categoryId = categoryId,
        )
    }

    /** Nach Kategorien gruppiert (in deren Reihenfolge); innerhalb einer Kategorie bleibt die Reihenfolge erhalten. */
    internal fun arrange(list: List<Station>, categories: List<Category> = _categories.value): List<Station> {
        val index = categories.withIndex().associate { (i, c) -> c.id to i }
        val fallback = index[Category.OTHER_ID] ?: categories.size
        return list.sortedBy { index[it.categoryId] ?: fallback }
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

    internal fun encodeCategories(list: List<Category>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("emoji", it.emoji)) }
    }.toString()

    /** Gespeicherte Kategorien; "Übrige" ist immer dabei. */
    internal fun decodeCategories(json: String?): List<Category> {
        val list = try {
            if (json.isNullOrBlank()) null
            else JSONArray(json).let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    Category(o.optString("id"), o.optString("title"), o.optString("emoji", "📻"))
                        .takeIf { it.id.isNotEmpty() && it.title.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            null
        } ?: return Category.defaults
        return if (list.any { it.isFallback }) list else list + Category.defaults.first { it.isFallback }
    }

    private fun decodeOverrides(json: String?): Map<String, String> = try {
        if (json.isNullOrBlank()) emptyMap()
        else JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    } catch (_: Exception) {
        emptyMap()
    }

    internal fun encodeCustom(list: List<Station>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.urls.first()).put("category", it.categoryId)) }
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
                else customStation(
                    o.optString("id", UUID.randomUUID().toString()),
                    name,
                    url,
                    o.optString("category", "").ifEmpty { Category.OTHER_ID },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
