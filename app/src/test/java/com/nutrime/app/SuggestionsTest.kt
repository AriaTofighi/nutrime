package com.nutrime.app

import com.nutrime.app.data.encodeNutrients
import com.nutrime.app.data.local.DiaryEntryEntity
import com.nutrime.app.model.N
import com.nutrime.app.model.Suggestions
import com.nutrime.app.model.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionsTest {
    private fun entry(foodId: String, grams: Double, per100: Map<Int, Double>) = DiaryEntryEntity(
        id = foodId, date = "2026-11-01", meal = 0, foodId = foodId, foodName = foodId, servingLabel = "g",
        servingGrams = 1.0, quantity = grams, nutrientsJson = encodeNutrients(per100), createdAt = 0, updatedAt = 0,
    )

    private val targets = mapOf(N.VIT_D to Target(15.0), N.VIT_C to Target(90.0), N.IRON to Target(8.0))

    @Test
    fun picksFoodThatFillsBiggestGap() {
        val totals = mapOf(N.VIT_D to 1.0, N.VIT_C to 80.0, N.IRON to 7.0)
        val salmon = entry("salmon", 140.0, mapOf(N.ENERGY to 200.0, N.VIT_D to 10.0))
        val orange = entry("orange", 130.0, mapOf(N.ENERGY to 47.0, N.VIT_C to 53.0))
        val best = Suggestions.suggest(totals, targets, listOf(orange, salmon), eatenToday = emptySet()).single()
        assertEquals("salmon", best.entry.foodId)
        assertEquals(N.VIT_D, best.covers.first().first.id)
        assertEquals(1.0, best.covers.first().second, 1e-9) // 14 µg covers the 14 µg still missing
    }

    @Test
    fun skipsFoodsEatenTodayAndQuickAdds() {
        val totals = mapOf(N.VIT_D to 1.0)
        val salmon = entry("salmon", 140.0, mapOf(N.VIT_D to 10.0))
        val quick = entry("quick:1", 100.0, mapOf(N.VIT_D to 50.0))
        assertTrue(Suggestions.suggest(totals, targets, listOf(salmon, quick), eatenToday = setOf("salmon")).isEmpty())
    }

    @Test
    fun noSuggestionWhenTargetsMet() {
        val totals = mapOf(N.VIT_D to 20.0, N.VIT_C to 100.0, N.IRON to 9.0)
        val salmon = entry("salmon", 140.0, mapOf(N.VIT_D to 10.0))
        assertTrue(Suggestions.suggest(totals, targets, listOf(salmon), emptySet()).isEmpty())
    }
}
