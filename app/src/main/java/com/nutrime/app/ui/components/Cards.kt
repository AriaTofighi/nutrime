package com.nutrime.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Target
import com.nutrime.app.ui.theme.DesignTokens.Motion
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme

/*
 * The calm layout: screens are a column of rounded cards on a sunken canvas. Each card holds one
 * idea (a label, one large value, one status and at most one graphic). Details open in a sheet or
 * on their own page.
 */

/** Widest a column of cards gets on tablets and in landscape. */
val ContentMaxWidth = 560.dp

/** Canvas color behind the cards. */
val canvas: Color @Composable get() = NutriTheme.colors.bgSunken

/** A tab or detail page: a lazy column of cards on the canvas, centred and width-limited on large screens. */
@Composable
fun Page(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    bottom: androidx.compose.ui.unit.Dp = Space.S6,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(canvas), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = ContentMaxWidth),
            state = state,
            contentPadding = PaddingValues(top = Space.S1, bottom = bottom),
            content = content,
        )
    }
}

/** Large title at the top of a tab, with optional controls on the right. */
@Composable
fun PageTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(start = Space.S5, end = Space.S4, top = Space.S2, bottom = Space.S1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = NutriTheme.type.pageTitle, color = NutriTheme.colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.S2), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Small uppercase label above a group of cards. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = NutriTheme.type.label, color = NutriTheme.colors.textTertiary,
        modifier = modifier.padding(start = Space.S5, end = Space.S5, top = Space.S4, bottom = Space.S1),
    )
}

/** One rounded card on the canvas. The outer padding keeps 12 dp between stacked cards. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(Space.S4),
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.S4, vertical = 6.dp)
            .clip(RoundedCornerShape(Radius.Xl2))
            .background(NutriTheme.colors.surface)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Card title row: title on the left, quiet meta text or a chevron on the right. */
@Composable
fun CardTitle(title: String, meta: String? = null, chevron: Boolean = false, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = NutriTheme.type.cardTitle.copy(fontSize = NutriTheme.type.title.fontSize), color = c.text, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = NutriTheme.type.small, color = c.textTertiary)
        if (chevron) Icon(Lucide.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.padding(start = 2.dp).size(18.dp))
    }
}

enum class Tone { Neutral, Good, Warn, Bad }

/** A short plain-language status ("526 under target") in a soft pill. */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    val (bg, fg) = when (tone) {
        Tone.Neutral -> c.track to c.text
        Tone.Good -> c.accentSoft to c.accent
        Tone.Warn -> c.warning.copy(alpha = 0.16f) to c.warning
        Tone.Bad -> c.dangerSoft to c.danger
    }
    Text(
        text, style = NutriTheme.type.small.copy(fontWeight = NutriTheme.type.cardTitle.fontWeight), color = fg, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(Radius.Full)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The one answer a card gives: a label, a large number with its unit, and an optional status pill. */
@Composable
fun Hero(label: String, value: String, unit: String?, status: String? = null, tone: Tone = Tone.Neutral, spoken: String? = null) {
    val c = NutriTheme.colors
    Column(Modifier.fillMaxWidth().then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)) {
        Text(label, style = NutriTheme.type.small, color = c.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(value, style = NutriTheme.type.hero, color = c.text)
            if (unit != null) Text(" $unit", style = NutriTheme.type.body, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (status != null) StatusPill(status, tone, Modifier.padding(top = Space.S2))
    }
}

/** Protein, carbs and fat side by side: grams (against a target when given) and a thin bar. */
@Composable
fun MacroTiles(values: NutrientMap, targets: Map<Int, Target>? = null, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.S3)) {
        listOf(Triple("Protein", N.PROTEIN, c.protein), Triple("Carbs", N.CARBS, c.carbs), Triple("Fat", N.FAT, c.fat)).forEach { (name, id, color) ->
            val v = values[id] ?: 0.0
            val t = targets?.get(id)?.min
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(color, 7.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(name, style = NutriTheme.type.caption, color = c.textSecondary)
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp, bottom = if (targets != null) 6.dp else 0.dp)) {
                    Text("%.0f".format(v), style = NutriTheme.type.title.copy(fontSize = com.nutrime.app.ui.theme.DesignTokens.TextSize.Xl, fontFeatureSettings = "tnum"), color = c.text)
                    Text(if (t != null) " / %.0f g".format(t) else " g", style = NutriTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary, modifier = Modifier.padding(bottom = 2.dp), maxLines = 1)
                }
                if (targets != null) ProgressBar(if (t != null && t > 0) (v / t).toFloat() else 0f, color, height = 4.dp)
            }
        }
    }
}

/** Calorie shares of protein, carbs and fat as one bar with gaps between the parts. */
@Composable
fun SplitBar(protein: Double, carbs: Double, fat: Double, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    val total = (protein + carbs + fat).coerceAtLeast(0.0001)
    Row(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(Radius.Full)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (protein + carbs + fat <= 0) Box(Modifier.fillMaxSize().background(c.track))
        listOf(protein to c.protein, carbs to c.carbs, fat to c.fat).forEach { (v, col) ->
            if (v > 0) Box(Modifier.weight((v / total).toFloat()).fillMaxSize().background(col))
        }
    }
}

