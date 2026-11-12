package com.nutrime.server

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.server.food.CatalogFoods
import com.nutrime.server.ai.encodeNutrients
import com.nutrime.server.ai.encodeServings
import com.nutrime.app.model.N
import com.nutrime.server.ai.DiaryRecord
import com.nutrime.server.ai.FoodRecord
import com.nutrime.server.ai.decodeNutrients
import com.nutrime.server.ai.decodeServings
import com.nutrime.server.ai.recordJson
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.math.abs
import kotlin.math.round

/**
 * Candidates for NutriMe's curated food database (data/foods): the branded foods people actually log,
 * most used first, each with a draft curated entry to check against the package before adding.
 *
 * Branded foods from USDA and Open Food Facts are public data, so every user's logs count toward the
 * ranking (only counts are returned, never who logged what). Custom foods are private: only the admin's
 * own custom foods (e.g. from label photos) are included.
 */
class FoodCandidates(private val config: Config, private val db: Database) {

    private data class Seen(val food: FoodRecord, val owners: MutableSet<String> = mutableSetOf())

    fun routes(route: Route) = with(route) {
        get("/api/foods/candidates") {
            val uid = call.principal<JWTPrincipal>()!!.payload.subject
            AdminAccess(config, db).requireAdmin(uid)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
            call.respond(candidates(uid, limit))
        }
    }

    fun candidates(adminId: String, limit: Int): JsonObject {
        val foods = HashMap<String, Seen>()
        val provenance = HashMap<String, Triple<String, String, Long>>()
        val logs = HashMap<String, Int>()
        val loggers = HashMap<String, MutableSet<String>>()
        val lastLogged = HashMap<String, String>()
        transaction(db) {
            Records.selectAll().where { (Records.collection eq "foods") and (Records.deleted eq false) }.forEach { row ->
                val f = runCatching { recordJson.decodeFromString(FoodRecord.serializer(), row[Records.data]) }.getOrNull() ?: return@forEach
                val owner = row[Records.userId]
                val public = f.kind == "usda_branded" || f.kind == "off"
                val ownCustom = f.kind == "custom" && owner == adminId
                if (f.deleted || !(public || ownCustom)) return@forEach
                foods.getOrPut(f.id) { Seen(f) }.owners += owner
            }
            // Provider snapshots are authoritative over user-uploaded copies of the same public id.
            // Unlogged results can also be reviewed, without exposing search histories or identities.
            CatalogFoods.selectAll().orderBy(CatalogFoods.fetchedAt, SortOrder.DESC).limit(1000).forEach { row ->
                val f = recordJson.decodeFromString(Food.serializer(), row[CatalogFoods.payload])
                if (f.source !in setOf(FoodSource.OPEN_FOOD_FACTS, FoodSource.USDA_BRANDED)) return@forEach
                val record = FoodRecord(f.id, when (f.source) { FoodSource.OPEN_FOOD_FACTS -> "off"; FoodSource.USDA_BRANDED -> "usda_branded"; else -> "usda" },
                    f.name, f.brand, f.barcode, encodeNutrients(f.nutrients), encodeServings(f.servings),
                    updatedAt = row[CatalogFoods.fetchedAt])
                foods[f.id] = Seen(record, foods[f.id]?.owners ?: mutableSetOf())
                provenance[f.id] = Triple(row[CatalogFoods.sourceUrl], row[CatalogFoods.license], row[CatalogFoods.fetchedAt])
            }
            Records.selectAll().where { (Records.collection eq "diary") and (Records.deleted eq false) }.forEach { row ->
                val d = runCatching { recordJson.decodeFromString(DiaryRecord.serializer(), row[Records.data]) }.getOrNull() ?: return@forEach
                if (d.deleted || d.foodId !in foods) return@forEach
                logs[d.foodId] = (logs[d.foodId] ?: 0) + 1
                loggers.getOrPut(d.foodId) { mutableSetOf() } += row[Records.userId]
                if (d.date > (lastLogged[d.foodId] ?: "")) lastLogged[d.foodId] = d.date
            }
        }
        val ranked = foods.values
            .sortedWith(compareByDescending<Seen> { loggers[it.food.id]?.size ?: 0 }.thenByDescending { logs[it.food.id] ?: 0 })
            .take(limit)
        return buildJsonObject {
            put("note", "Check each draft against the package (or the maker's site), fix what differs, replace the TODO " +
                "basis with where you checked it, and add it to data/foods/branded/. See data/foods/README.md.")
            putJsonArray("candidates") {
                ranked.forEach { (f, owners) ->
                    val n = decodeNutrients(f.nutrientsJson)
                    addJsonObject {
                        put("id", f.id)
                        put("source", SOURCE[f.kind] ?: f.kind)
                        provenance[f.id]?.let { (url, license, fetchedAt) ->
                            put("source_url", url)
                            put("license", license)
                            put("fetched_at", fetchedAt)
                            put("review_status", "unreviewed_provider_data")
                        }
                        put("name", f.name)
                        f.brand?.let { put("brand", it) }
                        f.barcode?.let { put("barcode", it) }
                        put("users", (loggers[f.id] ?: owners).size)
                        put("logs", logs[f.id] ?: 0)
                        lastLogged[f.id]?.let { put("last_logged", it) }
                        put("kcal_per_100g", r(n[N.ENERGY] ?: 0.0))
                        putJsonArray("flags") {
                            flags(f, n).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            if (provenance[f.id] == null && f.kind != "custom")
                                add(kotlinx.serialization.json.JsonPrimitive("user-saved copy; verify source and label"))
                        }
                        put("draft", draft(f, n))
                    }
                }
            }
        }
    }

