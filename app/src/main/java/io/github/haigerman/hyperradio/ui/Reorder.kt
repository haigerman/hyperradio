package io.github.haigerman.hyperradio.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sortieren einer LazyColumn per langem Drücken und Ziehen. Verschoben werden nur Einträge,
 * für die [canMove] true liefert (die Sender – nicht Spotify- oder Hinzufügen-Karte).
 */
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canMove: (Int) -> Boolean,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set
    private var dragDistance by mutableFloatStateOf(0f)
    private var startOffset by mutableIntStateOf(0)
    private var suppressClicksUntil = 0L

    /** Verschiebung des gezogenen Eintrags gegenüber seinem Platz in der Liste (für graphicsLayer). */
    val draggingOffset: Float
        get() {
            val item = draggingIndex?.let(::info) ?: return 0f
            return startOffset + dragDistance - item.offset
        }

    /** Nach dem Ziehen löst das Loslassen keinen Klick (= Sender starten) aus. */
    val clicksSuppressed: Boolean get() = SystemClock.uptimeMillis() < suppressClicksUntil

    private fun info(index: Int): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    internal fun start(y: Float): Boolean {
        // Fingerposition (relativ zur Liste) in Item-Koordinaten umrechnen (Innenabstand oben)
        val pos = y.toInt() + listState.layoutInfo.viewportStartOffset
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { pos in it.offset..(it.offset + it.size) } ?: return false
        if (!canMove(item.index)) return false
        draggingIndex = item.index
        startOffset = item.offset
        dragDistance = 0f
        suppressClicksUntil = Long.MAX_VALUE
        return true
    }

    internal fun drag(dy: Float) {
        val index = draggingIndex ?: return
        dragDistance += dy
        val current = info(index) ?: return
        val top = startOffset + dragDistance
        val middle = top + current.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index != index && middle.toInt() in it.offset..(it.offset + it.size) && canMove(it.index)
        }
        if (target != null) {
            // Wird der oberste sichtbare Eintrag verschoben, würde die Liste mitspringen – Position halten
            if (index == listState.firstVisibleItemIndex || target.index == listState.firstVisibleItemIndex) {
                val first = listState.firstVisibleItemIndex
                val off = listState.firstVisibleItemScrollOffset
                scope.launch { listState.scrollToItem(first, off) }
            }
            onMove(index, target.index)
            draggingIndex = target.index
        } else {
            // am Rand automatisch weiterscrollen
            val info = listState.layoutInfo
            val over = when {
                top + current.size > info.viewportEndOffset -> top + current.size - info.viewportEndOffset
                top < info.viewportStartOffset -> top - info.viewportStartOffset
                else -> 0f
            }
            if (over != 0f) scope.launch { listState.scrollBy(over * 0.5f) }
        }
    }

    internal fun end() {
        draggingIndex = null
        dragDistance = 0f
        suppressClicksUntil = SystemClock.uptimeMillis() + 300
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    canMove: (Int) -> Boolean,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val currentCanMove by rememberUpdatedState(canMove)
    val currentOnMove by rememberUpdatedState(onMove)
    return remember(listState) {
        ReorderState(listState, scope, { currentCanMove(it) }, { a, b -> currentOnMove(a, b) })
    }
}

/** Gesten für die ganze Liste: langes Drücken auf einen Eintrag hebt ihn an, Ziehen verschiebt ihn. */
@Composable
fun Modifier.reorderable(state: ReorderState, onDragEnd: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    return this.pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset ->
                if (state.start(offset.y)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDrag = { change, amount ->
                if (state.draggingIndex != null) {
                    change.consume()
                    state.drag(amount.y)
                }
            },
            onDragEnd = {
                val moved = state.draggingIndex != null
                state.end()
                if (moved) currentOnDragEnd()
            },
            onDragCancel = {
                val moved = state.draggingIndex != null
                state.end()
                if (moved) currentOnDragEnd()
            },
        )
    }
}
