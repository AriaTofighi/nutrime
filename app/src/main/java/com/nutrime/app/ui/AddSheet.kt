package com.nutrime.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NDialog
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme

enum class AddAction { FOOD, SCAN, ASSISTANT, QUICK, WEIGHT }

/** The + button's sheet: the things you add, as big thumb-height rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(onDismiss: () -> Unit, onPick: (AddAction) -> Unit) {
    val c = NutriTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.borderStrong)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.S3)) {
            SheetRow(Lucide.Search, "Add food", c.accent) { onPick(AddAction.FOOD) }
            SheetRow(Lucide.ScanBarcode, "Scan food", c.carbs) { onPick(AddAction.SCAN) }
            SheetRow(com.nutrime.app.ui.theme.NutriIcons.Assistant, "Describe or snap a meal", c.protein) { onPick(AddAction.ASSISTANT) }
            SheetRow(Lucide.Flame, "Quick add calories", c.energy) { onPick(AddAction.QUICK) }
            SheetRow(Lucide.Scale, "Add weight", c.alcohol) { onPick(AddAction.WEIGHT) }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val c = NutriTheme.colors
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick).padding(horizontal = Space.S5),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(Space.S4))
        Text(label, style = NutriTheme.type.bodyMedium.copy(fontSize = NutriTheme.type.title.fontSize), color = c.text)
    }
}

/** Log calories (and optionally macros) without searching for a food. */
@Composable
fun QuickAddDialog(onDismiss: () -> Unit, onSave: (name: String, kcal: Double, protein: Double?, carbs: Double?, fat: Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val k = num(kcal)
    NDialog(
        title = "Quick add",
        onDismiss = onDismiss,
        confirm = { NButton("Add", { onSave(name.trim(), k!!, num(protein), num(carbs), num(fat)) }, kind = ButtonKind.Primary, enabled = k != null && k > 0) },
        dismiss = { NButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
            NTextField(kcal, { kcal = it }, Modifier.fillMaxWidth(), label = "Calories", suffix = "kcal", keyboardType = KeyboardType.Decimal)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                NTextField(protein, { protein = it }, Modifier.weight(1f), label = "Protein", suffix = "g", keyboardType = KeyboardType.Decimal)
                NTextField(carbs, { carbs = it }, Modifier.weight(1f), label = "Carbs", suffix = "g", keyboardType = KeyboardType.Decimal)
                NTextField(fat, { fat = it }, Modifier.weight(1f), label = "Fat", suffix = "g", keyboardType = KeyboardType.Decimal)
            }
            NTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", placeholder = "Quick add")
        }
    }
}
