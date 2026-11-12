package com.nutrime.app.ui.trends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nutrime.app.model.BucketSize
import com.nutrime.app.model.Insights
import com.nutrime.app.model.LoggedItem
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.TrendBuckets
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.components.BreakdownMode
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.EmptyCard
import com.nutrime.app.ui.components.GroupLabel
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.NutrientBreakdown
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.ProgressBar
import com.nutrime.app.ui.components.RowDivider
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.components.ScoreBar
import com.nutrime.app.ui.components.Tone
import com.nutrime.app.ui.components.fmtAmount
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.NutriTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Every nutrient's daily average against its target, in closed groups with a summary each. */
@Composable
fun TrendsNutrientsScreen(nav: NavController, initialRange: String) {
    val (range, setRange) = rememberRange(initialRange)
    val p = rememberPeriod(range)
    val colors = NutriTheme.colors
    SubPage("Nutrients", onBack = { nav.popBackStack() }, actions = { RangeControl(range, setRange) }) { padding ->
        Page(Modifier.padding(padding)) {
            if (p.counted.isEmpty()) {
                item { EmptyCard("No days logged yet. Daily averages show here once you log food.") }
            } else {
                val (low, _, met) = watchList(p.avg, p.targets)
                item {
                    Card {
                        Hero(
                            "Vitamins and minerals · ${p.counted.size} day${if (p.counted.size == 1) "" else "s"} · ${rangeName(range)}",
                            "${met.first}", "of ${met.second} targets met",
                        )
                        Spacer(Modifier.height(Space.S3))
                        ScoreBar(met.first, met.second)
                        if (low.isNotEmpty()) {
                            Text(
                                "Lowest: " + low.take(3).joinToString(", ") { shortName(it) },
                                style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = Space.S3),
                            )
                        }
                        Text("Tap a nutrient to see where it comes from.", style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = Space.S2))
                    }
                }
                item {
                    RowGroup {
                        NutrientBreakdown(p.avg, p.targets, mode = BreakdownMode.Intake, onOpen = { d -> nav.navigate(com.nutrime.app.ui.Routes.trendsNutrient(d.id, range.encode())) })
                    }
                }
            }
        }
    }
}

/** One nutrient over time: average against target, a chart, where it comes from, and foods that have the most. */
@Composable
fun TrendsNutrientScreen(nav: NavController, nutrientId: Int, initialRange: String) {
    val def = Nutrients.byId[nutrientId] ?: Nutrients.all.first()
    val (range, setRange) = rememberRange(initialRange)
    val p = rememberPeriod(range)
    val colors = NutriTheme.colors
    val target = p.targets[def.id]
    var selected by remember(p.from, p.to) { mutableStateOf<Int?>(null) }
    SubPage(def.shortName.substringBefore(" ("), onBack = { nav.popBackStack() }, actions = { RangeControl(range, setRange) }) { padding ->
        Page(Modifier.padding(padding)) {
            if (p.counted.isEmpty()) {
                item { EmptyCard("No days logged in this range.") }
                return@Page
            }
            val avg = p.avg[def.id] ?: 0.0
            item {
                Card {
                    val pct = target?.min?.let { avg / it * 100 }
                    Hero(
                        "Daily average · ${rangeName(range)}",
                        fmtAmount(avg), def.unit,
                        status = when {
                            target?.max != null && avg > target.max!! -> "Over the limit of ${fmtAmount(target.max!!)} ${def.unit}"
                            pct != null -> "%.0f%% of target".format(pct)
                            else -> null
                        },
                        tone = when {
                            target?.max != null && avg > target.max!! -> Tone.Warn
                            pct != null && pct >= 100 -> Tone.Good
                            else -> Tone.Neutral
                        },
                    )
                    if (p.hasPrev) CompareLine(compareText(avg, p.prevAvg!![def.id] ?: 0.0, def.unit, p.prevLabel, if (avg < 10) 1 else 0))
                    Spacer(Modifier.height(Space.S4))
                    val buckets = remember(p.from, p.to) { TrendBuckets.buckets(p.from, p.to) }
                    val counted = p.counted.toSet()
                    val values = TrendBuckets.average(buckets, p.totals.mapValues { it.value[def.id] ?: 0.0 }, counted) { it.average() }
                    val size = buckets.first().size
                    val fmt = DateTimeFormatter.ofPattern(when (size) { BucketSize.DAY -> if (p.length <= 7) "EEEEE" else "MMM d"; BucketSize.WEEK -> "MMM d"; BucketSize.MONTH -> "MMM" })
                    MiniBarChart(values, buckets.map { it.from.format(fmt) }, target?.min, selected, { selected = it }, limit = target?.max)
                    selected?.let { i ->
                        Text(
                            buckets[i].from.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + "  " + (values[i]?.let { "${fmtAmount(it)} ${def.unit}" } ?: "Not logged"),
                            style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = Space.S2),
                        )
                    }
                }
            }
            val logged = p.items.map { LoggedItem(LocalDate.parse(it.entity.date), it.entity.meal, it.entity.foodId, it.entity.foodName, it.nutrients) }
            val sources = Insights.topSources(logged, def.id)
            if (sources.isNotEmpty()) {
                item { GroupLabel("Where it comes from") }
                item {
                    RowGroup {
                        sources.forEachIndexed { i, s ->
                            if (i > 0) RowDivider()
                            Column(Modifier.fillMaxWidth().padding(horizontal = Space.S4, vertical = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.name, style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("%.0f%%".format(s.share * 100), style = NutriTheme.type.numeric, color = colors.text)
                                }
                                Text("${fmtAmount(s.amount / p.counted.size.coerceAtLeast(1))} ${def.unit} a day · logged ${s.times}×", style = NutriTheme.type.caption, color = colors.textTertiary)
                                Spacer(Modifier.height(4.dp))
                                ProgressBar(s.share.toFloat(), colors.accent, height = 4.dp)
                            }
                        }
                    }
                }
            }
            val min = target?.min
            if (min != null && avg < min) {
                // Foods the user already eats, by how much one usual serving gives.
                val rich = logged.groupBy { it.foodId }.filterKeys { !it.startsWith("quick:") && !it.startsWith("import:") }
                    .map { (_, l) -> l.maxBy { it.date } }
                    .map { it to (it.nutrients[def.id] ?: 0.0) }
                    .filter { it.second > 0 }
                    .sortedByDescending { it.second }.take(5)
                if (rich.isNotEmpty()) {
                    item { GroupLabel("Your foods with the most") }
                    item {
                        RowGroup {
                            rich.forEachIndexed { i, (it, amount) ->
                                if (i > 0) RowDivider()
                                Row(Modifier.fillMaxWidth().padding(horizontal = Space.S4, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(it.name, style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${fmtAmount(amount)} ${def.unit} · %.0f%%".format(amount / min * 100), style = NutriTheme.type.small, color = colors.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
