package com.nutrime.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.BuildConfig
import com.nutrime.app.data.Appearance
import com.nutrime.app.model.ActivityLevel
import com.nutrime.app.model.BurnMode
import com.nutrime.app.model.Goal
import com.nutrime.app.model.GoalWarning
import com.nutrime.app.model.ProteinMode
import com.nutrime.app.ui.trends.burnSource
import androidx.compose.material3.Slider
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientDef
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Sex
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import android.text.format.DateUtils
import androidx.compose.ui.text.style.TextOverflow
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.GroupLabel
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.NavRow
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.PageTitle
import com.nutrime.app.ui.components.RowDivider
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.components.SheetLine
import com.nutrime.app.ui.components.SplitBar
import com.nutrime.app.ui.components.Tone
import com.nutrime.app.ui.components.CardHeader
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.LB_PER_KG
import com.nutrime.app.ui.components.ListRow
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NCard
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.NDialog
import com.nutrime.app.ui.components.NSelect
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.SectionLabel
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.components.formatQty
import com.nutrime.app.ui.theme.DesignTokens
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import com.nutrime.app.ui.theme.ThemeMode
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val CM_PER_IN = 2.54

/** The profile's sub-pages. Each edits one part of the profile and saves as you go. */
enum class ProfileSection(val route: String, val title: String) {
    GOAL("goal", "Goal & calories"),
    MACROS("macros", "Macros"),
    TARGETS("targets", "Nutrient targets"),
    BODY("body", "Body & activity"),
    APPEARANCE("appearance", "Appearance"),
    CONNECTIONS("connections", "Connections"),
    REMINDERS("reminders", "Reminders"),
    DATA("data", "Your data"),
    ABOUT("about", "About"),
    ;

    companion object {
        fun of(route: String) = entries.firstOrNull { it.route == route }
    }
}

private val MACRO_PRESETS = listOf(
    Triple(25, 45, 30) to "Balanced", Triple(35, 35, 30) to "High protein",
    Triple(30, 20, 50) to "Low carb", Triple(20, 5, 75) to "Keto",
)

private fun goalLabel(kgPerWeek: Double, imperial: Boolean): String {
    if (kotlin.math.abs(kgPerWeek) < 0.001) return "Maintain weight"
    val amount = if (imperial) "${formatQty((kotlin.math.abs(kgPerWeek) * LB_PER_KG * 10).roundToInt() / 10.0)} lb"
        else "${formatQty(kotlin.math.abs(kgPerWeek))} kg"
    return "${if (kgPerWeek < 0) "Lose" else "Gain"} $amount / week"
}

/**
 * The profile tab: who you are (account and sync), the plan at a glance, then short lists that lead
 * to each setting. Rows show a value only when it needs attention, so the screen stays calm.
 */