/** How many targets are met, as a row of small segments. */
@Composable
fun ScoreBar(met: Int, total: Int, modifier: Modifier = Modifier) {
    val c = NutriTheme.colors
    if (total <= 0) return
    Row(modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i -> Box(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(Radius.Full)).background(if (i < met) c.accent else c.track)) }
    }
}

/** A card that holds a list of rows. Put [RowDivider] between the rows. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Card(modifier, padding = PaddingValues(0.dp), content = content)

/** Inset hairline between rows of a [RowGroup]. */
@Composable
fun RowDivider(inset: androidx.compose.ui.unit.Dp = Space.S4) = Hairline(Modifier.padding(start = inset))

/**
 * Settings-style row: icon, label and a chevron. A value shows only when it needs attention
 * (pass [value] with a non-neutral [tone]) or when it is short and useful.
 */
@Composable
fun NavRow(label: String, icon: ImageVector? = null, value: String? = null, tone: Tone = Tone.Neutral, subtitle: String? = null, onClick: () -> Unit) {
    val c = NutriTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clickable(onClick = onClick).padding(horizontal = Space.S4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = NutriTheme.type.body.copy(fontSize = NutriTheme.type.title.fontSize * 0.94f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(
                value, style = NutriTheme.type.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when (tone) { Tone.Bad -> c.danger; Tone.Warn -> c.warning; Tone.Good -> c.accent; Tone.Neutral -> c.textTertiary },
                modifier = Modifier.padding(start = Space.S3),
            )
        }
        Icon(Lucide.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.padding(start = Space.S1).size(18.dp))
    }
}

/** A group that starts closed and shows a one-line summary; tap to open it. */
@Composable
fun Disclosure(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    startOpen: Boolean = false,
    key: Any = title,
    hPad: androidx.compose.ui.unit.Dp = Space.S4,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = NutriTheme.colors
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.Duration), label = "chev")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clickable(onClickLabel = if (open) "Close" else "Open") { open = !open }
                .padding(horizontal = hPad, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = NutriTheme.type.bodyMedium, color = c.text)
                if (!summary.isNullOrBlank()) Text(summary, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.ChevronDown, null, tint = c.textTertiary, modifier = Modifier.size(18.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = expandVertically(tween(Motion.Duration)), exit = shrinkVertically(tween(Motion.DurationFast))) {
            Column(Modifier.fillMaxWidth().padding(start = hPad, end = hPad, bottom = Space.S3), content = content)
        }
    }
}

/** Bottom sheet for the "explain" level: a title, an optional subtitle and scrollable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(title: String, subtitle: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = NutriTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl2, topEnd = Radius.Xl2),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.borderStrong)) },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.S5).padding(bottom = Space.S5),
            verticalArrangement = Arrangement.spacedBy(Space.S3),
        ) {
            Column {
                Text(title, style = NutriTheme.type.title, color = c.text)
                if (subtitle != null) Text(subtitle, style = NutriTheme.type.small, color = c.textSecondary)
            }
            content()
        }
    }
}

/** Label on the left, value on the right: one line inside a detail sheet. */
@Composable
fun SheetLine(label: String, value: String, strong: Boolean = false, color: Color? = null) {
    val c = NutriTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (color != null) { Dot(color); Spacer(Modifier.width(Space.S2)) }
        Text(label, style = if (strong) NutriTheme.type.bodyMedium else NutriTheme.type.body, color = if (strong) c.text else c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = NutriTheme.type.numeric.copy(fontWeight = if (strong) NutriTheme.type.numeric.fontWeight else NutriTheme.type.body.fontWeight), color = c.text)
    }
}

/** A quiet card for "nothing here yet": one sentence and one optional action. */
@Composable
fun EmptyCard(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = NutriTheme.colors
    Card(onClick = onAction) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = NutriTheme.type.small, color = c.textSecondary, modifier = Modifier.weight(1f))
            if (action != null) {
                Text(action, style = NutriTheme.type.small.copy(fontWeight = NutriTheme.type.bodyMedium.fontWeight), color = c.accent)
                Icon(Lucide.Plus, null, tint = c.accent, modifier = Modifier.padding(start = 4.dp).size(14.dp))
            }
        }
    }
}

/**
 * One row of a rounded group that is built from separate lazy-list items: side margins, the surface
 * color, and round corners on the [first] and [last] row.
 */
@Composable
fun Modifier.groupRow(first: Boolean, last: Boolean, inset: Boolean = true): Modifier {
    val top = if (first) Radius.Xl2 else 0.dp
    val bottom = if (last) Radius.Xl2 else 0.dp
    return this
        .then(if (inset) Modifier.padding(horizontal = Space.S4) else Modifier)
        .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
        .background(NutriTheme.colors.surface)
}
