package com.nutrime.app.data

import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.model.Food
import com.nutrime.app.model.Meal
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Serving
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.sumNutrients
import com.nutrime.app.model.FoodUsage
import com.nutrime.app.model.N
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate

// ---------------------------------------------------------------------------------------------
// Diary
// ---------------------------------------------------------------------------------------------

data class DiaryItem(val entity: DiaryEntryEntity, val nutrients: NutrientMap) {
    val id get() = entity.id
    val meal get() = Meal.entries[entity.meal.coerceIn(0, Meal.entries.size - 1)]
    val kcal get() = nutrients[com.nutrime.app.model.N.ENERGY] ?: 0.0
}

class DiaryRepository(private val db: AppDatabase, private val onChange: () -> Unit) {

    fun observeDay(date: LocalDate): Flow<List<DiaryItem>> =
        db.diary().observeDay(date.toString()).map { list -> list.map { it.toItem() } }

    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DiaryItem>> =
        db.diary().observeRange(from.toString(), to.toString()).map { list -> list.map { it.toItem() } }.flowOn(Dispatchers.Default)

    fun observeRecent(): Flow<List<DiaryEntryEntity>> = db.diary().observeRecent()

    suspend fun get(id: String): DiaryEntryEntity? = db.diary().get(id)?.takeIf { !it.deleted }

    /** Returns the new entry's id. */
    suspend fun add(date: LocalDate, meal: Meal, food: Food, serving: Serving, quantity: Double): String {
        val t = now()
        val id = newId()
        db.diary().upsert(
            DiaryEntryEntity(
                id = id, date = date.toString(), meal = meal.ordinal, foodId = food.id,
                foodName = food.name, brand = food.brand, servingLabel = serving.label,
                servingGrams = serving.grams, quantity = quantity,
                nutrientsJson = encodeNutrients(food.nutrients), createdAt = t, updatedAt = t,
            )
        )
        onChange()
        return id
    }

    /** Log a saved meal: one entry per item. Returns the new ids (for undo). */
    suspend fun logItems(date: LocalDate, meal: Meal, items: List<RecipeIngredient>): List<String> {
        val t = now()
        val entries = items.mapIndexed { i, it ->
            DiaryEntryEntity(
                id = newId(), date = date.toString(), meal = meal.ordinal, foodId = it.foodId, foodName = it.name,
                servingLabel = it.servingLabel, servingGrams = it.servingGrams, quantity = it.quantity,
                nutrientsJson = encodeNutrients(it.nutrients), createdAt = t + i, updatedAt = t + i,
            )
        }
        if (entries.isEmpty()) return emptyList()
        db.diary().upsertAll(entries)
        onChange()
        return entries.map { it.id }
    }

    suspend fun deleteMany(ids: List<String>) {
        val t = now()
        val list = ids.mapNotNull { db.diary().get(it) }.map { it.copy(deleted = true, updatedAt = t, dirty = true) }
        if (list.isEmpty()) return
        db.diary().upsertAll(list)
        onChange()
    }

    /** How the user logs each food (last 180 days), by food id. */
    fun observeUsage(today: LocalDate = LocalDate.now()): Flow<Map<String, FoodUsage>> =
        db.diary().observeUsage(today.minusDays(180).toString()).map { rows ->
            rows.associate { r ->
                r.foodId to FoodUsage(
                    foodId = r.foodId, count = r.cnt,
                    daysSince = ((now() - r.lastAt) / 86_400_000.0).coerceAtLeast(0.0),
                    byMeal = listOf(r.m0, r.m1, r.m2, r.m3),
                )
            }
        }

    /** The newest entry of a food: its usual amount. */
    suspend fun lastOf(foodId: String): DiaryEntryEntity? = db.diary().lastOf(foodId)

    fun observeFirstDate(): Flow<LocalDate?> = db.diary().observeFirstDate().map { s -> s?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }

    /** Daily totals for each date that has entries in from..to. */
    fun observeDayTotals(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, NutrientMap>> =
        db.diary().observeRange(from.toString(), to.toString()).map { list ->
            list.groupBy { it.date }.mapNotNull { (d, l) ->
                runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to sumNutrients(l.map { e -> decodeNutrients(e.nutrientsJson).forGrams(e.grams) }) }
            }.toMap()
        }.flowOn(Dispatchers.Default)

