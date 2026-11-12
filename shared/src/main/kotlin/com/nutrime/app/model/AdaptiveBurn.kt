package com.nutrime.app.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** One day of logged food, as the burn filter sees it. */
data class DayLog(
    val date: LocalDate,
    val kcal: Double,
    val carbsG: Double = 0.0,
    /** The user marked the day's log complete (this includes a day with no food). */
    val complete: Boolean = false,
    val hasEntries: Boolean = kcal > 0,
)

data class WeighIn(val date: LocalDate, val kg: Double)

data class BurnDay(
    val date: LocalDate,
    /** Smoothed true weight. */
    val trendKg: Double,
    val trendSd: Double,
    val burnKcal: Double,
    val burnSd: Double,
    /** The intake the filter used; null on a day it did not trust. */
    val intakeKcal: Double?,
    val scaleKg: Double?,
)

enum class Confidence { LEARNING, MEDIUM, HIGH }

data class BurnEstimate(
    val days: List<BurnDay>,
    val burnKcal: Int,
    val burnSd: Int,
    val confidence: Confidence,
    val trendKg: Double,
    /** Slope of the trend weight over the last 14 days, kg per week. */
    val ratePerWeekKg: Double,
    val knownDays: Int,
    val weighIns: Int,
    val spanDays: Int,
    val formulaKcal: Int,
) {
    /** What is still needed before the estimate leaves LEARNING, as short phrases. */
    val missing: List<String>
        get() = buildList {
            if (spanDays < AdaptiveBurn.MIN_SPAN) add("${AdaptiveBurn.MIN_SPAN - spanDays} more days")
            if (weighIns < AdaptiveBurn.MIN_WEIGH_INS) add("${AdaptiveBurn.MIN_WEIGH_INS - weighIns} more weigh-ins")
            if (knownDays < AdaptiveBurn.MIN_KNOWN) add("${AdaptiveBurn.MIN_KNOWN - knownDays} more fully logged days")
        }
}

/**
 * Learns the user's true daily burn from logged food and weigh-ins.
 *
 * A Kalman filter with two hidden values per day: true weight W and daily burn E.
 *   W[t+1] = W[t] + (intake[t] − E[t]) / 7700
 *   E[t+1] = E[t] + drift
 *   scale[t] = W[t] + noise
 * Days with an untrusted log are not read as zero: the filter uses recent mean intake and widens its
 * uncertainty instead. A Rauch-Tung-Striebel pass then smooths the past days (the trend weight).
 *
 * The web app has a copy of this code in web/src/lib/burn.ts; both must give the same numbers
 * (shared/testdata/burn_vectors.json).
 */
object AdaptiveBurn {
    const val KCAL_PER_KG = 7700.0
    const val SIGMA_E0 = 300.0
    const val Q_E = 12.0
    const val Q_W = 0.03
    const val SIGMA_I = 150.0
    const val SIGMA_I_MISSING = 500.0
    const val MAX_DAYS = 365
    const val MIN_SPAN = 14
    const val MIN_WEIGH_INS = 6
    const val MIN_KNOWN = 10

    /** Whether a day's intake is trusted: marked complete, or a past day with at least half the target. */
    fun isKnown(log: DayLog?, target: Double, today: LocalDate): Boolean {
        if (log == null) return false
        if (log.complete) return true
        return log.date.isBefore(today) && log.hasEntries && log.kcal >= 0.5 * target
    }

    fun estimate(profile: Profile, logs: List<DayLog>, weights: List<WeighIn>, today: LocalDate): BurnEstimate? {
        val windowStart = today.minusDays(MAX_DAYS - 1L)
        val scale = HashMap<LocalDate, Double>()
        weights.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(today) && it.kg > 0 }
            .forEach { scale[it.date] = it.kg }
        val start = scale.keys.minOrNull() ?: return null
        val logByDate = logs.associateBy { it.date }
        val n = ChronoUnit.DAYS.between(start, today).toInt() + 1

        fun bmrAt(w: Double) = profile.copy(weightKg = w).bmr
        val w0 = scale[start]!!
        val formula0 = profile.copy(weightKg = w0).formulaTdee

