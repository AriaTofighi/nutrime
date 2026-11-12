package com.nutrime.app.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Target
import com.nutrime.app.ui.components.NutrientBreakdown
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hold-and-drag reordering for rows of a LazyColumn. Rows move as the dragged one passes their middle;
 * [onDrop] saves the new order once the finger lifts.
 */
class ReorderState(
    private val list: LazyListState,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: () -> Unit,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    private var initialOffset = 0
    private var delta by mutableFloatStateOf(0f)

    fun start(key: Any) {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        dragging = key
        initialOffset = info.offset
        delta = 0f
    }

    fun drag(dy: Float, movable: Set<Any>) {
        val key = dragging ?: return
        delta += dy
        val layout = list.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val middle = initialOffset + delta + info.size / 2f
        layout.visibleItemsInfo.firstOrNull { it.key != key && it.key in movable && middle.roundToInt() in it.offset..(it.offset + it.size) }
            ?.let { onMove(key, it.key) }
        // Scroll a little when the row reaches the top or bottom edge.
        val edge = 64f
        if (middle > layout.viewportEndOffset - edge) list.dispatchRawDelta(14f)
        else if (middle < layout.viewportStartOffset + edge) list.dispatchRawDelta(-14f)
    }

    /** How far to draw [key] from its laid-out position: it follows the finger while the others move around it. */
    fun offsetOf(key: Any): Float {
        if (key != dragging) return 0f
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return initialOffset + delta - info.offset
    }

    fun end() {
        if (dragging == null) return
        dragging = null
        delta = 0f
        onDrop()
    }
}

private val REVEAL = 64.dp

/**
 * A diary row. Tap opens it; drag it right to show a ⋮ button for more actions; swipe it left to
 * delete; hold it, then drag, to move it up or down the day.
 */
@Composable
fun DiaryEntryRow(
    key: Any,
    revealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    onDelete: () -> Unit,
    reorder: ReorderState,
    movable: Set<Any>,
    content: @Composable () -> Unit,
) {
    val c = NutriTheme.colors
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val revealPx = with(density) { REVEAL.toPx() }
    val offset = remember(key) { Animatable(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val currentRevealed by rememberUpdatedState(revealed)
    val click by rememberUpdatedState(onClick)
    val delete by rememberUpdatedState(onDelete)
    val reveal by rememberUpdatedState(onRevealChange)
    val movableNow by rememberUpdatedState(movable)

    // Another row opening (or an action finishing) closes this one.
    LaunchedEffect(revealed) { if (!revealed && offset.targetValue != 0f) offset.animateTo(0f, tween(180)) }

    Box(Modifier.fillMaxWidth()) {
        // Behind the row: ⋮ on the left (revealed by dragging right), delete on the right.
        Row(Modifier.matchParentSize()) {
            if (offset.value > 0f) {
                Box(
                    Modifier.width(REVEAL).fillMaxHeight().background(c.bgSunken).clickable { onMenu() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Lucide.EllipsisVertical, "More actions", tint = c.text, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.weight(1f))
            if (offset.value < 0f) {
                Box(Modifier.fillMaxHeight().width(with(density) { (-offset.value).toDp() }).background(c.danger), contentAlignment = Alignment.CenterEnd) {
                    Icon(Lucide.Trash2, "Delete", tint = Color.White, modifier = Modifier.padding(end = Space.S5).size(18.dp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .background(if (reorder.dragging == key) c.surfaceRaised else c.surface)
                .pointerInput(key) {
                    width = size.width.toFloat()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var total = androidx.compose.ui.geometry.Offset.Zero
                        // Decide what the gesture is: a tap, a sideways drag, a scroll, or (after a hold) a reorder.
                        val decided = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull "cancel"
                                if (ch.changedToUp()) return@withTimeoutOrNull if (ch.isConsumed) "cancel" else "tap"
                                total += ch.positionChange()
                                if (abs(total.x) > slop && abs(total.x) > abs(total.y)) return@withTimeoutOrNull "swipe"
                                if (abs(total.y) > slop || ch.isConsumed) return@withTimeoutOrNull "cancel"
                            }
                            @Suppress("UNREACHABLE_CODE") "cancel"
                        }
                        when (decided) {
                            "tap" -> if (currentRevealed) reveal(false) else click()
                            "swipe" -> {
                                if (!currentRevealed) reveal(false)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUp()) break
                                    val dx = ch.positionChange().x
                                    ch.consume()
                                    scope.launch { offset.snapTo((offset.value + dx).coerceIn(-width, revealPx * 1.4f)) }
                                }
                                scope.launch {
                                    when {
                                        offset.value < -width * 0.35f -> {
                                            offset.animateTo(-width, tween(160))
                                            delete()
                                        }
                                        offset.value > revealPx / 2 -> {
                                            offset.animateTo(revealPx, tween(180))
                                            reveal(true)
                                        }
                                        else -> {
                                            offset.animateTo(0f, tween(180))
                                            if (currentRevealed) reveal(false)
                                        }
                                    }
                                }
                            }
                            null -> {
                                // Held without moving: pick the row up and move it with the finger.
                                if (currentRevealed) reveal(false)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reorder.start(key)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (ch.changedToUp()) break
                                    reorder.drag(ch.positionChange().y, movableNow)
                                    ch.consume()
                                }
                                reorder.end()
                            }
                            else -> Unit
                        }
                    }
                },
        ) { content() }
    }
}

/** One action in the entry sheet. */
data class EntryAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/** The ⋮ sheet: things you can do with one diary entry, as big rows like the + sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryActionsSheet(title: String, subtitle: String, actions: List<EntryAction>, onDismiss: () -> Unit) {
    val c = NutriTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.borderStrong)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.S3)) {
            Column(Modifier.padding(horizontal = Space.S5, vertical = Space.S2)) {
                Text(title, style = NutriTheme.type.cardTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = NutriTheme.type.small, color = c.textSecondary)
            }
            actions.forEach { a ->
                val tint = if (a.danger) c.danger else c.text
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clickable(onClick = a.onClick).padding(horizontal = Space.S5),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(a.icon, null, tint = if (a.danger) c.danger else c.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Space.S4))
                    Text(a.label, style = NutriTheme.type.bodyMedium, color = tint)
                }
            }
        }
    }
}

/** All nutrients of one entry, with bars against the day's targets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryNutritionSheet(title: String, subtitle: String, nutrients: NutrientMap, targets: Map<Int, Target>, onDismiss: () -> Unit) {
    val c = NutriTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.S4).padding(bottom = Space.S4),
            verticalArrangement = Arrangement.spacedBy(Space.S3),
        ) {
            Column(Modifier.padding(horizontal = Space.S1)) {
                Text(title, style = NutriTheme.type.cardTitle, color = c.text)
                Text(subtitle, style = NutriTheme.type.small, color = c.textSecondary)
            }
            com.nutrime.app.ui.components.MacroTiles(nutrients)
            NutrientBreakdown(nutrients, targets, mode = com.nutrime.app.ui.components.BreakdownMode.Food, hPad = 0.dp)
        }
    }
}
