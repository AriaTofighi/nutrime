package com.nutrime.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.NutriTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

/**
 * Log or edit a weigh-in. With [existing], the fields start with its values (selected, so typing replaces them)
 * and [onDelete] adds a Delete button. [date] is the day the weigh-in is for (it sets the title).
 */
@Composable
fun WeightDialog(
    imperial: Boolean,
    existing: WeightEntity? = null,
    date: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (kg: Double, bodyFat: Double?) -> Unit,
) {
    var weight by remember(existing?.id) {
        mutableStateOf(existing?.let { fmtWeight(if (imperial) it.weightKg * LB_PER_KG else it.weightKg) }.orEmpty())
    }
    var bodyFat by remember(existing?.id) { mutableStateOf(existing?.bodyFatPct?.let { fmtWeight(it) }.orEmpty()) }
    val w = weight.replace(',', '.').toDoubleOrNull()
    val focus = remember { FocusRequester() }
    val title = when {
        existing != null -> "Edit weight"
        date == LocalDate.now() -> "Log today's weight"
        else -> "Log weight · " + date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
    NDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = {
            NButton("Save", {
                val kg = if (imperial) w!! / LB_PER_KG else w!!
                onSave(kg, bodyFat.replace(',', '.').toDoubleOrNull())
            }, kind = ButtonKind.Primary, enabled = w != null && w > 0)
        },
        dismiss = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S1)) {
                if (existing != null && onDelete != null) NButton("Delete", onDelete, kind = ButtonKind.Danger)
                NButton("Cancel", onDismiss, kind = ButtonKind.Ghost)
            }
        },
    ) {
        // Inside the dialog's own composition, so the field is attached when focus is requested.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(verticalArrangement = Arrangement.spacedBy(Space.S3)) {
            NTextField(
                weight, { weight = it }, Modifier.fillMaxWidth(), label = "Weight", suffix = if (imperial) "lb" else "kg",
                keyboardType = KeyboardType.Decimal, selectAllOnFocus = true, focusRequester = focus,
            )
            NTextField(bodyFat, { bodyFat = it }, Modifier.fillMaxWidth(), label = "Body fat (optional)", suffix = "%", keyboardType = KeyboardType.Decimal, selectAllOnFocus = true)
        }
    }
}
