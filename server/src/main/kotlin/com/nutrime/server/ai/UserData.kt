package com.nutrime.server.ai

import com.nutrime.app.model.AdaptiveBurn
import com.nutrime.app.model.BurnEstimate
import com.nutrime.app.model.DayLog
import com.nutrime.app.model.Dri
import com.nutrime.app.model.WeighIn
import java.time.LocalDate
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Serving
import com.nutrime.app.model.Target
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.sumNutrients
import com.nutrime.server.Records
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/*
 * Synced record shapes. Field names match the Android app's Room entities and the web app's types
 * (DiaryEntryEntity, FoodEntity, WeightEntity, ProfileEntity), so records the assistant writes sync
 * to both apps unchanged.
 */

@Serializable
data class DiaryRecord(
    val id: String,
    val date: String,
    val meal: Int,
    val foodId: String,
    val foodName: String,
    val brand: String? = null,
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    val nutrientsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    val grams: Double get() = servingGrams * quantity
    val nutrients: NutrientMap get() = decodeNutrients(nutrientsJson).forGrams(grams)
}

@Serializable
data class FoodRecord(
    val id: String,
    val kind: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val nutrientsJson: String,
    val servingsJson: String = "[]",
    val ingredientsJson: String? = null,
    val recipeServings: Double? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One ingredient of a recipe, as the apps store it in FoodRecord.ingredientsJson. */
@Serializable
data class IngredientRecord(
    val foodId: String,
    val name: String,
    val servingLabel: String,
    val servingGrams: Double,
    val quantity: Double,
    /** Per 100 g snapshot. */
    val nutrients: Map<Int, Double>,
) {
    val grams: Double get() = servingGrams * quantity
}

fun decodeIngredients(s: String?): List<IngredientRecord> =
    s?.let { runCatching { recordJson.decodeFromString(ListSerializer(IngredientRecord.serializer()), it) }.getOrNull() }.orEmpty()
fun encodeIngredients(list: List<IngredientRecord>): String = recordJson.encodeToString(ListSerializer(IngredientRecord.serializer()), list)

@Serializable
data class WeightRecord(
    val id: String,
    val date: String,
    val weightKg: Double,
    val bodyFatPct: Double? = null,
    val note: String? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Serializable
data class ProfileRecord(
    val id: String = "profile",
    val json: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** One record per calendar date (id = ISO date): "day complete" flag, note and activity. */
@Serializable
data class DayRecord(
    val id: String,
    val complete: Boolean = false,
    val note: String? = null,
    val steps: Int? = null,
    val activeKcal: Double? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A saved meal: a named list of foods, logged as separate diary entries. */
@Serializable
data class SavedMealRecord(
    val id: String,
    val name: String,
    val itemsJson: String,
    val defaultMeal: Int? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
) {
    val items: List<IngredientRecord> get() = decodeIngredients(itemsJson)
}

val recordJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; isLenient = true }
private val nutrientSerializer = MapSerializer(Int.serializer(), Double.serializer())
private val servingsSerializer = ListSerializer(Serving.serializer())

fun decodeNutrients(s: String): NutrientMap = runCatching { recordJson.decodeFromString(nutrientSerializer, s) }.getOrDefault(emptyMap())
fun encodeNutrients(n: NutrientMap): String = recordJson.encodeToString(nutrientSerializer, n)
fun decodeServings(s: String): List<Serving> = runCatching { recordJson.decodeFromString(servingsSerializer, s) }.getOrDefault(emptyList())
fun encodeServings(s: List<Serving>): String = recordJson.encodeToString(servingsSerializer, s)

val MEALS = listOf("breakfast", "lunch", "dinner", "snacks")

/** One user's synced data, loaded lazily from the sync records table. */
class UserData(private val db: Database, val userId: String) {
    private fun <T> load(collection: String, decode: (String) -> T?): List<T> = transaction(db) {
        Records.selectAll()
            .where { (Records.userId eq userId) and (Records.collection eq collection) and (Records.deleted eq false) }
            .mapNotNull { row -> runCatching { decode(row[Records.data]) }.getOrNull() }
    }

    val diary: List<DiaryRecord> by lazy {
        load("diary") { recordJson.decodeFromString(DiaryRecord.serializer(), it) }.filter { !it.deleted }
    }
    val foods: List<FoodRecord> by lazy {
        load("foods") { recordJson.decodeFromString(FoodRecord.serializer(), it) }.filter { !it.deleted }
    }
    val weights: List<WeightRecord> by lazy {
        load("weights") { recordJson.decodeFromString(WeightRecord.serializer(), it) }.filter { !it.deleted }.sortedBy { it.date }
    }
    val profileRecord: ProfileRecord? by lazy {
        load("profile") { recordJson.decodeFromString(ProfileRecord.serializer(), it) }.firstOrNull { !it.deleted }
    }
    val profile: Profile by lazy {
        profileRecord?.let { runCatching { recordJson.decodeFromString(Profile.serializer(), it.json) }.getOrNull() } ?: Profile()
    }
    val targets: Map<Int, Target> by lazy { Dri.targets(profile) }
    val days: Map<String, DayRecord> by lazy {
        load("days") { recordJson.decodeFromString(DayRecord.serializer(), it) }.filter { !it.deleted }.associateBy { it.id }
    }
    val meals: List<SavedMealRecord> by lazy {
        load("meals") { recordJson.decodeFromString(SavedMealRecord.serializer(), it) }.filter { !it.deleted }
    }

    /** The learned daily burn and trend weight (same filter as the apps). */
    val burn: BurnEstimate? by lazy {
        val byDate = diary.groupBy { it.date }
        val dates = byDate.keys + days.filterValues { it.complete }.keys
        val logs = dates.mapNotNull { d ->
            val date = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@mapNotNull null
            val t = totals(byDate[d].orEmpty())
            DayLog(date, t.kcal(), t[N.CARBS] ?: 0.0, days[d]?.complete == true, byDate[d].orEmpty().isNotEmpty())
        }
        val w = weights.groupBy { it.date }.mapNotNull { (d, l) ->
            runCatching { WeighIn(LocalDate.parse(d), l.maxBy { it.updatedAt }.weightKg) }.getOrNull()
        }
        AdaptiveBurn.estimate(profile, logs, w, LocalDate.now())
    }

    fun day(date: String): List<DiaryRecord> = diary.filter { it.date == date }.sortedWith(compareBy({ it.meal }, { it.createdAt }))

    fun totals(entries: List<DiaryRecord>): NutrientMap = sumNutrients(entries.map { it.nutrients })

    fun entry(id: String): DiaryRecord? = diary.firstOrNull { it.id == id }

    fun food(id: String): FoodRecord? = foods.firstOrNull { it.id == id }
}

fun NutrientMap.kcal(): Double = this[N.ENERGY] ?: 0.0
