package com.nutrime.server.food

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodNames
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.N
import com.nutrime.app.model.Serving
import com.nutrime.app.model.UsdaNutrients
import com.nutrime.server.ai.recordJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.batchUpsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import java.io.File
import java.util.zip.GZIPInputStream

/** Bulk imports into the catalog: which release of which source, when, and how many foods. */
object CatalogImports : Table("catalog_imports") {
    val sourceName = varchar("source", 32)
    val release = varchar("release", 32)
    val foods = integer("foods")
    val importedAt = long("imported_at")
    override val primaryKey = PrimaryKey(sourceName)
}

/** One line of tools/import_usda_branded.py's output. */
@Serializable
private data class BrandedLine(
    val fdcId: Long,
    val description: String,
    val brand: String? = null,
    val gtin: String? = null,
    val nutrients: Map<String, Double> = emptyMap(),
    val servings: List<BrandedServing> = emptyList(),
    val countries: List<String> = emptyList(),
)

@Serializable
private data class BrandedServing(val label: String, val grams: Double)

@Serializable
private data class BrandedHeader(val source: String, val release: String? = null, val foods: Int? = null)

data class ImportResult(val release: String?, val imported: Int, val skipped: Int)

/**
 * Loads USDA Branded Foods prepared by tools/import_usda_branded.py into the catalog, with the ids
 * "usda:<fdcId>" that live USDA results and diary entries already use. Repeatable: rows are upserted by
 * id, so a new release updates foods in place and older ones stay (diary entries may point to them).
 */
object BrandedImport {
    const val SOURCE = "usda-branded"

    fun release(db: Database): String? = transaction(db) {
        CatalogImports.selectAll().where { CatalogImports.sourceName eq SOURCE }.singleOrNull()?.get(CatalogImports.release)
    }

    fun run(db: Database, file: File, log: (String) -> Unit = ::println): ImportResult {
        var imported = 0
        var skipped = 0
        var header: BrandedHeader? = null
        val batch = ArrayList<Food>(BATCH)
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                if (header == null) {
                    header = recordJson.decodeFromString(BrandedHeader.serializer(), line)
                    require(header!!.source == SOURCE) { "Not a USDA Branded import file (source ${header!!.source})" }
                    continue
                }
                val food = toFood(recordJson.decodeFromString(BrandedLine.serializer(), line))
                if (food == null) { skipped++; continue }
                batch += food
                if (batch.size >= BATCH) {
                    save(db, batch)
                    imported += batch.size
                    batch.clear()
                    if (imported % 50_000 == 0) log("imported $imported foods")
                }
            }
        }
        if (batch.isNotEmpty()) { save(db, batch); imported += batch.size }
        val release = header?.release
        transaction(db) {
            CatalogImports.upsert {
                it[sourceName] = SOURCE
                it[CatalogImports.release] = release ?: "unknown"
                it[foods] = imported
                it[importedAt] = System.currentTimeMillis()
            }
        }
        return ImportResult(release, imported, skipped)
    }

    /** The same checks and names as the live USDA parser ([FoodProviders]); null for a food to skip. */
    private fun toFood(l: BrandedLine): Food? {
        val nutrients = l.nutrients.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()
        val kcal = nutrients[N.ENERGY] ?: return null
        if (nutrients.values.any { !it.isFinite() || it < 0 } || kcal > 900) return null
        val brand = l.brand?.takeIf { it.isNotBlank() }?.let { UsdaNutrients.prettify(it, true) }
        val name = FoodNames.display(l.description, brand).takeIf { it.isNotBlank() } ?: return null
        return Food(
            id = "usda:${l.fdcId}",
            source = FoodSource.USDA_BRANDED,
            name = name,
            brand = brand,
            nutrients = nutrients,
            servings = l.servings.filter { it.grams > 0 && it.grams <= 2000 && it.label.isNotBlank() }.map { Serving(it.label, it.grams) },
            barcode = l.gtin?.takeIf { it.matches(Regex("[0-9]{4,14}")) },
            countries = l.countries,
        )
    }

    private fun save(db: Database, foods: List<Food>) = transaction(db) {
        val now = System.currentTimeMillis()
        CatalogFoods.batchUpsert(foods, shouldReturnGeneratedValues = false) { food ->
            this[CatalogFoods.id] = food.id
            this[CatalogFoods.provider] = "usda"
            this[CatalogFoods.searchText] = CatalogStore.normalize("${food.name} ${food.brand.orEmpty()}")
            this[CatalogFoods.barcode] = food.barcode?.trimStart('0')
            this[CatalogFoods.payload] = recordJson.encodeToString(food)
            this[CatalogFoods.fetchedAt] = now
            this[CatalogFoods.sourceUrl] = "https://fdc.nal.usda.gov/food-details/${food.id.removePrefix("usda:")}/nutrients"
            this[CatalogFoods.license] = "CC0-1.0"
        }
    }

    private const val BATCH = 1000
}
