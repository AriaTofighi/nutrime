package com.nutrime.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientDef
import com.nutrime.app.model.NutrientGroup
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Target
import com.nutrime.app.ui.theme.DesignTokens.Control
import com.nutrime.app.ui.theme.DesignTokens.Motion
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme

private val colors @Composable get() = NutriTheme.colors
private val type get() = NutriTheme.type

// ---------------------------------------------------------------------------------------------
// Layout
// ---------------------------------------------------------------------------------------------

/** A rounded card on the canvas (see [Card]). */
@Composable
fun NCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(horizontal = Space.S4, vertical = Space.S4),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Card(modifier, onClick = onClick, padding = padding, content = content)

@Composable
fun CardHeader(title: String, modifier: Modifier = Modifier, meta: String? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = Space.S3), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.cardTitle, color = colors.text, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = type.caption, color = colors.textTertiary)
        action?.invoke(this)
    }
}

/** Small uppercase heading inside lists and cards (`.list-head`). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = type.label, color = colors.textTertiary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = colors.border)

@Composable
fun EmptyState(icon: ImageVector?, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.S8, horizontal = Space.S6), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(Space.S3))
        }
        Text(title, style = type.bodyMedium, color = colors.text, textAlign = TextAlign.Center)
        if (body.isNotBlank()) {
            Spacer(Modifier.height(Space.S1))
            Text(body, style = type.small, color = colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) { Spacer(Modifier.height(Space.S4)); action() }
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons & controls
// ---------------------------------------------------------------------------------------------

enum class ButtonKind { Primary, Secondary, Ghost, Danger }
enum class ButtonSize(val height: Dp, val hPad: Dp) { Sm(Control.HeightSm, 10.dp), Md(Control.Height, 12.dp), Lg(Control.HeightLg, 16.dp) }

@Composable
fun NButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    size: ButtonSize = ButtonSize.Md,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fill: Boolean = false,
    loading: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(if (pressed) colors.accentHover else colors.accent, colors.onAccent, Color.Transparent)
        ButtonKind.Secondary -> Triple(if (pressed) colors.surfaceActive else colors.surface, colors.text, colors.borderStrong)
        ButtonKind.Ghost -> Triple(if (pressed) colors.surfaceActive else Color.Transparent, colors.textSecondary, Color.Transparent)
        ButtonKind.Danger -> Triple(if (pressed) colors.dangerSoft else Color.Transparent, colors.danger, colors.borderStrong)
    }
    val shape = RoundedCornerShape(Radius.Md)
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(size.height)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .clickable(source, null, enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = size.hPad),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = 0.45f)
        if (loading) {
            Spinner(Modifier.size(14.dp), c)
            Spacer(Modifier.width(Space.S2))
        } else if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(if (size == ButtonSize.Sm) 14.dp else 16.dp))
            Spacer(Modifier.width(if (size == ButtonSize.Sm) 6.dp else Space.S2))
        }
        Text(text, style = if (size == ButtonSize.Sm) type.small.copy(fontWeight = type.bodyMedium.fontWeight) else type.bodyMedium, color = c, maxLines = 1)
    }
}

/** Square icon-only button (ghost by default). */
@Composable
fun NIconButton(icon: ImageVector, contentDescription: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = colors.textSecondary, size: Dp = Control.Height, bordered: Boolean = false, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Radius.Md)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .then(if (bordered) Modifier.background(colors.surface).border(1.dp, colors.borderStrong, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(if (size < 32.dp) 15.dp else 17.dp)) }
}

@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = colors.textSecondary) {
    val rotation = rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rot",
    )
    Canvas(modifier) {
        drawArc(color, rotation.value, 270f, false, style = Stroke(size.minDimension * 0.14f, cap = StrokeCap.Round))
    }
}

/** Segmented control (`.segmented`): sunken track, the selected option raised on a surface pill. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    val shape = RoundedCornerShape(Radius.Md)
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(Control.Height).clip(shape).background(colors.bgSunken).border(1.dp, colors.border, shape).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            val inner = RoundedCornerShape(Radius.Sm)
            val bg by animateColorAsState(if (on) colors.surfaceRaised else Color.Transparent, tween(Motion.DurationFast), label = "seg")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .fillMaxHeight()
                    .then(if (on && !colors.isDark) Modifier.shadow(1.dp, inner, ambientColor = Color(0x22161615), spotColor = Color(0x22161615)) else Modifier)
                    .clip(inner)
                    .background(bg)
                    .then(if (on) Modifier.border(1.dp, colors.border, inner) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = type.small.copy(fontWeight = type.bodyMedium.fontWeight), color = if (on) colors.text else colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** Selectable chip (`.chip`): bordered, accent-tinted when selected. */
