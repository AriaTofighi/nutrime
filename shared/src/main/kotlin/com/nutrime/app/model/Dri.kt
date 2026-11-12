package com.nutrime.app.model

import kotlin.math.roundToInt

/**
 * Dietary Reference Intakes (RDA/AI) for adults, from the US National Academies.
 * Ages under 19 use the 19–30 values.
 */
object Dri {
    /** Targets for [p]; with a [date], the calorie target follows the weekday schedule. */
    fun targets(p: Profile, date: java.time.LocalDate? = null): Map<Int, Target> {
        val male = p.sex == Sex.MALE
        val age = p.age
        val kcal = p.calorieTargetOn(date).toDouble()
        val t = HashMap<Int, Target>()
        fun set(id: Int, min: Double?, max: Double? = null) { t[id] = Target(min, max) }

        set(N.ENERGY, kcal)
        val m = macroGrams(p, kcal)
        set(N.PROTEIN, m.protein)
        set(N.CARBS, m.carbs)
        set(N.FAT, m.fat)
        set(N.FIBER, 14.0 * kcal / 1000.0)
        set(N.SAT_FAT, null, kcal * 0.10 / 9.0)
        set(N.ADDED_SUGARS, null, kcal * 0.10 / 4.0)
        set(N.TRANS_FAT, null, 2.0)
        set(N.CHOLESTEROL, null, 300.0)
        set(N.CAFFEINE, null, 400.0)
        set(N.WATER, if (male) 3700.0 else 2700.0)
        set(N.OMEGA3_ALA, if (male) 1.6 else 1.1)
        set(N.OMEGA6_LA, when {
            male && age <= 50 -> 17.0
            male -> 14.0
            age <= 50 -> 12.0
            else -> 11.0
        })

        set(N.VIT_A, if (male) 900.0 else 700.0, 3000.0)
        set(N.VIT_C, if (male) 90.0 else 75.0, 2000.0)
        set(N.VIT_D, if (age > 70) 20.0 else 15.0, 100.0)
        set(N.VIT_E, 15.0, 1000.0)
        set(N.VIT_K, if (male) 120.0 else 90.0)
        set(N.THIAMIN, if (male) 1.2 else 1.1)
        set(N.RIBOFLAVIN, if (male) 1.3 else 1.1)
        set(N.NIACIN, if (male) 16.0 else 14.0, 35.0)
        set(N.PANTOTHENIC, 5.0)
        set(N.B6, if (age <= 50) 1.3 else if (male) 1.7 else 1.5, 100.0)
        set(N.FOLATE, 400.0)
        set(N.B12, 2.4)
        set(N.CHOLINE, if (male) 550.0 else 425.0, 3500.0)

        set(N.CALCIUM, when {
            male && age > 70 -> 1200.0
            !male && age > 50 -> 1200.0
            else -> 1000.0
        }, if (age > 50) 2000.0 else 2500.0)
        set(N.COPPER, 0.9, 10.0)
        set(N.IRON, if (!male && age <= 50) 18.0 else 8.0, 45.0)
        set(N.MAGNESIUM, when {
            male && age <= 30 -> 400.0
            male -> 420.0
            age <= 30 -> 310.0
            else -> 320.0
        })
        set(N.MANGANESE, if (male) 2.3 else 1.8, 11.0)
        set(N.PHOSPHORUS, 700.0, if (age > 70) 3000.0 else 4000.0)
        set(N.POTASSIUM, if (male) 3400.0 else 2600.0)
        set(N.SELENIUM, 55.0, 400.0)
        set(N.SODIUM, 1500.0, 2300.0)
        set(N.ZINC, if (male) 11.0 else 8.0, 40.0)

        p.targetOverrides.forEach { (id, v) -> t[id] = Target(v, t[id]?.max) }
        return t
    }

    data class MacroGrams(val protein: Double, val carbs: Double, val fat: Double) {
        /** Share of calories from each macro, 0..100. */
        fun percents(kcal: Double): Triple<Int, Int, Int> = if (kcal <= 0) Triple(0, 0, 0) else Triple(
            (protein * 4 / kcal * 100).roundToInt(), (carbs * 4 / kcal * 100).roundToInt(), (fat * 9 / kcal * 100).roundToInt(),
        )
    }

    /**
     * Macro grams for [kcal]. In PER_KG mode protein is grams per kg of body weight, fat keeps its
     * percent, and carbohydrate takes the remaining calories.
     */
    fun macroGrams(p: Profile, kcal: Double = p.calorieTarget.toDouble()): MacroGrams = when (p.proteinMode) {
        ProteinMode.PERCENT -> MacroGrams(kcal * p.proteinPct / 100.0 / 4.0, kcal * p.carbsPct / 100.0 / 4.0, kcal * p.fatPct / 100.0 / 9.0)
        ProteinMode.PER_KG -> {
            val protein = p.proteinGPerKg * p.weightKg
            val fat = kcal * p.fatPct / 100.0 / 9.0
            MacroGrams(protein, ((kcal - protein * 4 - fat * 9) / 4.0).coerceAtLeast(0.0), fat)
        }
    }
}
