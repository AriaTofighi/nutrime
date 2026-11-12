package com.nutrime.app.model

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil

enum class GoalWarning { FAST_RATE, LARGE_DEFICIT, WRONG_DIRECTION, AT_FLOOR }

/** Goal weight projections and safety warnings. Copied in web/src/lib/goal.ts. */
object Goal {
    /** Weeks until [goalKg] at [kgPerWeek], or null when the rate is 0 or goes the other way. */
    fun weeksToGoal(trendKg: Double, goalKg: Double, kgPerWeek: Double): Double? {
        val diff = goalKg - trendKg
        if (abs(diff) < REACHED_KG) return 0.0
        if (abs(kgPerWeek) < 0.01 || diff * kgPerWeek < 0) return null
        return diff / kgPerWeek
    }

    fun projectedDate(today: LocalDate, trendKg: Double, goalKg: Double, kgPerWeek: Double): LocalDate? =
        weeksToGoal(trendKg, goalKg, kgPerWeek)?.let { w -> today.plusDays(ceil(w * 7).toLong()) }

    fun reached(trendKg: Double, goalKg: Double?): Boolean = goalKg != null && abs(goalKg - trendKg) < REACHED_KG

    fun warnings(p: Profile, trendKg: Double = p.weightKg): List<GoalWarning> = buildList {
        val rate = abs(p.goalKgPerWeek)
        if (rate > 0.01 * trendKg) add(GoalWarning.FAST_RATE)
        if (p.goalKcalPerDay < 0 && -p.goalKcalPerDay > 0.25 * p.tdee) add(GoalWarning.LARGE_DEFICIT)
        val goal = p.goalWeightKg
        if (goal != null && abs(goal - trendKg) >= REACHED_KG && p.goalKgPerWeek != 0.0 && (goal - trendKg) * p.goalKgPerWeek < 0) add(GoalWarning.WRONG_DIRECTION)
        if (p.raisedToFloor) add(GoalWarning.AT_FLOOR)
    }

    fun warningText(w: GoalWarning): String = when (w) {
        GoalWarning.FAST_RATE -> "This rate is more than 1% of your body weight a week. A slower rate is easier to keep and keeps more muscle."
        GoalWarning.LARGE_DEFICIT -> "This deficit is more than a quarter of your daily burn."
        GoalWarning.WRONG_DIRECTION -> "Your goal weight is in the other direction from your weekly goal."
        GoalWarning.AT_FLOOR -> "Raised to 1,200 kcal, the lowest suggested target."
    }

    /** Lose up to 1 kg a week, gain up to 0.5 kg. */
    const val MAX_LOSS = 1.0
    const val MAX_GAIN = 0.5
    const val REACHED_KG = 0.2

    /**
     * Change one weekday of a calorie schedule and spread the opposite amount over the days the user
     * has not touched, so the week still adds up to 0.
     */
    fun editSchedule(schedule: List<Int>?, day: Int, value: Int, touched: Set<Int>): List<Int> {
        val s = (schedule?.takeIf { it.size == 7 } ?: List(7) { 0 }).toMutableList()
        s[day] = value
        val free = (0 until 7).filter { it != day && it !in touched }.ifEmpty { (0 until 7).filter { it != day } }
        val rest = -s.filterIndexed { i, _ -> i !in free }.sum()
        val each = rest / free.size
        var rem = rest - each * free.size
        free.forEach { i -> s[i] = each + if (rem != 0) { val step = if (rem > 0) 1 else -1; rem -= step; step } else 0 }
        return s
    }

    /** Saturday and Sunday get +[extra]; the five weekdays share the cost. */
    fun higherWeekends(extra: Int): List<Int> {
        val weekday = -(extra * 2) / 5
        val s = MutableList(7) { weekday }
        s[5] = extra; s[6] = extra
        s[0] -= s.sum() // keep the sum at exactly 0
        return s
    }
}