        // Per day: intake used (null = not known), carbohydrate for the water-shift guard.
        val known = BooleanArray(n)
        val intake = DoubleArray(n)
        val carbs = DoubleArray(n) { Double.NaN }
        for (i in 0 until n) {
            val d = start.plusDays(i.toLong())
            val log = logByDate[d]
            if (isKnown(log, profile.calorieTargetOn(d).toDouble(), today)) {
                known[i] = true
                intake[i] = log!!.kcal
                carbs[i] = log.carbsG
            }
        }
        // Water-shift guard: a large change in carbohydrate makes the next 3 weigh-ins less certain.
        val rBoost = DoubleArray(n + 4) { 1.0 }
        for (i in 0 until n) {
            if (!known[i]) continue
            var sum = 0.0; var cnt = 0
            for (j in maxOf(0, i - 7) until i) if (known[j]) { sum += carbs[j]; cnt++ }
            if (cnt >= 3 && abs(carbs[i] - sum / cnt) > 100.0) for (k in 1..3) rBoost[i + k] = 4.0
        }

        // Filter storage for the smoother.
        val xf = Array(n) { DoubleArray(2) }
        val pf = Array(n) { DoubleArray(4) }
        val xp = Array(n) { DoubleArray(2) }
        val pp = Array(n) { DoubleArray(4) }
        val fB = DoubleArray(n) // F = [[1, fB], [0, 1]] for the step from day i to i+1
        val used = arrayOfNulls<Double>(n)

        var x = doubleArrayOf(w0, formula0)
        var p = doubleArrayOf(0.0, 0.0, 0.0, SIGMA_E0 * SIGMA_E0)
        var weighIns = 0
        for (i in 0 until n) {
            if (i == 0) {
                val r = measurementSd(w0)
                p[0] = r * r
            }
            xp[i] = x.copyOf(); pp[i] = p.copyOf()
            // Update with the morning weigh-in.
            val z = scale[start.plusDays(i.toLong())]
            if (z != null) {
                weighIns++
                if (i > 0) {
                    val sd = measurementSd(x[0])
                    var r = sd * sd * rBoost[i]
                    val v = z - x[0]
                    var s = p[0] + r
                    if (v * v > 9 * s) { r *= v * v / (9 * s); s = p[0] + r }
                    val k0 = p[0] / s
                    val k1 = p[2] / s
                    x = doubleArrayOf(x[0] + k0 * v, x[1] + k1 * v)
                    p = doubleArrayOf(p[0] - k0 * p[0], p[1] - k0 * p[1], p[2] - k1 * p[0], p[3] - k1 * p[1])
                }
            }
            val bmr = bmrAt(x[0])
            x[1] = x[1].coerceIn(0.9 * bmr, 2.5 * bmr)
            xf[i] = x.copyOf(); pf[i] = p.copyOf()
            if (i == n - 1) break

            // Predict through day i's food to the next morning.
            val sigmaI: Double
            val b: Double
            val iUse: Double?
            if (known[i]) {
                iUse = intake[i]; sigmaI = SIGMA_I; b = -1.0 / KCAL_PER_KG
            } else {
                var sum = 0.0; var cnt = 0
                for (j in maxOf(0, i - 14) until i) if (known[j]) { sum += intake[j]; cnt++ }
                sigmaI = SIGMA_I_MISSING
                if (cnt > 0) { iUse = sum / cnt; b = -1.0 / KCAL_PER_KG } else { iUse = null; b = 0.0 }
            }
            used[i] = if (known[i]) intake[i] else null
            fB[i] = b
            val w = if (iUse != null) x[0] + (iUse - x[1]) / KCAL_PER_KG else x[0]
            // P' = F P Fᵀ + Q
            val p00 = p[0] + b * (p[2] + p[1]) + b * b * p[3]
            val p01 = p[1] + b * p[3]
            val p10 = p[2] + b * p[3]
            val p11 = p[3]
            val qW = (sigmaI / KCAL_PER_KG).let { it * it } + Q_W * Q_W
            x = doubleArrayOf(w, x[1])
            p = doubleArrayOf(p00 + qW, p01, p10, p11 + Q_E * Q_E)
        }

