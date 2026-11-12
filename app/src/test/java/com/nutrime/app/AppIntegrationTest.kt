package com.nutrime.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nutrime.app.data.DiaryRepository
import com.nutrime.app.data.FoodRepository
import com.nutrime.app.data.ProfileRepository
import com.nutrime.app.data.SettingsStore
import com.nutrime.app.data.local.AppDatabase
import com.nutrime.app.data.local.BundledFoodDb
import com.nutrime.app.data.local.RecipeIngredient
import com.nutrime.app.data.remote.FoodCatalogClient
import com.nutrime.app.data.remote.SyncClient
import com.nutrime.app.model.Meal
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Serving
import com.nutrime.app.sync.SyncManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppIntegrationTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun memDb() = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()

    @Test
    fun bundledDatabaseSearch() = runBlocking {
        val db = BundledFoodDb(ctx)
        val results = db.search("apple raw")
        assertTrue("expected results", results.isNotEmpty())
        assertTrue(results.take(10).any { it.name.startsWith("Apples, raw") })
        val banana = db.search("banana").first { it.name.startsWith("Bananas, raw") }
        assertEquals(89.0, banana.nutrients[N.ENERGY]!!, 1.0)
        assertTrue("bananas have potassium", (banana.nutrients[N.POTASSIUM] ?: 0.0) > 300)
        assertTrue(banana.servings.isNotEmpty())
        assertNotNull(db.get(banana.id))
        // Curated NutriMe foods rank first, found by their aliases too, and load by their "nm:" id.
        val basmati = db.search("basmati").first()
        assertEquals(com.nutrime.app.model.FoodSource.NUTRIME, basmati.source)
        assertTrue(basmati.id.startsWith("nm:"))
        assertEquals(basmati.name, db.get(basmati.id)?.name)
        assertEquals("nm:lentils-cooked", db.search("dal").first().id)
        // FNDDS prepared foods are bundled too.
        assertTrue(db.search("chicken breast grilled").any { it.name.startsWith("Chicken breast, grilled") })
        assertTrue(db.search("   ").isEmpty())
        assertTrue(db.search("\"quote's & weird*").size >= 0)
    }

    @Test
    fun diaryCustomFoodsAndRecipes() = runBlocking {
        val db = memDb()
        val foods = FoodRepository(db, BundledFoodDb(ctx), FoodCatalogClient(SettingsStore(ctx))) {}
        val diary = DiaryRepository(db) {}
        val oats = foods.searchLocal("oats").first()
        val today = LocalDate.now()
        diary.add(today, Meal.BREAKFAST, oats, Serving("g", 1.0), 50.0)

        val id = foods.saveCustomFood(null, "Protein bar", "Acme", "123456", mapOf(N.ENERGY to 400.0, N.PROTEIN to 30.0), listOf(Serving("bar", 60.0)))
        val bar = foods.get(id)!!
        assertEquals("bar", bar.defaultServing.label)
        diary.add(today, Meal.SNACKS, bar, bar.defaultServing, 1.0)
        assertEquals(id, foods.byBarcode("123456")!!.id)

        val items = diary.observeDay(today).first()
        assertEquals(2, items.size)
        assertEquals(240.0, items.first { it.entity.foodId == id }.kcal, 0.01)
        assertEquals(oats.kcalPer100g / 2, items.first { it.entity.foodId == oats.id }.kcal, 0.01)

        // Recipe: 100 g oats + 1 bar, 2 servings
        val rid = foods.saveRecipe(null, "Oat bowl", 2.0, listOf(
            RecipeIngredient(oats.id, oats.name, "g", 1.0, 100.0, oats.nutrients),
            RecipeIngredient(bar.id, bar.name, "bar", 60.0, 1.0, bar.nutrients),
        ))
        val recipe = foods.get(rid)!!
        val perServing = recipe.nutrients[N.ENERGY]!! * recipe.defaultServing.grams / 100
        assertEquals((oats.kcalPer100g + 240) / 2, perServing, 0.01)

        // Edit + delete + copy day
        val entry = items.first { it.entity.foodId == id }
        diary.update(entry.id, Meal.LUNCH, bar.defaultServing, 2.0)
        assertEquals(480.0, diary.observeDay(today).first().first { it.id == entry.id }.kcal, 0.01)
        diary.copyDay(today, today.plusDays(1))
        assertEquals(2, diary.observeDay(today.plusDays(1)).first().size)
        diary.delete(entry.id)
        assertEquals(1, diary.observeDay(today).first().size)

        foods.delete(id)
        assertNull(foods.get(id))
        assertEquals(1, foods.observeMine().first().size)
    }

    /** Runs against a live server when SYNC_TEST_URL is set (e.g. the docker compose stack). */
    @Test
    fun syncBetweenTwoDevices() = runBlocking {
        val url = System.getenv("SYNC_TEST_URL")
        assumeTrue("SYNC_TEST_URL not set", !url.isNullOrBlank())
        val client = SyncClient()
        val email = "test-${UUID.randomUUID()}@example.com"
        val auth = client.register(url!!, email, "password123")

        fun device(): Triple<AppDatabase, SettingsStore, SyncManager> {
            val db = memDb()
            val settings = SettingsStore(ctx)
            return Triple(db, settings, SyncManager(ctx, db, settings, client))
        }
        // Device A logs food and sets a profile.
        val (dbA, settings, syncA) = device()
        settings.setLogin(url, email, auth.token, auth.userId)
        val foodsA = FoodRepository(dbA, BundledFoodDb(ctx), FoodCatalogClient(SettingsStore(ctx))) {}
        val diaryA = DiaryRepository(dbA) {}
        val custom = foodsA.saveCustomFood(null, "Smoothie", null, null, mapOf(N.ENERGY to 80.0), listOf(Serving("glass", 300.0)))
        diaryA.add(LocalDate.now(), Meal.BREAKFAST, foodsA.get(custom)!!, Serving("glass", 300.0), 1.0)
        ProfileRepository(dbA) {}.save(Profile(weightKg = 70.0, configured = true))
        assertNull(syncA.syncNow())
        assertTrue(dbA.diary().dirty().isEmpty())

        // Device B (fresh) pulls everything.
        val dbB = memDb()
        settings.setLogin(url, email, auth.token, auth.userId) // same store; reset cursor for B
        settings.setSynced(0, 0)
        val syncB = SyncManager(ctx, dbB, settings, client)
        assertNull(syncB.syncNow())
        val itemsB = DiaryRepository(dbB) {}.observeDay(LocalDate.now()).first()
        assertEquals(1, itemsB.size)
        assertEquals(240.0, itemsB.single().kcal, 0.01)
        assertEquals(70.0, ProfileRepository(dbB) {}.get().weightKg, 0.0)

        // B deletes the entry; A sees the deletion.
        DiaryRepository(dbB) {}.delete(itemsB.single().id)
        assertNull(syncB.syncNow())
        settings.setSynced(0, 0)
        assertNull(syncA.syncNow())
        assertTrue(diaryA.observeDay(LocalDate.now()).first().isEmpty())
    }
}
