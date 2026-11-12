package com.nutrime.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import com.nutrime.app.ui.theme.DesignTokens
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nutrime.app.ui.components.WeightDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nutrime.app.AppContainer
import com.nutrime.app.NutriMeApplication
import com.nutrime.app.ui.assistant.AssistantScreen
import com.nutrime.app.ui.diary.DiaryScreen
import com.nutrime.app.ui.diary.NutrientsScreen
import com.nutrime.app.ui.diary.defaultMealForNow
import com.nutrime.app.ui.food.CustomFoodEditorScreen
import com.nutrime.app.ui.food.FoodDetailScreen
import com.nutrime.app.ui.food.FoodSearchScreen
import com.nutrime.app.ui.food.MyFoodsScreen
import com.nutrime.app.ui.food.RecipeEditorScreen
import com.nutrime.app.ui.profile.AccountScreen
import com.nutrime.app.ui.profile.ProfileScreen
import com.nutrime.app.ui.profile.ProfileSectionScreen
import com.nutrime.app.ui.trends.TrendsBurnScreen
import com.nutrime.app.ui.trends.TrendsNutrientScreen
import com.nutrime.app.ui.trends.TrendsNutrientsScreen
import com.nutrime.app.ui.trends.TrendsScreen
import com.nutrime.app.ui.trends.TrendsWeightScreen

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as NutriMeApplication).container

/** What the food search/detail flow does with the chosen food. */
object PickMode {
    const val LOG = "log"
    const val INGREDIENT = "ingredient"
}

object Routes {
    const val DIARY = "diary"
    const val FOODS = "foods"
    const val TRENDS = "trends"
    const val PROFILE = "profile"
    const val ACCOUNT = "account"
    const val ASSISTANT = "assistant"

    const val TRENDS_WEIGHT = "trends/weight"
    const val TRENDS_BURN = "trends/burn"

    fun nutrients(date: String) = "nutrients?date=$date"
    fun trendsNutrients(range: String) = "trends/nutrients?range=${Uri.encode(range)}"
    fun trendsNutrient(id: Int, range: String) = "trends/nutrient/$id?range=${Uri.encode(range)}"
    fun profileSection(section: String) = "profile/$section"
    fun assistant(prompt: String) = "assistant?prompt=${Uri.encode(prompt)}"

    fun search(date: String, meal: Int, mode: String = PickMode.LOG, scan: Boolean = false) = "search?date=$date&meal=$meal&mode=$mode&scan=$scan"
    fun food(
        id: String, date: String = "", meal: Int = 0, mode: String = PickMode.LOG, entryId: String = "",
        qty: Double? = null, serving: String? = null, scanned: Boolean = false,
    ) = "food?id=${Uri.encode(id)}&date=$date&meal=$meal&mode=$mode&entryId=$entryId" +
        "&qty=${qty ?: ""}&serving=${Uri.encode(serving ?: "")}&scanned=$scanned"
    fun customFood(id: String? = null, barcode: String? = null) =
        "customFood?id=${Uri.encode(id ?: "")}&barcode=${barcode ?: ""}"
    fun recipe(id: String? = null) = "recipe?id=${Uri.encode(id ?: "")}"
}

private val tabs = listOf(
    TabItem(Routes.DIARY, "Diary", Lucide.BookOpen),
    TabItem(Routes.TRENDS, "Trends", Lucide.ChartLine),
    TabItem(Routes.FOODS, "Foods", Lucide.Salad),
    TabItem(Routes.PROFILE, "Profile", Lucide.UserRound),
)