        // Rauch-Tung-Striebel smoother.
        val xs = Array(n) { xf[it].copyOf() }
        val ps = Array(n) { pf[it].copyOf() }
        for (i in n - 2 downTo 0) {
            val b = fB[i]
            val pi = pf[i]
            // P F^T
            val a00 = pi[0] + pi[1] * b
            val a01 = pi[1]
            val a10 = pi[2] + pi[3] * b
            val a11 = pi[3]
            val q = pp[i + 1]
            val det = q[0] * q[3] - q[1] * q[2]
            if (abs(det) < 1e-12) continue
            val i00 = q[3] / det; val i01 = -q[1] / det; val i10 = -q[2] / det; val i11 = q[0] / det
            val c00 = a00 * i00 + a01 * i10
            val c01 = a00 * i01 + a01 * i11
            val c10 = a10 * i00 + a11 * i10
            val c11 = a10 * i01 + a11 * i11
            val dx0 = xs[i + 1][0] - xp[i + 1][0]
            val dx1 = xs[i + 1][1] - xp[i + 1][1]
            xs[i] = doubleArrayOf(xf[i][0] + c00 * dx0 + c01 * dx1, xf[i][1] + c10 * dx0 + c11 * dx1)
            val d00 = ps[i + 1][0] - q[0]; val d01 = ps[i + 1][1] - q[1]
            val d10 = ps[i + 1][2] - q[2]; val d11 = ps[i + 1][3] - q[3]
            // C D Cᵀ
            val e00 = c00 * d00 + c01 * d10; val e01 = c00 * d01 + c01 * d11
            val e10 = c10 * d00 + c11 * d10; val e11 = c10 * d01 + c11 * d11
            ps[i] = doubleArrayOf(
                pi[0] + e00 * c00 + e01 * c01, pi[1] + e00 * c10 + e01 * c11,
                pi[2] + e10 * c00 + e11 * c01, pi[3] + e10 * c10 + e11 * c11,
            )
        }

        val days = (0 until n).map { i ->
            BurnDay(
                date = start.plusDays(i.toLong()),
                trendKg = xs[i][0], trendSd = sqrt(maxOf(ps[i][0], 0.0)),
                burnKcal = xs[i][1], burnSd = sqrt(maxOf(ps[i][3], 0.0)),
                intakeKcal = used[i], scaleKg = scale[start.plusDays(i.toLong())],
            )
        }
        val last = days.last()
        val knownDays = known.count { it }
        val burnSd = last.burnSd
        val confidence = when {
            n < MIN_SPAN || weighIns < MIN_WEIGH_INS || knownDays < MIN_KNOWN || burnSd > 200 -> Confidence.LEARNING
            burnSd < 120 -> Confidence.HIGH
            else -> Confidence.MEDIUM
        }
        return BurnEstimate(
            days = days,
            burnKcal = Math.round(last.burnKcal).toInt(),
            burnSd = Math.round(burnSd).toInt(),
            confidence = confidence,
            trendKg = last.trendKg,
            ratePerWeekKg = slopePerWeek(days.takeLast(14).map { it.trendKg }),
            knownDays = knownDays,
            weighIns = weighIns,
            spanDays = n,
            formulaKcal = Math.round(profile.formulaTdee).toInt(),
        )
    }

    /** Typical day-to-day scale noise for a body weight. */
    fun measurementSd(kg: Double): Double = maxOf(0.4, 0.008 * kg)

    /** Least-squares slope of daily values, per week. */
    fun slopePerWeek(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val n = values.size
        val mx = (n - 1) / 2.0
        val my = values.average()
        var num = 0.0; var den = 0.0
        values.forEachIndexed { i, v -> num += (i - mx) * (v - my); den += (i - mx) * (i - mx) }
        return if (den == 0.0) 0.0 else num / den * 7.0
    }

    /** Weekly check-in: the new accepted burn, limited to a 150 kcal step from [current]. */
    fun nextAccepted(current: Double, learned: Int): Pair<Int, Boolean> {
        val step = (learned - current).coerceIn(-MAX_STEP, MAX_STEP)
        val limited = abs(learned - current) > MAX_STEP
        return Math.round(current + step).toInt() to limited
    }

    const val MAX_STEP = 150.0
    const val CHECK_IN_DAYS = 7L
    const val MIN_CHANGE = 40

    /** Whether the weekly check-in card should show. */
    fun checkInDue(p: Profile, est: BurnEstimate?, today: LocalDate): Boolean {
        if (est == null || est.confidence == Confidence.LEARNING) return false
        if (p.burnModeChosen && p.burnMode != BurnMode.ADAPTIVE) return false
        val last = p.adaptiveBurnDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (last != null && ChronoUnit.DAYS.between(last, today) < CHECK_IN_DAYS) return false
        return abs(est.burnKcal - p.tdee) >= MIN_CHANGE
    }
}
