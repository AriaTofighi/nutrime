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
    /** Last edit time (ms) for non-favourite profile settings, for conflict-safe sync merging. */
    val settingsUpdatedAt: Long = 0L,
    /** Last edit time (ms) for favourites, for conflict-safe sync merging. */
    val favoritesUpdatedAt: Long = 0L,
    /** Per-food favourite change timestamps (+ms = added, -ms = removed) so concurrent favourite edits merge. */
    val favoriteChanges: Map<String, Long> = emptyMap(),
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

    private fun settingsSignature() = copy(
        favoriteFoodIds = emptyList(),
        settingsUpdatedAt = 0L,
        favoritesUpdatedAt = 0L,
        favoriteChanges = emptyMap(),
    )

    companion object {
        /** ~7700 kcal per kg of body weight change. */
        const val KCAL_PER_KG = 7700.0
        const val MIN_CALORIES = 1200.0

        /**
         * Stamp [settingsUpdatedAt], [favoritesUpdatedAt], and [favoriteChanges] based on what actually
         * changed from [prev] to [next], so concurrent edits to settings vs. favourites can merge safely.
         */
        fun stampEdit(prev: Profile?, next: Profile, at: Long): Profile {
            val prevFavs = prev?.favoriteFoodIds?.toSet().orEmpty()
            val nextFavs = next.favoriteFoodIds.distinct()
            val nextFavSet = nextFavs.toSet()
            val favsChanged = if (prev == null) nextFavs.isNotEmpty() else prev.favoriteFoodIds != nextFavs
            val settingsChanged = if (prev == null) {
                next.settingsSignature() != Profile().settingsSignature() || !favsChanged
            } else {
                prev.settingsSignature() != next.settingsSignature()
            }
            val changes = LinkedHashMap<String, Long>(prev?.favoriteChanges ?: next.favoriteChanges)
            if (favsChanged) {
                for (id in nextFavSet) if (id !in prevFavs) changes[id] = at
                for (id in prevFavs) if (id !in nextFavSet) changes[id] = -at
            }
            val sAt = if (settingsChanged) maxOf(at, prev?.settingsUpdatedAt ?: 0L) else (prev?.settingsUpdatedAt ?: next.settingsUpdatedAt)
            val fAt = if (favsChanged) maxOf(at, prev?.favoritesUpdatedAt ?: 0L) else (prev?.favoritesUpdatedAt ?: next.favoritesUpdatedAt)
            return next.copy(
                favoriteFoodIds = nextFavs,
                settingsUpdatedAt = sAt,
                favoritesUpdatedAt = fAt,
                favoriteChanges = changes,
            )
        }

        private fun settingsTime(p: Profile, recordUpdatedAt: Long): Long = when {
            p.settingsUpdatedAt > 0L -> p.settingsUpdatedAt
            p.favoritesUpdatedAt > 0L -> 0L
            else -> recordUpdatedAt
        }

        /**
         * Merge two concurrently edited profiles without clobbering settings against favourites or
         * concurrent favourite toggles against each other.
         */
        fun merge(a: Profile, aUpdatedAt: Long, b: Profile, bUpdatedAt: Long): Profile {
            val sa = settingsTime(a, aUpdatedAt)
            val sb = settingsTime(b, bUpdatedAt)
            val base = when {
                sb > sa -> b
                sa > sb -> a
                bUpdatedAt > aUpdatedAt -> b
                else -> a
            }
            val mergedChanges = LinkedHashMap<String, Long>(a.favoriteChanges)
            for ((id, tb) in b.favoriteChanges) {
                val ta = mergedChanges[id]
                if (ta == null || kotlin.math.abs(tb) > kotlin.math.abs(ta) || (kotlin.math.abs(tb) == kotlin.math.abs(ta) && tb > ta)) {
                    mergedChanges[id] = tb
                }
            }
            val legacyFavs = when {
                b.favoritesUpdatedAt > a.favoritesUpdatedAt -> b.favoriteFoodIds
                a.favoritesUpdatedAt > b.favoritesUpdatedAt -> a.favoriteFoodIds
                else -> (a.favoriteFoodIds + b.favoriteFoodIds).distinct()
            }
            val favs = LinkedHashSet<String>()
            for (id in legacyFavs) {
                val ts = mergedChanges[id]
                if (ts == null || ts > 0L) favs += id
            }
            mergedChanges.entries.filter { it.value > 0L }.sortedBy { it.value }.forEach { favs += it.key }
            return base.copy(
                favoriteFoodIds = favs.toList(),
                settingsUpdatedAt = maxOf(a.settingsUpdatedAt, b.settingsUpdatedAt),
                favoritesUpdatedAt = maxOf(a.favoritesUpdatedAt, b.favoritesUpdatedAt),
                favoriteChanges = mergedChanges,
            )
        }
    }
}

data class Target(val min: Double?, val max: Double? = null)
