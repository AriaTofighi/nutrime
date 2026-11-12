package com.nutrime.app.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import com.nutrime.app.model.Suggestions
import com.nutrime.app.ui.components.CardHeader
import com.nutrime.app.ui.theme.DesignTokens.Space
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.model.Nutrients
import com.nutrime.app.ui.components.BreakdownMode
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.GroupLabel
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.components.ScoreBar
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.components.NutrientBreakdown
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Every nutrient for one day against its target, opened by tapping the Diary summary. */
@Composable
fun NutrientsScreen(nav: NavController, dateArg: String) {
    val c = appContainer()
    val date = remember(dateArg) { runCatching { LocalDate.parse(dateArg) }.getOrDefault(LocalDate.now()) }
    val items by remember(date) { c.diary.observeDay(date) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val profile by c.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    val totals = sumNutrients(items.map { it.nutrients })
    val title = when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    val recent by c.diary.observeRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    val targets = Dri.targets(profile)
    val suggestions = Suggestions.suggest(totals, targets, recent, items.map { it.entity.foodId }.toSet(), count = 3)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Vitamins and minerals with a minimum target: the "targets met" score at the top.
    val skip = setOf(N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.WATER)
    val scored = Nutrients.all.filter { (targets[it.id]?.min ?: 0.0) > 0 && it.id !in skip }
    val met = scored.count { (totals[it.id] ?: 0.0) >= targets[it.id]!!.min!! }
    SubPage(title, onBack = { nav.popBackStack() }, snackbar = snackbar) { padding ->
        Page(Modifier.padding(padding)) {
            item {
                Card {
                    Hero("Vitamins and minerals", "$met", "of ${scored.size} targets met", spoken = "$met of ${scored.size} vitamin and mineral targets met")
                    Spacer(Modifier.height(Space.S3))
                    ScoreBar(met, scored.size)
                }
            }
            if (suggestions.isNotEmpty()) {
                item { GroupLabel("Suggestions") }
                items(suggestions.size) { i ->
                    SuggestionRow(suggestions[i], onLog = {
                        scope.launch {
                            val id = c.diary.relog(it.entry, date)
                            val r = snackbar.showSnackbar("Added ${it.entry.foodName}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                            if (r == SnackbarResult.ActionPerformed) c.diary.delete(id)
                        }
                    }, modifier = Modifier.padding(horizontal = Space.S4, vertical = 4.dp))
                }
            }
            item { GroupLabel("All nutrients") }
            item { RowGroup { NutrientBreakdown(totals, targets, mode = BreakdownMode.Intake) } }
        }
    }
}
