package com.nutrime.app

import com.nutrime.app.model.OpenFoodFactsNutrients
import com.nutrime.app.model.UsdaNutrients
import com.nutrime.app.model.Dri
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Sex
import com.nutrime.app.model.ActivityLevel
import com.nutrime.app.model.forGrams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NutritionLogicTest {
    private val year = LocalDate.now().year

    @Test
    fun mifflinStJeorAndGoal() {
        val p = Profile(sex = Sex.MALE, birthYear = year - 30, heightCm = 180.0, weightKg = 80.0, activity = ActivityLevel.SEDENTARY)
        // 10*80 + 6.25*180 - 5*30 + 5 = 1780
        assertEquals(1780.0, p.bmr, 0.01)
        assertEquals(2136, p.computedCalories)
        val losing = p.copy(goalKgPerWeek = -0.5)
        assertEquals(2136 - 550, losing.computedCalories)
        assertEquals(1800, p.copy(calorieOverride = 1800).calorieTarget)
    }

    @Test
    fun driTargetsDependOnSexAndAge() {
        val woman = Profile(sex = Sex.FEMALE, birthYear = year - 35, calorieOverride = 2000)
        val t = Dri.targets(woman)
        assertEquals(18.0, t[N.IRON]!!.min!!, 0.0)
        assertEquals(75.0, t[N.VIT_C]!!.min!!, 0.0)
        assertEquals(28.0, t[N.FIBER]!!.min!!, 0.01)
        assertEquals(2000 * 0.25 / 4, t[N.PROTEIN]!!.min!!, 0.01)
        assertEquals(2300.0, t[N.SODIUM]!!.max!!, 0.0)
        val older = Dri.targets(woman.copy(birthYear = year - 60))
        assertEquals(8.0, older[N.IRON]!!.min!!, 0.0)
        assertEquals(1200.0, older[N.CALCIUM]!!.min!!, 0.0)
        val custom = Dri.targets(woman.copy(targetOverrides = mapOf(N.PROTEIN to 150.0)))
        assertEquals(150.0, custom[N.PROTEIN]!!.min!!, 0.0)
    }

    @Test
    fun scalingPer100g() {
        val n = mapOf(N.ENERGY to 52.0, N.PROTEIN to 0.3)
        assertEquals(78.0, n.forGrams(150.0)[N.ENERGY]!!, 1e-9)
    }

    @Test
    fun openFoodFactsUnitsConverted() {
        val json = Json.parseToJsonElement(
            """{"energy-kcal_100g": 539, "proteins_100g": 6.3, "fat_100g": 30.9, "carbohydrates_100g": 57.5,
               "sodium_100g": 0.043, "calcium_100g": 0.1, "vitamin-d_100g": 0.0000025, "iron_100g": "0.004"}"""
        ).jsonObject
        val n = OpenFoodFactsNutrients.parseNutriments(json)
        assertEquals(539.0, n[N.ENERGY]!!, 1e-9)
        assertEquals(43.0, n[N.SODIUM]!!, 1e-6)
        assertEquals(100.0, n[N.CALCIUM]!!, 1e-6)
        assertEquals(2.5, n[N.VIT_D]!!, 1e-6)
        assertEquals(4.0, n[N.IRON]!!, 1e-6)
    }

    @Test
    fun openFoodFactsEnergyFromKjAndSalt() {
        val n = OpenFoodFactsNutrients.parseNutriments(Json.parseToJsonElement("""{"energy_100g": 418.4, "salt_100g": 1.0}""").jsonObject)
        assertEquals(100.0, n[N.ENERGY]!!, 1e-6)
        assertEquals(400.0, n[N.SODIUM]!!, 1e-6)
    }

    @Test
    fun usdaFallbacks() {
        val n = UsdaNutrients.canonicalize(
            mapOf(2048 to (61.0 to "KCAL"), 1050 to (14.0 to "G"), 1177 to (5.0 to "UG"), 1110 to (40.0 to "IU"))
        )
        assertEquals(61.0, n[N.ENERGY]!!, 0.0)
        assertEquals(14.0, n[N.CARBS]!!, 0.0)
        assertEquals(5.0, n[N.FOLATE]!!, 0.0)
        assertEquals(1.0, n[N.VIT_D]!!, 1e-9)
        assertNull(n[N.PROTEIN])
    }

    @Test
    fun prettifyBrandedNames() {
        assertEquals("Honey Nut Cheerios", UsdaNutrients.prettify("HONEY NUT CHEERIOS", true))
        assertEquals("Apples, raw", UsdaNutrients.prettify("Apples, raw", false))
    }
}
