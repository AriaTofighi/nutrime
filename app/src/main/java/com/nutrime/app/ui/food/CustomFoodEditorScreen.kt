package com.nutrime.app.ui.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientGroup
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Serving
import com.nutrime.app.ui.BottomAction
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.CardHeader
import com.nutrime.app.ui.components.ConfirmDialog
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NCard
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.SectionLabel
import com.nutrime.app.ui.components.formatQty
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch

@Composable
fun CustomFoodEditorScreen(nav: NavController, id: String?, initialBarcode: String) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf(initialBarcode) }
    var servingLabel by remember { mutableStateOf("serving") }
    var servingGrams by remember { mutableStateOf("100") }
    val values = remember { mutableStateMapOf<Int, String>() }
    var showAll by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val colors = NutriTheme.colors

    LaunchedEffect(id) {
        if (id == null) return@LaunchedEffect
        val f = c.foods.get(id) ?: return@LaunchedEffect
        name = f.name
        brand = f.brand.orEmpty()
        barcode = f.barcode.orEmpty()
        val s = f.servings.firstOrNull() ?: Serving("100 g", 100.0)
        servingLabel = s.label
        servingGrams = formatQty(s.grams)
        f.nutrients.forEach { (nid, v) -> values[nid] = formatQty(Math.round(v * s.grams / 100.0 * 100) / 100.0) }
        if (f.nutrients.keys.any { it !in Nutrients.labelNutrients }) showAll = true
    }

    val grams = servingGrams.replace(',', '.').toDoubleOrNull()
    val valid = name.isNotBlank() && grams != null && grams > 0 && values[N.ENERGY]?.replace(',', '.')?.toDoubleOrNull() != null

    fun save() {
        val g = grams ?: return
        val per100 = values.mapNotNull { (k, v) -> v.replace(',', '.').toDoubleOrNull()?.let { k to it * 100.0 / g } }.toMap()
        scope.launch {
            c.foods.saveCustomFood(id, name.trim(), brand.trim(), barcode.trim(), per100, listOf(Serving(servingLabel.ifBlank { "serving" }.trim(), g)))
            nav.popBackStack()
        }
    }

    SubPage(
        title = if (id == null) "New food" else "Edit food",
        onBack = { nav.popBackStack() },
        actions = { if (id != null) NIconButton(Lucide.Trash2, "Delete", { confirmDelete = true }) },
        bottomBar = { BottomAction { NButton("Save food", ::save, kind = ButtonKind.Primary, size = ButtonSize.Lg, fill = true, enabled = valid, icon = Lucide.Check) } },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(top = Space.S1, bottom = Space.S4),
        ) {
            NCard {
                CardHeader("Food")
                Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
                    NTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", placeholder = "e.g. Protein bar")
                    NTextField(brand, { brand = it }, Modifier.fillMaxWidth(), label = "Brand", placeholder = "Optional")
                    NTextField(
                        barcode, { barcode = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                        label = "Barcode", placeholder = "Optional", keyboardType = KeyboardType.Number,
                        trailing = { NIconButton(Lucide.ScanBarcode, "Scan", { scope.launch { scanBarcode(context)?.let { barcode = it } } }, size = 32.dp) },
                    )
                }
            }
            NCard {
                CardHeader("Serving size")
                Row(verticalAlignment = Alignment.Bottom) {
                    NTextField(servingLabel, { servingLabel = it }, Modifier.weight(1f), label = "Name", placeholder = "e.g. 1 cup")
                    Spacer(Modifier.width(Space.S2))
                    NTextField(
                        servingGrams, { servingGrams = it }, Modifier.width(110.dp), label = "Weight", suffix = "g",
                        keyboardType = KeyboardType.Decimal, isError = grams == null || grams <= 0,
                    )
                }
            }
            NCard {
                CardHeader("Per serving")
                val defs = if (showAll) Nutrients.all else Nutrients.labelNutrients.mapNotNull { Nutrients.byId[it] }
                var lastGroup: NutrientGroup? = null
                Column(verticalArrangement = Arrangement.spacedBy(Space.S2)) {
                    defs.forEach { def ->
                        if (showAll && def.group != lastGroup) {
                            lastGroup = def.group
                            SectionLabel(def.group.title, Modifier.padding(top = Space.S2))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(def.name, style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                            NTextField(
                                values[def.id].orEmpty(), { values[def.id] = it }, Modifier.width(132.dp),
                                placeholder = "0", suffix = def.unit, keyboardType = KeyboardType.Decimal,
                                textStyle = NutriTheme.type.numeric,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.S3))
                NButton(if (showAll) "Show label nutrients only" else "Show all nutrients", { showAll = !showAll }, kind = ButtonKind.Ghost, size = ButtonSize.Sm)
            }
        }
    }

    if (confirmDelete && id != null) {
        ConfirmDialog(
            "Delete food?", "Diary entries that used it keep their nutrition values.", "Delete",
            onConfirm = { confirmDelete = false; scope.launch { c.foods.delete(id); nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
        )
    }
}
