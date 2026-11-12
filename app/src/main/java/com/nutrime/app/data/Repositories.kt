package com.nutrime.app.data

import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.ProfileEntity
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.data.local.WeightEntity
import com.nutrime.app.data.remote.Http
import com.nutrime.app.model.Meal
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Serving
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.model.AdaptiveBurn
import com.nutrime.app.model.BurnEstimate
import com.nutrime.app.model.DayLog
import com.nutrime.app.model.N
import com.nutrime.app.model.WeighIn
import com.nutrime.app.data.local.DayEntity
import com.nutrime.app.data.local.SavedMealEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate
import java.util.UUID

private val nutrientSerializer = MapSerializer(Int.serializer(), Double.serializer())
internal val servingsSerializer = ListSerializer(Serving.serializer())
internal val ingredientsSerializer = ListSerializer(RecipeIngredient.serializer())

fun encodeNutrients(n: NutrientMap): String = Http.json.encodeToString(nutrientSerializer, n)
fun decodeNutrients(s: String): NutrientMap = runCatching { Http.json.decodeFromString(nutrientSerializer, s) }.getOrDefault(emptyMap())

internal fun now() = System.currentTimeMillis()
internal fun newId() = UUID.randomUUID().toString()

// ---------------------------------------------------------------------------------------------
// Weight & profile
// ---------------------------------------------------------------------------------------------

class WeightRepository(
    private val db: AppDatabase,
    private val profiles: ProfileRepository,
    private val onChange: () -> Unit,
) {
    fun observeAll(): Flow<List<WeightEntity>> = db.weights().observeAll()

    /** The most recent weigh-in logged on [date], if any. */
    fun observeOn(date: LocalDate): Flow<WeightEntity?> = observeAll().map { all -> all.firstOrNull { it.date == date.toString() } }

    suspend fun add(date: LocalDate, weightKg: Double, bodyFatPct: Double?) {
        db.weights().upsert(
            WeightEntity(id = newId(), date = date.toString(), weightKg = weightKg, bodyFatPct = bodyFatPct, updatedAt = now())
        )
        syncProfileWeight()
        onChange()
    }

    /** Weigh-ins from Health Connect (or an import). */
    suspend fun addAll(list: List<WeightEntity>) {
        if (list.isEmpty()) return
        db.weights().upsertAll(list)
        syncProfileWeight()
        onChange()
    }

    suspend fun all(): List<WeightEntity> = db.weights().observeAll().first()

    suspend fun delete(id: String) {
        val e = db.weights().get(id) ?: return
        db.weights().upsert(e.copy(deleted = true, updatedAt = now(), dirty = true))
        syncProfileWeight()
        onChange()
    }

    /** Keep the profile's weight (used for calorie targets) equal to the most recent log. */
    private suspend fun syncProfileWeight() {
        val latest = db.weights().latest() ?: return
        val p = profiles.get()
        if (p.weightKg != latest.weightKg) profiles.save(p.copy(weightKg = latest.weightKg))
    }
}

class ProfileRepository(private val db: AppDatabase, private val onChange: () -> Unit) {
    fun observe(): Flow<Profile> = db.profile().observe().map { it.toProfile() }

    suspend fun get(): Profile = db.profile().get().toProfile()

    suspend fun save(p: Profile) {
        db.profile().upsert(ProfileEntity(json = Http.json.encodeToString(Profile.serializer(), p), updatedAt = now()))
        onChange()
    }

    suspend fun toggleFavorite(foodId: String) {
        val p = get()
        val favs = if (foodId in p.favoriteFoodIds) p.favoriteFoodIds - foodId else p.favoriteFoodIds + foodId
        save(p.copy(favoriteFoodIds = favs))
    }

    private fun ProfileEntity?.toProfile(): Profile =
        this?.let { runCatching { Http.json.decodeFromString(Profile.serializer(), it.json) }.getOrNull() } ?: Profile()
}

// ---------------------------------------------------------------------------------------------
// Days, saved meals, learned burn
// ---------------------------------------------------------------------------------------------

class DayRepository(private val db: AppDatabase, private val onChange: () -> Unit) {
    fun observe(date: LocalDate): Flow<DayEntity?> = db.days().observe(date.toString())
    fun observeAll(): Flow<Map<String, DayEntity>> = db.days().observeAll().map { l -> l.associateBy { it.id } }
    suspend fun all(): List<DayEntity> = db.days().all()