@Composable
fun NutriMeNavHost(nav: NavHostController = rememberNavController(), link: String? = null, onLinkHandled: () -> Unit = {}) {
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val isTab = tabs.any { it.route == current }
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val profile by container.profiles.observe().collectAsStateWithLifecycle(initialValue = com.nutrime.app.model.Profile())
    var addSheet by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf<AddAction?>(null) }
    val day = container.selectedDate.value

    // Links from the widget and notifications: nutrime://add[?meal=n], nutrime://weight, nutrime://diary, nutrime://connections.
    LaunchedEffect(link) {
        val uri = link?.let { android.net.Uri.parse(it) } ?: return@LaunchedEffect
        onLinkHandled()
        when (uri.host) {
            "add" -> {
                val meal = uri.getQueryParameter("meal")?.toIntOrNull()
                container.selectedDate.value = java.time.LocalDate.now()
                if (meal != null) nav.navigate(Routes.search(java.time.LocalDate.now().toString(), meal.coerceIn(0, 3))) else addSheet = true
            }
            "weight" -> nav.navigate(Routes.TRENDS_WEIGHT)
            "connections" -> nav.navigate(Routes.profileSection("connections"))
            "diary" -> { container.selectedDate.value = java.time.LocalDate.now(); nav.navigate(Routes.DIARY) { launchSingleTop = true } }
        }
    }

    if (addSheet) {
        AddSheet(onDismiss = { addSheet = false }) { action ->
            addSheet = false
            when (action) {
                AddAction.FOOD -> nav.navigate(Routes.search(day.toString(), defaultMealForNow().ordinal))
                AddAction.SCAN -> nav.navigate(Routes.search(day.toString(), defaultMealForNow().ordinal, scan = true))
                AddAction.ASSISTANT -> nav.navigate(Routes.ASSISTANT)
                else -> addDialog = action
            }
        }
    }
    when (addDialog) {
        AddAction.WEIGHT -> WeightDialog(profile.imperial, onDismiss = { addDialog = null }) { kg, bf ->
            addDialog = null
            scope.launch { container.weights.add(container.selectedDate.value, kg, bf) }
        }
        AddAction.QUICK -> QuickAddDialog(onDismiss = { addDialog = null }) { name, kcal, p, cb, f ->
            addDialog = null
            scope.launch { container.diary.quickAdd(container.selectedDate.value, name, kcal, p, cb, f) }
        }
        else -> {}
    }
    // The tab bar floats over the screens and slides away for sub-pages. Screens never change their own size or
    // padding when the route changes, so nothing jumps while one fades into the other.
    val tabBarHeight = 61.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    @Composable
    fun TabPage(content: @Composable () -> Unit) =
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = tabBarHeight)) { content() }

    Box(Modifier.fillMaxSize().background(NutriTheme.colors.bgSunken)) {
        NavHost(nav, startDestination = Routes.DIARY, modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(DesignTokens.Motion.Duration)) },
            exitTransition = { fadeOut(tween(DesignTokens.Motion.DurationFast)) }) {
            composable(Routes.DIARY) { TabPage { DiaryScreen(nav) } }
            composable(Routes.FOODS) { TabPage { MyFoodsScreen(nav) } }
            composable(Routes.TRENDS) { TabPage {
                val container = appContainer()
                TrendsScreen(
                    onOpenDay = { d ->
                        container.selectedDate.value = d
                        nav.navigate(Routes.DIARY) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenWeight = { nav.navigate(Routes.TRENDS_WEIGHT) },
                    onOpenNutrients = { r -> nav.navigate(Routes.trendsNutrients(r)) },
                    onOpenBurn = { nav.navigate(Routes.TRENDS_BURN) },
                    onOpenNutrient = { id, r -> nav.navigate(Routes.trendsNutrient(id, r)) },
                )
            } }
            composable(Routes.TRENDS_WEIGHT) { TrendsWeightScreen(nav) }
            composable(Routes.TRENDS_BURN) { TrendsBurnScreen(nav) }
            composable(
                "trends/nutrients?range={range}",
                arguments = listOf(navArgument("range") { type = NavType.StringType; defaultValue = "7d" }),
            ) { e -> TrendsNutrientsScreen(nav, e.arguments!!.getString("range")!!) }
            composable(
                "trends/nutrient/{id}?range={range}",
                arguments = listOf(
                    navArgument("id") { type = NavType.IntType },
                    navArgument("range") { type = NavType.StringType; defaultValue = "30d" },
                ),
            ) { e -> TrendsNutrientScreen(nav, e.arguments!!.getInt("id"), e.arguments!!.getString("range")!!) }
            composable(
                "profile/{section}",
                arguments = listOf(navArgument("section") { type = NavType.StringType; defaultValue = "goal" }),
            ) { e -> ProfileSectionScreen(nav, e.arguments!!.getString("section")!!) }
            composable(Routes.PROFILE) { TabPage { ProfileScreen(nav) } }
            composable(Routes.ACCOUNT) { AccountScreen(nav) }
            composable(
                "assistant?prompt={prompt}",
                arguments = listOf(navArgument("prompt") { type = NavType.StringType; defaultValue = "" }),
            ) { e -> AssistantScreen(nav, e.arguments!!.getString("prompt")!!.ifEmpty { null }) }
            composable(
                "nutrients?date={date}",
                arguments = listOf(navArgument("date") { type = NavType.StringType; defaultValue = "" }),
            ) { e -> NutrientsScreen(nav, e.arguments!!.getString("date")!!) }
            composable(
                "search?date={date}&meal={meal}&mode={mode}&scan={scan}",
                arguments = listOf(
                    navArgument("scan") { type = NavType.BoolType; defaultValue = false },
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                    navArgument("meal") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("mode") { type = NavType.StringType; defaultValue = PickMode.LOG },
                ),
            ) { e ->
                val a = e.arguments!!
                FoodSearchScreen(nav, a.getString("date")!!, a.getInt("meal"), a.getString("mode")!!, a.getBoolean("scan"))
            }
            composable(
                "food?id={id}&date={date}&meal={meal}&mode={mode}&entryId={entryId}&qty={qty}&serving={serving}&scanned={scanned}",
                arguments = listOf(
                    navArgument("qty") { type = NavType.StringType; defaultValue = "" },
                    navArgument("serving") { type = NavType.StringType; defaultValue = "" },
                    navArgument("scanned") { type = NavType.BoolType; defaultValue = false },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("date") { type = NavType.StringType; defaultValue = "" },
                    navArgument("meal") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("mode") { type = NavType.StringType; defaultValue = PickMode.LOG },
                    navArgument("entryId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                val a = e.arguments!!
                FoodDetailScreen(
                    nav, a.getString("id")!!, a.getString("date")!!, a.getInt("meal"),
                    a.getString("mode")!!, a.getString("entryId")!!.ifEmpty { null },
                    initialQty = a.getString("qty")!!.toDoubleOrNull(), initialServing = a.getString("serving")!!.ifEmpty { null },
                    scanned = a.getBoolean("scanned"),
                )
            }
            composable(
                "customFood?id={id}&barcode={barcode}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("barcode") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { e ->
                CustomFoodEditorScreen(nav, e.arguments!!.getString("id")!!.ifEmpty { null }, e.arguments!!.getString("barcode")!!)
            }
            composable(
                "recipe?id={id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" }),
            ) { e ->
                RecipeEditorScreen(nav, e.arguments!!.getString("id")!!.ifEmpty { null })
            }
        }
        AnimatedVisibility(
            visible = isTab,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(DesignTokens.Motion.Duration)) { it },
            exit = slideOutVertically(tween(DesignTokens.Motion.DurationFast)) { it },
        ) {
            TabBar(
                tabs, current,
                onSelect = { route ->
                    nav.navigate(route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onAdd = { addSheet = true },
            )
        }
    }
}
