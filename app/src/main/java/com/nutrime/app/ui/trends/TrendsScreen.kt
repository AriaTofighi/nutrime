package com.nutrime.app.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrime.app.data.DiaryItem
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientDef
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Target
import com.nutrime.app.model.TrendBuckets
import com.nutrime.app.model.TrendRange
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.DetailSheet
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.PageTitle
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.NutriTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch

/** The range control: three common ranges, and "More" for the others and a custom range. */
private const val MORE = "more"
private val QUICK = listOf("7d" to "Week", "30d" to "Month", "90d" to "3 mo", MORE to "More")

fun rangeName(r: TrendRange): String = when (r) {
    TrendRange.All -> "All time"
    is TrendRange.Custom -> {
        val sameYear = r.from.year == LocalDate.now().year && r.to.year == r.from.year
        val f = DateTimeFormatter.ofPattern(if (sameYear) "MMM d" else "MMM d, yyyy")
        "${r.from.format(f)} – ${r.to.format(f)}"
    }
    is TrendRange.Last -> when (r.days) {
        7 -> "This week"; 30 -> "This month"; 90 -> "Last 3 months"; 180 -> "Last 6 months"; 365 -> "Last year"
        else -> "Last ${r.days} days"
    }
}

/** Everything the Trends screens need for the chosen range, computed once. */
internal class Period(
    val range: TrendRange,
    val from: LocalDate,
    val to: LocalDate,
    val days: List<LocalDate>,
    /** Totals of each day that has entries. */
    val totals: Map<LocalDate, NutrientMap>,
    /** Days in the averages: logged days, without today while today is still going (unless it is the only one). */
    val counted: List<LocalDate>,
    val avg: NutrientMap,
    val prevAvg: NutrientMap?,
    val prevCounted: Int,
    val items: List<DiaryItem>,
    val profile: Profile,
    val targets: Map<Int, Target>,
) {
    val length: Int get() = days.size
    val logged: Set<LocalDate> get() = totals.keys
    /** Mean calorie target of the counted days (follows a weekday schedule). */
    val calorieTarget: Double
        get() = if (counted.isEmpty()) profile.calorieTarget.toDouble() else counted.map { profile.calorieTargetOn(it) }.average()
    val todayExcluded: Boolean get() = to == LocalDate.now() && to in logged && to !in counted
    val prevLabel: String get() = "the ${length} days before"
    val hasPrev: Boolean get() = prevAvg != null && prevCounted >= 3
}

private fun average(days: List<LocalDate>, totals: Map<LocalDate, NutrientMap>): NutrientMap =
    if (days.isEmpty()) emptyMap() else sumNutrients(days.mapNotNull { totals[it] }).mapValues { it.value / days.size }

private fun countedDays(totals: Map<LocalDate, NutrientMap>, today: LocalDate): List<LocalDate> {
    val logged = totals.keys.sorted()
    return logged.filter { it != today }.ifEmpty { logged }
}

@Composable
internal fun rememberPeriod(range: TrendRange): Period {
    val c = appContainer()
    val today = LocalDate.now()
    val firstDate by c.diary.observeFirstDate().collectAsStateWithLifecycle(initialValue = null)
    val (from, to) = range.bounds(today, firstDate)
    val (prevFrom, prevTo) = TrendBuckets.previous(from, to)
    val items by remember(prevFrom, to) { c.diary.observeRange(prevFrom, to) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile by c.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    return remember(items, profile, from, to) {
        val byDay = items.groupBy { it.entity.date }.mapNotNull { (d, l) ->
            runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to sumNutrients(l.map { i -> i.nutrients }) }
        }.toMap()
        val inRange = byDay.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        val prev = byDay.filterKeys { !it.isBefore(prevFrom) && !it.isAfter(prevTo) }
        val counted = countedDays(inRange, today)
        val prevCounted = prev.keys.sorted()
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        Period(
            range, from, to, days, inRange, counted, average(counted, inRange),
            if (prevCounted.isEmpty()) null else average(prevCounted, prev), prevCounted.size,
            items.filter { it.entity.date >= from.toString() && it.entity.date <= to.toString() },
            profile, Dri.targets(profile),
        )
    }
}

/** Vitamins and minerals furthest below target, and limits exceeded. Macros, energy and water are shown elsewhere or rarely logged. */
internal fun watchList(avg: NutrientMap, targets: Map<Int, Target>): Triple<List<NutrientDef>, List<NutrientDef>, Pair<Int, Int>> {
    val skip = setOf(N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.WATER)
    val withMin = Nutrients.all.filter { targets[it.id]?.min != null && it.id !in skip }
    fun ratio(d: NutrientDef) = (avg[d.id] ?: 0.0) / targets[d.id]!!.min!!
    val low = withMin.filter { ratio(it) < 0.9 }.sortedBy { ratio(it) }
    val over = Nutrients.all.filter { d -> targets[d.id]?.max?.let { (avg[d.id] ?: 0.0) > it } == true }
    val met = withMin.count { ratio(it) >= 1.0 }
    return Triple(low, over, met to withMin.size)
}

internal fun shortName(d: NutrientDef) = d.name.substringBefore(" (").replaceFirstChar { it.lowercase() }