@Composable
fun NChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    val shape = RoundedCornerShape(Radius.Md)
    Row(
        modifier
            .height(Control.HeightSm + 2.dp)
            .clip(shape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent.copy(alpha = 0.45f) else colors.borderStrong, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Dot(dot); Spacer(Modifier.width(6.dp)) }
        Text(text, style = type.small.copy(fontWeight = type.bodyMedium.fontWeight), color = if (selected) colors.accent else colors.text, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

/** Text input with an optional label above and unit suffix inside (`.field` + `.input`). */
@Composable
fun NTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    suffix: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onIme: (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    large: Boolean = false,
    textStyle: TextStyle = type.body,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Radius.Md)
    val borderColor = when {
        isError -> colors.danger
        focused -> colors.accent
        else -> colors.borderStrong
    }
    Column(modifier) {
        if (label != null) {
            Text(label, style = type.small.copy(fontWeight = type.bodyMedium.fontWeight), color = colors.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = textStyle.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            interactionSource = source,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(if (large) Control.HeightLg + 4.dp else Control.HeightLg)
                        .clip(shape)
                        .background(colors.surface)
                        .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                        .padding(start = if (leading != null) 10.dp else 12.dp, end = if (trailing != null) 2.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) {
                        Icon(leading, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle, color = colors.textTertiary, maxLines = 1)
                        inner()
                    }
                    if (suffix != null) Text(suffix, style = type.small, color = colors.textTertiary, modifier = Modifier.padding(start = 6.dp))
                    trailing?.invoke()
                }
            },
        )
    }
}

/** Dropdown select styled like an input (`select.input`). */
@Composable
fun <T> NSelect(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fieldLabel: String? = null,
    optionSubtitle: ((T) -> String?)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.Md)
    Column(modifier) {
        if (fieldLabel != null) Text(fieldLabel, style = type.small.copy(fontWeight = type.bodyMedium.fontWeight), color = colors.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().height(Control.HeightLg).clip(shape).background(colors.surface)
                    .border(1.dp, colors.borderStrong, shape).clickable { open = true }.padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label(selected), style = type.body, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Lucide.ChevronDown, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(Radius.Lg), containerColor = colors.surfaceRaised, border = BorderStroke(1.dp, colors.border)) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(label(o), style = type.body, color = colors.text)
                                optionSubtitle?.invoke(o)?.let { Text(it, style = type.caption, color = colors.textTertiary) }
                            }
                        },
                        trailingIcon = if (o == selected) ({ Icon(Lucide.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp)) }) else null,
                        onClick = { open = false; onSelect(o) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Data display
// ---------------------------------------------------------------------------------------------

/** Thin rounded progress bar. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(Motion.DurationSlow, easing = Motion.EaseOut), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Radius.Full)).background(colors.track)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(RoundedCornerShape(Radius.Full)).background(color))
    }
}

/** Ring made of colored segments (fractions of the full circle), with content in the middle. */
@Composable
fun Ring(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    stroke: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val track = colors.track
    val progress by animateFloatAsState(1f, tween(Motion.DurationSlow, easing = Motion.EaseOut), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            // Segments are fractions of the full circle, drawn clockwise from 12 o'clock with small gaps.
            val gap = if (segments.count { it.first > 0.004f } > 1) 3f else 0f
            var start = -90f
            segments.forEach { (frac, color) ->
                val sweep = 360f * frac.coerceIn(0f, 1f) * progress
                if (sweep > 0.5f) drawArc(color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, Offset(inset, inset), arc, style = Stroke(w))
                start += sweep
            }
        }
        content()
    }
}

/** Swipe left to delete (with a danger backdrop); the caller offers Undo. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(key: Any, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v -> if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false },
    )
    androidx.compose.runtime.key(key) {
        androidx.compose.material3.SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(Modifier.fillMaxSize().background(colors.danger).padding(horizontal = Space.S5), contentAlignment = Alignment.CenterEnd) {
                    Icon(Lucide.Trash2, "Delete", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            },
        ) { Box(Modifier.background(colors.surface)) { content() } }
    }
}

fun fmtAmount(v: Double): String = when {
    v >= 100 -> "%,.0f".format(v)
    v >= 10 -> "%.1f".format(v).removeSuffix(".0")
    v == 0.0 -> "0"
    v >= 1 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

/**
 * Nutrient row: name, percent of target and a bar. Tap the row to show the amount ("62 / 90 mg").
 * A nutrient without a target shows its amount.
 */
