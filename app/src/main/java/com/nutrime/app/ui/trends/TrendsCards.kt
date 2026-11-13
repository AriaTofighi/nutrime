package com.nutrime.app.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.model.BucketSize
import com.nutrime.app.model.BurnEstimate
import com.nutrime.app.model.BurnMode
import com.nutrime.app.model.Confidence
import com.nutrime.app.model.Dri
import com.nutrime.app.model.Goal
import com.nutrime.app.model.Insights
import com.nutrime.app.model.LoggedItem
import com.nutrime.app.model.N
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Target
import com.nutrime.app.model.TrendBuckets
import com.nutrime.app.model.TrendRange
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.CardTitle
import com.nutrime.app.ui.components.DetailSheet
import com.nutrime.app.ui.components.EmptyCard
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.LB_PER_KG
import com.nutrime.app.ui.components.MacroTiles
import com.nutrime.app.ui.components.ScoreBar
import com.nutrime.app.ui.components.SheetLine
import com.nutrime.app.ui.components.SplitBar
import com.nutrime.app.ui.components.StatusPill
import com.nutrime.app.ui.components.Tone
import com.nutrime.app.ui.components.LinkedSheetLine
import com.nutrime.app.ui.components.fmtWeight1
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// The cards on the Trends overview.

@Composable
internal fun CaloriesCard(p: Period, burn: BurnEstimate?, onOpenDay: (LocalDate) -> Unit, onZoom: (TrendRange) -> Unit, onOpenBurn: () -> Unit) {
    val colors = NutriTheme.colors
    var selected by rememberSaveable(p.from, p.to) { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf(false) }
    val avg = p.avg[N.ENERGY] ?: 0.0
    val target = p.calorieTarget
    val diff = avg - target
    val (status, tone) = when {
        p.counted.isEmpty() -> "Nothing logged yet" to Tone.Neutral
        abs(diff) <= target * 0.05 -> "On target" to Tone.Good
        diff < 0 -> "%,.0f under target".format(-diff) to Tone.Neutral
        diff > target * 0.15 -> "%,.0f over target".format(diff) to Tone.Warn
        else -> "%,.0f over target".format(diff) to Tone.Neutral
    }
    val buckets = remember(p.from, p.to) { TrendBuckets.buckets(p.from, p.to) }
    val size = buckets.firstOrNull()?.size ?: BucketSize.DAY
    val counted = p.counted.toSet()
    val values = buckets.map { b ->
        if (size == BucketSize.DAY) p.totals[b.from]?.get(N.ENERGY)
        else TrendBuckets.average(listOf(b), p.totals.mapValues { it.value[N.ENERGY] ?: 0.0 }, counted) { it.average() }.first()
    }
    val schedule = p.profile.calorieSchedule != null
    val barTargets = if (!schedule) null else buckets.map { b ->
        generateSequence(b.from) { it.plusDays(1) }.takeWhile { !it.isAfter(b.to) }.map { p.profile.calorieTargetOn(it).toDouble() }.average()
    }
    Card {
        Column(Modifier.fillMaxWidth().clickable(enabled = p.counted.isNotEmpty(), onClickLabel = "Explain") { sheet = true }) {
            Hero(
                label = if (p.counted.isEmpty()) "Daily average" else "Daily average · ${p.counted.size} of ${p.length} days",
                value = if (p.counted.isEmpty()) "—" else "%,.0f".format(avg),
                unit = "kcal",
                status = status,
                tone = tone,
                spoken = if (p.counted.isEmpty()) "Nothing logged yet" else "Daily average %,.0f kilocalories, %s".format(avg, status),
            )
            if (p.hasPrev && p.counted.isNotEmpty()) CompareLine(compareText(avg, p.prevAvg!![N.ENERGY] ?: 0.0, "kcal", p.prevLabel))
        }
        Spacer(Modifier.height(Space.S4))
        val defaultSel = values.indexOfLast { it != null }.takeIf { it >= 0 }
        val sel = selected ?: defaultSel
        val labelFmt = DateTimeFormatter.ofPattern(
            when (size) { BucketSize.DAY -> if (p.length <= 7) "EEEEE" else "MMM d"; BucketSize.WEEK -> "MMM d"; BucketSize.MONTH -> "MMM" },
        )
        MiniBarChart(
            values = values,
            labels = buckets.map { it.from.format(labelFmt) },
            target = target,
            targets = barTargets,
            selected = sel,
            onSelect = { selected = it },
        )
        Spacer(Modifier.height(Space.S2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val b = sel?.let { buckets.getOrNull(it) }
            if (b != null) {
                val v = values[sel]
                val title = when (b.size) {
                    BucketSize.DAY -> b.from.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
                    BucketSize.WEEK -> "Week of " + b.from.format(DateTimeFormatter.ofPattern("MMM d"))
                    BucketSize.MONTH -> b.from.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                }
                Row(
                    Modifier.weight(1f).clickable(onClickLabel = if (b.size == BucketSize.DAY) "Open day" else "Show this period") {
                        if (b.size == BucketSize.DAY) onOpenDay(b.from) else TrendRange.custom(b.from, b.to)?.let(onZoom)
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = NutriTheme.type.bodyMedium, color = colors.text)
                    Text(
                        when {
                            v == null -> "  Not logged"
                            b.size == BucketSize.DAY -> "  %,.0f kcal".format(v)
                            else -> "  %,.0f kcal a day".format(v)
                        },
                        style = NutriTheme.type.small.copy(fontFeatureSettings = "tnum"), color = colors.textSecondary,
                    )
                    Icon(Lucide.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                }
            } else {
                Text("No days logged", style = NutriTheme.type.small, color = colors.textTertiary, modifier = Modifier.weight(1f))
            }
            Text("Target %,.0f".format(target), style = NutriTheme.type.small.copy(fontFeatureSettings = "tnum"), color = colors.textTertiary)
        }
    }
    if (sheet) BalanceSheet(p, burn, onOpenBurn) { sheet = false }
}

/** Where the daily burn comes from, in a few words. */
fun burnSource(profile: Profile, est: BurnEstimate?): String = when (profile.burnMode) {
    BurnMode.ADAPTIVE -> if (profile.adaptiveBurnKcal != null) "learned from your log" + (est?.let { " · ${it.knownDays} days" } ?: "") else "from your body and activity"
    BurnMode.MANUAL -> if (profile.manualBurnKcal != null) "your own value" else "from your body and activity"
    BurnMode.FORMULA -> "from your body and activity"
}

/** The explanation behind the average: eaten against daily burn, the weekly rate, and which days count. */
@Composable
private fun BalanceSheet(p: Period, est: BurnEstimate?, onOpenBurn: () -> Unit, onDismiss: () -> Unit) {
    val colors = NutriTheme.colors
    val avg = p.avg[N.ENERGY] ?: 0.0
    val burn = p.profile.tdee
    val balance = avg - burn
    val kgPerWeek = balance * 7 / 7700.0
    val rate = if (p.profile.imperial) "%.1f lb".format(abs(kgPerWeek) * LB_PER_KG) else "%.2f kg".format(abs(kgPerWeek))
    DetailSheet(rangeName(p.range), "Daily average over ${p.counted.size} day${if (p.counted.size == 1) "" else "s"}", onDismiss) {
        SheetLine("Eaten", "%,.0f kcal".format(avg))
        LinkedSheetLine(
            "Daily burn", "%,.0f kcal".format(burn), burnSource(p.profile, est).replaceFirstChar { it.uppercase() },
            onClickLabel = "Daily burn details",
        ) { onDismiss(); onOpenBurn() }
        SheetLine(if (balance < 0) "Deficit" else "Surplus", "%,.0f kcal".format(abs(balance)), strong = true)
        Text(
            if (abs(kgPerWeek) < 0.02) "At this rate, your weight stays about the same."
            else "At this rate, you ${if (kgPerWeek < 0) "lose" else "gain"} about $rate a week.",
            style = NutriTheme.type.small, color = colors.textSecondary,
        )
        SheetLine("Your target", "%,.0f kcal".format(p.calorieTarget))
        val partial = p.counted.filter { (p.totals[it]?.get(N.ENERGY) ?: 0.0) < p.profile.calorieTargetOn(it) * 0.5 }
        val notes = buildList {
            if (p.todayExcluded) add("Today is not in the average until the day ends.")
            if (partial.isNotEmpty()) add(
                partial.take(5).joinToString(", ") { it.format(DateTimeFormatter.ofPattern("EEE, MMM d")) } +
                    (if (partial.size > 5) " and ${partial.size - 5} more" else "") +
                    (if (partial.size == 1) " looks" else " look") + " partly logged (below half of your target). This pulls the average down.",
            )
        }
        notes.forEach { Text(it, style = NutriTheme.type.caption, color = colors.textTertiary) }
    }
}

@Composable
internal fun BurnCard(profile: Profile, est: BurnEstimate?, onOpen: () -> Unit) {
    val colors = NutriTheme.colors
    Card(onClick = onOpen, onClickLabel = "Daily burn details") {
        CardTitle("Daily burn", chevron = true)
        Spacer(Modifier.height(Space.S2))
        when {
            est == null -> Text("Log your weight a few times a week. NutriMe then learns your real daily burn from your food log and weight.", style = NutriTheme.type.small, color = colors.textSecondary)
            est.confidence == Confidence.LEARNING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%,.0f".format(profile.tdee), style = NutriTheme.type.pageTitle.copy(fontFeatureSettings = "tnum"), color = colors.text)
                    Text(" kcal", style = NutriTheme.type.small, color = colors.textSecondary)
                    Spacer(Modifier.weight(1f))
                    StatusPill("Learning")
                }
                Text("Needs " + est.missing.joinToString(", ") + ".", style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%,d".format(est.burnKcal), style = NutriTheme.type.pageTitle.copy(fontFeatureSettings = "tnum"), color = colors.text)
                    Text(" ± %d kcal".format(est.burnSd), style = NutriTheme.type.small, color = colors.textSecondary)
                    Spacer(Modifier.weight(1f))
                    StatusPill(if (est.confidence == Confidence.HIGH) "Sure" else "Fairly sure", if (est.confidence == Confidence.HIGH) Tone.Good else Tone.Neutral)
                }
                val d = est.burnKcal - est.formulaKcal
                Text(
                    "Learned from ${est.knownDays} logged days and ${est.weighIns} weigh-ins. " +
                        (if (abs(d) < 50) "Close to the formula." else "%,d kcal %s than the formula.".format(abs(d), if (d > 0) "more" else "less")),
                    style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun MacrosCard(p: Period, onOpenNutrient: (Int) -> Unit) {
    var sheet by remember { mutableStateOf(false) }
    Card(onClick = if (p.counted.isEmpty()) null else ({ sheet = true }), onClickLabel = "Show calorie split") {
        CardTitle("Macros", meta = "Daily average")
        Spacer(Modifier.height(Space.S3))
        MacroTiles(p.avg, p.targets)
        if (p.hasPrev && p.counted.isNotEmpty()) {
            val prev = p.prevAvg!!
            val parts = listOf(N.PROTEIN to "protein", N.CARBS to "carbs", N.FAT to "fat").map { (id, name) ->
                val d = (p.avg[id] ?: 0.0) - (prev[id] ?: 0.0)
                "$name " + (if (abs(d) < 0.5) "±0" else (if (d > 0) "+" else "−") + "%.0f".format(abs(d))) + " g"
            }
            CompareLine(parts.joinToString(", ").replaceFirstChar { it.uppercase() } + " against ${p.prevLabel}")
        }
    }
    if (sheet) MacroSheet(p, onOpenNutrient) { sheet = false }
}

@Composable
private fun MacroSheet(p: Period, onOpenNutrient: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = NutriTheme.colors
    val pr = (p.avg[N.PROTEIN] ?: 0.0) * 4
    val cb = (p.avg[N.CARBS] ?: 0.0) * 4
    val f = (p.avg[N.FAT] ?: 0.0) * 9
    val total = (pr + cb + f).coerceAtLeast(1.0)
    var selected by remember { mutableStateOf<Int?>(null) }
    val goal = Dri.macroGrams(p.profile).percents(p.profile.calorieTarget.toDouble())
    DetailSheet("Calorie split", "Share of calories from each macro", onDismiss) {
        SplitBar(pr, cb, f)
        listOf(Triple(N.PROTEIN, "Protein", pr) to goal.first, Triple(N.CARBS, "Carbs", cb) to goal.second, Triple(N.FAT, "Fat", f) to goal.third).forEach { (t, g) ->
            val color = when (t.first) { N.PROTEIN -> colors.protein; N.CARBS -> colors.carbs; else -> colors.fat }
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${t.second}") { onDismiss(); onOpenNutrient(t.first) }) {
                Column(Modifier.weight(1f)) { SheetLine(t.second, "%.0f%%  ·  goal %d%%".format(t.third / total * 100, g), color = color) }
                Icon(Lucide.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
            }
        }
        Spacer(Modifier.height(Space.S2))
        if (p.length <= 31) {
            Text("Each day", style = NutriTheme.type.bodyMedium, color = colors.text)
            val labelFmt = DateTimeFormatter.ofPattern(if (p.length <= 7) "EEE" else "M/d")
            StackedBarChart(
                points = p.days.map { d ->
                    val n = p.totals[d].orEmpty()
                    BarPoint(
                        d.format(labelFmt),
                        listOf(
                            (n[N.PROTEIN] ?: 0.0) * 4 to colors.protein,
                            (n[N.CARBS] ?: 0.0) * 4 to colors.carbs,
                            (n[N.FAT] ?: 0.0) * 9 to colors.fat,
                            (n[N.ALCOHOL] ?: 0.0) * 7 to colors.alcohol,
                        ),
                    )
                },
                target = p.calorieTarget,
                selected = selected,
                onSelect = { selected = if (selected == it) null else it },
                height = 180.dp,
            )
        }
    }
}

@Composable
internal fun NutrientsCard(p: Period, onOpenAll: () -> Unit) {
    val colors = NutriTheme.colors
    if (p.counted.isEmpty()) {
        Card {
            CardTitle("Nutrients")
            Text("Log a few days to see which vitamins and minerals you are short on.", style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = Space.S2))
        }
        return
    }
    val (low, over, met) = watchList(p.avg, p.targets)
    Card(onClick = onOpenAll, onClickLabel = "Show all nutrients") {
        CardTitle("Nutrients", chevron = true)
        Spacer(Modifier.height(Space.S3))
        ScoreBar(met.first, met.second)
        Spacer(Modifier.height(Space.S3))
        Text("${met.first} of ${met.second} targets met", style = NutriTheme.type.bodyMedium, color = colors.text)
        if (p.hasPrev) {
            val before = watchList(p.prevAvg!!, p.targets).third.first
            CompareLine(if (before == met.first) "Same as ${p.prevLabel}" else "$before met in ${p.prevLabel}")
        }
        if (over.isNotEmpty()) {
            Text("Over the limit: " + over.take(2).joinToString(", ") { shortName(it) }, style = NutriTheme.type.small, color = colors.warning)
        }
        if (low.isNotEmpty()) {
            Text("Lowest: " + low.take(3).joinToString(", ") { shortName(it) }, style = NutriTheme.type.small, color = colors.textSecondary)
        } else if (over.isEmpty()) {
            Text("You meet every target on average.", style = NutriTheme.type.small, color = colors.textSecondary)
        }
    }
}

internal fun signed(v: Double, unit: String) =
    if (abs(v) < 0.05) "No change" else (if (v > 0) "+" else "−") + "%.1f %s".format(abs(v), unit)

@Composable
internal fun WeightCard(p: Period, weights: List<WeightEntity>, est: BurnEstimate?, onOpen: () -> Unit) {
    val colors = NutriTheme.colors
    val imperial = p.profile.imperial
    val unit = if (imperial) "lb" else "kg"
    fun conv(kg: Double) = if (imperial) kg * LB_PER_KG else kg
    val latest = weights.firstOrNull()
    if (latest == null || est == null) {
        EmptyCard("No weigh-ins yet", "Log weight", onOpen)
        return
    }
    val inPeriod = est.days.filter { !it.date.isBefore(p.from) && !it.date.isAfter(p.to) }
    val change = if (inPeriod.size >= 2 && inPeriod.count { it.scaleKg != null } >= 2) conv(inPeriod.last().trendKg - inPeriod.first().trendKg) else null
    Card(onClick = onOpen, onClickLabel = "Weight details") {
        CardTitle("Weight", meta = "Trend", chevron = true)
        Spacer(Modifier.height(Space.S2))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Trend weight ${fmtWeight1(conv(est.trendKg))} $unit" + (change?.let { ", ${signed(it, unit)} in this period" } ?: "")
            },
        ) {
            Text(fmtWeight1(conv(est.trendKg)), style = NutriTheme.type.pageTitle.copy(fontFeatureSettings = "tnum"), color = colors.text)
            Text(" $unit", style = NutriTheme.type.small, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            if (change != null) StatusPill(signed(change, unit))
            else Text("last on " + LocalDate.parse(latest.date).format(DateTimeFormatter.ofPattern("MMM d")), style = NutriTheme.type.small, color = colors.textTertiary)
        }
        val goal = p.profile.goalWeightKg
        if (goal != null) {
            val date = Goal.projectedDate(LocalDate.now(), est.trendKg, goal, est.ratePerWeekKg)
            Text(
                when {
                    Goal.reached(est.trendKg, goal) -> "At your goal weight"
                    date != null -> "Goal ${fmtWeight1(conv(goal))} $unit around ${date.format(DateTimeFormatter.ofPattern("MMM d"))} at your current rate"
                    else -> "Goal ${fmtWeight1(conv(goal))} $unit"
                },
                style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (inPeriod.size >= 2) {
            Spacer(Modifier.height(Space.S3))
            Sparkline(inPeriod.map { conv(it.trendKg) })
        }
    }
}

@Composable
internal fun PatternsCard(p: Period) {
    val colors = NutriTheme.colors
    val pat = remember(p.items, p.counted) {
        val counted = p.counted.toSet()
        Insights.patterns(
            p.items.map { LoggedItem(LocalDate.parse(it.entity.date), it.entity.meal, it.entity.foodId, it.entity.foodName, it.nutrients) },
            p.totals.filterKeys { it in counted }.mapValues { it.value[N.ENERGY] ?: 0.0 }, p.from, p.to,
        )
    }
    Card {
        CardTitle("Patterns")
        Spacer(Modifier.height(Space.S2))
        if (pat.weekdayKcal != null && pat.weekendKcal != null) {
            val d = pat.weekendKcal - pat.weekdayKcal
            SheetLine("Weekdays", "%,.0f kcal".format(pat.weekdayKcal))
            SheetLine("Weekends", "%,.0f kcal".format(pat.weekendKcal))
            Text(
                if (abs(d) < 75) "You eat about the same on weekends." else "You eat %,.0f kcal %s on weekend days.".format(abs(d), if (d > 0) "more" else "less"),
                style = NutriTheme.type.caption, color = colors.textTertiary,
            )
            Spacer(Modifier.height(Space.S2))
        }
        SheetLine("Days logged", "${pat.loggedDays} of ${pat.totalDays}")
        if (pat.currentRun >= 2) SheetLine("Days in a row", "${pat.currentRun}")
        Spacer(Modifier.height(Space.S2))
        Text("Calories by meal", style = NutriTheme.type.small, color = colors.textSecondary)
        Spacer(Modifier.height(4.dp))
        val mealColors = listOf(colors.carbs, colors.protein, colors.fat, colors.alcohol)
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            pat.mealShares.forEachIndexed { i, s ->
                if (s > 0.005) Box(Modifier.weight(s.toFloat()).height(10.dp).padding(end = 2.dp).background(mealColors[i], RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.height(4.dp))
        FlowRowText(pat.mealShares, mealColors)
        if (pat.steadiestDay != null && pat.mostVariableDay != null) {
            Spacer(Modifier.height(Space.S2))
            Text(
                "Steadiest day: ${pat.steadiestDay.getDisplayName(TextStyle.FULL, Locale.getDefault())}. " +
                    "Most variable: ${pat.mostVariableDay.getDisplayName(TextStyle.FULL, Locale.getDefault())}.",
                style = NutriTheme.type.caption, color = colors.textTertiary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowText(shares: List<Double>, colors: List<androidx.compose.ui.graphics.Color>) {
    val names = listOf("Breakfast", "Lunch", "Dinner", "Snacks")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.S3)) {
        shares.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.nutrime.app.ui.components.Dot(colors[i], 7.dp)
                Text(" ${names[i]} %.0f%%".format(s * 100), style = NutriTheme.type.caption, color = NutriTheme.colors.textSecondary)
            }
        }
    }
}
