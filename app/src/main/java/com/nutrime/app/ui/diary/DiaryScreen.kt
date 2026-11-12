package com.nutrime.app.ui.diary

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nutrime.app.AppContainer
import com.nutrime.app.data.DiaryItem
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.model.Dri
import com.nutrime.app.model.Meal
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Suggestion
import com.nutrime.app.model.Suggestions
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.model.Target
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.Card
import com.nutrime.app.ui.components.ContentMaxWidth
import com.nutrime.app.ui.components.groupRow
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.ProgressBar
import com.nutrime.app.ui.components.RowDivider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.nutrime.app.ui.components.LB_PER_KG
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NutrientRow
import com.nutrime.app.ui.components.Ring
import com.nutrime.app.ui.components.SwipeToDelete
import com.nutrime.app.ui.components.WeightDialog
import com.nutrime.app.ui.components.fmtAmount
import com.nutrime.app.ui.components.servingText
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DiaryState(
    val date: LocalDate,
    val items: List<DiaryItem>,
    val weight: WeightEntity?,
    val profile: Profile,
    val recent: List<DiaryEntryEntity>,
    val day: com.nutrime.app.data.local.DayEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DiaryViewModel(private val c: AppContainer) : ViewModel() {
    val date = c.selectedDate

    val state = combine(
        combine(date, date.flatMapLatest { c.diary.observeDay(it) }, date.flatMapLatest { c.weights.observeOn(it) }) { d, i, w -> Triple(d, i, w) },
        c.profiles.observe(),
        c.diary.observeRecent(),
        date.flatMapLatest { c.days.observe(it) },
    ) { (d, items, w), p, recent, day -> DiaryState(d, items, w, p, recent, day) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val burn = c.burn.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val meals = c.meals.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setComplete(complete: Boolean) = viewModelScope.launch { c.days.setComplete(date.value, complete) }
    fun setNote(note: String?) = viewModelScope.launch { c.days.setNote(date.value, note) }
    suspend fun saveMeal(name: String, items: List<DiaryItem>, defaultMeal: Int?) = c.meals.save(
        null, name,
        items.map { i ->
            val e = i.entity
            com.nutrime.app.data.local.RecipeIngredient(e.foodId, e.foodName, e.servingLabel, e.servingGrams, e.quantity, com.nutrime.app.data.decodeNutrients(e.nutrientsJson))
        },
        defaultMeal,
    )
    suspend fun logMeal(meal: com.nutrime.app.data.SavedMeal, at: Meal): List<String> = c.diary.logItems(date.value, at, meal.items)
    fun deleteMany(ids: List<String>) = viewModelScope.launch { c.diary.deleteMany(ids) }

    /** Weekly check-in: accept the learned burn (limited step). Returns the previous profile for undo. */
    suspend fun acceptBurn(p: Profile, learned: Int): Profile {
        val (value, _) = com.nutrime.app.model.AdaptiveBurn.nextAccepted(p.tdee, learned)
        c.profiles.save(p.copy(burnMode = com.nutrime.app.model.BurnMode.ADAPTIVE, burnModeChosen = true, adaptiveBurnKcal = value, adaptiveBurnDate = LocalDate.now().toString()))
        return p
    }
    fun keepBurn(p: Profile) = viewModelScope.launch {
        c.profiles.save(p.copy(adaptiveBurnDate = LocalDate.now().toString(), burnModeChosen = true, burnMode = if (p.burnModeChosen) p.burnMode else com.nutrime.app.model.BurnMode.FORMULA))
    }
    fun restoreProfile(p: Profile) = viewModelScope.launch { c.profiles.save(p) }
    fun maintain(p: Profile) = viewModelScope.launch { c.profiles.save(p.copy(goalKgPerWeek = 0.0)) }

    fun delete(id: String) = viewModelScope.launch { c.diary.delete(id) }
    fun restore(id: String) = viewModelScope.launch { c.diary.restore(id) }
    suspend fun relog(entry: DiaryEntryEntity): String = c.diary.relog(entry, date.value)
    suspend fun relogOn(entry: DiaryEntryEntity, day: LocalDate): String = c.diary.relog(entry, day)
    fun reorder(ids: List<String>) = viewModelScope.launch { c.diary.reorder(date.value, ids) }
    fun deleteWeight(id: String) = viewModelScope.launch { c.weights.delete(id) }
    fun copyFromYesterday() = viewModelScope.launch { c.diary.copyDay(date.value.minusDays(1), date.value) }
    fun logWeight(kg: Double, bodyFat: Double?) = viewModelScope.launch { c.weights.add(date.value, kg, bodyFat) }
}

fun defaultMealForNow(): Meal {
    val h = LocalTime.now().hour
    return when {
        h < 11 -> Meal.BREAKFAST
        h < 15 -> Meal.LUNCH
        h in 17..21 -> Meal.DINNER
        else -> Meal.SNACKS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(nav: NavController) {
    val container = appContainer()
    val vm: DiaryViewModel = viewModel { DiaryViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showWeight by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    // Entry gestures: one row open at a time, the ⋮ sheet, the nutrition sheet, and "copy to another day".
    var revealedId by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<DiaryItem?>(null) }
    var nutritionFor by remember { mutableStateOf<DiaryItem?>(null) }
    var copyFor by remember { mutableStateOf<DiaryItem?>(null) }
    // While a row is dragged (and until the saved order comes back), show the order locally.
    var localOrder by remember { mutableStateOf<List<DiaryItem>?>(null) }
    val dbItems by rememberUpdatedState(state?.items.orEmpty())
    val reorder = remember(listState) {
        ReorderState(
            listState,
            onMove = { from, to ->
                val l = (localOrder ?: dbItems).toMutableList()
                val i = l.indexOfFirst { it.id == from }
                val j = l.indexOfFirst { it.id == to }
                if (i >= 0 && j >= 0) { l.add(j, l.removeAt(i)); localOrder = l }
            },
            onDrop = { localOrder?.let { l -> vm.reorder(l.map { it.id }) } },
        )
    }
    LaunchedEffect(state?.items) { if (reorder.dragging == null) localOrder = null }
    val scope = rememberCoroutineScope()
    val c = NutriTheme.colors

    val hiddenSuggestionDay by container.settings.hiddenSuggestionDay.collectAsStateWithLifecycle(initialValue = null)
    val goalReachedHidden by container.settings.goalReachedHidden.collectAsStateWithLifecycle(initialValue = null)
    val burn by vm.burn.collectAsStateWithLifecycle()
    val savedMeals by vm.meals.collectAsStateWithLifecycle()
    var noteDialog by remember { mutableStateOf(false) }
    var saveMealDialog by remember { mutableStateOf(false) }
    var mealsSheet by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    // Weekly check-in with "update without asking": apply it once, with an undo.
    LaunchedEffect(burn, state?.profile) {
        val p = state?.profile ?: return@LaunchedEffect
        val e = burn ?: return@LaunchedEffect
        if (p.autoAcceptBurn && com.nutrime.app.model.AdaptiveBurn.checkInDue(p, e, today)) {
            val before = vm.acceptBurn(p, e.burnKcal)
            val r = snackbar.showSnackbar("Daily burn updated from your log", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.restoreProfile(before.copy(adaptiveBurnDate = today.toString()))
        }
    }

    Box(Modifier.fillMaxSize().background(c.bgSunken)) {
        val s = state ?: return@Box
        val totals = sumNutrients(s.items.map { it.nutrients })
        val targets = Dri.targets(s.profile, date)
        val shownItems = localOrder ?: s.items
        val movable = remember(shownItems) { shownItems.map<DiaryItem, Any> { it.id }.toSet() }
        // The weigh-in and the entries form one rounded group of rows.
        val groupSize = shownItems.size + (if (s.weight != null) 1 else 0)
        LazyColumn(
            Modifier.fillMaxSize().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter),
            state = listState, contentPadding = PaddingValues(bottom = Space.S6),
        ) {
            item {
                DayBar(
                    date = date,
                    onPrev = { vm.date.value = date.minusDays(1) },
                    onNext = { vm.date.value = date.plusDays(1) },
                    onPick = { showPicker = true },
                    onWeight = { showWeight = true },
                    onToday = { vm.date.value = LocalDate.now() },
                    onAssistant = { nav.navigate(Routes.ASSISTANT) },
                    onCopy = {
                        vm.copyFromYesterday()
                        scope.launch { snackbar.showSnackbar("Copied the previous day") }
                    },
                    complete = s.day?.complete == true,
                    onComplete = { vm.setComplete(!(s.day?.complete ?: false)) },
                    hasNote = !s.day?.note.isNullOrBlank(),
                    onNote = { noteDialog = true },
                    onSaveMeal = if (s.items.isNotEmpty()) ({ saveMealDialog = true }) else null,
                    onAddMeal = { mealsSheet = true },
                )
            }
            val est = burn
            if (date == today && est != null && !s.profile.autoAcceptBurn && com.nutrime.app.model.AdaptiveBurn.checkInDue(s.profile, est, today)) {
                item(key = "checkin") {
                    CheckInCard(
                        s.profile, est,
                        onAccept = {
                            scope.launch {
                                val before = vm.acceptBurn(s.profile, est.burnKcal)
                                val r = snackbar.showSnackbar("Target updated", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) vm.restoreProfile(before)
                            }
                        },
                        onKeep = { vm.keepBurn(s.profile) },
                        onReview = { nav.navigate(Routes.assistant(WEEKLY_REVIEW)) },
                    )
                }
            }
            val goal = s.profile.goalWeightKg
            if (date == today && goal != null && est != null && abs(s.profile.goalKgPerWeek) > 0.001 &&
                com.nutrime.app.model.Goal.reached(est.trendKg, goal) && goalReachedHidden != goal.toString()
            ) {
                item(key = "goal") {
                    Card {
                        Text("You reached your goal weight", style = NutriTheme.type.bodyMedium, color = c.text)
                        Text("Change your plan to Maintain to keep it?", style = NutriTheme.type.small, color = c.textSecondary)
                        Spacer(Modifier.height(Space.S3))
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                            NButton("Maintain", { vm.maintain(s.profile) }, kind = ButtonKind.Primary)
                            NButton("Not now", { scope.launch { container.settings.hideGoalReached(goal.toString()) } }, kind = ButtonKind.Ghost)
                        }
                    }
                }
            }
            if (!s.profile.configured) {
                item {
                    Card(onClick = { nav.navigate(Routes.PROFILE) }, padding = PaddingValues(horizontal = Space.S4, vertical = Space.S3)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Lucide.Target, null, tint = c.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.S3))
                            Text("Set your targets", style = NutriTheme.type.bodyMedium, color = c.accent, modifier = Modifier.weight(1f))
                            Icon(Lucide.ChevronRight, null, tint = c.accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            item {
                TodayCard(totals, s.profile.calorieTargetOn(date), targets, onOpen = { nav.navigate(Routes.nutrients(date.toString())) })
            }
            s.day?.note?.takeIf { it.isNotBlank() }?.let { note ->
                item(key = "note") {
                    Card(onClick = { noteDialog = true }, onClickLabel = "Edit note", padding = PaddingValues(horizontal = Space.S4, vertical = Space.S3)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Lucide.NotebookPen, null, tint = c.textTertiary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(Space.S3))
                            Text(note, style = NutriTheme.type.small, color = c.textSecondary)
                        }
                    }
                }
            }
            if (s.items.isNotEmpty() && hiddenSuggestionDay != date.toString()) {
                item(key = "suggestion") {
                    val suggestion = remember(s.items, s.recent, targets) {
                        Suggestions.suggest(totals, targets, s.recent, s.items.map { it.entity.foodId }.toSet()).firstOrNull()
                    }
                    if (suggestion != null) {
                        SuggestionRow(
                            suggestion,
                            onLog = { sug ->
                                scope.launch {
                                    val id = vm.relog(sug.entry)
                                    val r = snackbar.showSnackbar("Added ${sug.entry.foodName}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.delete(id)
                                }
                            },
                            onDismiss = { scope.launch { container.settings.hideSuggestion(date.toString()) } },
                            modifier = Modifier.padding(horizontal = Space.S4, vertical = 6.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(6.dp)) }
            s.weight?.let { w ->
                item(key = "weight-${w.id}") {
                    Box(Modifier.groupRow(first = true, last = groupSize == 1)) {
                        SwipeToDelete(w.id, onDelete = { vm.deleteWeight(w.id) }) {
                            LogRow(
                                title = "Weight",
                                subtitle = w.bodyFatPct?.let { "%.1f%% body fat".format(it) },
                                value = fmtAmount(if (s.profile.imperial) w.weightKg * LB_PER_KG else w.weightKg),
                                unit = if (s.profile.imperial) "lb" else "kg",
                                accent = c.alcohol,
                                onClick = { showWeight = true },
                            )
                        }
                    }
                }
            }
            if (s.items.isEmpty() && s.weight == null) {
                item {
                    Card(padding = PaddingValues(vertical = Space.S8, horizontal = Space.S4)) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nothing logged", style = NutriTheme.type.bodyMedium, color = c.textSecondary)
                            Spacer(Modifier.height(Space.S1))
                            Text("Tap + to add food", style = NutriTheme.type.small, color = c.textTertiary)
                        }
                    }
                }
            }
            itemsIndexed(shownItems, key = { _, it -> it.id }) { index, item ->
                val dragged = reorder.dragging == item.id
                val pos = index + (if (s.weight != null) 1 else 0)
                Column(
                    Modifier
                        .zIndex(if (dragged) 1f else 0f)
                        .then(if (dragged) Modifier else Modifier.animateItem())
                        .padding(horizontal = Space.S4)
                        .graphicsLayer {
                            translationY = reorder.offsetOf(item.id)
                            shadowElevation = if (dragged) 8f else 0f
                            shape = RoundedCornerShape(if (dragged) Radius.Lg else 0.dp)
                            clip = dragged
                        },
                ) {
                    Column(Modifier.groupRow(first = pos == 0 && !dragged, last = pos == groupSize - 1 && !dragged, inset = false)) {
                        if (pos > 0 && !dragged) RowDivider()
                        DiaryEntryRow(
                            key = item.id,
                            revealed = revealedId == item.id,
                            onRevealChange = { open -> revealedId = if (open) item.id else revealedId.takeIf { it != item.id } },
                            onClick = { nav.navigate(Routes.food(item.entity.foodId, date.toString(), item.entity.meal, entryId = item.id)) },
                            onMenu = { menuFor = item; revealedId = null },
                            onDelete = {
                                vm.delete(item.id)
                                scope.launch {
                                    val r = snackbar.showSnackbar("Removed ${item.entity.foodName}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.restore(item.id)
                                }
                            },
                            reorder = reorder,
                            movable = movable,
                        ) {
                            LogRow(
                                title = item.entity.foodName,
                                subtitle = servingText(item.entity.servingLabel, item.entity.quantity) + (item.entity.brand?.let { " · $it" } ?: ""),
                                value = "%,.0f".format(item.kcal),
                                unit = null,
                                onClick = null,
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(Space.S2))
    }

    state?.let { st ->
        EntrySheets(
            vm, nav, date, Dri.targets(st.profile, date), snackbar,
            menuFor = menuFor, onMenuDone = { menuFor = null },
            nutritionFor = nutritionFor, onNutrition = { nutritionFor = it },
            copyFor = copyFor, onCopy = { copyFor = it },
        )
    }

    if (noteDialog) {
        NoteDialog(state?.day?.note.orEmpty(), onDismiss = { noteDialog = false }) { vm.setNote(it); noteDialog = false }
    }
    if (saveMealDialog) {
        state?.let { st ->
            SaveMealDialog(st.items, onDismiss = { saveMealDialog = false }) { name, items, meal ->
                saveMealDialog = false
                scope.launch { vm.saveMeal(name, items, meal); snackbar.showSnackbar("Saved “$name”") }
            }
        }
    }
    if (mealsSheet) {
        SavedMealsSheet(savedMeals, onDismiss = { mealsSheet = false }) { m ->
            mealsSheet = false
            scope.launch {
                val at = m.defaultMeal?.let { Meal.entries.getOrNull(it) } ?: defaultMealForNow()
                val ids = vm.logMeal(m, at)
                val r = snackbar.showSnackbar("Added ${m.name}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) vm.deleteMany(ids)
            }
        }
    }

    if (showWeight) {
        val imperial = state?.profile?.imperial ?: false
        WeightDialog(imperial, onDismiss = { showWeight = false }) { kg, bf ->
            showWeight = false
            vm.logWeight(kg, bf)
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                NButton("Go to date", {
                    pickerState.selectedDateMillis?.let { vm.date.value = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showPicker = false
                }, kind = ButtonKind.Primary, modifier = Modifier.padding(end = Space.S3, bottom = Space.S2))
            },
            dismissButton = { NButton("Cancel", { showPicker = false }, kind = ButtonKind.Ghost, modifier = Modifier.padding(bottom = Space.S2)) },
        ) { DatePicker(pickerState) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheets(
    vm: DiaryViewModel,
    nav: NavController,
    date: LocalDate,
    targets: Map<Int, Target>,
    snackbar: SnackbarHostState,
    menuFor: DiaryItem?,
    onMenuDone: () -> Unit,
    nutritionFor: DiaryItem?,
    onNutrition: (DiaryItem?) -> Unit,
    copyFor: DiaryItem?,
    onCopy: (DiaryItem?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    fun subtitle(i: DiaryItem) = servingText(i.entity.servingLabel, i.entity.quantity) + " · " + "%,.0f kcal".format(i.kcal)
    fun copied(item: DiaryItem, to: LocalDate, message: String) = scope.launch {
        val id = vm.relogOn(item.entity, to)
        val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) vm.delete(id)
    }
    menuFor?.let { item ->
        val actions = buildList {
            add(EntryAction(Lucide.Pencil, "Edit amount or meal") {
                onMenuDone(); nav.navigate(Routes.food(item.entity.foodId, date.toString(), item.entity.meal, entryId = item.id))
            })
            add(EntryAction(Lucide.ChartColumn, "Show nutrition") { onMenuDone(); onNutrition(item) })
            add(EntryAction(Lucide.CopyPlus, "Duplicate") { onMenuDone(); copied(item, date, "Duplicated ${item.entity.foodName}") })
            if (date != today) add(EntryAction(Lucide.CalendarArrowDown, "Copy to today") { onMenuDone(); copied(item, today, "Copied to today") })
            add(EntryAction(Lucide.Copy, "Copy to another day…") { onMenuDone(); onCopy(item) })
            add(EntryAction(Lucide.Trash2, "Delete", danger = true) {
                onMenuDone()
                vm.delete(item.id)
                scope.launch {
                    val r = snackbar.showSnackbar("Removed ${item.entity.foodName}", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) vm.restore(item.id)
                }
            })
        }
        EntryActionsSheet(item.entity.foodName, subtitle(item), actions, onDismiss = onMenuDone)
    }
    nutritionFor?.let { item ->
        EntryNutritionSheet(item.entity.foodName, subtitle(item), item.nutrients, targets, onDismiss = { onNutrition(null) })
    }
    copyFor?.let { item ->
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { onCopy(null) },
            confirmButton = {
                NButton("Copy", {
                    pickerState.selectedDateMillis?.let { ms ->
                        val to = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        copied(item, to, "Copied to ${dateTitle(to)}")
                    }
                    onCopy(null)
                }, kind = ButtonKind.Primary, modifier = Modifier.padding(end = Space.S3, bottom = Space.S2))
            },
            dismissButton = { NButton("Cancel", { onCopy(null) }, kind = ButtonKind.Ghost, modifier = Modifier.padding(bottom = Space.S2)) },
        ) { DatePicker(pickerState) }
    }
}

private fun dateTitle(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}

/** Compact day switcher: ‹ Today Mon, Nov 9 ›, with weight and more actions on the right. */
@Composable
private fun DayBar(
    date: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPick: () -> Unit,
    onWeight: () -> Unit,
    onToday: () -> Unit,
    onAssistant: () -> Unit,
    onCopy: () -> Unit,
    complete: Boolean = false,
    onComplete: () -> Unit = {},
    hasNote: Boolean = false,
    onNote: () -> Unit = {},
    onSaveMeal: (() -> Unit)? = null,
    onAddMeal: () -> Unit = {},
) {
    val c = NutriTheme.colors
    var menu by remember { mutableStateOf(false) }
    val relative = date in LocalDate.now().minusDays(1)..LocalDate.now().plusDays(1)
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Space.S2), verticalAlignment = Alignment.CenterVertically) {
        NIconButton(Lucide.ChevronLeft, "Previous day", onPrev, tint = c.text, size = 32.dp)
        Row(
            Modifier.clip(RoundedCornerShape(Radius.Md)).clickable(onClick = onPick).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(dateTitle(date), style = NutriTheme.type.cardTitle, color = c.text)
            if (relative) Text("  " + date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = NutriTheme.type.small, color = c.textTertiary)
            if (complete) Icon(Lucide.CircleCheck, "Day complete", tint = c.accent, modifier = Modifier.padding(start = 6.dp).size(15.dp))
        }
        NIconButton(Lucide.ChevronRight, "Next day", onNext, tint = c.text, size = 32.dp)
        Spacer(Modifier.weight(1f))
        NIconButton(com.nutrime.app.ui.theme.NutriIcons.Assistant, "Assistant", onAssistant, tint = c.accent, size = 36.dp)
        Box {
            NIconButton(Lucide.EllipsisVertical, "More", { menu = true }, size = 36.dp)
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(Radius.Lg), containerColor = c.surfaceRaised) {
                if (date != LocalDate.now()) {
                    DropdownMenuItem(
                        text = { Text("Go to today", style = NutriTheme.type.body) },
                        leadingIcon = { Icon(Lucide.History, null, Modifier.size(16.dp), tint = c.textSecondary) },
                        onClick = { menu = false; onToday() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Copy previous day", style = NutriTheme.type.body) },
                    leadingIcon = { Icon(Lucide.Copy, null, Modifier.size(16.dp), tint = c.textSecondary) },
                    onClick = { menu = false; onCopy() },
                )
                DropdownMenuItem(
                    text = { Text(if (complete) "Mark day not complete" else "Mark day complete", style = NutriTheme.type.body) },
                    leadingIcon = { Icon(Lucide.CircleCheck, null, Modifier.size(16.dp), tint = c.textSecondary) },
                    onClick = { menu = false; onComplete() },
                )
                DropdownMenuItem(
                    text = { Text(if (hasNote) "Edit note" else "Add note", style = NutriTheme.type.body) },
                    leadingIcon = { Icon(Lucide.NotebookPen, null, Modifier.size(16.dp), tint = c.textSecondary) },
                    onClick = { menu = false; onNote() },
                )
                DropdownMenuItem(
                    text = { Text("Add saved meal", style = NutriTheme.type.body) },
                    leadingIcon = { Icon(Lucide.Bookmark, null, Modifier.size(16.dp), tint = c.textSecondary) },
                    onClick = { menu = false; onAddMeal() },
                )
                if (onSaveMeal != null) DropdownMenuItem(
                    text = { Text("Save as meal…", style = NutriTheme.type.body) },
                    leadingIcon = { Icon(Lucide.Utensils, null, Modifier.size(16.dp), tint = c.textSecondary) },
                    onClick = { menu = false; onSaveMeal() },
                )
            }
        }
    }
}

/**
 * The day at a glance in one card: a ring with the calories left (or over), and the three macros
 * against their targets. Tap it for every nutrient.
 */
@Composable
private fun TodayCard(totals: NutrientMap, calorieTarget: Int, targets: Map<Int, Target>, onOpen: () -> Unit) {
    val c = NutriTheme.colors
    val target = calorieTarget.toDouble().coerceAtLeast(1.0)
    val consumed = totals[N.ENERGY] ?: 0.0
    val remaining = calorieTarget - consumed
    val over = remaining < 0
    Card(onClick = onOpen, onClickLabel = "Show all nutrients") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clearAndSetSemantics {
                    contentDescription = if (over) "%,.0f kilocalories over target".format(-remaining) else "%,.0f kilocalories left of %,d".format(remaining, calorieTarget)
                },
            ) {
                Ring(
                    listOf((consumed / target).toFloat().coerceAtMost(1f) to if (over) c.warning else c.accent),
                    size = 112.dp, stroke = 9.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("%,.0f".format(kotlin.math.abs(remaining)), style = NutriTheme.type.pageTitle.copy(fontFeatureSettings = "tnum"), color = c.text)
                        Text(if (over) "kcal over" else "kcal left", style = NutriTheme.type.caption, color = if (over) c.warning else c.textSecondary)
                    }
                }
            }
            Spacer(Modifier.width(Space.S5))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MacroLine("Protein", totals[N.PROTEIN] ?: 0.0, targets[N.PROTEIN]?.min, c.protein)
                MacroLine("Carbs", totals[N.CARBS] ?: 0.0, targets[N.CARBS]?.min, c.carbs)
                MacroLine("Fat", totals[N.FAT] ?: 0.0, targets[N.FAT]?.min, c.fat)
            }
        }
        Text(
            "%,.0f eaten · target %,d".format(consumed, calorieTarget),
            style = NutriTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary,
            modifier = Modifier.padding(top = Space.S3),
        )
    }
}

@Composable
private fun MacroLine(name: String, grams: Double, target: Double?, color: Color) {
    val c = NutriTheme.colors
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = NutriTheme.type.small, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text("%.0f".format(grams), style = NutriTheme.type.numeric.copy(fontSize = NutriTheme.type.small.fontSize), color = c.text)
            if (target != null) Text(" / %.0f g".format(target), style = NutriTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textTertiary)
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(if (target != null && target > 0) (grams / target).toFloat() else 0f, color, height = 5.dp)
    }
}

/** "Low on vitamin D. Salmon, 140 g covers 100%." with a one-tap add and an optional dismiss. */
@Composable
fun SuggestionRow(s: Suggestion, onLog: (Suggestion) -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val c = NutriTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Xl)).background(c.accentSoft).padding(start = Space.S4, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val top = s.covers.firstOrNull()
            Text(
                top?.let { "Low on " + it.first.name.substringBefore(" (").replaceFirstChar { ch -> ch.lowercase() } } ?: "Suggestion",
                style = NutriTheme.type.small.copy(fontWeight = NutriTheme.type.cardTitle.fontWeight), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${s.entry.foodName}, ${servingText(s.entry.servingLabel, s.entry.quantity)}" + (top?.let { " covers %.0f%%".format(it.second * 100) } ?: ""),
                style = NutriTheme.type.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDismiss != null) NIconButton(Lucide.X, "Hide suggestion for this day", onDismiss, tint = c.textTertiary, size = 40.dp)
        Box(
            Modifier.padding(end = 6.dp).size(32.dp).clip(CircleShape).background(c.accent).clickable(onClickLabel = "Add ${s.entry.foodName}") { onLog(s) },
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Plus, "Add ${s.entry.foodName}", tint = c.onAccent, modifier = Modifier.size(16.dp)) }
    }
}

/** One line of the day's log: name and amount on the left, value and unit on the right. */
@Composable
private fun LogRow(title: String, subtitle: String?, value: String, unit: String?, onClick: (() -> Unit)?, accent: Color? = null) {
    val c = NutriTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = Space.S4, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(Space.S3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = NutriTheme.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.S3))
        Text(value, style = NutriTheme.type.numeric, color = c.text)
        if (unit != null) Text(" $unit", style = NutriTheme.type.small, color = c.textTertiary)
    }
}
