package com.nutrime.app.ui.trends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.model.Goal
import com.nutrime.app.model.Profile
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.CardTitle
import com.nutrime.app.ui.components.GroupLabel
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.LB_PER_KG
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.RowDivider
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.components.SheetLine
import com.nutrime.app.ui.components.WeightDialog
import com.nutrime.app.ui.components.fmtAmount
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.launch

private val WEIGHT_RANGES = listOf(30 to "Month", 90 to "3 mo", 365 to "Year", 0 to "All")

/** Weight over time: the trend line, raw weigh-ins, the goal and a projection; and every weigh-in. */
@Composable
fun TrendsWeightScreen(nav: NavController) {
    val c = appContainer()
    val weights by c.weights.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val profile by c.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    val est by remember { c.burn.observe() }.collectAsStateWithLifecycle(initialValue = null)
    var showDialog by remember { mutableStateOf(false) }
    var window by rememberSaveable { mutableStateOf(90) }
    val scope = rememberCoroutineScope()
    val imperial = profile.imperial
    val unit = if (imperial) "lb" else "kg"
    fun conv(kg: Double) = if (imperial) kg * LB_PER_KG else kg
    val colors = NutriTheme.colors
    SubPage(
        "Weight",
        onBack = { nav.popBackStack() },
        actions = { NButton("Log", { showDialog = true }, size = ButtonSize.Sm, icon = Lucide.Plus) },
    ) { padding ->
        Page(Modifier.padding(padding)) {
            val e = est
            if (weights.isEmpty() || e == null) {
                item {
                    Card {
                        Text("No weigh-ins yet", style = NutriTheme.type.bodyMedium, color = colors.text)
                        Text("Log your weight to see your trend. Daily weigh-ins in the morning give the best trend.", style = NutriTheme.type.small, color = colors.textSecondary)
                        Spacer(Modifier.height(Space.S3))
                        NButton("Log weight", { showDialog = true }, kind = ButtonKind.Primary, icon = Lucide.Plus)
                    }
                }
                return@Page
            }
            val today = LocalDate.now()
            val shown = if (window == 0) e.days else e.days.filter { ChronoUnit.DAYS.between(it.date, today) < window }
            val monthAgo = e.days.firstOrNull { ChronoUnit.DAYS.between(it.date, today) <= 30 }
            val change = monthAgo?.let { conv(e.trendKg - it.trendKg) }?.takeIf { e.days.size > 7 }
            item {
                Card {
                    Hero(
                        "Trend weight", fmtAmount(conv(e.trendKg)), unit,
                        status = change?.let { signed(it, unit) + " in 30 days" },
                    )
                    Text(
                        "Rate now: " + (if (abs(e.ratePerWeekKg) < 0.02) "steady" else signed(conv(e.ratePerWeekKg), unit) + " a week"),
                        style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(Space.S3))
                    Segmented(WEIGHT_RANGES, window, { window = it })
                    Spacer(Modifier.height(Space.S3))
                    if (shown.size >= 2) {
                        val first = shown.first().date
                        fun x(d: LocalDate) = ChronoUnit.DAYS.between(first, d).toFloat()
                        val goal = profile.goalWeightKg
                        val proj = goal?.let { g ->
                            Goal.projectedDate(today, e.trendKg, g, e.ratePerWeekKg)?.takeIf { ChronoUnit.DAYS.between(today, it) in 1..365 }?.let { d ->
                                listOf(x(today) to conv(e.trendKg), x(d) to conv(g))
                            }
                        }.orEmpty()
                        val fmt = DateTimeFormatter.ofPattern("MMM d")
                        val lastX = proj.lastOrNull()?.first ?: x(shown.last().date)
                        TrendLineChart(
                            line = shown.map { x(it.date) to conv(it.trendKg) },
                            dots = shown.mapNotNull { d -> d.scaleKg?.let { x(d.date) to conv(it) } },
                            refs = listOfNotNull(goal?.let { RefLine(conv(it), "Goal") }),
                            projection = proj,
                            xLabels = listOf(0f to first.format(fmt), lastX to first.plusDays(lastX.toLong()).format(fmt)).distinctBy { it.first },
                            format = { fmtAmount(it) },
                        )
                        Text("Line: trend weight. Dots: weigh-ins. A single weigh-in can be up or down by about 1 kg from water and food.", style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = Space.S2))
                    }
                }
            }
            profile.goalWeightKg?.let { g ->
                item {
                    Card {
                        CardTitle("Goal", meta = "${fmtAmount(conv(g))} $unit")
                        Spacer(Modifier.height(Space.S2))
                        val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
                        if (Goal.reached(e.trendKg, g)) {
                            Text("You are at your goal weight.", style = NutriTheme.type.small, color = colors.textSecondary)
                        } else {
                            val atGoal = Goal.projectedDate(today, e.trendKg, g, profile.goalKgPerWeek)
                            val atNow = Goal.projectedDate(today, e.trendKg, g, e.ratePerWeekKg)
                            SheetLine("At your goal rate", atGoal?.format(fmt) ?: "—")
                            SheetLine("At your current rate", atNow?.format(fmt) ?: "Not at this rate")
                            Text("%s to go".format(fmtAmount(conv(abs(g - e.trendKg))) + " $unit"), style = NutriTheme.type.caption, color = colors.textTertiary)
                        }
                    }
                }
            }
            item { GroupLabel("Weigh-ins") }
            val trendOn = e.days.associate { it.date.toString() to it.trendKg }
            item {
                RowGroup {
                    weights.forEachIndexed { i, w ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(start = Space.S4, end = Space.S1, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LocalDate.parse(w.date).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), style = NutriTheme.type.body, color = colors.text)
                                trendOn[w.date]?.let { t ->
                                    val d = conv(w.weightKg - t)
                                    Text(if (abs(d) < 0.05) "on trend" else "%s%.1f %s from trend".format(if (d > 0) "+" else "−", abs(d), unit), style = NutriTheme.type.caption, color = colors.textTertiary)
                                }
                            }
                            Text(fmtAmount(conv(w.weightKg)), style = NutriTheme.type.numeric, color = colors.text)
                            Text(" $unit" + (w.bodyFatPct?.let { " · %.1f%%".format(it) } ?: ""), style = NutriTheme.type.small, color = colors.textTertiary)
                            Spacer(Modifier.width(Space.S1))
                            NIconButton(Lucide.X, "Delete weigh-in", { scope.launch { c.weights.delete(w.id) } }, size = 40.dp, tint = colors.textTertiary)
                        }
                    }
                }
            }
        }
    }
    if (showDialog) {
        WeightDialog(imperial, onDismiss = { showDialog = false }) { kg, bf ->
            showDialog = false
            scope.launch { c.weights.add(LocalDate.now(), kg, bf) }
        }
    }
}
