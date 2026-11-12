package com.nutrime.app.model

import java.time.DayOfWeek
import java.time.LocalDate

/** One logged item for insights: what food, when, at which meal, and its nutrients. */
data class LoggedItem(val date: LocalDate, val meal: Int, val foodId: String, val name: String, val nutrients: NutrientMap)

data class Source(val key: String, val name: String, val amount: Double, val share: Double, val times: Int)

data class Patterns(
    val weekdayKcal: Double?,
    val weekendKcal: Double?,
    val loggedDays: Int,
    val totalDays: Int,
    /** Consecutive logged days up to the newest counted day. */
    val currentRun: Int,
    /** Share of calories at each meal (breakfast, lunch, dinner, snacks). */
    val mealShares: List<Double>,
    /** Weekday with the smallest and the largest spread of calories (for ranges of 28+ days). */
    val steadiestDay: DayOfWeek?,
    val mostVariableDay: DayOfWeek?,
)

/** Where nutrients come from, and eating patterns. Copied in web/src/lib/insights.ts. */
object Insights {
    /** Foods that gave the most of [nutrientId], grouped by food (by name for quick and imported entries). */
    fun topSources(items: List<LoggedItem>, nutrientId: Int, limit: Int = 8): List<Source> {
        val groups = items.groupBy { if (it.foodId.startsWith("quick:") || it.foodId.startsWith("import:")) "name:" + it.name.lowercase().trim() else it.foodId }
        val sums = groups.map { (k, list) -> Triple(k, list, list.sumOf { it.nutrients[nutrientId] ?: 0.0 }) }.filter { it.third > 0 }
        val total = sums.sumOf { it.third }
        if (total <= 0) return emptyList()
        return sums.sortedByDescending { it.third }.take(limit).map { (k, list, amount) ->
            Source(k, list.maxBy { it.date }.name, amount, amount / total, list.size)
        }
    }

    /** [dayKcal]: calories of each counted (fully logged) day in the range. */
    fun patterns(items: List<LoggedItem>, dayKcal: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): Patterns {
        val weekday = dayKcal.filterKeys { it.dayOfWeek.value <= 5 }.values
        val weekend = dayKcal.filterKeys { it.dayOfWeek.value >= 6 }.values
        var run = 0
        var d = dayKcal.keys.maxOrNull()
        while (d != null && d in dayKcal) { run++; d = d.minusDays(1) }
        val mealKcal = DoubleArray(4)
        items.filter { it.date in dayKcal }.forEach { i -> if (i.meal in 0..3) mealKcal[i.meal] += i.nutrients[N.ENERGY] ?: 0.0 }
        val totalKcal = mealKcal.sum()
        val total = java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1
        var steady: DayOfWeek? = null
        var variable: DayOfWeek? = null
        if (total >= 28) {
            val spread = dayKcal.entries.groupBy { it.key.dayOfWeek }.filterValues { it.size >= 3 }.mapValues { (_, l) ->
                val m = l.map { it.value }.average()
                kotlin.math.sqrt(l.sumOf { (it.value - m) * (it.value - m) } / l.size)
            }
            if (spread.size >= 2) { steady = spread.minBy { it.value }.key; variable = spread.maxBy { it.value }.key }
        }
        return Patterns(
            weekdayKcal = weekday.takeIf { it.isNotEmpty() }?.average(),
            weekendKcal = weekend.takeIf { it.isNotEmpty() }?.average(),
            loggedDays = dayKcal.size,
            totalDays = total,
            currentRun = run,
            mealShares = mealKcal.map { if (totalKcal > 0) it / totalKcal else 0.0 },
            steadiestDay = steady,
            mostVariableDay = variable,
        )
    }
}
