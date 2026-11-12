package com.nutrime.app.model

import com.nutrime.app.data.decodeNutrients
import com.nutrime.app.data.local.DiaryEntryEntity

/** A food worth eating next: one of the user's own foods, at their usual amount, and what it fixes. */
data class Suggestion(
    val entry: DiaryEntryEntity,
    /** Nutrient and the fraction of today's remaining gap one serving covers (0..1), best first. */
    val covers: List<Pair<NutrientDef, Double>>,
    val kcal: Double,
)

object Suggestions {
    private val SKIP = setOf(N.ENERGY, N.PROTEIN, N.CARBS, N.FAT, N.WATER)

    /** Nutrients below target today, worst first, with the amount still missing. */
    fun gaps(totals: NutrientMap, targets: Map<Int, Target>): List<Pair<NutrientDef, Double>> =
        Nutrients.all
            .filter { it.id !in SKIP }
            .mapNotNull { def ->
                val min = targets[def.id]?.min ?: return@mapNotNull null
                val have = totals[def.id] ?: 0.0
                if (have >= min) null else Triple(def, min - have, have / min)
            }
            .sortedBy { it.third }
            .map { it.first to it.second }

    /**
     * Rank [candidates] (recently logged entries, one per food) by how much of today's biggest gaps
     * their usual serving fills, favouring nutrient-dense foods. Foods already eaten today and
     * quick-add entries are skipped.
     */
    fun suggest(
        totals: NutrientMap,
        targets: Map<Int, Target>,
        candidates: List<DiaryEntryEntity>,
        eatenToday: Set<String>,
        count: Int = 1,
    ): List<Suggestion> {
        val gaps = gaps(totals, targets).take(5)
        if (gaps.isEmpty()) return emptyList()
        return candidates
            .filter { it.foodId !in eatenToday && !it.foodId.startsWith("quick:") && !it.deleted }
            .mapNotNull { e ->
                val per100 = decodeNutrients(e.nutrientsJson)
                val factor = e.grams / 100.0
                val covers = gaps.mapNotNull { (def, missing) ->
                    val got = (per100[def.id] ?: 0.0) * factor
                    val frac = (got / missing).coerceAtMost(1.0)
                    // Weight by how severe the gap is: filling a nearly-met target matters little.
                    val severity = missing / (targets[def.id]?.min ?: missing)
                    if (frac >= 0.15) Triple(def, frac, frac * severity) else null
                }
                if (covers.isEmpty()) return@mapNotNull null
                val kcal = (per100[N.ENERGY] ?: 0.0) * factor
                val score = covers.sumOf { it.third } / (1.0 + kcal / 600.0)
                score to Suggestion(e, covers.sortedByDescending { it.second }.map { it.first to it.second }, kcal)
            }
            .sortedByDescending { it.first }
            .take(count)
            .map { it.second }
    }
}