@Composable
fun ProfileScreen(nav: NavController) {
    val c = appContainer()
    val p by c.profiles.observe().collectAsStateWithLifecycle(initialValue = null)
    val account by c.settings.account.collectAsStateWithLifecycle(initialValue = null)
    val colors = NutriTheme.colors
    val profile = p ?: return
    val targets = Dri.targets(profile)
    fun open(s: ProfileSection) = nav.navigate(Routes.profileSection(s.route))

    Page {
        item { PageTitle("Profile") }
        item {
            val a = account
            val signedIn = a?.loggedIn == true
            RowGroup {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Account and sync") { nav.navigate(Routes.ACCOUNT) }.padding(horizontal = Space.S4, vertical = Space.S3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                        if (signedIn) Text(a?.email.orEmpty().take(1).uppercase(), style = NutriTheme.type.title, color = colors.accent)
                        else Icon(Lucide.CloudOff, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(Space.S3))
                    Column(Modifier.weight(1f)) {
                        Text(if (signedIn) a?.email.orEmpty() else "Sign in to sync", style = NutriTheme.type.bodyMedium, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val (line, bad) = when {
                            a?.lastError != null -> "Sync problem. Tap to see why." to true
                            signedIn && (a?.lastSync ?: 0) > 0 -> "Synced " + DateUtils.getRelativeTimeSpanString(a!!.lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) to false
                            signedIn -> "Not synced yet" to false
                            else -> "Keep your diary on all your devices" to false
                        }
                        Text(line.toString(), style = NutriTheme.type.caption, color = if (bad) colors.danger else colors.textTertiary, maxLines = 1)
                    }
                    Icon(Lucide.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            Card(onClick = { open(ProfileSection.GOAL) }, onClickLabel = "Change goal") {
                Hero(
                    "Daily target", "%,d".format(profile.calorieTarget), "kcal",
                    status = goalLabel(profile.goalKgPerWeek, profile.imperial).let { if (it.startsWith("Maintain")) it else it.replace(" / week", " a week") },
                    tone = Tone.Good,
                )
                Spacer(Modifier.height(Space.S4))
                val split = Dri.macroGrams(profile)
                SplitBar(split.protein * 4, split.carbs * 4, split.fat * 9)
                Spacer(Modifier.height(Space.S2))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("protein" to N.PROTEIN, "carbs" to N.CARBS, "fat" to N.FAT).forEach { (name, id) ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%.0f g".format(targets[id]?.min ?: 0.0), style = NutriTheme.type.small.copy(fontWeight = NutriTheme.type.cardTitle.fontWeight, fontFeatureSettings = "tnum"), color = colors.text)
                            Text(" $name", style = NutriTheme.type.small, color = colors.textSecondary)
                        }
                    }
                }
            }
        }

        item { GroupLabel("Plan") }
        item {
            RowGroup {
                NavRow("Goal & calories", Lucide.Target) { open(ProfileSection.GOAL) }
                RowDivider(48.dp)
                val sum = if (profile.proteinMode == ProteinMode.PER_KG) 100 else profile.proteinPct + profile.carbsPct + profile.fatPct
                NavRow("Macros", Lucide.ChartPie, value = if (sum != 100) "Adds up to $sum%" else null, tone = Tone.Bad) { open(ProfileSection.MACROS) }
                RowDivider(48.dp)
                NavRow("Nutrient targets", Lucide.ListChecks, value = if (profile.targetOverrides.isEmpty()) null else "${profile.targetOverrides.size} custom") { open(ProfileSection.TARGETS) }
                RowDivider(48.dp)
                NavRow("Body & activity", Lucide.Ruler) { open(ProfileSection.BODY) }
            }
        }

        item { GroupLabel("App") }
        item {
            RowGroup {
                NavRow("Appearance", Lucide.Palette) { open(ProfileSection.APPEARANCE) }
                RowDivider(48.dp)
                NavRow("Reminders", Lucide.Bell) { open(ProfileSection.REMINDERS) }
                RowDivider(48.dp)
                NavRow("Connections", Lucide.Activity, subtitle = "Health Connect") { open(ProfileSection.CONNECTIONS) }
                RowDivider(48.dp)
                NavRow("Your data", Lucide.Download, subtitle = "Export") { open(ProfileSection.DATA) }
                RowDivider(48.dp)
                NavRow("About & data sources", Lucide.Info) { open(ProfileSection.ABOUT) }
            }
        }
    }
}

/**
 * The profile being edited on a sub-page, and a function that changes it. Changes are saved shortly
 * after the last edit, and when the page closes.
 */
@OptIn(DelicateCoroutinesApi::class)
@Composable
private fun rememberProfileEditor(): Pair<Profile?, (Profile) -> Unit> {
    val c = appContainer()
    val saved by c.profiles.observe().collectAsStateWithLifecycle(initialValue = null)
    var draft by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(saved) { if (saved != null && draft == null) draft = saved }
    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        if (d != saved) {
            delay(400)
            c.profiles.save(d.copy(configured = true))
        }
    }
    val latest by rememberUpdatedState(draft)
    val latestSaved by rememberUpdatedState(saved)
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the page within the delay above must not lose the last edit.
            val d = latest
            if (d != null && d != latestSaved) GlobalScope.launch { c.profiles.save(d.copy(configured = true)) }
        }
    }
    return draft to { p: Profile -> draft = p }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileSectionScreen(nav: NavController, route: String) {
    val section = ProfileSection.of(route) ?: ProfileSection.GOAL
    val c = appContainer()
    val (draft, update) = rememberProfileEditor()
    val appearance by c.settings.appearance.collectAsStateWithLifecycle(initialValue = Appearance(ThemeMode.SYSTEM, DesignTokens.Accent.Default))
    val scope = rememberCoroutineScope()
    var editTarget by remember { mutableStateOf<NutrientDef?>(null) }
    val colors = NutriTheme.colors

    SubPage(section.title, onBack = { nav.popBackStack() }) { padding ->
        val p = draft ?: return@SubPage
        val targets = Dri.targets(p)
        Page(Modifier.padding(padding)) {
            when (section) {
                ProfileSection.GOAL -> goalSection(p, update) { nav.navigate(Routes.TRENDS_BURN) }
                ProfileSection.MACROS -> item { MacrosSection(p, update) }
                ProfileSection.BODY -> item { BodySection(p, update) }
                ProfileSection.CONNECTIONS -> item { ConnectionsSection() }
                ProfileSection.REMINDERS -> item { RemindersSection() }
                ProfileSection.DATA -> item { DataSection() }
                ProfileSection.TARGETS -> {
                    item {
                        Text(
                            "Based on Dietary Reference Intakes for your age and sex. Tap a nutrient to set your own target.",
                            style = NutriTheme.type.small, color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = Space.S5, vertical = Space.S2),
                        )
                    }
                    item {
                        RowGroup {
                            Nutrients.all.filter { targets[it.id] != null && it.id !in setOf(N.ENERGY, N.PROTEIN, N.CARBS, N.FAT) }.forEachIndexed { i, def ->
                                if (i > 0) RowDivider()
                                val t = targets[def.id]!!
                                Row(
                                    Modifier.fillMaxWidth().clickable { editTarget = def }.padding(horizontal = Space.S4, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(def.name, style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                                    val text = listOfNotNull(
                                        t.min?.let { "≥ " + Nutrients.format(it, def.unit) },
                                        t.max?.let { "≤ " + Nutrients.format(it, def.unit) },
                                    ).joinToString("   ")
                                    Text(text, style = NutriTheme.type.small.copy(fontFeatureSettings = "tnum"), color = if (def.id in p.targetOverrides) colors.accent else colors.textSecondary)
                                }
                            }
                        }
                    }
                }
                ProfileSection.ABOUT -> item {
                    RowGroup {
                        Column(Modifier.padding(Space.S4), verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                            Text("NutriMe ${BuildConfig.VERSION_NAME}", style = NutriTheme.type.title, color = colors.text)
                            Text("Build ${BuildConfig.VERSION_CODE}", style = NutriTheme.type.small, color = colors.textTertiary)
                            AboutPart("Targets", "Calorie targets use the Mifflin-St Jeor equation and your activity level. Vitamin and mineral targets use the Dietary Reference Intakes for your age and sex. They are general guidance, not medical advice.")
                            AboutPart("Food data", "USDA FoodData Central (SR Legacy, Foundation and FNDDS), Open Food Facts (Open Database License), and foods that NutriMe checked from package labels.")
                        }
                    }
                }
                ProfileSection.APPEARANCE -> item {
                    NCard {
                        FieldLabel("Theme")
                        Segmented(ThemeMode.entries.map { it to it.label }, appearance.mode, { m -> scope.launch { c.settings.setAppearance(mode = m) } })
                        Spacer(Modifier.height(Space.S5))
                        FieldLabel("Accent")
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.S3)) {
                            DesignTokens.Accent.entries.forEach { a ->
                                val swatch = Color.hsl(a.hue, a.saturation, a.swatchLightness)
                                val on = a == appearance.accent
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { scope.launch { c.settings.setAppearance(accent = a) } }) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape)
                                            .border(2.dp, if (on) swatch else Color.Transparent, CircleShape)
                                            .padding(4.dp).clip(CircleShape).background(swatch),
                                        contentAlignment = Alignment.Center,
                                    ) { if (on) Icon(Lucide.Check, null, tint = colors.onAccent, modifier = Modifier.size(14.dp)) }
                                    Spacer(Modifier.height(4.dp))
                                    Text(a.label, style = NutriTheme.type.caption, color = if (on) colors.text else colors.textTertiary)
                                }
                            }
                        }
                    }
                }
            }
        }

        editTarget?.let { def ->
            var text by remember(def) { mutableStateOf(p.targetOverrides[def.id]?.let { formatQty(it) } ?: "") }
            val default = Dri.targets(p.copy(targetOverrides = emptyMap()))[def.id]?.min
            NDialog(
                title = def.name,
                onDismiss = { editTarget = null },
                confirm = {
                    NButton(if (text.isBlank()) "Reset to default" else "Save", {
                        val v = text.replace(',', '.').toDoubleOrNull()
                        update(p.copy(targetOverrides = if (v == null) p.targetOverrides - def.id else p.targetOverrides + (def.id to v)))
                        editTarget = null
                    }, kind = ButtonKind.Primary)
                },
                dismiss = { NButton("Cancel", { editTarget = null }, kind = ButtonKind.Ghost) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                    Text("Default: " + (default?.let { Nutrients.format(it, def.unit) } ?: "none"), style = NutriTheme.type.small, color = colors.textSecondary)
                    NTextField(text, { text = it }, Modifier.fillMaxWidth(), label = "Daily target", suffix = def.unit, keyboardType = KeyboardType.Decimal, placeholder = "Default")
                }
            }
        }
    }
}