    private fun flags(f: FoodRecord, n: Map<Int, Double>): List<String> = buildList {
        val kcal = n[N.ENERGY] ?: 0.0
        val atwater = 4 * (n[N.PROTEIN] ?: 0.0) + 4 * (n[N.CARBS] ?: 0.0) + 9 * (n[N.FAT] ?: 0.0) + 7 * (n[N.ALCOHOL] ?: 0.0)
        if (kcal > 900) add("more than 900 kcal per 100 g: almost certainly wrong")
        if (kcal > 20 && abs(atwater - kcal) > maxOf(20.0, 0.15 * kcal)) add("calories don't match macros (${r(kcal)} vs ${r(atwater)} from macros, per 100 g)")
        if (decodeServings(f.servingsJson).none { it.grams > 0 }) add("no serving size")
        if (f.barcode == null) add("no barcode")
        if (f.brand == null) add("no brand")
    }

    /** A curated entry built from the stored values, as the label would print them for one serving. */
    private fun draft(f: FoodRecord, n: Map<Int, Double>): JsonObject {
        val serving = decodeServings(f.servingsJson).firstOrNull { it.grams > 0 }
        val g = serving?.grams ?: 100.0
        fun per(id: Int) = n[id]?.let { r(it * g / 100.0) }
        return buildJsonObject {
            put("key", slug(listOfNotNull(f.brand, f.name).joinToString(" ")))
            put("name", f.name)
            f.brand?.let { put("brand", it) }
            f.barcode?.let { put("barcode", it) }
            put("category", "Branded")
            putJsonObject("label") {
                put("serving_label", serving?.label ?: "100 g")
                put("serving_g", r(g))
                LABEL.forEach { (field, id) -> per(id)?.let { put(field, it) } }
            }
            put("basis", "TODO: check against the package. Values from ${SOURCE[f.kind] ?: f.kind} ${f.id}.")
        }
    }

    companion object {
        private val SOURCE = mapOf("usda" to "USDA", "usda_branded" to "USDA Branded", "off" to "Open Food Facts", "custom" to "Custom (yours)")

        /** Same fields as tools/update_food_db.py's label format. */
        private val LABEL = listOf(
            "calories" to N.ENERGY, "fat_g" to N.FAT, "saturated_fat_g" to N.SAT_FAT, "trans_fat_g" to N.TRANS_FAT,
            "cholesterol_mg" to N.CHOLESTEROL, "sodium_mg" to N.SODIUM, "carbs_g" to N.CARBS, "fiber_g" to N.FIBER,
            "sugars_g" to N.SUGARS, "added_sugars_g" to N.ADDED_SUGARS, "protein_g" to N.PROTEIN, "vitamin_d_mcg" to N.VIT_D,
            "calcium_mg" to N.CALCIUM, "iron_mg" to N.IRON, "potassium_mg" to N.POTASSIUM,
        )

        private fun r(d: Double) = round(d * 10) / 10

        fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).trimEnd('-')
    }
}