/** "↑ 120 kcal against the 30 days before". */
internal fun compareText(now: Double, before: Double?, unit: String, label: String, digits: Int = 0): String? {
    if (before == null) return null
    val d = now - before
    val fmt = if (digits == 0) "%,.0f" else "%,.${digits}f"
    if (abs(d) < (if (digits == 0) 0.5 else 0.05)) return "Same as $label"
    return (if (d > 0) "↑ " else "↓ ") + fmt.format(abs(d)) + " $unit against $label"
}

@Composable
internal fun CompareLine(text: String?) {
    if (text == null) return
    Text(text, style = NutriTheme.type.caption, color = NutriTheme.colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
}

/**
 * Trends at a glance: how much you eat on average (with the bars), daily burn, weight, macros,
 * nutrients and patterns. Each card gives one answer; tap it for the explanation or the full list.
 */
@Composable
fun TrendsScreen(
    onOpenDay: (LocalDate) -> Unit,
    onOpenWeight: () -> Unit = {},
    onOpenNutrients: (String) -> Unit = {},
    onOpenBurn: () -> Unit = {},
    onOpenNutrient: (Int, String) -> Unit = { _, _ -> },
) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val saved by c.settings.trendsRange.collectAsStateWithLifecycle(initialValue = null)
    var rangeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val range = TrendRange.decode(rangeKey ?: saved) ?: TrendRange.Last(7)
    fun setRange(r: TrendRange) {
        rangeKey = r.encode()
        scope.launch { c.settings.setTrendsRange(r.encode()) }
    }
    val p = rememberPeriod(range)
    val burn by remember { c.burn.observe() }.collectAsStateWithLifecycle(initialValue = null)
    val weights by c.weights.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val weightInPeriod = weights.any { it.date >= p.from.toString() }
    // With a weight goal and recent weigh-ins, weight is the second thing people look for.
    val weightFirst = abs(p.profile.goalKgPerWeek) > 0.001 && weightInPeriod

    Page {
        item {
            PageTitle("Trends") { RangeControl(range, ::setRange) }
        }
        if (range !is TrendRange.Last || range.days !in setOf(7, 30, 90)) {
            item { Text(rangeName(range), style = NutriTheme.type.small, color = NutriTheme.colors.textSecondary, modifier = Modifier.padding(horizontal = Space.S1)) }
        }
        item { CaloriesCard(p, burn, onOpenDay, ::setRange, onOpenBurn) }
        if (weightFirst) item { WeightCard(p, weights, burn, onOpenWeight) }
        item { BurnCard(p.profile, burn, onOpenBurn) }
        item { MacrosCard(p) { onOpenNutrient(it, range.encode()) } }
        item { NutrientsCard(p) { onOpenNutrients(range.encode()) } }
        if (!weightFirst) item { WeightCard(p, weights, burn, onOpenWeight) }
        if (p.counted.size >= 14) item { PatternsCard(p) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RangeControl(range: TrendRange, onChange: (TrendRange) -> Unit) {
    var more by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    val key = range.encode().takeIf { k -> QUICK.any { it.first == k } } ?: MORE
    Segmented(QUICK, key, { k -> if (k == MORE) more = true else TrendRange.decode(k)?.let(onChange) })
    if (more) {
        DetailSheet("Range", null, { more = false }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TrendRange.Last(180), TrendRange.Last(365), TrendRange.All).forEach { r ->
                    NChip(rangeName(r), range == r, { more = false; onChange(r) })
                }
                NChip("Custom range…", range is TrendRange.Custom, { more = false; custom = true })
            }
            Spacer(Modifier.height(Space.S4))
        }
    }
    if (custom) {
        val today = LocalDate.now()
        val toMillis = { d: LocalDate -> d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
        val (f0, t0) = range.bounds(today, null)
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = toMillis(f0), initialSelectedEndDateMillis = toMillis(t0),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= toMillis(today)
            },
        )
        fun toDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        val start = state.selectedStartDateMillis?.let(::toDate)
        val end = state.selectedEndDateMillis?.let(::toDate)
        val valid = start != null && end != null && TrendRange.custom(start, end) != null
        DatePickerDialog(
            onDismissRequest = { custom = false },
            confirmButton = {
                NButton("Show", { TrendRange.custom(start!!, end!!)?.let(onChange); custom = false }, kind = ButtonKind.Primary, enabled = valid)
            },
            dismissButton = { NButton("Cancel", { custom = false }, kind = ButtonKind.Ghost) },
        ) {
            DateRangePicker(state, modifier = Modifier.weight(1f), title = {
                Text(
                    if (start != null && end != null && !valid) "Choose up to ${TrendRange.MAX_DAYS} days" else "Choose a range",
                    style = NutriTheme.type.small, color = NutriTheme.colors.textSecondary, modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                )
            })
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Detail pages
// ---------------------------------------------------------------------------------------------

@Composable
internal fun rememberRange(initial: String): Pair<TrendRange, (TrendRange) -> Unit> {
    var key by rememberSaveable { mutableStateOf(initial) }
    return (TrendRange.decode(key) ?: TrendRange.Last(7)) to { r: TrendRange -> key = r.encode() }
}
