package com.nutrime.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nutrime.app.NutriMeApplication
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.data.decodeNutrients
import com.nutrime.app.model.N
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.sumNutrients
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Health Connect: read weight (smart scales), steps and active energy; write each meal's nutrition.
 * Everything is off until the user turns it on in Profile → Connections.
 */
object HealthConnect {
    val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
    val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val READ_ACTIVE = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val WRITE_NUTRITION = HealthPermission.getWritePermission(NutritionRecord::class)
    const val PROVIDER = "com.google.android.apps.healthdata"

    fun available(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context, PROVIDER) == HealthConnectClient.SDK_AVAILABLE

    /** Health Connect is on the phone but needs an update (or install from the store). */
    fun needsUpdate(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context, PROVIDER) == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED

    fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun granted(context: Context): Set<String> =
        if (!available(context)) emptySet() else runCatching { client(context).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    /**
     * Weigh-ins from Health Connect for dates that have none in NutriMe. Ids are "hc:<record id>", so a
     * second import changes nothing. A weigh-in typed in NutriMe always wins. Returns the new weigh-ins.
     */
    suspend fun weightsToImport(context: Context, since: LocalDate): List<WeightEntity> {
        val app = context.applicationContext as NutriMeApplication
        val zone = ZoneId.systemDefault()
        val response = client(context).readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                TimeRangeFilter.after(since.atStartOfDay(zone).toInstant()),
            ),
        )
        val existing = app.container.db.weights().observeAll().first()
        val ownDates = existing.filter { !it.id.startsWith("hc:") }.map { it.date }.toSet()
        val known = existing.map { it.id }.toSet()
        // The first weigh-in of each day (mornings are the most comparable).
        return response.records
            .groupBy { it.time.atZone(zone).toLocalDate() }
            .mapNotNull { (date, recs) ->
                val r = recs.minBy { it.time }
                val id = "hc:" + r.metadata.id
                if (date.toString() in ownDates || id in known) null
                else WeightEntity(id = id, date = date.toString(), weightKg = Math.round(r.weight.inKilograms * 100) / 100.0, updatedAt = System.currentTimeMillis())
            }
    }

    suspend fun importWeights(context: Context, since: LocalDate): Int {
        val list = weightsToImport(context, since)
        if (list.isEmpty()) return 0
        val app = context.applicationContext as NutriMeApplication
        app.container.weights.addAll(list)
        return list.size
    }

    /** Steps and active energy per day, saved on each day's record. */
    suspend fun importActivity(context: Context, since: LocalDate, granted: Set<String>) {
        val app = context.applicationContext as NutriMeApplication
        val metrics = buildSet {
            if (READ_STEPS in granted) add(StepsRecord.COUNT_TOTAL)
            if (READ_ACTIVE in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isEmpty()) return
        val result = client(context).aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(since.atStartOfDay(), LocalDate.now().plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        result.forEach { bucket ->
            val date = bucket.startTime.toLocalDate()
            val steps = bucket.result[StepsRecord.COUNT_TOTAL]?.toInt()
            val active = bucket.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            if (steps != null || active != null) app.container.days.setActivity(date, steps, active)
        }
    }

    private val MEAL_TYPES = listOf(MealType.MEAL_TYPE_BREAKFAST, MealType.MEAL_TYPE_LUNCH, MealType.MEAL_TYPE_DINNER, MealType.MEAL_TYPE_SNACK)
    private val MEAL_HOURS = listOf(8, 12, 18, 15)

    /**
     * One nutrition record per meal of [date], replaced on each write (client record id
     * "nutrime:<date>:<meal>", version = time of writing). An emptied meal's record is deleted.
     */
    suspend fun writeNutrition(context: Context, date: LocalDate, entries: List<DiaryEntryEntity>) {
        val zone = ZoneId.systemDefault()
        val c = client(context)
        val version = System.currentTimeMillis()
        val records = mutableListOf<NutritionRecord>()
        val empty = mutableListOf<String>()
        for (meal in 0..3) {
            val clientId = "nutrime:$date:$meal"
            val list = entries.filter { it.meal == meal && !it.deleted }
            if (list.isEmpty()) { empty += clientId; continue }
            val t = sumNutrients(list.map { decodeNutrients(it.nutrientsJson).forGrams(it.grams) })
            val start = date.atTime(MEAL_HOURS[meal], 0).atZone(zone)
            fun g(id: Int) = t[id]?.takeIf { it > 0 }?.let { Mass.grams(it) }
            fun mg(id: Int) = t[id]?.takeIf { it > 0 }?.let { Mass.milligrams(it) }
            fun mcg(id: Int) = t[id]?.takeIf { it > 0 }?.let { Mass.micrograms(it) }
            records += NutritionRecord(
                startTime = start.toInstant(), startZoneOffset = start.offset,
                endTime = start.plusMinutes(30).toInstant(), endZoneOffset = start.offset,
                metadata = Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = version),
                energy = t[N.ENERGY]?.let { Energy.kilocalories(it) },
                protein = g(N.PROTEIN), totalCarbohydrate = g(N.CARBS), totalFat = g(N.FAT),
                dietaryFiber = g(N.FIBER), sugar = g(N.SUGARS), saturatedFat = g(N.SAT_FAT),
                monounsaturatedFat = g(N.MONO_FAT), polyunsaturatedFat = g(N.POLY_FAT), transFat = g(N.TRANS_FAT),
                cholesterol = mg(N.CHOLESTEROL), sodium = mg(N.SODIUM), potassium = mg(N.POTASSIUM),
                calcium = mg(N.CALCIUM), iron = mg(N.IRON), magnesium = mg(N.MAGNESIUM), zinc = mg(N.ZINC),
                phosphorus = mg(N.PHOSPHORUS), copper = mg(N.COPPER), manganese = mg(N.MANGANESE), selenium = mcg(N.SELENIUM),
                vitaminA = mcg(N.VIT_A), vitaminC = mg(N.VIT_C), vitaminD = mcg(N.VIT_D), vitaminE = mg(N.VIT_E), vitaminK = mcg(N.VIT_K),
                thiamin = mg(N.THIAMIN), riboflavin = mg(N.RIBOFLAVIN), niacin = mg(N.NIACIN), pantothenicAcid = mg(N.PANTOTHENIC),
                vitaminB6 = mg(N.B6), folate = mcg(N.FOLATE), vitaminB12 = mcg(N.B12), caffeine = mg(N.CAFFEINE),
                mealType = MEAL_TYPES[meal],
            )
        }
        if (records.isNotEmpty()) c.insertRecords(records)
        if (empty.isNotEmpty()) runCatching { c.deleteRecords(NutritionRecord::class, emptyList(), empty) }
    }

    /** Run the switched-on parts: import the last [days] days, write nutrition for days changed since the last run. */
    suspend fun run(context: Context, days: Long = 30): String? {
        val app = context.applicationContext as NutriMeApplication
        val settings = app.container.settings
        val h = settings.health.first()
        if (!h.any || !available(context)) return null
        val granted = granted(context)
        val since = LocalDate.now().minusDays(days)
        return try {
            if (h.importWeights && READ_WEIGHT in granted) importWeights(context, since)
            if (h.importActivity) importActivity(context, since, granted)
            if (h.writeNutrition && WRITE_NUTRITION in granted) {
                val changedSince = if (h.lastRun > 0) h.lastRun - 60_000 else 0L
                val db = app.container.db
                val recent = db.diary().all().filter { it.date >= since.toString() }
                val dates = db.diary().changedSince(changedSince).map { it.date }.filter { it >= since.toString() }.toSet()
                dates.forEach { d -> writeNutrition(context, LocalDate.parse(d), recent.filter { it.date == d }) }
            }
            settings.setHealthRun(System.currentTimeMillis(), null)
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            settings.setHealthRun(System.currentTimeMillis(), msg)
            msg
        }
    }

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<HealthWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.NONE).build()
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork("health", ExistingPeriodicWorkPolicy.KEEP, req) }
    }

    /** Run soon (app start, a diary change, or "Import now"). */
    fun runSoon(context: Context, delaySeconds: Long = 20) {
        val req = OneTimeWorkRequestBuilder<HealthWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork("health-once", ExistingWorkPolicy.REPLACE, req) }
    }
}

class HealthWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        HealthConnect.run(applicationContext)
        return Result.success()
    }
}
