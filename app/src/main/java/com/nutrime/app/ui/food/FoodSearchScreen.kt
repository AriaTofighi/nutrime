package com.nutrime.app.ui.food

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nutrime.app.AppContainer
import com.nutrime.app.data.FoodRepository
import com.nutrime.app.data.SavedMeal
import com.nutrime.app.data.decodeNutrients
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodNames
import com.nutrime.app.model.FoodQuality
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.FoodUsage
import com.nutrime.app.model.Meal
import com.nutrime.app.model.N
import com.nutrime.app.model.QueryAmount
import com.nutrime.app.model.SearchRank
import com.nutrime.app.model.Serving
import com.nutrime.app.ui.PickMode
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.ConfirmDialog
import com.nutrime.app.ui.components.EmptyState
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.SectionLabel
import com.nutrime.app.ui.components.Spinner
import com.nutrime.app.ui.components.StatusPill
import com.nutrime.app.ui.components.formatQty
import com.nutrime.app.ui.components.servingText
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

data class RecentFood(val entry: DiaryEntryEntity, val foodId: String, val name: String, val brand: String?, val subtitle: String, val kcal: Double)

/** After a scanned food is logged, the search screen offers to scan the next one. */
object ScanNext {
    val added = MutableStateFlow<String?>(null)
}

