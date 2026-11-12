package com.nutrime.app.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.roundToInt

@Serializable
enum class Sex { MALE, FEMALE }

@Serializable
enum class ActivityLevel(val label: String, val description: String, val factor: Double) {
    SEDENTARY("Sedentary", "Little or no exercise", 1.2),
    LIGHT("Lightly active", "Exercise 1–3 days/week", 1.375),
    MODERATE("Moderately active", "Exercise 3–5 days/week", 1.55),
    VERY("Very active", "Hard exercise 6–7 days/week", 1.725),
    EXTRA("Extra active", "Physical job or 2x/day training", 1.9),
}

/** Where the daily burn (total energy expenditure) comes from. */
@Serializable
enum class BurnMode { FORMULA, ADAPTIVE, MANUAL }

/** How the protein target is set: a share of calories, or grams per kg of body weight. */
@Serializable
enum class ProteinMode { PERCENT, PER_KG }

@Serializable
data class Profile(
    val sex: Sex = Sex.MALE,
    val birthYear: Int = 1990,
    val heightCm: Double = 175.0,
    val weightKg: Double = 75.0,
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    /** Weekly weight change goal in kg (negative = lose). */
    val goalKgPerWeek: Double = 0.0,
    val proteinPct: Int = 25,
    val carbsPct: Int = 45,
    val fatPct: Int = 30,
    /** If set, overrides the computed calorie target. */
    val calorieOverride: Int? = null,
    /** Per-nutrient minimum target overrides (nutrient id -> amount). */
    val targetOverrides: Map<Int, Double> = emptyMap(),
    val imperial: Boolean = false,
    val configured: Boolean = false,
    val burnMode: BurnMode = BurnMode.FORMULA,
    /** The learned daily burn the user last accepted (see AdaptiveBurn). */
    val adaptiveBurnKcal: Int? = null,
    /** ISO date of the last weekly check-in (accepted or kept). */
    val adaptiveBurnDate: String? = null,
    /** True once the user has chosen a burn mode or answered a check-in. */
    val burnModeChosen: Boolean = false,
    /** Daily burn typed by the user (BurnMode.MANUAL). */
    val manualBurnKcal: Int? = null,
    val goalWeightKg: Double? = null,
    val proteinMode: ProteinMode = ProteinMode.PERCENT,
    val proteinGPerKg: Double = 1.8,
    /** kcal added to the target on each weekday, Monday first. The values add up to 0. */
    val calorieSchedule: List<Int>? = null,
    /** Apply weekly check-in results without asking. */
    val autoAcceptBurn: Boolean = false,
    val favoriteFoodIds: List<String> = emptyList(),
) {
    val age: Int get() = (LocalDate.now().year - birthYear).coerceIn(1, 120)

    /** Mifflin-St Jeor resting energy expenditure. */
    val bmr: Double
        get() = 10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5 else -161

    /** Daily burn from the formula: resting burn × activity factor. */
    val formulaTdee: Double get() = bmr * activity.factor

    /** The daily burn used for targets: learned, typed, or the formula. */
    val tdee: Double
        get() = when (burnMode) {
            BurnMode.ADAPTIVE -> adaptiveBurnKcal?.toDouble() ?: formulaTdee
            BurnMode.MANUAL -> manualBurnKcal?.toDouble() ?: formulaTdee
            BurnMode.FORMULA -> formulaTdee
        }

    /** Daily calorie change for the weekly goal. */
    val goalKcalPerDay: Double get() = goalKgPerWeek * KCAL_PER_KG / 7.0

    /** ~7700 kcal per kg of body weight. */
    val computedCalories: Int
        get() = (tdee + goalKcalPerDay).coerceAtLeast(MIN_CALORIES).roundToInt()

    val calorieTarget: Int get() = calorieOverride ?: computedCalories

    /** The calorie target on [date], with the weekday schedule applied. */
    fun calorieTargetOn(date: LocalDate?): Int {
        val add = date?.let { d -> calorieSchedule?.takeIf { it.size == 7 }?.get(d.dayOfWeek.value - 1) } ?: 0
        return (calorieTarget + add).coerceAtLeast(800)
    }

    /** True when the weekly goal would put the target below the 1,200 kcal floor. */
    val raisedToFloor: Boolean get() = calorieOverride == null && tdee + goalKcalPerDay < MIN_CALORIES

    companion object {
        /** ~7700 kcal per kg of body weight change. */
        const val KCAL_PER_KG = 7700.0
        const val MIN_CALORIES = 1200.0
    }
}

data class Target(val min: Double?, val max: Double? = null)
