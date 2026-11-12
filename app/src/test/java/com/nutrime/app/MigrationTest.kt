package com.nutrime.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.nutrime.app.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room 1 → 2 only adds the days and meals tables: every existing row must still be there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version1To2KeepsAllRows() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO diary_entries (id, date, meal, foodId, foodName, brand, servingLabel, servingGrams, quantity, nutrientsJson, createdAt, updatedAt, deleted, dirty) " +
                    "VALUES ('e1', '2026-11-10', 0, 'usda:1', 'Oats', NULL, 'g', 1.0, 50.0, '{\"1008\":380.0}', 1, 1, 0, 1)",
            )
            execSQL("INSERT INTO foods (id, kind, name, brand, barcode, nutrientsJson, servingsJson, ingredientsJson, recipeServings, updatedAt, deleted, dirty) VALUES ('custom:1', 'custom', 'Bar', NULL, NULL, '{}', '[]', NULL, NULL, 1, 0, 1)")
            execSQL("INSERT INTO weights (id, date, weightKg, bodyFatPct, note, updatedAt, deleted, dirty) VALUES ('w1', '2026-11-10', 80.5, NULL, NULL, 1, 0, 1)")
            execSQL("INSERT INTO profile (id, json, updatedAt, deleted, dirty) VALUES ('profile', '{\"weightKg\":80.5}', 1, 0, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true)
        fun count(table: String) = db.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, count("diary_entries"))
        assertEquals(1, count("foods"))
        assertEquals(1, count("weights"))
        assertEquals(1, count("profile"))
        assertEquals(0, count("days"))
        assertEquals(0, count("meals"))
        db.execSQL("INSERT INTO days (id, complete, note, steps, activeKcal, updatedAt, deleted, dirty) VALUES ('2026-11-10', 1, 'note', NULL, NULL, 1, 0, 1)")
        assertEquals(1, count("days"))
    }
}
