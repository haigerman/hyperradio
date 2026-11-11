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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import io.github.haigerman.hyperradio.data.Category
import io.github.haigerman.hyperradio.data.PlaybackStatus
import io.github.haigerman.hyperradio.data.PlayerPhase
import io.github.haigerman.hyperradio.data.RadioRepository
import io.github.haigerman.hyperradio.data.Station
import io.github.haigerman.hyperradio.data.isValidStreamUrl

@Composable
fun RadioScreen(onStationClick: (String) -> Unit, onPlayPause: () -> Unit, onSpotifyClick: () -> Unit) {
    val stations by RadioRepository.stations.collectAsStateWithLifecycle()
    val categories by RadioRepository.categories.collectAsStateWithLifecycle()
    val collapsed by RadioRepository.collapsed.collectAsStateWithLifecycle()
    val hiddenCount by RadioRepository.hiddenCount.collectAsStateWithLifecycle()
    val status by RadioRepository.status.collectAsStateWithLifecycle()

    // Dialoge und Menüs
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var stationMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingCategoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    /** Sender, der nach dem Anlegen einer neuen Kategorie in diese wandert */
    var moveIntoNewCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(text: String, undo: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(text, actionLabel = if (undo != null) "Rückgängig" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) undo?.invoke()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, DeepTeal))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(Modifier.statusBarsPadding())
            // lokale Kopie, damit die Karten beim Ziehen sofort mitwandern; gespeichert wird beim Loslassen
            var ordered by remember(stations) { mutableStateOf(stations) }
            val rows = buildRows(ordered, categories, collapsed, hiddenCount)
            val listState = rememberLazyListState()
            val reorder = rememberReorderState(
                listState = listState,
                canMove = { rows.getOrNull(it) is ListRow.StationRow },
                // per Ziehen innerhalb der Kategorie; in andere Kategorien über das ⋮-Menü
                canDrop = { from, to ->
                    val a = rows.getOrNull(from) as? ListRow.StationRow
                    val b = rows.getOrNull(to) as? ListRow.StationRow
                    a != null && b != null && a.station.categoryId == b.station.categoryId
                },
                onMove = { from, to ->
                    val a = (rows[from] as ListRow.StationRow).station
                    val b = (rows[to] as ListRow.StationRow).station
                    ordered = ordered.toMutableList().apply { add(indexOf(b), removeAt(indexOf(a))) }
                },
            )
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .reorderable(reorder, onDragEnd = { RadioRepository.setOrder(ordered.map { it.id }) }),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                        when (row) {
                            is ListRow.Header -> CategoryHeader(
                                row = row,
                                playing = status.phase != PlayerPhase.IDLE &&
                                    ordered.any { it.id == status.stationId && it.categoryId == row.category.id },
                                onClick = { RadioRepository.toggleCategory(row.category.id) },
                                onMenu = { categoryMenuId = row.category.id },
                                modifier = Modifier.animateItem(),
                            )
                            is ListRow.StationRow -> {
                                val s = row.station
                                val dragging = reorder.draggingIndex == index
                                val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "lift")
                                StationCard(
                                    s,
                                    status,
                                    onClick = { if (!reorder.clicksSuppressed) onStationClick(s.id) },
                                    onMenu = { if (!reorder.clicksSuppressed) stationMenuId = s.id },
                                    modifier = (if (dragging) Modifier else Modifier.animateItem())
                                        .zIndex(if (dragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (dragging) reorder.draggingOffset else 0f
                                            scaleX = 1f + 0.03f * lift
                                            scaleY = 1f + 0.03f * lift
                                            shadowElevation = 16.dp.toPx() * lift
                                            shape = RoundedCornerShape(20.dp)
                                            clip = false
                                        },
                                    lifted = lift,
                                )
                            }
                            is ListRow.Empty -> EmptyCategoryHint(Modifier.animateItem())
                            ListRow.Spotify -> SpotifyCard(onClick = onSpotifyClick, modifier = Modifier.animateItem())
                            ListRow.Add -> AddButtons(onAddStation = { adding = true }, onAddCategory = { creatingCategory = true })
                            is ListRow.Restore -> RestoreRow(row.count, onClick = {
                                RadioRepository.restoreHiddenPresets()
                                notify("Eingebaute Sender wiederhergestellt")
                            })
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp)) { data ->
                    Snackbar(
                        data,
                        containerColor = CardOn,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = Sand,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
            BottomPanel(stations, status, onPlayPause, Modifier.navigationBarsPadding())
        }
    }

    // ---------------------------------------------------------------- Menüs & Dialoge

    RadioRepository.find(stationMenuId)?.let { station ->
        StationMenuSheet(
            station = station,
            categories = categories,
            onDismiss = { stationMenuId = null },
            onMoveTo = { cat ->
                RadioRepository.setStationCategory(station.id, cat.id)
                RadioRepository.expand(cat.id)
                stationMenuId = null
                notify("„${station.name}“ → ${cat.emoji} ${cat.title}")
            },
            onNewCategory = {
                moveIntoNewCategory = station.id
                creatingCategory = true
                stationMenuId = null
            },
            onEdit = if (station.custom) ({ editingId = station.id; stationMenuId = null }) else null,
            onDelete = {
                stationMenuId = null
                RadioRepository.deleteStation(station.id)
                notify("„${station.name}“ gelöscht") { RadioRepository.undoDelete() }
            },
        )
    }

    categories.firstOrNull { it.id == categoryMenuId }?.let { cat ->
        val index = categories.indexOf(cat)
        CategoryMenuSheet(
            category = cat,
            count = stations.count { it.categoryId == cat.id },
            canMoveUp = index > 0,
            canMoveDown = index < categories.lastIndex,
            onDismiss = { categoryMenuId = null },
            onEdit = { editingCategoryId = cat.id; categoryMenuId = null },
            onMove = { delta -> RadioRepository.moveCategory(cat.id, delta) },
            onDelete = {
                categoryMenuId = null
                RadioRepository.deleteCategory(cat.id)
                notify("Kategorie „${cat.title}“ gelöscht")
            },
        )
    }

    if (creatingCategory) {
        CategoryDialog(
            title = "Neue Kategorie",
            initialTitle = "",
            initialEmoji = Category.emojis.firstOrNull { e -> categories.none { it.emoji == e } } ?: "⭐",
            confirmLabel = "Anlegen",
            onConfirm = { title, emoji ->
                val cat = RadioRepository.addCategory(title, emoji)
                moveIntoNewCategory?.let { id ->
                    RadioRepository.setStationCategory(id, cat.id)
                    RadioRepository.find(id)?.let { notify("„${it.name}“ → ${cat.emoji} ${cat.title}") }
                }
                moveIntoNewCategory = null
                creatingCategory = false
            },
            onDismiss = {
                moveIntoNewCategory = null
                creatingCategory = false
            },
        )
    }

    categories.firstOrNull { it.id == editingCategoryId }?.let { cat ->
        CategoryDialog(
            title = "Kategorie bearbeiten",
            initialTitle = cat.title,
            initialEmoji = cat.emoji,
            confirmLabel = "Speichern",
            onConfirm = { title, emoji ->
                RadioRepository.updateCategory(cat.id, title, emoji)
                editingCategoryId = null
            },
            onDismiss = { editingCategoryId = null },
        )
    }

    if (adding) {
        StationDialog(
            title = "Sender hinzufügen",
            initialName = "",
            initialUrl = "",
            confirmLabel = "Hinzufügen",
            categories = categories,
            initialCategoryId = Category.OTHER_ID,
            onConfirm = { name, url, categoryId ->
                RadioRepository.addCustom(name, url, categoryId)
                // Kategorie aufklappen, damit man den neuen Sender sieht
                RadioRepository.expand(categoryId)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    RadioRepository.find(editingId)?.let { editing ->
        StationDialog(
            title = "Sender bearbeiten",
            initialName = editing.name,
            initialUrl = editing.urls.first(),
            confirmLabel = "Speichern",
            categories = categories,
            initialCategoryId = editing.categoryId,
            onConfirm = { name, url, categoryId ->
                RadioRepository.updateCustom(editing.id, name, url, categoryId)
                RadioRepository.expand(categoryId)
                editingId = null
            },
            onDismiss = { editingId = null },
        )
    }
}

/** Zeilen der Senderliste. */
private sealed interface ListRow {
    val key: String

    data class Header(val category: Category, val count: Int, val expanded: Boolean) : ListRow {
        override val key = "head-" + category.id
    }

    data class StationRow(val station: Station) : ListRow {
        override val key = station.id
    }

    /** Hinweis in einer leeren, aufgeklappten Kategorie */
    data class Empty(val categoryId: String) : ListRow {
        override val key = "empty-$categoryId"
    }

    data object Spotify : ListRow {
        override val key = "spotify"
    }

    data object Add : ListRow {
        override val key = "add"
    }

    data class Restore(val count: Int) : ListRow {
        override val key = "restore"
    }
}

private fun buildRows(stations: List<Station>, categories: List<Category>, collapsed: Set<String>, hiddenCount: Int): List<ListRow> =
    buildList {
        categories.forEach { cat ->
            val members = stations.filter { it.categoryId == cat.id }
            val withSpotify = cat.isFallback
            val expanded = cat.id !in collapsed
            add(ListRow.Header(cat, members.size + if (withSpotify) 1 else 0, expanded))
            if (expanded) {
                members.forEach { add(ListRow.StationRow(it)) }
                if (withSpotify) add(ListRow.Spotify)
                if (members.isEmpty() && !withSpotify) add(ListRow.Empty(cat.id))
            }
        }
        add(ListRow.Add)
        if (hiddenCount > 0) add(ListRow.Restore(hiddenCount))
    }

/** Kopf einer Kategorie – Antippen klappt sie auf oder zu, ⋮ öffnet das Kategorie-Menü. */
@Composable
private fun CategoryHeader(row: ListRow.Header, playing: Boolean, onClick: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val arrow by animateFloatAsState(if (row.expanded) 0f else -90f, label = "arrow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            "▾",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { rotationZ = arrow },
        )
        Spacer(Modifier.width(8.dp))
        Text(row.category.emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            row.category.title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (playing) Sand else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 1.dp),
        ) {
            Text("${row.count}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (playing && !row.expanded) Equalizer(active = true, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.weight(1f))
        MenuButton(onMenu, "Kategorie-Optionen")
    }
}

/** Runder ⋮-Knopf für Menüs. */
@Composable
private fun MenuButton(onClick: () -> Unit, description: String) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("⋮", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyCategoryHint(modifier: Modifier = Modifier) {
    Text(
        "Noch leer – über ⋮ bei einem Sender hierher verschieben oder einen Sender hinzufügen",
        fontSize = 13.sp,
        color = TextSoft.copy(alpha = 0.7f),
        modifier = modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RestoreRow(count: Int, onClick: () -> Unit) {
    Text(
        "Gelöschte Sender wiederherstellen ($count)",
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
        Text("HyperRadio", fontSize = 30.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Sender lange drücken zum Verschieben",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StationCard(
    station: Station,
    status: PlaybackStatus,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    /** 0 = normal, 1 = beim Verschieben angehoben */
    lifted: Float = 0f,
) {
    val selected = status.stationId == station.id && status.phase != PlayerPhase.IDLE
    val base by animateColorAsState(if (selected) CardOn else Card.copy(alpha = 0.7f), label = "bg")
    val bg = lerp(base, CardOn, lifted)
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        border = if (lifted > 0f) BorderStroke(1.dp, Sand.copy(alpha = 0.5f * lifted)) else null,
        modifier = modifier
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
            MenuButton(onMenu, "Optionen für ${station.name}")
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
private fun SpotifyCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
        modifier = modifier
            .fillMaxWidth()
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

/** Am Listenende: neuen Sender oder neue Kategorie anlegen. */
@Composable
private fun AddButtons(onAddStation: () -> Unit, onAddCategory: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlineAction("＋  Sender", onAddStation, Modifier.weight(1f))
        OutlineAction("＋  Kategorie", onAddCategory, Modifier.weight(1f))
    }
}

@Composable
private fun OutlineAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Sand.copy(alpha = 0.35f)),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Text(label, fontSize = 16.sp, color = Sand, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun StationDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    confirmLabel: String,
    categories: List<Category>,
    initialCategoryId: String,
    onConfirm: (name: String, url: String, categoryId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var categoryId by rememberSaveable { mutableStateOf(initialCategoryId) }
    var tried by remember { mutableStateOf(false) }
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
                Text("Kategorie", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CategoryChips(categories, selectedId = categoryId, onSelect = { categoryId = it.id })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (nameOk && urlOk) onConfirm(name, url, categoryId)
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

// ---------------------------------------------------------------- Menüs und Dialoge

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    categories: List<Category>,
    selectedId: String?,
    onSelect: (Category) -> Unit,
    onNew: (() -> Unit)? = null,
) {
    val colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = Sand.copy(alpha = 0.25f),
        selectedLabelColor = Sand,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { c ->
            FilterChip(
                selected = c.id == selectedId,
                onClick = { onSelect(c) },
                label = { Text("${c.emoji} ${c.title}") },
                colors = colors,
            )
        }
        if (onNew != null) {
            FilterChip(selected = false, onClick = onNew, label = { Text("＋ Neue Kategorie", color = Sand) }, colors = colors)
        }
    }
}

/** Menü eines Senders (⋮): Kategorie wechseln, bearbeiten, löschen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationMenuSheet(
    station: Station,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onMoveTo: (Category) -> Unit,
    onNewCategory: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) { Text(station.emoji, fontSize = 22.sp) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(station.name, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(station.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Kategorie", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            CategoryChips(categories, selectedId = station.categoryId, onSelect = { if (it.id != station.categoryId) onMoveTo(it) }, onNew = onNewCategory)
            Spacer(Modifier.height(12.dp))
            if (onEdit != null) SheetAction("✎", "Name oder Adresse bearbeiten", onClick = onEdit)
            SheetAction("🗑", "Sender löschen", color = Coral, onClick = onDelete)
            if (!station.custom) {
                Text(
                    "Eingebaute Sender lassen sich jederzeit wiederherstellen (am Ende der Liste).",
                    fontSize = 12.sp,
                    color = TextSoft.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/** Menü einer Kategorie (⋮): umbenennen, verschieben, löschen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryMenuSheet(
    category: Category,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(category.title, fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (count == 1) "1 Sender" else "$count Sender",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetAction("✎", "Umbenennen / Symbol ändern", onClick = onEdit)
            SheetAction("▲", "Nach oben", enabled = canMoveUp, onClick = { onMove(-1) })
            SheetAction("▼", "Nach unten", enabled = canMoveDown, onClick = { onMove(1) })
            if (category.isFallback) {
                Text(
                    "„${category.title}“ sammelt die Sender gelöschter Kategorien und kann deshalb nicht gelöscht werden.",
                    fontSize = 12.sp,
                    color = TextSoft.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            } else {
                SheetAction(
                    "🗑",
                    when {
                        !confirmDelete -> "Kategorie löschen"
                        count > 0 -> "Wirklich löschen? Die Sender kommen nach „Übrige“"
                        else -> "Wirklich löschen?"
                    },
                    color = Coral,
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                )
            }
        }
    }
}

@Composable
private fun SheetAction(icon: String, label: String, color: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 14.dp),
    ) {
        Text(icon, fontSize = 18.sp, color = color.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.width(32.dp))
        Text(label, fontSize = 16.sp, color = color.copy(alpha = if (enabled) 1f else 0.3f))
    }
}

/** Kategorie anlegen oder bearbeiten: Name und Symbol. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryDialog(
    title: String,
    initialTitle: String,
    initialEmoji: String,
    confirmLabel: String,
    onConfirm: (title: String, emoji: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialTitle) }
    var emoji by rememberSaveable { mutableStateOf(initialEmoji) }
    var tried by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(title, fontWeight = FontWeight.Light) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("Name") },
                    placeholder = { Text("z. B. Chillen, Sport, Auto") },
                    singleLine = true,
                    isError = tried && name.isBlank(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Sand, focusedLabelColor = Sand, cursorColor = Sand),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Symbol", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Category.emojis.forEach { e ->
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (e == emoji) Sand.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f))
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                tried = true
                if (name.isNotBlank()) onConfirm(name, emoji)
            }) { Text(confirmLabel, color = Sand) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}
