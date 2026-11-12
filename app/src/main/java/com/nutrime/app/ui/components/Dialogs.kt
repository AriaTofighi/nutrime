package com.nutrime.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.NutriTheme

const val LB_PER_KG = 2.20462

/** Dialog shell shared by every dialog: surface, large radius, title + body + actions. */
@Composable
fun NDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = NutriTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(Radius.Xl),
        containerColor = c.surfaceRaised,
        tonalElevation = androidx.compose.ui.unit.Dp(0f),
        title = { Text(title, style = NutriTheme.type.title, color = c.text) },
        text = content,
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    NDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = { NButton(confirmLabel, onConfirm, kind = if (danger) ButtonKind.Danger else ButtonKind.Primary) },
        dismiss = { NButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    ) { Text(body, style = NutriTheme.type.body, color = NutriTheme.colors.textSecondary) }
}

@Composable
fun WeightDialog(imperial: Boolean, onDismiss: () -> Unit, onSave: (kg: Double, bodyFat: Double?) -> Unit) {
    var weight by remember { mutableStateOf("") }
    var bodyFat by remember { mutableStateOf("") }
    val w = weight.replace(',', '.').toDoubleOrNull()
    NDialog(
        title = "Log today's weight",
        onDismiss = onDismiss,
        confirm = {
            NButton("Save", {
                val kg = if (imperial) w!! / LB_PER_KG else w!!
                onSave(kg, bodyFat.replace(',', '.').toDoubleOrNull())
            }, kind = ButtonKind.Primary, enabled = w != null && w > 0)
        },
        dismiss = { NButton("Cancel", onDismiss, kind = ButtonKind.Ghost) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
            NTextField(weight, { weight = it }, Modifier.fillMaxWidth(), label = "Weight", suffix = if (imperial) "lb" else "kg", keyboardType = KeyboardType.Decimal)
            NTextField(bodyFat, { bodyFat = it }, Modifier.fillMaxWidth(), label = "Body fat (optional)", suffix = "%", keyboardType = KeyboardType.Decimal)
        }
    }
}