enum class SearchFilter(val label: String) { ALL("All"), MINE("My foods"), FAVORITES("Favourites"), COMPLETE("Complete data") }

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoodSearchViewModel(private val c: AppContainer, val meal: Int) : ViewModel() {
    val query = MutableStateFlow("")
    val online = MutableStateFlow<List<Food>?>(null)
    val onlineErrors = MutableStateFlow<List<String>>(emptyList())
    val onlineLoading = MutableStateFlow(false)
    val barcodeLoading = MutableStateFlow(false)
    val filter = MutableStateFlow(SearchFilter.ALL)
    private var onlineJob: Job? = null

    /** The query without a typed amount ("200g chicken" → "chicken"), and the amount. */
    val amount = query.map { QueryAmount.parse(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val local = query.debounce(200).distinctUntilChanged()
        .mapLatest { q ->
            val text = QueryAmount.parse(q)?.query ?: q
            if (text.isBlank()) FoodRepository.LocalResults(emptyList()) else c.foods.searchLocalDetailed(text)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FoodRepository.LocalResults(emptyList()))

    val usage = c.diary.observeUsage().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val profile = c.profiles.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val meals = c.meals.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val loggedIn = c.settings.account.map { it.loggedIn }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val recent = c.diary.observeRecent().mapLatest { list ->
        list.map {
            RecentFood(it, it.foodId, it.foodName, it.brand, servingText(it.servingLabel, it.quantity),
                (decodeNutrients(it.nutrientsJson)[N.ENERGY] ?: 0.0) * it.grams / 100.0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Favourite foods, loaded by id. */
    val favorites = profile.map { it?.favoriteFoodIds.orEmpty() }.distinctUntilChanged()
        .mapLatest { ids -> ids.mapNotNull { c.foods.get(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The device's country as Open Food Facts names it ("canada"), for ranking products sold here. */
    private val country = FoodNames.countryName(java.util.Locale.getDefault().country)

    /**
     * Local and online results ranked by name match, usage, favourites and data quality, one row per
     * product, then split: Open Food Facts products (unless logged or a favourite) go under "More results".
     */
    val results = combine(local, online, usage, profile, filter) { l, o, u, p, f ->
        val favs = p?.favoriteFoodIds.orEmpty().toSet()
        val text = QueryAmount.parse(query.value)?.query ?: query.value
        val ranked = SearchRank.rank(l.foods + o.orEmpty(), text, u, meal, favs, country, corrected = l.corrected)
        val collapsed = SearchRank.collapse(ranked, u.keys, favs, country)
        val filtered = when (f) {
            SearchFilter.ALL -> collapsed
            SearchFilter.MINE -> collapsed.filter { it.source == FoodSource.CUSTOM || it.source == FoodSource.RECIPE || it.id in u }
            SearchFilter.FAVORITES -> collapsed.filter { it.id in favs }
            SearchFilter.COMPLETE -> collapsed.filter { FoodQuality.isComplete(it) }
        }
        SearchRank.splitMoreResults(filtered, u.keys, favs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Food>() to emptyList())

    init {
        // Search online without a button press when local results are weak and typing has stopped.
        viewModelScope.launch {
            local.collect { l ->
                val text = (QueryAmount.parse(query.value)?.query ?: query.value).trim()
                if (!loggedIn.value || text.length < 3 || online.value != null) return@collect
                val words = com.nutrime.app.model.FoodQuality.words(text)
                val strong = l.foods.count { SearchRank.textScore(words, it, text) >= 1.0 }
                if (strong >= 5) return@collect
                onlineJob?.cancel()
                onlineJob = launch {
                    delay(500) // plus the 200 ms debounce above: about 700 ms after the last key
                    if ((QueryAmount.parse(query.value)?.query ?: query.value).trim() == text) searchOnline()
                }
            }
        }
    }

    fun setQuery(q: String) {
        query.value = q
        onlineJob?.cancel()
        online.value = null
        onlineErrors.value = emptyList()
        onlineLoading.value = false
    }

    fun searchOnline() {
        val q = (QueryAmount.parse(query.value)?.query ?: query.value).trim()
        if (q.isEmpty()) return
        onlineJob?.cancel()
        onlineJob = viewModelScope.launch {
            onlineLoading.value = true
            val r = c.foods.searchOnline(q)
            if ((QueryAmount.parse(query.value)?.query ?: query.value).trim() == q) {
                online.value = r.foods
                onlineErrors.value = r.errors
            }
            onlineLoading.value = false
        }
    }

    suspend fun lookupBarcode(code: String): Food? {
        barcodeLoading.value = true
        return try { c.foods.byBarcode(code) } finally { barcodeLoading.value = false }
    }

    /** One-tap add: the typed amount, else the usual amount, else the default serving. Returns the entry id. */
    suspend fun quickLog(food: Food, date: LocalDate): String {
        val qa = amount.value
        val (serving, qty) = when {
            qa != null -> qa.servingFor(food)
            else -> c.diary.lastOf(food.id)?.let { Serving(it.servingLabel, it.servingGrams) to it.quantity } ?: (food.defaultServing to food.defaultQuantity)
        }
        c.foods.ensureSaved(food)
        return c.diary.add(date, Meal.entries[meal.coerceIn(0, 3)], food, serving, qty)
    }
}

suspend fun scanBarcode(context: Context): String? {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
        .enableAutoZoom()
        .build()
    return runCatching { GmsBarcodeScanning.getClient(context, options).startScan().await()?.rawValue }.getOrNull()
}

@Composable
fun FoodSearchScreen(nav: NavController, date: String, meal: Int, mode: String, scanOnOpen: Boolean = false) {
    val container = appContainer()
    val vm: FoodSearchViewModel = viewModel { FoodSearchViewModel(container, meal) }
    val query by vm.query.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val savedMeals by vm.meals.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val onlineErrors by vm.onlineErrors.collectAsStateWithLifecycle()
    val onlineLoading by vm.onlineLoading.collectAsStateWithLifecycle()
    val barcodeLoading by vm.barcodeLoading.collectAsStateWithLifecycle()
    val amount by vm.amount.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var notFoundBarcode by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val c = NutriTheme.colors
    val day = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
    val logMode = mode == PickMode.LOG

    fun undoable(name: String, undo: suspend () -> Unit) = scope.launch {
        added++
        val result = snackbar.showSnackbar("Added $name", actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) { undo(); added-- }
    }

    /** Log a recent food again with its last amount, without leaving search. */
    fun quickAdd(r: RecentFood) = scope.launch {
        val id = container.diary.relog(r.entry.copy(meal = meal.coerceIn(0, 3)), day)
        undoable(r.name) { container.diary.delete(id) }
    }

    fun quickAdd(f: Food) = scope.launch {
        val id = vm.quickLog(f, day)
        undoable(f.name) { container.diary.delete(id) }
    }

    fun addMeal(m: SavedMeal) = scope.launch {
        val ids = container.diary.logItems(day, Meal.entries[meal.coerceIn(0, 3)], m.items)
        undoable(m.name) { container.diary.deleteMany(ids) }
    }

    fun open(food: Food, scanned: Boolean = false) {
        container.foods.remember(food)
        val qa = amount
        val (s, q) = qa?.servingFor(food) ?: (null to null)
        nav.navigate(Routes.food(food.id, date, meal, mode, qty = q, serving = s?.label, scanned = scanned))
    }

    fun scan() = scope.launch {
        val code = scanBarcode(context) ?: return@launch
        try {
            val food = vm.lookupBarcode(code)
            if (food != null) open(food, scanned = true) else notFoundBarcode = code
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { snackbar.showSnackbar(com.nutrime.app.data.remote.foodLookupError(e)) }
    }

    // A scanned food was just logged: offer the next scan.
    val scannedAdded by ScanNext.added.collectAsStateWithLifecycle()
    LaunchedEffect(scannedAdded) {
        val name = scannedAdded ?: return@LaunchedEffect
        ScanNext.added.value = null
        added++
        if (snackbar.showSnackbar("Added $name", actionLabel = "Scan next", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) scan()
    }

    // Opened from "Scan food": go straight to the camera; otherwise focus the search field.
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }.let { started ->
        LaunchedEffect(Unit) {
            if (scanOnOpen && !started.value) { started.value = true; scan() }
            else if (!scanOnOpen) runCatching { focusRequester.requestFocus() }
        }
    }

    val title = if (mode == PickMode.INGREDIENT) "Add ingredient" else "Add to ${Meal.entries[meal.coerceIn(0, 3)].label.lowercase()}"
    SubPage(
        title, onBack = { nav.popBackStack() }, snackbar = snackbar, plain = true,
        actions = {
            if (added > 0) {
                Text("$added added", style = NutriTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(end = Space.S2))
                NButton("Done", { nav.popBackStack(Routes.DIARY, inclusive = false) }, kind = ButtonKind.Primary, size = ButtonSize.Sm)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = Space.S4, end = Space.S4, top = Space.S4, bottom = Space.S2), verticalAlignment = Alignment.CenterVertically) {
                NTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = "Search foods, or “200 g rice”",
                    leading = Lucide.Search,
                    trailing = if (query.isNotEmpty()) ({ NIconButton(Lucide.X, "Clear", { vm.setQuery("") }, size = 32.dp) }) else null,
                    imeAction = ImeAction.Search,
                    onIme = { focus.clearFocus(); vm.searchOnline() },
                    large = true,
                )
                Spacer(Modifier.width(Space.S2))
                NIconButton(Lucide.ScanBarcode, "Scan barcode", { scan() }, bordered = true, tint = c.text, size = 44.dp)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.S4), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                amount?.let { StatusPill(it.label) }
                SearchFilter.entries.forEach { f -> NChip(f.label, filter == f, { vm.filter.value = f }) }
            }
            Spacer(Modifier.height(Space.S2))
            if (barcodeLoading) {
                Row(Modifier.padding(horizontal = Space.S4, vertical = Space.S2), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(Modifier.size(14.dp))
                    Spacer(Modifier.width(Space.S2))
                    Text("Looking up barcode…", style = NutriTheme.type.small, color = c.textSecondary)
                }
            }
            Hairline()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.S8)) {
                if (query.isBlank()) {
                    emptySearch(
                        filter, meal, usage, recent, favorites, savedMeals, logMode,
                        onOpenRecent = { r -> nav.navigate(Routes.food(r.foodId, date, meal, mode)) },
                        onAddRecent = ::quickAdd, onOpenFood = { open(it) }, onAddFood = ::quickAdd, onAddMeal = ::addMeal,
                    )
                } else {
                    local.correctedQuery?.let { cq ->
                        item {
                            Text("Showing results for “$cq”", style = NutriTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.S4, vertical = Space.S2))
                        }
                    }
                    val (mainResults, moreResults) = results
                    val favIds = vm.profile.value?.favoriteFoodIds.orEmpty().toSet()
                    items(mainResults, key = { "f-" + it.id }) { f ->
                        FoodResult(f, favorite = f.id in favIds, usual = usage[f.id], onAdd = if (logMode) ({ quickAdd(f) }) else null) { open(f) }
                    }
                    if (moreResults.isNotEmpty()) {
                        // "More results" only below other results; alone, they are simply the results.
                        if (mainResults.isNotEmpty()) item(key = "more-header") { Header("More results") }
                        items(moreResults, key = { "f-" + it.id }) { f ->
                            FoodResult(f, favorite = f.id in favIds, usual = usage[f.id], onAdd = if (logMode) ({ quickAdd(f) }) else null) { open(f) }
                        }
                    }
                    if (onlineErrors.isNotEmpty()) {
                        item {
                            val failedAll = online.isNullOrEmpty()
                            Column(Modifier.padding(horizontal = Space.S4, vertical = Space.S2)) {
                                if (failedAll) Text("Couldn't search online", style = NutriTheme.type.small, color = c.text)
                                onlineErrors.forEach { err ->
                                    Text(err, style = NutriTheme.type.caption, color = if (failedAll) c.danger else c.textTertiary)
                                }
                                if (failedAll && !onlineLoading) {
                                    Spacer(Modifier.height(Space.S2))
                                    NButton("Try again", { vm.searchOnline() }, kind = ButtonKind.Ghost, icon = Lucide.RefreshCw)
                                }
                            }
                        }
                    }
                    if (results.first.isEmpty() && results.second.isEmpty() && !onlineLoading && online != null && onlineErrors.isEmpty()) {
                        item { Text("No foods found", style = NutriTheme.type.small, color = c.textTertiary, modifier = Modifier.padding(Space.S4)) }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(Space.S4), horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                onlineLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spinner(Modifier.size(16.dp)); Spacer(Modifier.width(Space.S2))
                                    Text("Searching branded and online foods…", style = NutriTheme.type.small, color = c.textSecondary)
                                }
                                online == null -> NButton("Search branded & online foods", { focus.clearFocus(); vm.searchOnline() }, icon = Lucide.Globe)
                            }
                            Spacer(Modifier.height(Space.S2))
                            NButton("Create a custom food", { nav.navigate(Routes.customFood()) }, kind = ButtonKind.Ghost, icon = Lucide.Plus)
                        }
                    }
                }
            }
        }
    }

    notFoundBarcode?.let { code ->
        ConfirmDialog(
            title = "Product not found",
            body = "Barcode $code isn't in Open Food Facts or USDA. Create a custom food for it?",
            confirmLabel = "Create food",
            danger = false,
            onConfirm = { notFoundBarcode = null; nav.navigate(Routes.customFood(barcode = code)) },
            onDismiss = { notFoundBarcode = null },
        )
    }
}

/** Before typing: usual foods for this meal, saved meals, favourites and recent foods. */
private fun androidx.compose.foundation.lazy.LazyListScope.emptySearch(
    filter: SearchFilter,
    meal: Int,
    usage: Map<String, FoodUsage>,
    recent: List<RecentFood>,
    favorites: List<Food>,
    meals: List<SavedMeal>,
    logMode: Boolean,
    onOpenRecent: (RecentFood) -> Unit,
    onAddRecent: (RecentFood) -> Unit,
    onOpenFood: (Food) -> Unit,
    onAddFood: (Food) -> Unit,
    onAddMeal: (SavedMeal) -> Unit,
) {
    val byId = recent.associateBy { it.foodId }
    val usual = if (filter == SearchFilter.ALL || filter == SearchFilter.MINE) SearchRank.usualFor(meal, usage.values).mapNotNull { byId[it.foodId] } else emptyList()
    val shownMeals = if (logMode && (filter == SearchFilter.ALL || filter == SearchFilter.MINE)) meals.sortedByDescending { it.defaultMeal == meal } else emptyList()
    val favs = if (filter != SearchFilter.COMPLETE) favorites else favorites.filter { FoodQuality.isComplete(it) }
    val rest = if (filter == SearchFilter.ALL || filter == SearchFilter.MINE) recent.filter { r -> usual.none { it.foodId == r.foodId } } else emptyList()
    if (usual.isNotEmpty()) {
        item { Header("Usual for ${Meal.entries[meal.coerceIn(0, 3)].label.lowercase()}") }
        items(usual, key = { "u-" + it.foodId }) { r -> RecentRow(r, logMode, onOpenRecent, onAddRecent) }
    }
    if (shownMeals.isNotEmpty()) {
        item { Header("Saved meals") }
        items(shownMeals, key = { "m-" + it.id }) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ResultRow(m.name, m.items.joinToString(", ") { it.name }, m.kcal, unit = "kcal") { onAddMeal(m) }
                }
                NIconButton(Lucide.Plus, "Add ${m.name}", { onAddMeal(m) }, tint = NutriTheme.colors.accent, modifier = Modifier.padding(end = Space.S2))
            }
        }
    }
    if (favs.isNotEmpty()) {
        item { Header("Favourites") }
        items(favs, key = { "fav-" + it.id }) { f -> FoodResult(f, favorite = true, usual = usage[f.id], onAdd = if (logMode) ({ onAddFood(f) }) else null) { onOpenFood(f) } }
    }
    if (rest.isNotEmpty()) {
        item { Header("Recent") }
        items(rest, key = { "r-" + it.foodId }) { r -> RecentRow(r, logMode, onOpenRecent, onAddRecent) }
    }
    if (usual.isEmpty() && rest.isEmpty() && favs.isEmpty() && shownMeals.isEmpty()) {
        item {
            EmptyState(
                Lucide.Search, "Search 13,000+ foods",
                "Type a food, or an amount and a food (“2 eggs”, “150 g chicken”). Online and branded foods show when you are signed in. Or scan a barcode.",
            )
        }
    }
}