    private suspend fun edit(date: LocalDate, change: (DayEntity) -> DayEntity) {
        val id = date.toString()
        val cur = db.days().get(id)?.let { if (it.deleted) DayEntity(id = id, updatedAt = now()) else it } ?: DayEntity(id = id, updatedAt = now())
        db.days().upsert(change(cur).copy(updatedAt = now(), dirty = true, deleted = false))
        onChange()
    }

    suspend fun setComplete(date: LocalDate, complete: Boolean) = edit(date) { it.copy(complete = complete) }
    suspend fun setNote(date: LocalDate, note: String?) = edit(date) { it.copy(note = note?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    /** Steps and active energy from Health Connect; writes only when a value changed. */
    suspend fun setActivity(date: LocalDate, steps: Int?, activeKcal: Double?) {
        val cur = db.days().get(date.toString())
        if (cur != null && !cur.deleted && cur.steps == steps && cur.activeKcal?.let { Math.round(it) } == activeKcal?.let { Math.round(it) }) return
        edit(date) { it.copy(steps = steps, activeKcal = activeKcal) }
    }
}

class SavedMealRepository(private val db: AppDatabase, private val onChange: () -> Unit) {
    fun observeAll(): Flow<List<SavedMeal>> = db.meals().observeAll().map { l -> l.map { it.toMeal() } }
    suspend fun get(id: String): SavedMeal? = db.meals().get(id)?.takeIf { !it.deleted }?.toMeal()
    suspend fun all(): List<SavedMealEntity> = db.meals().all()

    suspend fun save(id: String?, name: String, items: List<RecipeIngredient>, defaultMeal: Int?): String {
        val mid = id ?: newId()
        db.meals().upsert(
            SavedMealEntity(
                id = mid, name = name.trim().ifEmpty { "Meal" },
                itemsJson = Http.json.encodeToString(ingredientsSerializer, items),
                defaultMeal = defaultMeal, updatedAt = now(),
            )
        )
        onChange()
        return mid
    }

    suspend fun delete(id: String) {
        val e = db.meals().get(id) ?: return
        db.meals().upsert(e.copy(deleted = true, updatedAt = now(), dirty = true))
        onChange()
    }

    private fun SavedMealEntity.toMeal() = SavedMeal(
        id, name,
        runCatching { Http.json.decodeFromString(ingredientsSerializer, itemsJson) }.getOrDefault(emptyList()),
        defaultMeal,
    )
}

data class SavedMeal(val id: String, val name: String, val items: List<RecipeIngredient>, val defaultMeal: Int?) {
    val kcal: Double get() = items.sumOf { (it.nutrients[N.ENERGY] ?: 0.0) * it.grams / 100.0 }
}

/** The learned daily burn and trend weight, recomputed when the diary, days, weights or profile change. */
class BurnRepository(private val db: AppDatabase, private val profiles: ProfileRepository) {
    fun observe(today: LocalDate = LocalDate.now()): Flow<BurnEstimate?> {
        val from = today.minusDays(AdaptiveBurn.MAX_DAYS - 1L).toString()
        return combine(
            db.diary().observeRange(from, today.toString()),
            db.days().observeAll(),
            db.weights().observeAll(),
            profiles.observe(),
        ) { entries, days, weights, profile ->
            val byDate = entries.groupBy { it.date }
            val complete = days.filter { it.complete }.associateBy { it.id }
            val logs = (byDate.keys + complete.keys).mapNotNull { d ->
                val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@mapNotNull null
                val list = byDate[d].orEmpty()
                val t = sumNutrients(list.map { decodeNutrients(it.nutrientsJson).forGrams(it.grams) })
                DayLog(date, t[N.ENERGY] ?: 0.0, t[N.CARBS] ?: 0.0, d in complete, list.isNotEmpty())
            }
            val w = weights.groupBy { it.date }.mapNotNull { (d, l) ->
                runCatching { WeighIn(LocalDate.parse(d), l.maxBy { it.updatedAt }.weightKg) }.getOrNull()
            }
            AdaptiveBurn.estimate(profile, logs, w, today)
        }.flowOn(Dispatchers.Default).distinctUntilChanged()
    }
}
