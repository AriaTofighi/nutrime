package com.nutrime.app

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.Context
import com.nutrime.app.data.AssistantSession
import com.nutrime.app.data.DiaryRepository
import com.nutrime.app.data.FoodRepository
import com.nutrime.app.data.ProfileRepository
import com.nutrime.app.data.SettingsStore
import com.nutrime.app.data.WeightRepository
import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.BundledFoodDb
import com.nutrime.app.data.remote.AssistantClient
import com.nutrime.app.data.remote.FoodCatalogClient
import com.nutrime.app.data.remote.SyncClient
import com.nutrime.app.sync.SyncManager

class AppContainer(context: Context) {
    val db = AppDatabase.create(context)
    val settings = SettingsStore(context)
    val syncClient = SyncClient()
    val sync = SyncManager(context, db, settings, syncClient)
    /** Background work that outlives a screen: widget redraws, Health Connect. */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val appContext = context.applicationContext
    private var widgetJob: kotlinx.coroutines.Job? = null
    private val onChange: () -> Unit = {
        sync.requestSync()
        // Redraw the home screen widget shortly after the last change.
        widgetJob?.cancel()
        widgetJob = appScope.launch {
            kotlinx.coroutines.delay(1500)
            com.nutrime.app.widget.TodayWidget.refresh(appContext)
        }
        appScope.launch {
            if (settings.health.first().writeNutrition) com.nutrime.app.health.HealthConnect.runSoon(appContext, 60)
        }
    }

    /** The day shown in the Diary; the + button and Trends act on it too. */
    val selectedDate = kotlinx.coroutines.flow.MutableStateFlow(java.time.LocalDate.now())

    val bundled = BundledFoodDb(context)
    val foods = FoodRepository(db, bundled, FoodCatalogClient(settings), onChange)
    val diary = DiaryRepository(db, onChange)
    val profiles = ProfileRepository(db, onChange)
    val weights = WeightRepository(db, profiles, onChange)
    val days = com.nutrime.app.data.DayRepository(db, onChange)
    val meals = com.nutrime.app.data.SavedMealRepository(db, onChange)
    val burn = com.nutrime.app.data.BurnRepository(db, profiles)
    val assistant = AssistantSession(settings, sync, AssistantClient())
}

class NutriMeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        runCatching { container.sync.schedulePeriodic() }
        com.nutrime.app.reminders.Reminders.createChannels(this)
        container.appScope.launch {
            if (container.settings.reminders.first().any) com.nutrime.app.reminders.Reminders.schedule(this@NutriMeApplication, true)
            if (container.settings.health.first().any) {
                com.nutrime.app.health.HealthConnect.schedule(this@NutriMeApplication)
                com.nutrime.app.health.HealthConnect.runSoon(this@NutriMeApplication, 5)
            }
        }
    }
}
