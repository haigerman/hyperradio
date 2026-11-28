package io.github.haigerman.hyperradio.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Ein mit HyperRadio verknüpfter Bluetooth-Lautsprecher. */
data class Speaker(val address: String, val name: String, val associationId: Int = -1)

/**
 * Bluetooth-Lautsprecher, bei deren Verbindung der letzte Sender startet – samt Zeitpunkten der
 * letzten Trennung bzw. des letzten Starts (für die 30-Sekunden-Regel, auch über Neustarts der
 * App hinweg, deshalb in den Einstellungen gespeichert).
 */
object SpeakerStore {
    private val _speakers = MutableStateFlow<List<Speaker>>(emptyList())
    val speakers: StateFlow<List<Speaker>> = _speakers.asStateFlow()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("speakers", Context.MODE_PRIVATE)
        prefs = p
        _speakers.value = decode(p.getString(KEY_LIST, null))
    }

    fun find(address: String?): Speaker? = address?.let { a -> _speakers.value.firstOrNull { it.address == normalize(a) } }

    fun add(speaker: Speaker) {
        val s = speaker.copy(address = normalize(speaker.address))
        _speakers.value = _speakers.value.filterNot { it.address == s.address } + s
        save()
    }

    fun remove(address: String) {
        _speakers.value = _speakers.value.filterNot { it.address == normalize(address) }
        save()
    }

    /** Lautsprecher verbunden: true = letzten Sender starten. */
    fun onConnected(address: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs ?: return false
        if (find(address) == null) return false
        val a = normalize(address)
        val lastOff = p.getLong(KEY_OFF + a, 0L).takeIf { it > 0 }
        val lastStart = p.getLong(KEY_START + a, 0L).takeIf { it > 0 }
        val start = shouldStart(now, lastOff, lastStart)
        if (start) p.edit().putLong(KEY_START + a, now).apply()
        return start
    }

    fun onDisconnected(address: String, now: Long = System.currentTimeMillis()) {
        prefs?.edit()?.putLong(KEY_OFF + normalize(address), now)?.apply()
    }

    /** Starten, wenn der Lautsprecher mindestens 30 s getrennt war und nicht gerade erst gestartet wurde. */
    internal fun shouldStart(now: Long, lastDisconnect: Long?, lastStart: Long?): Boolean {
        val longEnough = lastDisconnect == null || now - lastDisconnect >= REARM_MS
        val notJustStarted = lastStart == null || now - lastStart >= REARM_MS
        return longEnough && notJustStarted
    }

    internal fun normalize(address: String) = address.trim().uppercase()

    private fun save() {
        prefs?.edit()?.putString(KEY_LIST, encode(_speakers.value))?.apply()
    }

    internal fun encode(list: List<Speaker>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("address", it.address).put("name", it.name).put("id", it.associationId)) }
    }.toString()

    internal fun decode(json: String?): List<Speaker> = try {
        if (json.isNullOrBlank()) emptyList()
        else JSONArray(json).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val address = o.optString("address")
                if (address.isBlank()) null else Speaker(normalize(address), o.optString("name", address), o.optInt("id", -1))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private const val REARM_MS = 30_000L
    private const val KEY_LIST = "speakers"
    private const val KEY_OFF = "off_"
    private const val KEY_START = "start_"
}
