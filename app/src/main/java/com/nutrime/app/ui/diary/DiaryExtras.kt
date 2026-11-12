package com.nutrime.app.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nutrime.app.data.DiaryItem
import com.nutrime.app.data.SavedMeal
import com.nutrime.app.model.AdaptiveBurn
import com.nutrime.app.model.BurnEstimate
import com.nutrime.app.model.Meal
import com.nutrime.app.model.Profile
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.DetailSheet
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.NDialog
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.RowDivider
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlin.math.abs

/** The prepared question behind "Review my week". */
const val WEEKLY_REVIEW =
    "Review my last 7 days: calories against my target, my trend weight and daily burn, my three weakest nutrients and where they come from, and one suggestion for next week."

/** Weekly check-in: the learned burn moved; update the target, or keep it. */
@Composable
fun CheckInCard(p: Profile, est: BurnEstimate, onAccept: () -> Unit, onKeep: () -> Unit, onReview: () -> Unit) {
    val c = NutriTheme.colors
    val (value, limited) = AdaptiveBurn.nextAccepted(p.tdee, est.burnKcal)
    val newTarget = p.copy(burnMode = com.nutrime.app.model.BurnMode.ADAPTIVE, adaptiveBurnKcal = value).let { it.calorieOverride ?: it.computedCalories }
    val first = !p.burnModeChosen
    Card {
        Text(if (first) "NutriMe learned your daily burn" else "Weekly check-in", style = NutriTheme.type.bodyMedium, color = c.text)
        Spacer(Modifier.height(4.dp))
        Text(
            (if (first) "From your log and weigh-ins, you burn about %,d kcal a day. The formula says %,.0f. ".format(est.burnKcal, p.tdee)
            else "Your daily burn is %,d kcal (was %,.0f). ".format(est.burnKcal, p.tdee)) +
                (if (p.calorieOverride != null) "You use a custom calorie target, so it does not change."
                else "New target: %,d kcal.".format(newTarget)) +
                (if (limited) " The change is limited to %,d kcal this week.".format(abs(value - p.tdee.toInt())) else ""),
            style = NutriTheme.type.small, color = c.textSecondary,
        )
        Spacer(Modifier.height(Space.S3))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.S2), verticalAlignment = Alignment.CenterVertically) {
            NButton(if (first) "Use it" else "Update target", onAccept, kind = ButtonKind.Primary)
            NButton("Keep", onKeep, kind = ButtonKind.Ghost)
            Spacer(Modifier.weight(1f))
            NButton("Review my week", onReview, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    NDialog(
        title = "Note for this day",
        onDismiss = onDismiss,
        confirm = { NButton("Save", { onSave(text.trim().ifEmpty { null }) }, kind = ButtonKind.Primary) },
        dismiss = { NButton(if (initial.isNotEmpty()) "Delete" else "Cancel", { if (initial.isNotEmpty()) onSave(null) else onDismiss() }, kind = ButtonKind.Ghost) },
    ) {
        NTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(), label = "Note", placeholder = "Ate out, felt hungry, training day…")
    }
}

/** Save some of the day's meals as one saved meal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveMealDialog(items: List<DiaryItem>, onDismiss: () -> Unit, onSave: (String, List<DiaryItem>, Int?) -> Unit) {
    val present = Meal.entries.filter { m -> items.any { it.meal == m } }
    var chosen by remember { mutableStateOf(present.toSet()) }
    var name by remember { mutableStateOf("") }
    val picked = items.filter { it.meal in chosen }
    NDialog(
        title = "Save as meal",
        onDismiss = onDismiss,
        confirm = {
            NButton("Save", { onSave(name.trim(), picked, chosen.singleOrNull()?.ordinal) }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && picked.isNotEmpty())
        },
        dismiss = { NButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
            NTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = "Name", placeholder = "Usual breakfast")
            if (present.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    present.forEach { m -> NChip(m.label, m in chosen, { chosen = if (m in chosen) chosen - m else chosen + m }) }
                }
            }
            Text(
                "${picked.size} food${if (picked.size == 1) "" else "s"} · %,.0f kcal".format(picked.sumOf { it.kcal }),
                style = NutriTheme.type.caption, color = NutriTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
fun SavedMealsSheet(meals: List<SavedMeal>, onDismiss: () -> Unit, onPick: (SavedMeal) -> Unit) {
    val c = NutriTheme.colors
    DetailSheet("Saved meals", if (meals.isEmpty()) null else "Tap one to add it", onDismiss) {
        if (meals.isEmpty()) {
            Text("No saved meals yet. In the diary menu, choose “Save as meal…” to keep foods you eat together.", style = NutriTheme.type.small, color = c.textSecondary)
        }
        meals.forEachIndexed { i, m ->
            if (i > 0) RowDivider(0.dp)
            Row(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Bookmark, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(Space.S3))
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = NutriTheme.type.body, color = c.text)
                    Text(
                        m.items.joinToString(", ") { it.name }.take(80) + " · %,.0f kcal".format(m.kcal),
                        style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.S3))
    }
}
