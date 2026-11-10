package io.github.haigerman.hyperradio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.hyperradio.R
import io.github.haigerman.hyperradio.spotify.SpotifyArtist
import io.github.haigerman.hyperradio.spotify.SpotifyRepository
import io.github.haigerman.hyperradio.spotify.SpotifyState
import kotlinx.coroutines.delay
import java.text.Normalizer

@Composable
fun SpotifyScreen(onBack: () -> Unit, onStartMix: () -> Unit) {
    val state by SpotifyRepository.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    BackHandler(onBack = onBack)

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn && state.library.isEmpty()) SpotifyRepository.loadLibrary()
    }
    // laufenden Titel aktuell halten, solange der Bildschirm offen ist
    LaunchedEffect(state.loggedIn) {
        while (state.loggedIn) {
            SpotifyRepository.refreshPlayback()
            delay(5000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, DeepTeal))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(state, onBack, Modifier.statusBarsPadding())
            OutlinedTextField(
                value = state.query,
                onValueChange = SpotifyRepository::setQuery,
                placeholder = { Text("Künstler suchen …") },
                singleLine = true,
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        Text(
                            "✕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { SpotifyRepository.setQuery("") }
                                .padding(8.dp),
                        )
                    }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Sand,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    cursorColor = Sand,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.query.isNotBlank()) {
                    if (state.suggestions.isEmpty()) {
                        item { Hint(if (state.searching) "Suche …" else "Keine Künstler gefunden") }
                    }
                    items(state.suggestions, key = { "s-" + it.id }) { a ->
                        ArtistRow(a, selected = state.selected.any { it.id == a.id }, onClick = {
                            SpotifyRepository.toggleArtist(a)
                            SpotifyRepository.setQuery("")
                            focus.clearFocus()
                        })
                    }
                } else {
                    item { Label("Deine Auswahl (${state.selected.size})") }
                    if (state.selected.isEmpty()) {
                        item { Hint("Noch keine Künstler – suche oben oder wähle unten aus deiner Bibliothek.") }
                    }
                    items(state.selected, key = { "sel-" + it.id }) { a ->
                        ArtistRow(a, selected = true, onClick = { SpotifyRepository.toggleArtist(a) })
                    }
                    item { Label("Aus deiner Bibliothek · A–Z") }
                    if (state.library.isEmpty()) {
                        item { Hint(if (state.libraryLoading) "Lade deine Künstler …" else "Keine gefolgten oder oft gehörten Künstler gefunden") }
                    }
                    val groups = state.library.groupBy { letterOf(it.name) }.toSortedMap(compareBy { if (it == "#") "~" else it })
                    groups.forEach { (letter, artists) ->
                        item(key = "l-$letter") {
                            Text(letter, fontSize = 15.sp, color = Sand, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
                        }
                        items(artists, key = { "lib-" + it.id }) { a ->
                            ArtistRow(a, selected = state.selected.any { it.id == a.id }, onClick = { SpotifyRepository.toggleArtist(a) })
                        }
                    }
                }
            }
            BottomPanel(state, onStartMix, Modifier.navigationBarsPadding().imePadding())
        }
    }
}

private fun letterOf(name: String): String {
    val c = Normalizer.normalize(name.trim().removePrefix("The ").ifEmpty { name }, Normalizer.Form.NFD)
        .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: return "#"
    return if (c in 'A'..'Z') c.toString() else "#"
}

@Composable
private fun Header(state: SpotifyState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Image(painterResource(R.drawable.ic_spotify), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Spotify-Mix", fontSize = 26.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
            Text(
                state.userName?.let { "Angemeldet als $it" } ?: "Zufallswiedergabe aus deinen Künstlern",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = {
            SpotifyRepository.logout()
            onBack()
        }) { Text("Abmelden", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 14.sp, color = TextSoft.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun ArtistRow(artist: SpotifyArtist, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) CardOn else Card.copy(alpha = 0.7f), label = "bg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (selected) SpotifyGreen.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(letterOf(artist.name), fontSize = 16.sp, color = if (selected) SpotifyGreen else TextSoft)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(artist.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val info = artist.genres.take(2).joinToString(" · ")
                if (info.isNotEmpty()) {
                    Text(info, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(
                if (selected) "✓" else "＋",
                fontSize = 18.sp,
                color = if (selected) SpotifyGreen else Sand,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun BottomPanel(state: SpotifyState, onStartMix: () -> Unit, modifier: Modifier = Modifier) {
    val pb = state.playback
    val playing = state.mixActive && pb?.isPlaying == true
    Column(
        modifier
            .fillMaxWidth()
            .background(Night.copy(alpha = 0.85f))
            .padding(top = 14.dp, bottom = 16.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state.mixActive && pb?.track != null) pb.track else "Spotify-Mix",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (state.mixActive && pb?.artists != null) pb.artists else "Zufällige Titel deiner ${state.selected.size} Künstler",
            fontSize = 14.sp,
            color = if (state.mixActive && pb != null) SpotifyGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RoundTextButton("🔀", enabled = state.selected.isNotEmpty() && state.busy == null, onClick = onStartMix)
            PlayButton(
                playing = playing,
                live = playing,
                onClick = { if (state.mixActive && pb != null) SpotifyRepository.togglePause() else onStartMix() },
            )
            RoundTextButton("⏭", enabled = state.mixActive && pb != null, onClick = SpotifyRepository::skip)
        }
        Spacer(Modifier.height(8.dp))
        val status = when {
            state.message != null -> state.message
            state.busy != null -> state.busy
            playing -> "Läuft in Spotify${pb?.device?.let { " · $it" } ?: ""}"
            state.mixActive && pb != null -> "Pausiert"
            state.selected.isEmpty() -> "Wähle Künstler aus"
            else -> "Tippe zum Starten · 🔀 mischt neu"
        }
        Text(
            status,
            fontSize = 13.sp,
            color = if (state.message != null) Coral else TextSoft,
            modifier = Modifier.clickable(enabled = state.message != null) { SpotifyRepository.dismissMessage() },
        )
    }
}

@Composable
private fun RoundTextButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 20.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSoft.copy(alpha = 0.4f))
    }
}
