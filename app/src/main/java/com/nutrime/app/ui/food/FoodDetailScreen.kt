package com.nutrime.app.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.data.decodeNutrients
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.model.Dri
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.Meal
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Serving
import com.nutrime.app.model.forGrams
import com.nutrime.app.ui.BottomAction
import com.nutrime.app.ui.PickMode
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.CardHeader
import com.nutrime.app.ui.components.ConfirmDialog
import com.nutrime.app.ui.components.EmptyState
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.BreakdownMode
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.Hero
import com.nutrime.app.ui.components.MacroTiles
import com.nutrime.app.ui.components.RowGroup
import com.nutrime.app.ui.theme.DesignTokens.Control
import androidx.compose.ui.text.style.TextAlign
import com.nutrime.app.model.N
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NSelect
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.NutrientBreakdown
import com.nutrime.app.ui.components.Spinner
import com.nutrime.app.ui.components.formatQty
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Hand-off of a chosen ingredient from the food picker back to the recipe editor. */
object IngredientPicker {
    val picked = MutableStateFlow<RecipeIngredient?>(null)
}

@Composable
fun FoodDetailScreen(
    nav: NavController, foodId: String, date: String, meal: Int, mode: String, entryId: String?,
    initialQty: Double? = null, initialServing: String? = null, scanned: Boolean = false,
) {
    val c0 = appContainer()
    val scope = rememberCoroutineScope()
    var food by remember { mutableStateOf<Food?>(null) }
    var entry by remember { mutableStateOf<DiaryEntryEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var serving by remember { mutableStateOf<Serving?>(null) }
    var qtyText by remember { mutableStateOf("") }
    var selectedMeal by remember { mutableStateOf(Meal.entries[meal.coerceIn(0, 3)]) }
    val profile by c0.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    var lastTime by remember { mutableStateOf(false) }

    LaunchedEffect(foodId, entryId) {
        val e = entryId?.let { c0.diary.get(it) }
        var f = c0.foods.get(foodId)
        if (f == null && e != null) {
            // Food no longer available (e.g. deleted custom food): rebuild from the diary snapshot.
            f = Food(e.foodId, FoodSource.CUSTOM, e.foodName, e.brand, decodeNutrients(e.nutrientsJson),
                listOf(Serving(e.servingLabel, e.servingGrams)))
        }
        food = f
        entry = e
        if (e != null) {
            serving = f?.allServings?.firstOrNull { it.label == e.servingLabel && it.grams == e.servingGrams }
                ?: Serving(e.servingLabel, e.servingGrams)
            qtyText = formatQty(e.quantity)
            selectedMeal = Meal.entries[e.meal.coerceIn(0, 3)]
        } else if (f != null) {
            val last = if (mode == PickMode.LOG) c0.diary.lastOf(f.id) else null
            when {
                initialQty != null -> {
                    serving = f.allServings.firstOrNull { it.label == initialServing } ?: if (initialServing == "g") Serving("g", 1.0) else f.defaultServing
                    qtyText = formatQty(Math.round(initialQty * 100) / 100.0)
                }
                last != null -> {
                    // The usual amount: what the user logged last time.
                    serving = f.allServings.firstOrNull { it.label == last.servingLabel && it.grams == last.servingGrams } ?: Serving(last.servingLabel, last.servingGrams)
                    qtyText = formatQty(last.quantity)
                    lastTime = true
                }
                else -> {
                    serving = f.defaultServing
                    qtyText = formatQty(f.defaultQuantity)
                }
            }
        }
        loaded = true
    }

    val qty = qtyText.replace(',', '.').toDoubleOrNull()
    val f = food
    val s = serving
    val c = NutriTheme.colors
    var confirmDelete by remember { mutableStateOf(false) }

    fun submit() {
        val q = qty ?: return
        val food0 = f ?: return
        val serving0 = s ?: return
        scope.launch {
            when {
                mode == PickMode.INGREDIENT -> {
                    c0.foods.ensureSaved(food0)
                    IngredientPicker.picked.value = RecipeIngredient(food0.id, food0.name, serving0.label, serving0.grams, q, food0.nutrients)
                    nav.popBackStack("recipe?id={id}", inclusive = false)
                }
                entry != null -> {
                    c0.diary.update(entry!!.id, selectedMeal, serving0, q)
                    nav.popBackStack()
                }
                else -> {
                    c0.foods.ensureSaved(food0)
                    c0.diary.add(LocalDate.parse(date.ifEmpty { LocalDate.now().toString() }), selectedMeal, food0, serving0, q)
                    if (scanned) {
                        // Back to search, which offers to scan the next product.
                        ScanNext.added.value = food0.name
                        nav.popBackStack()
                    } else nav.popBackStack(Routes.DIARY, inclusive = false)
                }
            }
        }
    }

    SubPage(
        title = when {
            entry != null -> "Edit entry"
            mode == PickMode.INGREDIENT -> "Add ingredient"
            else -> "Add food"
        },
        onBack = { nav.popBackStack() },
        actions = {
            if (f != null) {
                val fav = f.id in profile.favoriteFoodIds
                NIconButton(Lucide.Star, if (fav) "Remove from favourites" else "Add to favourites", { scope.launch { c0.profiles.toggleFavorite(f.id) } }, tint = if (fav) c.accent else c.textSecondary)
            }
            if (f != null && (f.source == FoodSource.CUSTOM || f.source == FoodSource.RECIPE) && f.id.startsWith("custom:")) {
                NIconButton(Lucide.Pencil, "Edit food", {
                    nav.navigate(if (f.source == FoodSource.RECIPE) Routes.recipe(f.id) else Routes.customFood(f.id))
                })
            }
            if (entry != null) NIconButton(Lucide.Trash2, "Delete entry", { confirmDelete = true })
        },
        bottomBar = {
            if (f != null && s != null) {
                BottomAction {
                    NButton(
                        when {
                            mode == PickMode.INGREDIENT -> "Add ingredient"
                            entry != null -> "Save changes"
                            else -> "Add"
                        },
                        ::submit,
                        kind = ButtonKind.Primary, size = ButtonSize.Lg, fill = true, icon = if (entry != null) Lucide.Check else Lucide.Plus,
                        enabled = qty != null && qty > 0,
                    )
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Spinner(Modifier.size(22.dp)) }
            return@SubPage
        }
        if (f == null || s == null) {
            EmptyState(Lucide.Search, "Food not found", "It may have been deleted.", Modifier.padding(padding))
            return@SubPage
        }
        val grams = (qty ?: 0.0) * s.grams
        val totals = f.nutrients.forGrams(grams)
        val targets = Dri.targets(profile, runCatching { LocalDate.parse(date) }.getOrNull())
        val kcal = totals[N.ENERGY] ?: 0.0
        // Grams step by 10; servings by half a serving.
        val step = if (s.label == "g") 10.0 else 0.5
        fun stepBy(d: Double) {
            val next = (((qty ?: 0.0) + d) / step).let { Math.round(it) * step }.coerceAtLeast(step)
            qtyText = formatQty(Math.round(next * 100) / 100.0)
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = Space.S1, bottom = Space.S4),
        ) {
            Card {
                Text(f.name, style = NutriTheme.type.title, color = c.text)
                Text(listOfNotNull(f.brand, f.source.label, f.category).joinToString(" · "), style = NutriTheme.type.small, color = c.textTertiary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(Space.S4))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                    NIconButton(Lucide.Minus, "Less", { stepBy(-step) }, size = Control.HeightLg + 4.dp, bordered = true, tint = c.text)
                    NTextField(
                        qtyText, { qtyText = it }, Modifier.width(76.dp),
                        keyboardType = KeyboardType.Decimal, isError = qty == null || qty <= 0,
                        textStyle = NutriTheme.type.numeric.copy(textAlign = TextAlign.Center),
                    )
                    NSelect(
                        f.allServings, s, { if (it.label == "g") "g" else "${it.label} (${formatQty(Math.round(it.grams * 10) / 10.0)} g)" },
                        { new ->
                            // Keep the same gram amount when switching to/from raw grams.
                            if (new.label == "g" && s.label != "g" && qty != null) qtyText = formatQty(Math.round(qty * s.grams * 10) / 10.0)
                            else if (s.label == "g" && new.label != "g") qtyText = "1"
                            serving = new
                        },
                        Modifier.weight(1f),
                    )
                    NIconButton(Lucide.Plus, "More", { stepBy(step) }, size = Control.HeightLg + 4.dp, bordered = true, tint = c.text)
                }
                if (lastTime) Text("Last time's amount", style = NutriTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = Space.S2))
            }
            Card {
                Hero(
                    "Energy", "%,.0f".format(kcal), "kcal",
                    status = if (profile.calorieTarget > 0) "%.0f%% of your daily target".format(kcal / profile.calorieTarget * 100) else null,
                    spoken = "%,.0f kilocalories for %s grams".format(kcal, formatQty(Math.round(grams * 10) / 10.0)),
                )
                Spacer(Modifier.height(Space.S4))
                MacroTiles(totals)
                if (s.label != "g") {
                    Text("For ${formatQty(Math.round(grams * 10) / 10.0)} g", style = NutriTheme.type.caption, color = c.textTertiary, modifier = Modifier.padding(top = Space.S3))
                }
            }
            RowGroup { NutrientBreakdown(totals, targets, mode = BreakdownMode.Food) }
        }
    }

    if (confirmDelete && entry != null) {
        ConfirmDialog(
            "Delete entry?", "Remove ${entry!!.foodName} from your diary.", "Delete",
            onConfirm = { confirmDelete = false; scope.launch { c0.diary.delete(entry!!.id); nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
