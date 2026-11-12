package com.nutrime.app.data

import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.BundledFoodDb
import com.nutrime.app.data.local.FoodEntity
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.data.remote.Http
import com.nutrime.app.data.remote.FoodCatalogClient
import com.nutrime.app.data.remote.foodLookupError
import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Serving
import com.nutrime.app.model.forGrams
import com.nutrime.app.model.sumNutrients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------------------------
// Foods
// ---------------------------------------------------------------------------------------------

data class OnlineResults(val foods: List<Food>, val errors: List<String>)

data class Recipe(
    val id: String,
    val name: String,
    val servings: Double,
    val ingredients: List<RecipeIngredient>,
) {
    val totalGrams: Double get() = ingredients.sumOf { it.grams }
    val totalNutrients: NutrientMap get() = sumNutrients(ingredients.map { it.nutrients.forGrams(it.grams) })
}

class FoodRepository(
    private val db: AppDatabase,
    private val bundled: BundledFoodDb,
    private val catalog: FoodCatalogClient,
    private val onChange: () -> Unit,
) {
    /** Foods seen in online results this session, so detail screens can load them by id. */
    private val cache = ConcurrentHashMap<String, Food>()

    fun remember(food: Food) { cache[food.id] = food }

    fun observeMine(): Flow<List<Food>> = db.foods().observeMine().map { list -> list.map { it.toFood() } }

    /** Online foods (USDA branded/FNDDS, Open Food Facts) saved when they were first logged. */
    fun observeSaved(): Flow<List<Food>> = db.foods().observeSaved().map { list -> list.map { it.toFood() } }

    data class LocalResults(val foods: List<Food>, val corrected: Set<String> = emptySet(), val correctedQuery: String? = null)

    /** My foods and bundled foods, with synonyms and typing correction (see BundledFoodDb.searchDetailed). */
    suspend fun searchLocalDetailed(query: String): LocalResults {
        val q = query.trim()
        if (q.isEmpty()) return LocalResults(emptyList())
        val bundledResults = bundled.searchDetailed(q)
        val mine = db.foods().search(q).map { it.toFood() }
        val mineFixed = bundledResults.correctedQuery?.let { cq -> db.foods().search(cq).map { it.toFood() } }.orEmpty()
        val foods = (mine + bundledResults.foods + mineFixed).distinctBy { it.id }
        return LocalResults(foods, bundledResults.corrected + mineFixed.map { it.id }.filter { id -> mine.none { it.id == id } }, bundledResults.correctedQuery)
    }

    suspend fun searchLocal(query: String): List<Food> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val mine = db.foods().search(q).map { it.toFood() }
        val usdaLocal = bundled.search(q)
        return (mine + usdaLocal).distinctBy { it.id }
    }

    suspend fun searchOnline(query: String): OnlineResults = try {
        val result = catalog.search(query)
        result.foods.forEach { remember(it) }
        OnlineResults(result.foods, result.errors)
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { OnlineResults(emptyList(), listOf(foodLookupError(e))) }

    suspend fun byBarcode(code: String): Food? {
        db.foods().byBarcode(code)?.let { return it.toFood() }
        return catalog.barcode(code)?.also { remember(it) }
    }

    suspend fun get(id: String): Food? {
        db.foods().get(id)?.takeIf { !it.deleted }?.let { return it.toFood() }
        cache[id]?.let { return it }
        bundled.get(id)?.let { return it }
        return null
    }

    /** Persist online foods when used so they work offline, appear in search and sync. */
    suspend fun ensureSaved(food: Food) {
        if (food.source == FoodSource.CUSTOM || food.source == FoodSource.RECIPE) return
        if (db.foods().get(food.id) != null) return
        // Bundled foods (USDA and NutriMe curated) are on every device already.
        if ((food.source == FoodSource.USDA || food.source == FoodSource.NUTRIME) && bundled.get(food.id) != null) return
        db.foods().upsert(
            FoodEntity(
                id = food.id,
                kind = when (food.source) {
                    FoodSource.OPEN_FOOD_FACTS -> "off"
                    FoodSource.USDA_BRANDED -> "usda_branded"
                    else -> "usda"
                },
                name = food.name,
                brand = food.brand,
                barcode = food.barcode,
                nutrientsJson = encodeNutrients(food.nutrients),
                servingsJson = Http.json.encodeToString(servingsSerializer, food.servings),
                updatedAt = now(),
            )
        )
        onChange()
    }

    /** Save a custom food; [nutrientsPer100g] already normalized. Returns its id. */
    suspend fun saveCustomFood(
        id: String?,
        name: String,
        brand: String?,
        barcode: String?,
        nutrientsPer100g: NutrientMap,
        servings: List<Serving>,
    ): String {
        val fid = id ?: "custom:${newId()}"
        db.foods().upsert(
            FoodEntity(
                id = fid, kind = "custom", name = name, brand = brand?.takeIf { it.isNotBlank() },
                barcode = barcode?.takeIf { it.isNotBlank() },
                nutrientsJson = encodeNutrients(nutrientsPer100g),
                servingsJson = Http.json.encodeToString(servingsSerializer, servings),
                updatedAt = now(),
            )
        )
        onChange()
        return fid
    }

    suspend fun getRecipe(id: String): Recipe? {
        val e = db.foods().get(id)?.takeIf { it.kind == "recipe" && !it.deleted } ?: return null
        val ings = e.ingredientsJson?.let { runCatching { Http.json.decodeFromString(ingredientsSerializer, it) }.getOrNull() }.orEmpty()
        return Recipe(e.id, e.name, e.recipeServings ?: 1.0, ings)
    }

    suspend fun getRaw(id: String): FoodEntity? = db.foods().get(id)

    suspend fun saveRecipe(id: String?, name: String, servings: Double, ingredients: List<RecipeIngredient>): String {
        val rid = id ?: "custom:${newId()}"
        val recipe = Recipe(rid, name, servings, ingredients)
        val total = recipe.totalGrams.coerceAtLeast(0.0001)
        val per100 = recipe.totalNutrients.mapValues { it.value * 100.0 / total }
        val perServing = total / servings.coerceAtLeast(0.0001)
        db.foods().upsert(
            FoodEntity(
                id = rid, kind = "recipe", name = name,
                nutrientsJson = encodeNutrients(per100),
                servingsJson = Http.json.encodeToString(
                    servingsSerializer,
                    listOf(Serving("serving", perServing), Serving("whole recipe", total)),
                ),
                ingredientsJson = Http.json.encodeToString(ingredientsSerializer, ingredients),
                recipeServings = servings,
                updatedAt = now(),
            )
        )
        onChange()
        return rid
    }

    suspend fun delete(id: String) {
        val e = db.foods().get(id) ?: return
        db.foods().upsert(e.copy(deleted = true, updatedAt = now(), dirty = true))
        onChange()
    }

    private fun FoodEntity.toFood() = Food(
        id = id,
        source = when (kind) {
            "custom" -> FoodSource.CUSTOM
            "recipe" -> FoodSource.RECIPE
            "off" -> FoodSource.OPEN_FOOD_FACTS
            "usda_branded" -> FoodSource.USDA_BRANDED
            else -> FoodSource.USDA
        },
        name = name,
        brand = brand,
        nutrients = decodeNutrients(nutrientsJson),
        servings = runCatching { Http.json.decodeFromString(servingsSerializer, servingsJson) }.getOrDefault(emptyList()),
        barcode = barcode,
    )
}
