package com.nutrime.app.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nutrime.app.AppContainer
import com.nutrime.app.data.Recipe
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.scaled
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
import com.nutrime.app.ui.components.ListRow
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NCard
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.NutrientBreakdown
import com.nutrime.app.ui.components.formatQty
import com.nutrime.app.ui.components.servingText
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class RecipeEditorViewModel(private val c: AppContainer, private val id: String?) : ViewModel() {
    val name = MutableStateFlow("")
    val servings = MutableStateFlow("1")
    val ingredients = MutableStateFlow<List<RecipeIngredient>>(emptyList())
    private var loaded = false

    init {
        IngredientPicker.picked.value = null
        if (id != null) viewModelScope.launch {
            c.foods.getRecipe(id)?.let { r ->
                name.value = r.name
                servings.value = formatQty(r.servings)
                ingredients.value = r.ingredients
            }
            loaded = true
        } else loaded = true
    }

    fun consumePicked() {
        val p = IngredientPicker.picked.value ?: return
        IngredientPicker.picked.value = null
        ingredients.value = ingredients.value + p
    }

    fun remove(index: Int) { ingredients.value = ingredients.value.filterIndexed { i, _ -> i != index } }

    suspend fun save(): Boolean {
        val s = servings.value.replace(',', '.').toDoubleOrNull() ?: return false
        if (name.value.isBlank() || ingredients.value.isEmpty() || s <= 0) return false
        c.foods.saveRecipe(id, name.value.trim(), s, ingredients.value)
        return true
    }

    suspend fun delete() { id?.let { c.foods.delete(it) } }
}

@Composable
fun RecipeEditorScreen(nav: NavController, id: String?) {
    val c = appContainer()
    val vm: RecipeEditorViewModel = viewModel { RecipeEditorViewModel(c, id) }
    val name by vm.name.collectAsStateWithLifecycle()
    val servingsText by vm.servings.collectAsStateWithLifecycle()
    val ingredients by vm.ingredients.collectAsStateWithLifecycle()
    val picked by IngredientPicker.picked.collectAsStateWithLifecycle()
    val profile by c.profiles.observe().collectAsStateWithLifecycle(initialValue = Profile())
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = NutriTheme.colors

    LaunchedEffect(picked) { vm.consumePicked() }

    val servings = servingsText.replace(',', '.').toDoubleOrNull()
    val recipe = Recipe(id ?: "", name, servings ?: 1.0, ingredients)
    val perServing = recipe.totalNutrients.scaled(1.0 / recipe.servings.coerceAtLeast(0.0001))
    val valid = name.isNotBlank() && ingredients.isNotEmpty() && (servings ?: 0.0) > 0

    SubPage(
        title = if (id == null) "New recipe" else "Edit recipe",
        onBack = { nav.popBackStack() },
        actions = { if (id != null) NIconButton(Lucide.Trash2, "Delete", { confirmDelete = true }) },
        bottomBar = {
            BottomAction {
                NButton("Save recipe", { scope.launch { if (vm.save()) nav.popBackStack() } }, kind = ButtonKind.Primary, size = ButtonSize.Lg, fill = true, enabled = valid, icon = Lucide.Check)
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = Space.S1, bottom = Space.S4),
        ) {
            NCard {
                CardHeader("Recipe")
                Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                    NTextField(name, { vm.name.value = it }, Modifier.fillMaxWidth(), label = "Name", placeholder = "e.g. Overnight oats")
                    NTextField(servingsText, { vm.servings.value = it }, Modifier.fillMaxWidth(), label = "Servings", keyboardType = KeyboardType.Decimal, isError = (servings ?: 0.0) <= 0)
                }
            }
            NCard(padding = PaddingValues(bottom = Space.S1)) {
                CardHeader(
                    "Ingredients",
                    Modifier.padding(start = Space.S4, end = Space.S2, top = Space.S3),
                    meta = if (ingredients.isNotEmpty()) "%,.0f g total".format(recipe.totalGrams) else null,
                    action = {
                        NButton("Add", { nav.navigate(Routes.search(LocalDate.now().toString(), 0, PickMode.INGREDIENT)) }, kind = ButtonKind.Ghost, size = ButtonSize.Sm, icon = Lucide.Plus)
                    },
                )
                if (ingredients.isEmpty()) {
                    EmptyState(com.nutrime.app.ui.theme.NutriIcons.Recipe, "No ingredients yet", "Add the foods that go into this recipe.")
                }
                ingredients.forEachIndexed { i, ing ->
                    Hairline(Modifier.padding(start = Space.S4))
                    ListRow(
                        ing.name,
                        subtitle = servingText(ing.servingLabel, ing.quantity),
                        icon = Lucide.Apple, iconTint = colors.energy,
                    ) {
                        Text("%.0f".format(ing.nutrients.forGrams(ing.grams)[N.ENERGY] ?: 0.0), style = NutriTheme.type.numeric, color = colors.text)
                        NIconButton(Lucide.X, "Remove", { vm.remove(i) }, size = 32.dp, tint = colors.textTertiary)
                    }
                }
            }
            if (ingredients.isNotEmpty()) {
                NCard {
                    CardHeader("Per serving", meta = "%.0f g".format(recipe.totalGrams / recipe.servings.coerceAtLeast(0.0001)))
                    com.nutrime.app.ui.components.Hero("Energy", "%,.0f".format(perServing[N.ENERGY] ?: 0.0), "kcal")
                    Spacer(Modifier.height(Space.S4))
                    com.nutrime.app.ui.components.MacroTiles(perServing)
                }
                com.nutrime.app.ui.components.RowGroup {
                    NutrientBreakdown(perServing, Dri.targets(profile), mode = com.nutrime.app.ui.components.BreakdownMode.Food)
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete recipe?", "Diary entries that used it keep their nutrition values.", "Delete",
            onConfirm = { confirmDelete = false; scope.launch { vm.delete(); nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