@Composable
fun NutrientRow(def: NutrientDef, value: Double, target: Target?, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val min = target?.min
    val max = target?.max
    val pct = when {
        min != null && min > 0 -> value / min
        max != null && max > 0 -> value / max
        else -> null
    }
    val overMax = max != null && value > max
    val barColor = when {
        overMax -> colors.danger
        min == null && max != null -> colors.textTertiary
        else -> colors.accent
    }
    var showAmount by remember { mutableStateOf(false) }
    val amount = if (min != null || max != null) "${fmtAmount(value)} / ${fmtAmount(min ?: max!!)} ${def.unit}" else Nutrients.format(value, def.unit)
    Column(
        modifier.fillMaxWidth()
            .then(
                when {
                    onOpen != null -> Modifier.clickable(onClickLabel = "Open ${def.name}") { onOpen() }
                    pct != null -> Modifier.clickable(onClickLabel = "Show amount") { showAmount = !showAmount }
                    else -> Modifier
                },
            )
            .padding(vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.name, style = type.small, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pct == null || showAmount || onOpen != null) Text(amount, style = type.small.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary)
            if (pct != null) {
                Text(
                    "%.0f%%".format(pct * 100),
                    style = type.numeric.copy(fontSize = type.small.fontSize),
                    color = if (overMax) colors.danger else colors.text,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
        if (pct != null) {
            Spacer(Modifier.height(5.dp))
            ProgressBar(pct.toFloat(), barColor, height = 4.dp)
        }
    }
}

/** What a nutrient list describes: what someone ate (targets met) or one food (what it is high in). */
enum class BreakdownMode { Intake, Food }

/** One line about a nutrient group: "9 of 13 met · 1 over limit", or "High in selenium, niacin". */
fun groupSummary(group: NutrientGroup, totals: NutrientMap, targets: Map<Int, Target>, mode: BreakdownMode): String? {
    val defs = Nutrients.all.filter { it.group == group }
    fun v(d: NutrientDef) = totals[d.id] ?: 0.0
    return when (mode) {
        BreakdownMode.Intake -> {
            val withMin = defs.filter { (targets[it.id]?.min ?: 0.0) > 0 }
            val met = withMin.count { v(it) >= targets[it.id]!!.min!! && targets[it.id]?.max?.let { m -> v(it) <= m } != false }
            val over = defs.count { d -> targets[d.id]?.max?.let { v(d) > it } == true }
            listOfNotNull(
                if (withMin.isNotEmpty()) "$met of ${withMin.size} met" else null,
                if (over > 0) "$over over limit" else null,
            ).joinToString(" · ").ifEmpty { null }
        }
        BreakdownMode.Food -> {
            val high = defs.filter { d -> (targets[d.id]?.min ?: 0.0) > 0 && v(d) / targets[d.id]!!.min!! >= 0.2 }
                .sortedByDescending { d -> v(d) / targets[d.id]!!.min!! }
                .take(2)
                .map { it.name.substringBefore(" (").replaceFirstChar { ch -> ch.lowercase() } }
            if (high.isEmpty()) null else "High in " + high.joinToString(" and ")
        }
    }
}

/**
 * Nutrient targets grouped like the web (General, Carbohydrates, Lipids, ...). Each group starts
 * closed with a one-line summary; tap it to open. Place it in a card without padding.
 */
@Composable
fun NutrientBreakdown(
    totals: NutrientMap,
    targets: Map<Int, Target>,
    startExpanded: Boolean = false,
    mode: BreakdownMode = BreakdownMode.Intake,
    hPad: Dp = Space.S4,
    onOpen: ((NutrientDef) -> Unit)? = null,
) {
    Column {
        NutrientGroup.entries.forEachIndexed { i, group ->
            if (i > 0) Hairline(Modifier.padding(start = hPad))
            Disclosure(group.title, groupSummary(group, totals, targets, mode), startOpen = startExpanded, key = group, hPad = hPad) {
                Nutrients.all.filter { it.group == group }.forEach { def ->
                    NutrientRow(def, totals[def.id] ?: 0.0, targets[def.id], onOpen = onOpen?.let { { it(def) } })
                }
            }
        }
    }
}

/** Plain tappable list row with leading icon, title/subtitle, and trailing content. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = colors.textTertiary,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Control.RowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.S4, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(Space.S3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.S2))
        trailing()
    }
}

fun formatQty(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else "%.2f".format(q).trimEnd('0').trimEnd('.')

/** "1 cup, chopped" / "150 g". */
fun servingText(label: String, quantity: Double): String =
    if (label == "g") "${formatQty(quantity)} g" else "${formatQty(quantity)} × $label"
