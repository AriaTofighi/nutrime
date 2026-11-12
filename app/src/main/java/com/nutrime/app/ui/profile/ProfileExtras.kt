package com.nutrime.app.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrime.app.data.HealthSettings
import com.nutrime.app.data.ReminderSettings
import com.nutrime.app.data.exportZip
import com.nutrime.app.health.HealthConnect
import com.nutrime.app.model.Meal
import com.nutrime.app.reminders.Reminders
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.CardHeader
import com.nutrime.app.ui.components.ConfirmDialog
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NCard
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.NDialog
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = NutriTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.S3)) {
            Text(title, style = NutriTheme.type.body, color = if (enabled) c.text else c.textTertiary)
            if (subtitle != null) Text(subtitle, style = NutriTheme.type.caption, color = c.textTertiary)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/** Health Connect: read weight, steps and active energy; write nutrition. */
@Composable
fun ConnectionsSection() {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val colors = NutriTheme.colors
    val h by c.settings.health.collectAsStateWithLifecycle(initialValue = HealthSettings(false, false, false, 0L, null))
    val available = remember { HealthConnect.available(context) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<Int?>(null) }
    var pendingWeights by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { granted = HealthConnect.granted(context) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = result
        scope.launch {
            if (pendingWeights && HealthConnect.READ_WEIGHT in result) {
                preview = runCatching { HealthConnect.weightsToImport(context, LocalDate.now().minusDays(90)).size }.getOrDefault(0)
            }
            pendingWeights = false
            HealthConnect.schedule(context)
            HealthConnect.runSoon(context, 2)
        }
    }
    fun ask(vararg perms: String) = launcher.launch(perms.toSet())

    NCard {
        CardHeader("Health Connect")
        if (!available) {
            Text(
                if (HealthConnect.needsUpdate(context)) "Health Connect needs an update before NutriMe can use it."
                else "Health Connect is not on this phone. It is built into Android 14 and later, and is in the Play Store for older versions.",
                style = NutriTheme.type.small, color = colors.textSecondary,
            )
            if (Build.VERSION.SDK_INT < 34) {
                Spacer(Modifier.height(Space.S3))
                NButton("Open in Play Store", {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${HealthConnect.PROVIDER}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, kind = ButtonKind.Secondary)
            }
            return@NCard
        }
        Text(
            "Share data with your scale, watch and other health apps. NutriMe reads and writes only what you switch on here.",
            style = NutriTheme.type.small, color = colors.textSecondary,
        )
        Spacer(Modifier.height(Space.S2))
        SwitchRow("Import weigh-ins", "From a smart scale or another app. A weigh-in you type in NutriMe always wins.", h.importWeights) { on ->
            scope.launch { c.settings.setHealth(weights = on) }
            if (on && HealthConnect.READ_WEIGHT !in granted) { pendingWeights = true; ask(HealthConnect.READ_WEIGHT) }
            else if (on) scope.launch { preview = HealthConnect.weightsToImport(context, LocalDate.now().minusDays(90)).size }
        }
        SwitchRow("Import steps and active energy", "Shown with each day and in your export.", h.importActivity) { on ->
            scope.launch { c.settings.setHealth(activity = on) }
            if (on) ask(HealthConnect.READ_STEPS, HealthConnect.READ_ACTIVE)
        }
        SwitchRow("Write nutrition", "One record for each meal: calories, macros, vitamins and minerals.", h.writeNutrition) { on ->
            scope.launch { c.settings.setHealth(write = on) }
            if (on && HealthConnect.WRITE_NUTRITION !in granted) ask(HealthConnect.WRITE_NUTRITION)
        }
        Spacer(Modifier.height(Space.S2))
        Text(
            when {
                h.lastError != null -> "Last run failed: ${h.lastError}"
                h.lastRun > 0 -> "Last run " + DateUtils.getRelativeTimeSpanString(h.lastRun, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                else -> "Not run yet"
            },
            style = NutriTheme.type.caption, color = if (h.lastError != null) colors.danger else colors.textTertiary,
        )
        if (h.any) {
            Spacer(Modifier.height(Space.S2))
            NButton("Run now", { HealthConnect.runSoon(context, 0) }, kind = ButtonKind.Secondary, icon = Lucide.RefreshCw)
        }
    }
    preview?.let { n ->
        if (n == 0) {
            LaunchedEffect(n) { preview = null; HealthConnect.runSoon(context, 0) }
        } else {
            ConfirmDialog(
                title = "Import weigh-ins?",
                body = "Health Connect has $n weigh-ins from the last 90 days on days with none in NutriMe. Import them? Later weigh-ins import by themselves.",
                confirmLabel = "Import", danger = false,
                onConfirm = {
                    preview = null
                    scope.launch { HealthConnect.importWeights(context, LocalDate.now().minusDays(90)); HealthConnect.runSoon(context, 0) }
                },
                onDismiss = { preview = null; scope.launch { c.settings.setHealth(weights = false) } },
            )
        }
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Meal, weigh-in and check-in reminders. Device settings: they do not sync. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RemindersSection() {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val colors = NutriTheme.colors
    val r by c.settings.reminders.collectAsStateWithLifecycle(initialValue = ReminderSettings())
    var allowed by remember { mutableStateOf(Reminders.canNotify(context)) }
    var timeDialog by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok }
    fun save(next: ReminderSettings) {
        if (next.any && !allowed && Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        scope.launch {
            c.settings.setReminders(next)
            Reminders.schedule(context, next.any)
        }
    }
    NCard {
        CardHeader("Meals")
        Text("A reminder when a meal is not logged by your usual time. NutriMe learns the time from your diary.", style = NutriTheme.type.small, color = colors.textSecondary)
        Spacer(Modifier.height(Space.S2))
        Meal.entries.forEach { m ->
            SwitchRow(m.label, null, m.ordinal in r.meals) { on -> save(r.copy(meals = if (on) r.meals + m.ordinal else r.meals - m.ordinal)) }
        }
    }
    Spacer(Modifier.height(Space.S3))
    NCard {
        CardHeader("Weigh-in")
        SwitchRow("Remind me to weigh in", "Only on days without a weigh-in.", r.weighIn) { save(r.copy(weighIn = it)) }
        if (r.weighIn) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                Text("Time", style = NutriTheme.type.body, color = colors.text, modifier = Modifier.weight(1f))
                NButton("%02d:%02d".format(r.weighMinutes / 60, r.weighMinutes % 60), { timeDialog = true }, kind = ButtonKind.Secondary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DAY_LETTERS.forEachIndexed { i, l ->
                    val d = i + 1
                    NChip(l, d in r.weighDays, { save(r.copy(weighDays = if (d in r.weighDays) r.weighDays - d else r.weighDays + d)) })
                }
            }
        }
    }
    Spacer(Modifier.height(Space.S3))
    NCard {
        CardHeader("Weekly check-in")
        SwitchRow("Tell me when it is ready", "When NutriMe learned more about your daily burn.", r.checkIn) { save(r.copy(checkIn = it)) }
    }
    if (r.any && !allowed) {
        Text("Notifications are off for NutriMe. Turn them on in Android settings to get reminders.", style = NutriTheme.type.caption, color = colors.danger, modifier = Modifier.padding(Space.S4))
    }
    if (timeDialog) {
        val state = rememberTimePickerState(r.weighMinutes / 60, r.weighMinutes % 60)
        NDialog(
            title = "Weigh-in time",
            onDismiss = { timeDialog = false },
            confirm = { NButton("Save", { timeDialog = false; save(r.copy(weighMinutes = state.hour * 60 + state.minute)) }, kind = ButtonKind.Primary) },
            dismiss = { NButton("Cancel", { timeDialog = false }, kind = ButtonKind.Ghost) },
        ) { TimePicker(state) }
    }
}

/** Export everything as a ZIP of CSV files (and a full copy for the web app's import). */
@Composable
fun DataSection() {
    val context = LocalContext.current
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val colors = NutriTheme.colors
    var status by remember { mutableStateOf<String?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = "Exporting…"
            status = try {
                val bytes = exportZip(c)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Saved (${bytes.size / 1024} KB)."
            } catch (e: Exception) {
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    NCard {
        CardHeader("Export your data")
        Text(
            "A ZIP file with your diary, daily totals and weigh-ins as CSV files for a spreadsheet, and a full copy that the NutriMe web app can import.",
            style = NutriTheme.type.small, color = colors.textSecondary,
        )
        Spacer(Modifier.height(Space.S3))
        NButton("Export", { create.launch("nutrime-${LocalDate.now()}.zip") }, kind = ButtonKind.Primary, icon = Lucide.Download)
        status?.let { Text(it, style = NutriTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = Space.S2)) }
    }
    Spacer(Modifier.height(Space.S3))
    NCard {
        CardHeader("Import from other apps")
        Text(
            "To import from Cronometer or MyFitnessPal, open the NutriMe web app, go to Profile, and choose Import data. Your phone gets the data at the next sync.",
            style = NutriTheme.type.small, color = colors.textSecondary,
        )
    }
}