    suspend fun all(): List<DiaryItem> = db.diary().all().map { it.toItem() }

    suspend fun update(id: String, meal: Meal, serving: Serving, quantity: Double) {
        val e = db.diary().get(id) ?: return
        db.diary().upsert(
            e.copy(meal = meal.ordinal, servingLabel = serving.label, servingGrams = serving.grams,
                quantity = quantity, updatedAt = now(), dirty = true)
        )
        onChange()
    }

    suspend fun delete(id: String) {
        val e = db.diary().get(id) ?: return
        db.diary().upsert(e.copy(deleted = true, updatedAt = now(), dirty = true))
        onChange()
    }

    /**
     * Log calories (and optionally macros) without a food. Stored as a one-off entry whose
     * "per 100 g" values are the amounts themselves, with a single 100 g serving.
     */
    suspend fun quickAdd(date: LocalDate, name: String, kcal: Double, protein: Double?, carbs: Double?, fat: Double?): String {
        val t = now()
        val id = newId()
        val nutrients = buildMap {
            put(com.nutrime.app.model.N.ENERGY, kcal)
            protein?.let { put(com.nutrime.app.model.N.PROTEIN, it) }
            carbs?.let { put(com.nutrime.app.model.N.CARBS, it) }
            fat?.let { put(com.nutrime.app.model.N.FAT, it) }
        }
        db.diary().upsert(
            DiaryEntryEntity(
                id = id, date = date.toString(), meal = Meal.SNACKS.ordinal, foodId = "quick:$id",
                foodName = name.ifBlank { "Quick add" }, servingLabel = "serving", servingGrams = 100.0, quantity = 1.0,
                nutrientsJson = encodeNutrients(nutrients), createdAt = t, updatedAt = t,
            )
        )
        onChange()
        return id
    }

    /** Undo a delete. */
    suspend fun restore(id: String) {
        val e = db.diary().get(id) ?: return
        db.diary().upsert(e.copy(deleted = false, updatedAt = now(), dirty = true))
        onChange()
    }

    /** Log an existing entry again (same food and amount) on [date]; returns the new entry's id. */
    suspend fun relog(entry: DiaryEntryEntity, date: LocalDate): String {
        val t = now()
        val id = newId()
        db.diary().upsert(entry.copy(id = id, date = date.toString(), createdAt = t, updatedAt = t, deleted = false, dirty = true))
        onChange()
        return id
    }

    /**
     * Put a day's entries in the order of [ids]. The diary is ordered by createdAt, so the entries swap
     * their existing createdAt values (kept strictly increasing); every device sees the new order after sync.
     */
    suspend fun reorder(date: LocalDate, ids: List<String>) {
        val byId = db.diary().getDay(date.toString()).filter { !it.deleted }.associateBy { it.id }
        val entries = ids.mapNotNull { byId[it] }
        if (entries.size < 2) return
        val slots = entries.map { it.createdAt }.sorted().toMutableList()
        for (i in 1 until slots.size) if (slots[i] <= slots[i - 1]) slots[i] = slots[i - 1] + 1
        val t = now()
        val changed = entries.mapIndexedNotNull { i, e -> if (e.createdAt == slots[i]) null else e.copy(createdAt = slots[i], updatedAt = t, dirty = true) }
        if (changed.isEmpty()) return
        db.diary().upsertAll(changed)
        onChange()
    }

    /** Copy every entry from one day to another (e.g. "same as yesterday"). */
    suspend fun copyDay(from: LocalDate, to: LocalDate, meal: Meal? = null) {
        val t = now()
        val entries = db.diary().getDay(from.toString()).filter { meal == null || it.meal == meal.ordinal }
        db.diary().upsertAll(entries.mapIndexed { i, e ->
            e.copy(id = newId(), date = to.toString(), createdAt = t + i, updatedAt = t + i, deleted = false, dirty = true)
        })
        if (entries.isNotEmpty()) onChange()
    }

    private fun DiaryEntryEntity.toItem() = DiaryItem(this, decodeNutrients(nutrientsJson).forGrams(grams))
}