@Composable
private fun AboutPart(title: String, body: String) {
    Column {
        Text(title, style = NutriTheme.type.bodyMedium, color = NutriTheme.colors.text)
        Text(body, style = NutriTheme.type.small, color = NutriTheme.colors.textSecondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.goalSection(p: Profile, update: (Profile) -> Unit, openBurn: () -> Unit) {
    item {
        // How the target is made: daily burn (formula, learned or typed), then the weekly goal.
        val goalKcal = p.goalKcalPerDay
        Card {
            Hero("Daily target", "%,d".format(p.calorieTarget), "kcal", status = if (p.calorieOverride != null) "Custom target" else null, tone = Tone.Neutral)
            Spacer(Modifier.height(Space.S3))
            if (p.burnMode == BurnMode.FORMULA || p.tdee == p.formulaTdee) {
                SheetLine("Resting burn", "%,.0f kcal".format(p.bmr))
                SheetLine("Activity", "+ %,.0f kcal".format(p.formulaTdee - p.bmr))
            }
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Daily burn details", onClick = openBurn), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SheetLine("Daily burn", "%,.0f kcal".format(p.tdee), strong = true)
                    Text(burnSource(p, null).replaceFirstChar { it.uppercase() }, style = NutriTheme.type.caption, color = NutriTheme.colors.textTertiary)
                }
                Icon(Lucide.ChevronRight, null, tint = NutriTheme.colors.textTertiary, modifier = Modifier.size(16.dp))
            }
            SheetLine(
                goalLabel(p.goalKgPerWeek, p.imperial),
                if (kotlin.math.abs(goalKcal) < 1) "± 0 kcal" else (if (goalKcal < 0) "− " else "+ ") + "%,.0f kcal".format(kotlin.math.abs(goalKcal)),
            )
            Goal.warnings(p).forEach { w ->
                Text(Goal.warningText(w), style = NutriTheme.type.caption, color = if (w == GoalWarning.AT_FLOOR) NutriTheme.colors.textTertiary else NutriTheme.colors.warning, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    item { GoalCard(p, update) }
    item {
        NCard {
            CardHeader("Daily calories")
            NumberField("Custom calorie target", p.calorieOverride?.toString().orEmpty(), suffix = "kcal", placeholder = "${p.computedCalories} suggested") { v ->
                update(p.copy(calorieOverride = v.toIntOrNull()?.takeIf { it in 800..10000 }))
            }
            Text(
                "Leave blank to use the suggestion: %,d kcal.".format(p.computedCalories),
                style = NutriTheme.type.caption, color = NutriTheme.colors.textTertiary, modifier = Modifier.padding(top = Space.S2),
            )
        }
    }
    item { ScheduleCard(p, update) }
}

private enum class Direction { LOSE, MAINTAIN, GAIN }

@Composable
private fun GoalCard(p: Profile, update: (Profile) -> Unit) {
    val colors = NutriTheme.colors
    val dir = when { p.goalKgPerWeek < -0.001 -> Direction.LOSE; p.goalKgPerWeek > 0.001 -> Direction.GAIN; else -> Direction.MAINTAIN }
    val unit = if (p.imperial) "lb" else "kg"
    fun conv(kg: Double) = if (p.imperial) kg * LB_PER_KG else kg
    NCard {
        CardHeader("Goal")
        Segmented(
            listOf(Direction.LOSE to "Lose", Direction.MAINTAIN to "Maintain", Direction.GAIN to "Gain"), dir,
            { d ->
                val rate = kotlin.math.abs(p.goalKgPerWeek).takeIf { it > 0.001 } ?: 0.25
                update(p.copy(goalKgPerWeek = when (d) { Direction.LOSE -> -rate.coerceAtMost(Goal.MAX_LOSS); Direction.GAIN -> rate.coerceAtMost(Goal.MAX_GAIN); Direction.MAINTAIN -> 0.0 }))
            },
            fill = true,
        )
        Spacer(Modifier.height(Space.S3))
        NumberField(
            "Goal weight (optional)", p.goalWeightKg?.let { formatQty((conv(it) * 10).roundToInt() / 10.0) }.orEmpty(), suffix = unit,
        ) { v -> update(p.copy(goalWeightKg = v.toDoubleOrNull()?.takeIf { it in 20.0..700.0 }?.let { if (p.imperial) it / LB_PER_KG else it })) }
        if (dir != Direction.MAINTAIN) {
            val max = if (dir == Direction.LOSE) Goal.MAX_LOSS else Goal.MAX_GAIN
            val step = if (p.imperial) 0.1 / LB_PER_KG else 0.05
            val rate = kotlin.math.abs(p.goalKgPerWeek)
            Spacer(Modifier.height(Space.S3))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Rate", style = NutriTheme.type.small, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text("%s %s a week".format(formatQty((conv(rate) * 100).roundToInt() / 100.0), unit), style = NutriTheme.type.bodyMedium, color = colors.text)
            }
            Slider(
                value = rate.toFloat(),
                onValueChange = { v ->
                    val snapped = (kotlin.math.round(v / step) * step).coerceIn(step, max)
                    update(p.copy(goalKgPerWeek = if (dir == Direction.LOSE) -snapped else snapped))
                },
                valueRange = step.toFloat()..max.toFloat(),
            )
            Text(
                "%.1f%% of your body weight a week · %,.0f kcal a day".format(rate / p.weightKg * 100, rate * Profile.KCAL_PER_KG / 7),
                style = NutriTheme.type.caption, color = colors.textTertiary,
            )
        }
        val goal = p.goalWeightKg
        if (goal != null) {
            val date = Goal.projectedDate(java.time.LocalDate.now(), p.weightKg, goal, p.goalKgPerWeek)
            val weeks = Goal.weeksToGoal(p.weightKg, goal, p.goalKgPerWeek)
            val text = when {
                Goal.reached(p.weightKg, goal) -> "You are at your goal weight."
                date != null && weeks != null -> "About %d weeks · around %s".format(kotlin.math.ceil(weeks).toInt(), date.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy")))
                dir == Direction.MAINTAIN -> "Choose Lose or Gain to see a date."
                // The target card above already says the goal weight is in the other direction.
                else -> null
            }
            text?.let { Text(it, style = NutriTheme.type.small, color = colors.text, modifier = Modifier.padding(top = Space.S3)) }
        }
    }
}

private val WEEKDAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun ScheduleCard(p: Profile, update: (Profile) -> Unit) {
    val colors = NutriTheme.colors
    var touched by remember { mutableStateOf(setOf<Int>()) }
    NCard {
        CardHeader("Calorie schedule")
        Text(
            "Eat more on some days and less on others. The week keeps the same total.",
            style = NutriTheme.type.small, color = colors.textSecondary,
        )
        Spacer(Modifier.height(Space.S3))
        Segmented(
            listOf(false to "Same every day", true to "Higher weekends"), p.calorieSchedule != null,
            { on -> touched = emptySet(); update(p.copy(calorieSchedule = if (on) Goal.higherWeekends(250) else null)) },
            fill = true,
        )
        val s = p.calorieSchedule
        if (s != null && s.size == 7) {
            Spacer(Modifier.height(Space.S3))
            WEEKDAY_SHORT.chunked(4).forEachIndexed { row, days ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S2), modifier = Modifier.padding(bottom = Space.S2)) {
                    days.forEachIndexed { j, name ->
                        val i = row * 4 + j
                        NumberField(name, s[i].toString(), Modifier.weight(1f), "kcal") { v ->
                            val n = v.toIntOrNull() ?: return@NumberField
                            if (n == s[i] || kotlin.math.abs(n) > 1500) return@NumberField
                            touched = touched + i
                            update(p.copy(calorieSchedule = Goal.editSchedule(s, i, n, touched)))
                        }
                    }
                    if (days.size < 4) Spacer(Modifier.weight((4 - days.size).toFloat()))
                }
            }
            Text(
                WEEKDAY_SHORT.indices.joinToString(" · ") { "${WEEKDAY_SHORT[it]} %,d".format(p.calorieTarget + s[it]) },
                style = NutriTheme.type.caption, color = colors.textTertiary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacrosSection(p: Profile, update: (Profile) -> Unit) {
    val colors = NutriTheme.colors
    val kcal = p.calorieTarget.toDouble()
    val g = Dri.macroGrams(p, kcal)
    NCard {
        Segmented(listOf(ProteinMode.PERCENT to "Percent", ProteinMode.PER_KG to "Protein per kg"), p.proteinMode, { update(p.copy(proteinMode = it)) }, fill = true)
        Spacer(Modifier.height(Space.S4))
        if (p.proteinMode == ProteinMode.PERCENT) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MACRO_PRESETS.forEach { (t, label) ->
                    NChip(label, p.proteinPct == t.first && p.carbsPct == t.second && p.fatPct == t.third, {
                        update(p.copy(proteinPct = t.first, carbsPct = t.second, fatPct = t.third))
                    })
                }
            }
            Spacer(Modifier.height(Space.S4))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                NumberField("Protein", p.proteinPct.toString(), Modifier.weight(1f), "%") { v -> v.toIntOrNull()?.let { update(p.copy(proteinPct = it)) } }
                NumberField("Carbs", p.carbsPct.toString(), Modifier.weight(1f), "%") { v -> v.toIntOrNull()?.let { update(p.copy(carbsPct = it)) } }
                NumberField("Fat", p.fatPct.toString(), Modifier.weight(1f), "%") { v -> v.toIntOrNull()?.let { update(p.copy(fatPct = it)) } }
            }
            val sum = p.proteinPct + p.carbsPct + p.fatPct
            Text(
                if (sum != 100) "Adds up to $sum% — should be 100%"
                else "%.0f g protein · %.0f g carbs · %.0f g fat per day".format(g.protein, g.carbs, g.fat),
                style = NutriTheme.type.caption, color = if (sum != 100) colors.danger else colors.textTertiary,
                modifier = Modifier.padding(top = Space.S2),
            )
        } else {
            val unit = if (p.imperial) "g/lb" else "g/kg"
            val shown = if (p.imperial) p.proteinGPerKg / LB_PER_KG else p.proteinGPerKg
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                NumberField("Protein", formatQty((shown * 100).roundToInt() / 100.0), Modifier.weight(1f), unit) { v ->
                    v.toDoubleOrNull()?.let { if (p.imperial) it * LB_PER_KG else it }?.takeIf { it in 0.5..3.5 }?.let { update(p.copy(proteinGPerKg = it)) }
                }
                NumberField("Fat", p.fatPct.toString(), Modifier.weight(1f), "%") { v -> v.toIntOrNull()?.takeIf { it in 5..90 }?.let { update(p.copy(fatPct = it)) } }
            }
            val (pp, cp, fp) = g.percents(kcal)
            val over = g.protein * 4 + g.fat * 9 > kcal
            Text(
                if (over) "Protein and fat use more than your calories. Lower one of them."
                else "%.0f g protein (%d%%) · %.0f g fat (%d%%) · %.0f g carbs, the rest (%d%%)".format(g.protein, pp, g.fat, fp, g.carbs, cp),
                style = NutriTheme.type.caption, color = if (over) colors.danger else colors.textTertiary, modifier = Modifier.padding(top = Space.S2),
            )
            Text("Common ranges: 1.2–1.6 g/kg for health, 1.6–2.2 g/kg to keep or build muscle while you lose weight.", style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = Space.S2))
        }
    }
}

@Composable
private fun BodySection(p: Profile, update: (Profile) -> Unit) {
    NCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
            Segmented(listOf(Sex.MALE to "Male", Sex.FEMALE to "Female"), p.sex, { update(p.copy(sex = it)) })
            NumberField("Birth year", p.birthYear.toString()) { v -> v.toIntOrNull()?.takeIf { it in 1900..2025 }?.let { update(p.copy(birthYear = it)) } }
            if (p.imperial) {
                val totalIn = p.heightCm / CM_PER_IN
                Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                    NumberField("Height", (totalIn / 12).toInt().toString(), Modifier.weight(1f), "ft") { v ->
                        v.toIntOrNull()?.let { ft -> update(p.copy(heightCm = (ft * 12 + (totalIn % 12).roundToInt()) * CM_PER_IN)) }
                    }
                    NumberField(" ", (totalIn % 12).roundToInt().toString(), Modifier.weight(1f), "in") { v ->
                        v.toIntOrNull()?.let { inch -> update(p.copy(heightCm = ((totalIn / 12).toInt() * 12 + inch) * CM_PER_IN)) }
                    }
                }
                NumberField("Weight", formatQty((p.weightKg * LB_PER_KG * 10).roundToInt() / 10.0), suffix = "lb") { v ->
                    v.toDoubleOrNull()?.takeIf { it > 0 }?.let { update(p.copy(weightKg = it / LB_PER_KG)) }
                }
            } else {
                NumberField("Height", formatQty(p.heightCm.roundToInt().toDouble()), suffix = "cm") { v ->
                    v.toDoubleOrNull()?.takeIf { it > 0 }?.let { update(p.copy(heightCm = it)) }
                }
                NumberField("Weight", formatQty((p.weightKg * 10).roundToInt() / 10.0), suffix = "kg") { v ->
                    v.toDoubleOrNull()?.takeIf { it > 0 }?.let { update(p.copy(weightKg = it)) }
                }
            }
            NSelect(
                ActivityLevel.entries, p.activity, { "${it.label} — ${it.description}" }, { update(p.copy(activity = it)) },
                fieldLabel = "Activity level",
            )
            FieldLabel("Units")
            Segmented(listOf(false to "Metric (kg, cm)", true to "Imperial (lb, in)"), p.imperial, { update(p.copy(imperial = it)) })
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, style = NutriTheme.type.small.copy(fontWeight = NutriTheme.type.bodyMedium.fontWeight),
        color = NutriTheme.colors.textSecondary, modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Numeric field that keeps its own text while typing and reports raw text changes. */
@Composable
fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    suffix: String? = null,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    var text by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value.toDoubleOrNull()) text = value
    }
    NTextField(
        text, { text = it; onChange(it.replace(',', '.')) }, modifier,
        label = label, suffix = suffix, placeholder = placeholder, keyboardType = KeyboardType.Decimal,
        textStyle = NutriTheme.type.numeric.copy(fontWeight = NutriTheme.type.body.fontWeight),
    )
}
