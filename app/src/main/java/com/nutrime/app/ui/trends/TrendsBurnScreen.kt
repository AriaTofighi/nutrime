package com.nutrime.app.ui.trends

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.model.BurnMode
import com.nutrime.app.model.Confidence
import com.nutrime.app.model.Profile
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.CardTitle
import com.nutrime.app.ui.components.GroupLabel
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.LB_PER_KG
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.components.SheetLine
import com.nutrime.app.ui.components.Tone
import com.nutrime.app.ui.components.fmtAmount
import com.nutrime.app.ui.profile.NumberField
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.NutriTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.launch

/** The learned daily burn: the value, how sure it is, how it changed, and which burn the targets use. */
@Composable
fun TrendsBurnScreen(nav: NavController) {
    val c = appContainer()
    val profile by c.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    val est by remember { c.burn.observe() }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val colors = NutriTheme.colors
    fun save(p: Profile) = scope.launch { c.profiles.save(p) }
    SubPage("Daily burn", onBack = { nav.popBackStack() }) { padding ->
        Page(Modifier.padding(padding)) {
            val e = est
            item {
                Card {
                    if (e == null) {
                        Hero("From your body and activity", "%,.0f".format(profile.formulaTdee), "kcal")
                        Text("Log your weight a few times a week and log your food. After about two weeks NutriMe learns your real daily burn.", style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = Space.S2))
                    } else {
                        val learning = e.confidence == Confidence.LEARNING
                        Hero(
                            if (learning) "Learning your daily burn" else "Learned daily burn",
                            "%,d".format(e.burnKcal), "± %d kcal".format(e.burnSd),
                            status = when (e.confidence) { Confidence.HIGH -> "Sure"; Confidence.MEDIUM -> "Fairly sure"; Confidence.LEARNING -> "Learning" },
                            tone = if (e.confidence == Confidence.HIGH) Tone.Good else Tone.Neutral,
                        )
                        if (learning) Text("Needs " + e.missing.joinToString(", ") + ".", style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                        if (e.days.size >= 2) {
                            Spacer(Modifier.height(Space.S4))
                            val shown = e.days.takeLast(180)
                            val first = shown.first().date
                            fun x(d: LocalDate) = ChronoUnit.DAYS.between(first, d).toFloat()
                            val fmt = DateTimeFormatter.ofPattern("MMM d")
                            TrendLineChart(
                                line = shown.map { x(it.date) to it.burnKcal },
                                band = shown.map { Triple(x(it.date), it.burnKcal - it.burnSd, it.burnKcal + it.burnSd) },
                                refs = listOf(RefLine(e.formulaKcal.toDouble(), "Formula")),
                                xLabels = listOf(0f to first.format(fmt), x(shown.last().date) to shown.last().date.format(fmt)).distinctBy { it.first },
                                format = { "%,.0f".format(it) },
                            )
                        }
                    }
                }
            }
            if (e != null) {
                item {
                    RowGroup {
                        Column(Modifier.padding(Space.S4)) {
                            val d = e.burnKcal - e.formulaKcal
                            SheetLine("Formula says", "%,d kcal".format(e.formulaKcal))
                            SheetLine("Difference", (if (d >= 0) "+" else "−") + "%,d kcal".format(abs(d)))
                            SheetLine("Fully logged days used", "${e.knownDays}")
                            SheetLine("Weigh-ins used", "${e.weighIns}")
                            val conv = { kg: Double -> if (profile.imperial) kg * LB_PER_KG else kg }
                            val u = if (profile.imperial) "lb" else "kg"
                            SheetLine("Trend weight", "${fmtAmount(conv(e.trendKg))} $u")
                            SheetLine("Rate now", if (abs(e.ratePerWeekKg) < 0.02) "steady" else signed(conv(e.ratePerWeekKg), u) + " a week")
                        }
                    }
                }
            }
            item { GroupLabel("Burn used for your targets") }
            item {
                Card {
                    Segmented(
                        listOf(BurnMode.ADAPTIVE to "Learned", BurnMode.FORMULA to "Formula", BurnMode.MANUAL to "My own"),
                        profile.burnMode,
                        { m ->
                            val learned = e?.takeIf { it.confidence != Confidence.LEARNING }?.burnKcal
                            save(
                                profile.copy(
                                    burnMode = m, burnModeChosen = true,
                                    adaptiveBurnKcal = if (m == BurnMode.ADAPTIVE) profile.adaptiveBurnKcal ?: learned else profile.adaptiveBurnKcal,
                                    adaptiveBurnDate = if (m == BurnMode.ADAPTIVE && profile.adaptiveBurnKcal == null && learned != null) LocalDate.now().toString() else profile.adaptiveBurnDate,
                                ),
                            )
                        },
                        fill = true,
                    )
                    Spacer(Modifier.height(Space.S3))
                    when (profile.burnMode) {
                        BurnMode.ADAPTIVE -> {
                            Text(
                                if (profile.adaptiveBurnKcal != null) "Your targets use %,d kcal. Each week NutriMe asks before it changes this value, by 150 kcal at most.".format(profile.adaptiveBurnKcal)
                                else "Your targets use the formula until NutriMe is sure of your burn. Then it asks you each week.",
                                style = NutriTheme.type.small, color = colors.textSecondary,
                            )
                            Spacer(Modifier.height(Space.S2))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Update without asking", style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(profile.autoAcceptBurn, { save(profile.copy(autoAcceptBurn = it)) })
                            }
                        }
                        BurnMode.FORMULA -> Text(
                            "Resting burn × activity: %,.0f kcal.".format(profile.formulaTdee),
                            style = NutriTheme.type.small, color = colors.textSecondary,
                        )
                        BurnMode.MANUAL -> NumberField("Daily burn", profile.manualBurnKcal?.toString().orEmpty(), suffix = "kcal", placeholder = "%.0f".format(profile.formulaTdee)) { v ->
                            save(profile.copy(manualBurnKcal = v.toIntOrNull()?.takeIf { it in 1000..6000 }))
                        }
                    }
                }
            }
            item {
                Card {
                    CardTitle("How it works")
                    Spacer(Modifier.height(Space.S2))
                    listOf(
                        "Your weight changes by about 1 kg for each 7,700 kcal you eat above or below your burn. NutriMe compares what you log with how your trend weight moves, and from that learns your real burn.",
                        "Days that look partly logged are not used. Mark a day complete in the diary when you logged everything, including a day you ate little.",
                        "If you always log a little low, this value is also a little low. Your target is still correct, because it uses the same log.",
                    ).forEach { Text(it, style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.padding(bottom = Space.S2)) }
                }
            }
        }
    }
}
