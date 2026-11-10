package io.github.haigerman.hyperradio.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import io.github.haigerman.hyperradio.R
import io.github.haigerman.hyperradio.spotify.SpotifyRepository
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.hyperradio.data.PlaybackStatus
import io.github.haigerman.hyperradio.data.PlayerPhase
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.data.Station
import io.github.haigerman.hyperradio.data.isValidStreamUrl

@Composable
fun RadioScreen(onStationClick: (String) -> Unit, onPlayPause: () -> Unit, onSpotifyClick: () -> Unit) {
    val stations by RadioRepository.stations.collectAsStateWithLifecycle()
    val status by RadioRepository.status.collectAsStateWithLifecycle()

    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, DeepTeal))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(Modifier.statusBarsPadding())
            val presets = stations.filterNot { it.custom }
            val custom = stations.filter { it.custom }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(presets, key = { it.id }) { s ->
                    StationCard(s, status, onClick = { onStationClick(s.id) })
                }
                if (custom.isNotEmpty()) {
                    item(key = "custom-label") { SectionLabel("Eigene Sender") }
                    items(custom, key = { it.id }) { s ->
                        StationCard(s, status, onClick = { onStationClick(s.id) }, onEdit = { editingId = s.id })
                    }
                }
                item(key = "spotify") { SpotifyCard(onClick = onSpotifyClick) }
                item(key = "add") { AddCard(onClick = { adding = true }) }
            }
            BottomPanel(stations, status, onPlayPause, Modifier.navigationBarsPadding())
        }
    }

    if (adding) {
        StationDialog(
            title = "Sender hinzufügen",
            initialName = "",
            initialUrl = "",
            confirmLabel = "Hinzufügen",
            onConfirm = { name, url ->
                RadioRepository.addCustom(name, url)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    val editing = RadioRepository.find(editingId)
    if (editing != null) {
        StationDialog(
            title = "Sender bearbeiten",
            initialName = editing.name,
            initialUrl = editing.urls.first(),
            confirmLabel = "Speichern",
            onConfirm = { name, url ->
                RadioRepository.updateCustom(editing.id, name, url)
                editingId = null
            },
            onDelete = {
                RadioRepository.removeCustom(editing.id)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
        Text("HyperRadio", fontSize = 30.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Wähle einen Sender",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp),
    )
}

@Composable
private fun StationCard(
    station: Station,
    status: PlaybackStatus,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val selected = status.stationId == station.id && status.phase != PlayerPhase.IDLE
    val bg by animateColorAsState(if (selected) CardOn else Card.copy(alpha = 0.7f), label = "bg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (selected) Sand.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(station.emoji, fontSize = 22.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(station.name, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (selected && status.nowPlaying != null) status.nowPlaying else station.subtitle,
                    fontSize = 13.sp,
                    color = if (selected && status.nowPlaying != null) Mist else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Equalizer(active = status.phase == PlayerPhase.PLAYING, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (onEdit != null) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✎", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** Drei Balken, die zur laufenden Wiedergabe tanzen. */
@Composable
private fun Equalizer(active: Boolean, modifier: Modifier = Modifier, color: Color = Sand) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.4f, 0.9f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "c")
    Canvas(modifier.size(width = 20.dp, height = 18.dp)) {
        val w = size.width / 5
        listOf(a, b, c).forEachIndexed { i, v ->
            val h = size.height * (if (active) v else 0.25f)
            drawRoundRect(
                color,
                topLeft = Offset(i * 2 * w, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/** Karte im Stil der Sender, führt zur Spotify-Zufallswiedergabe. */
@Composable
private fun SpotifyCard(onClick: () -> Unit) {
    val sp by SpotifyRepository.state.collectAsStateWithLifecycle()
    val playing = sp.mixActive && sp.playback?.isPlaying == true
    val subtitle = when {
        !sp.configured -> "Einrichtung nötig"
        !sp.loggedIn -> "Tippen zum Anmelden"
        playing && sp.playback?.track != null -> "${sp.playback?.track} · ${sp.playback?.artists}"
        sp.selected.isEmpty() -> "Künstler auswählen"
        else -> "${sp.selected.size} Künstler · Zufallswiedergabe"
    }
    val bg by animateColorAsState(if (playing) CardOn else Card.copy(alpha = 0.7f), label = "bg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_spotify), contentDescription = "Spotify", modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Spotify-Mix", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = if (playing) SpotifyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playing) Equalizer(active = true, color = SpotifyGreen, modifier = Modifier.padding(start = 8.dp))
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Sand.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 16.dp),
        ) {
            Text("＋", fontSize = 18.sp, color = Sand)
            Spacer(Modifier.width(10.dp))
            Text("Sender hinzufügen", fontSize = 16.sp, color = Sand)
        }
    }
}

@Composable
private fun StationDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    confirmLabel: String,
    onConfirm: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var tried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameOk = name.isNotBlank()
    val urlOk = isValidStreamUrl(url)

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Sand,
        focusedLabelColor = Sand,
        cursorColor = Sand,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(title, fontWeight = FontWeight.Light) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("z. B. Mein Lieblingssender") },
                    singleLine = true,
                    isError = tried && !nameOk,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream-URL") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = tried && !urlOk,
                    supportingText = {
                        Text(
                            if (tried && !urlOk) "Bitte eine Adresse mit http:// oder https:// eingeben"
                            else "Direkter Stream oder .m3u/.pls/.m3u8-Link",
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Wirklich löschen?" else "Sender löschen", color = Coral)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (nameOk && urlOk) onConfirm(name, url)
            }) { Text(confirmLabel, color = Sand) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

@Composable
private fun BottomPanel(stations: List<Station>, status: PlaybackStatus, onPlayPause: () -> Unit, modifier: Modifier = Modifier) {
    val station = stations.firstOrNull { it.id == status.stationId }
    Column(
        modifier
            .fillMaxWidth()
            .background(Night.copy(alpha = 0.85f))
            .padding(top = 14.dp, bottom = 16.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            station?.let { "${it.emoji}  ${it.name}" } ?: "Kein Sender gewählt",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status.nowPlaying ?: " ",
            fontSize = 14.sp,
            color = Mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        val active = status.phase != PlayerPhase.IDLE && status.phase != PlayerPhase.PAUSED && status.phase != PlayerPhase.ERROR
        PlayButton(playing = active, live = status.phase == PlayerPhase.PLAYING, onClick = onPlayPause)
        Spacer(Modifier.height(8.dp))
        BufferBar(status)
        Spacer(Modifier.height(6.dp))
        Text(statusText(status, station), fontSize = 13.sp, color = statusColor(status.phase))
    }
}

private fun statusText(status: PlaybackStatus, station: Station?): String {
    val secs = status.bufferedMs / 1000
    return when (status.phase) {
        PlayerPhase.IDLE -> if (station != null) "Tippe zum Starten" else "Tippe auf einen Sender"
        PlayerPhase.CONNECTING -> "Verbinde und fülle Puffer …"
        PlayerPhase.BUFFERING -> "Puffer wird aufgefüllt … $secs s"
        PlayerPhase.PLAYING -> "Live · Puffer $secs s"
        PlayerPhase.PAUSED -> "Pausiert"
        PlayerPhase.RECONNECTING -> "Verbindung unterbrochen – verbinde neu …"
        PlayerPhase.WAITING_FOR_NETWORK -> "Kein Netz – geht automatisch weiter"
        PlayerPhase.ERROR -> "Sender nicht erreichbar – tippe für neuen Versuch"
    }
}

private fun statusColor(phase: PlayerPhase): Color = when (phase) {
    PlayerPhase.RECONNECTING, PlayerPhase.WAITING_FOR_NETWORK, PlayerPhase.ERROR -> Coral
    else -> TextSoft
}

/** Zeigt, wie viel Audio im Voraus geladen ist (voll = 30 s). */
@Composable
private fun BufferBar(status: PlaybackStatus) {
    val target = (status.bufferedMs / 30_000f).coerceIn(0f, 1f)
    val fill by animateFloatAsState(target, tween(800), label = "buffer")
    Canvas(
        Modifier
            .width(160.dp)
            .height(4.dp),
    ) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = r)
        if (fill > 0f) drawRoundRect(Mist, size = Size(size.width * fill, size.height), cornerRadius = r)
    }
}

@Composable
internal fun PlayButton(playing: Boolean, live: Boolean, onClick: () -> Unit) {
    val wave = rememberInfiniteTransition(label = "wave")
    // Funkwellen, die vom Knopf ausgehen, solange live gespielt wird
    val pulse by wave.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200)),
        label = "pulse",
    )
    Box(
        Modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            if (live) {
                drawCircle(Mist.copy(alpha = 0.28f * (1f - pulse)), radius = r * (0.70f + 0.30f * pulse), style = Stroke(width = 3.dp.toPx()))
                val p2 = (pulse + 0.5f) % 1f
                drawCircle(Mist.copy(alpha = 0.28f * (1f - p2)), radius = r * (0.70f + 0.30f * p2), style = Stroke(width = 3.dp.toPx()))
            } else if (playing) {
                drawCircle(Mist.copy(alpha = 0.10f + 0.10f * pulse), radius = r * 0.86f)
            }
            drawCircle(Sand, radius = r * 0.68f)
            drawCircle(Sand.copy(alpha = 0.4f), radius = r * 0.78f, style = Stroke(width = 2.dp.toPx()))
            val c = center
            if (playing) {
                val w = r * 0.12f
                val h = r * 0.46f
                drawRoundRect(
                    Night, topLeft = Offset(c.x - w * 1.9f, c.y - h / 2),
                    size = Size(w * 1.2f, h),
                    cornerRadius = CornerRadius(w / 2),
                )
                drawRoundRect(
                    Night, topLeft = Offset(c.x + w * 0.7f, c.y - h / 2),
                    size = Size(w * 1.2f, h),
                    cornerRadius = CornerRadius(w / 2),
                )
            } else {
                val s = r * 0.42f
                val path = Path().apply {
                    moveTo(c.x - s * 0.45f, c.y - s * 0.6f)
                    lineTo(c.x + s * 0.65f, c.y)
                    lineTo(c.x - s * 0.45f, c.y + s * 0.6f)
                    close()
                }
                drawPath(path, Night)
            }
        }
    }
}
