package com.nutrime.app.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.EmptyState
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.ListRow
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.Page
import com.nutrime.app.ui.components.PageTitle
import com.nutrime.app.ui.components.RowDivider
import com.nutrime.app.ui.components.groupRow
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import com.nutrime.app.ui.components.SwipeToDelete
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun MyFoodsScreen(nav: NavController) {
    val c = appContainer()
    val mine by c.foods.observeMine().collectAsStateWithLifecycle(initialValue = emptyList())
    val saved by c.foods.observeSaved().collectAsStateWithLifecycle(initialValue = emptyList())
    val meals by c.meals.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var editMeal by remember { mutableStateOf<com.nutrime.app.data.SavedMeal?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colors = NutriTheme.colors
    val list = (when (tab) { 0 -> mine.filter { it.source != FoodSource.RECIPE }; 1 -> mine.filter { it.source == FoodSource.RECIPE }; else -> saved }).filter {
        filter.isBlank() || it.name.contains(filter, ignoreCase = true) || it.brand?.contains(filter, ignoreCase = true) == true
    }

    Page {
        item {
            PageTitle("Foods") {
                if (tab == 1) NButton("New recipe", { nav.navigate(Routes.recipe()) }, size = ButtonSize.Sm, icon = com.nutrime.app.ui.theme.NutriIcons.Recipe)
                else NButton("New food", { nav.navigate(Routes.customFood()) }, size = ButtonSize.Sm, icon = Lucide.Plus)
            }
        }
        item {
            Column(Modifier.padding(horizontal = Space.S4, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                Segmented(listOf(0 to "Foods", 1 to "Recipes", 3 to "Meals", 2 to "Saved"), tab, { tab = it }, fill = true)
                if ((if (tab == 2) saved.size else mine.size) > 6 || filter.isNotBlank()) {
                    NTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = "Filter", leading = Lucide.Search)
                }
            }
        }
        when {
            tab == 3 -> {
                val shown = meals.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
                if (shown.isEmpty()) item {
                    Card { EmptyState(null, "No saved meals yet", "In the diary menu, choose “Save as meal…” to keep foods you eat together. Then add them in one tap.") }
                }
                shown.forEachIndexed { i, m ->
                    item(key = "meal-" + m.id) {
                        Column(Modifier.groupRow(first = i == 0, last = i == shown.lastIndex)) {
                            if (i > 0) RowDivider(48.dp)
                            SwipeToDelete(m.id, onDelete = { scope.launch { c.meals.delete(m.id) } }) {
                                ListRow(
                                    title = m.name,
                                    subtitle = m.items.joinToString(", ") { it.name },
                                    icon = Lucide.Bookmark, iconTint = colors.accent,
                                    onClick = { editMeal = m },
                                ) {
                                    Text("%,.0f kcal".format(m.kcal), style = NutriTheme.type.numeric, color = colors.text)
                                }
                            }
                        }
                    }
                }
                if (shown.isNotEmpty()) item {
                    Text("Swipe left to delete a meal. Tap one to rename it or remove foods.", style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(horizontal = Space.S5, vertical = Space.S3))
                }
            }
            list.isEmpty() && tab == 0 && filter.isBlank() -> item {
                Card {
                    EmptyState(
                        null, "No foods yet", "Create a food from its nutrition label.",
                        action = { NButton("Create a food", { nav.navigate(Routes.customFood()) }, kind = ButtonKind.Primary, icon = Lucide.Plus) },
                    )
                }
            }
            list.isEmpty() && tab == 1 && filter.isBlank() -> item {
                Card {
                    EmptyState(
                        null, "No recipes yet", "Build a recipe from its ingredients, like a dish you cook often.",
                        action = { NButton("Create a recipe", { nav.navigate(Routes.recipe()) }, kind = ButtonKind.Primary, icon = com.nutrime.app.ui.theme.NutriIcons.Recipe) },
                    )
                }
            }
            list.isEmpty() && filter.isBlank() -> item { Card { EmptyState(null, "Nothing saved yet", "Foods from USDA and Open Food Facts that you log show here, so they are quick to find again.") } }
            list.isEmpty() -> item { Card { EmptyState(null, "No matches", "") } }
            else -> {
                list.forEachIndexed { i, f ->
                    item(key = f.id) {
                        Column(Modifier.groupRow(first = i == 0, last = i == list.lastIndex)) {
                            if (i > 0) RowDivider(48.dp)
                            if (tab == 2) {
                                // Removing a saved food doesn't touch the diary: entries keep their own copy of the nutrients.
                                SwipeToDelete(f.id, onDelete = { scope.launch { c.foods.delete(f.id) } }) {
                                    FoodListRow(f) { nav.navigate(Routes.food(f.id, LocalDate.now().toString())) }
                                }
                            } else {
                                FoodListRow(f) { nav.navigate(Routes.food(f.id, LocalDate.now().toString())) }
                            }
                        }
                    }
                }
                if (tab == 2) item {
                    Text("Swipe left to remove a saved food. Your diary keeps its entries.", style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(horizontal = Space.S5, vertical = Space.S3))
                }
            }
        }
    }
    editMeal?.let { m ->
        MealEditor(m, onDismiss = { editMeal = null }) { name, items ->
            editMeal = null
            scope.launch { c.meals.save(m.id, name, items, m.defaultMeal) }
        }
    }
}

/** Rename a saved meal or remove foods from it. */
@Composable
private fun MealEditor(meal: com.nutrime.app.data.SavedMeal, onDismiss: () -> Unit, onSave: (String, List<com.nutrime.app.data.local.RecipeIngredient>) -> Unit) {
    var name by remember { mutableStateOf(meal.name) }
    var items by remember { mutableStateOf(meal.items) }
    val colors = NutriTheme.colors
    com.nutrime.app.ui.components.NDialog(
        title = "Edit meal",
        onDismiss = onDismiss,
        confirm = { NButton("Save", { onSave(name, items) }, kind = ButtonKind.Primary, enabled = name.isNotBlank() && items.isNotEmpty()) },
        dismiss = { NButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S2)) {
            NTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = "Name")
            items.forEachIndexed { i, it ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.name, style = NutriTheme.type.body, color = colors.text, maxLines = 1)
                        Text(com.nutrime.app.ui.components.servingText(it.servingLabel, it.quantity), style = NutriTheme.type.caption, color = colors.textTertiary)
                    }
                    com.nutrime.app.ui.components.NIconButton(Lucide.X, "Remove ${it.name}", { items = items.filterIndexed { j, _ -> j != i } }, size = 36.dp)
                }
            }
        }
    }
}

@Composable
private fun FoodListRow(f: Food, onClick: () -> Unit) {
    val colors = NutriTheme.colors
    val serving = f.servings.firstOrNull()
    val kcal = serving?.let { f.kcalPer100g * it.grams / 100 } ?: f.kcalPer100g
    ListRow(
        title = f.name,
        subtitle = listOfNotNull(f.brand, if (f.source == FoodSource.RECIPE) "Recipe" else f.source.label.takeIf { f.source != FoodSource.CUSTOM }).joinToString(" · "),
        icon = if (f.source == FoodSource.RECIPE) com.nutrime.app.ui.theme.NutriIcons.Recipe else Lucide.Apple,
        iconTint = if (f.source == FoodSource.RECIPE) colors.accent else colors.energy,
        onClick = onClick,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text("%.0f".format(kcal), style = NutriTheme.type.numeric, color = colors.text)
            Text(if (serving != null) "kcal / ${serving.label}" else "kcal / 100 g", style = NutriTheme.type.caption, color = colors.textTertiary, maxLines = 1)
        }
    }
}