@Composable
private fun RecentRow(r: RecentFood, logMode: Boolean, onOpen: (RecentFood) -> Unit, onAdd: (RecentFood) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ResultRow(r.name, listOfNotNull(r.brand, r.subtitle).joinToString(" · "), r.kcal, unit = "kcal") { onOpen(r) }
        }
        if (logMode) NIconButton(Lucide.Plus, "Add ${r.name} again", { onAdd(r) }, tint = NutriTheme.colors.accent, modifier = Modifier.padding(end = Space.S2))
    }
}

@Composable
private fun Header(text: String) {
    SectionLabel(text, Modifier.padding(start = Space.S4, end = Space.S4, top = Space.S4, bottom = Space.S1))
}

/**
 * A search result: the cleaned name; brand, default serving, how often it was logged and ★ below; energy for
 * the default serving (or per 100 g). The source is on the food's own screen; the "Complete data" filter finds full lab analyses.
 */
@Composable
private fun FoodResult(f: Food, favorite: Boolean = false, usual: FoodUsage? = null, onAdd: (() -> Unit)? = null, onClick: () -> Unit) {
    val serving = f.servings.firstOrNull()
    val sub = listOfNotNull(
        f.brand?.takeIf { it.isNotBlank() },
        serving?.let { servingName(it.label) },
        usual?.let { "logged ${it.count}×" },
        "★".takeIf { favorite },
    ).joinToString(" · ")
    val kcal = if (serving != null) f.kcalPer100g * serving.grams / 100.0 else f.kcalPer100g
    val unit = if (serving != null) "kcal · ${formatQty(serving.grams)} g" else "kcal/100g"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            ResultRow(FoodNames.display(f), sub, kcal, unit = unit, onClick = onClick)
        }
        if (onAdd != null) NIconButton(Lucide.Plus, "Add ${f.name}", onAdd, tint = NutriTheme.colors.accent, modifier = Modifier.padding(end = Space.S2))
    }
}

/** A serving label as a row reads it: "slice" → "1 slice"; "1 cup" stays. */
private fun servingName(label: String): String = if (label.firstOrNull()?.isDigit() == true) label else "1 $label"

@Composable
private fun ResultRow(name: String, subtitle: String, kcal: Double, unit: String = "kcal/100g", onClick: () -> Unit) {
    val c = NutriTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.S4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NutriTheme.type.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Space.S3))
        Column(horizontalAlignment = Alignment.End) {
            Text("%.0f".format(kcal), style = NutriTheme.type.numeric, color = c.text)
            Text(unit, style = NutriTheme.type.caption, color = c.textTertiary)
        }
    }
}
