package com.nutrime.server

import com.nutrime.app.model.FoodSource
import com.nutrime.server.food.BrandedImport
import com.nutrime.server.food.CatalogQueries
import com.nutrime.server.food.CatalogStore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The server side of tools/import_usda_branded.py: same checks as the live parser, cleaned names, repeatable. */
class BrandedImportTest {
    private fun db() = connectDatabase(
        Config(0, "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1", null, null, "x".repeat(40), true),
    )

    private fun file(vararg lines: String): File = File.createTempFile("branded", ".jsonl.gz").apply {
        deleteOnExit()
        GZIPOutputStream(outputStream()).bufferedWriter().use { w -> lines.forEach { w.write(it); w.newLine() } }
    }

    @Test
    fun importsCleanedFoodsAndSkipsBadOnes() {
        val db = db()
        val f = file(
            """{"source":"usda-branded","release":"2025-04-24","foods":2}""",
            """{"fdcId":1003,"description":"TYSON CHICKEN BREAST TENDERLOINS","brand":"TYSON","gtin":"0023700099999","nutrients":{"1008":110.0,"1003":23.0},"servings":[{"label":"4 oz (112 g)","grams":112.0}],"countries":["united-states"]}""",
            """{"fdcId":1006,"description":"BAD","brand":"Y","nutrients":{"1008":950.0}}""",
        )
        val r = BrandedImport.run(db, f) {}
        assertEquals(1, r.imported)
        assertEquals(1, r.skipped)
        assertEquals("2025-04-24", BrandedImport.release(db))
        val food = CatalogStore(db).get("usda:1003")!!
        assertEquals(FoodSource.USDA_BRANDED, food.source)
        assertEquals("Chicken breast tenderloins", food.name)
        assertEquals("Tyson", food.brand)
        assertEquals("usda:1003", CatalogStore(db).byBarcode("23700099999")?.id)
        assertEquals(listOf("usda:1003"), CatalogStore(db).search("chicken tenderloins", 10).map { it.id })
        assertNull(CatalogStore(db).get("usda:1006"))
        // Repeatable: a second run updates in place.
        assertEquals(1, BrandedImport.run(db, f) {}.imported)
    }

    @Test
    fun catalogSearchHandlesWholeWordsShortTokensPluralsAndFiniteTtl() {
        val db = db()
        val store = CatalogStore(db)
        val claim = store.claim("usda:search:test")
        val foods = listOf(
            com.nutrime.app.model.Food("usda:1", FoodSource.USDA_BRANDED, "White Rice", "Brand", mapOf(1008 to 130.0)),
            com.nutrime.app.model.Food("usda:2", FoodSource.USDA_BRANDED, "Ice Cream Vanilla", "Brand", mapOf(1008 to 200.0)),
            com.nutrime.app.model.Food("usda:3", FoodSource.USDA_BRANDED, "2% Milk", "Fairlife", mapOf(1008 to 50.0)),
            com.nutrime.app.model.Food("usda:4", FoodSource.USDA_BRANDED, "Whole Milk", "Fairlife", mapOf(1008 to 62.0)),
            com.nutrime.app.model.Food("usda:5", FoodSource.USDA_BRANDED, "Strawberry Yogurt", "Chobani", mapOf(1008 to 70.0)),
        )
        store.complete(claim, foods)
        // "ice" must not match "rice"
        assertEquals(listOf("usda:2"), store.search("ice", 10).map { it.id })
        // Short token "2%" must match "2% Milk" and not "Whole Milk"
        assertEquals(listOf("usda:3"), store.search("2% milk", 10).map { it.id })
        // Plural "strawberries" must match "Strawberry"
        assertEquals(listOf("usda:5"), store.search("strawberries", 10).map { it.id })
        // Finite TTL on CatalogQueries (not Long.MAX_VALUE)
        val expiresAt = transaction(db) {
            CatalogQueries.selectAll().single()[CatalogQueries.expiresAt]
        }
        kotlin.test.assertTrue(expiresAt < Long.MAX_VALUE && expiresAt > System.currentTimeMillis())
    }
}
