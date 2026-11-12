package com.nutrime.app.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** A Trends period. Copied in web/src/lib/range.ts. */
sealed interface TrendRange {
    data class Last(val days: Int) : TrendRange
    data object All : TrendRange
    data class Custom(val from: LocalDate, val to: LocalDate) : TrendRange

    /** Encoded for routes and settings: "7d", "all", "2026-01-01..2026-02-15". */
    fun encode(): String = when (this) {
        is Last -> "${days}d"
        All -> "all"
        is Custom -> "$from..$to"
    }

    /** First and last day; [firstData] is the first day with any diary entry or weigh-in. */
    fun bounds(today: LocalDate, firstData: LocalDate?): Pair<LocalDate, LocalDate> = when (this) {
        is Last -> today.minusDays(days - 1L) to today
        All -> ((firstData ?: today).let { if (it.isAfter(today)) today else it }).let { maxOf(it, today.minusDays(MAX_DAYS * 5L)) } to today
        is Custom -> from to to
    }

    companion object {
        const val MAX_DAYS = 730
        val PRESETS = listOf(7, 30, 90, 180, 365)

        fun decode(s: String?): TrendRange? {
            if (s.isNullOrBlank()) return null
            if (s == "all") return All
            if (s.endsWith("d")) return s.dropLast(1).toIntOrNull()?.takeIf { it in 1..3650 }?.let { Last(it) }
            val parts = s.split("..")
            if (parts.size != 2) return null
            val a = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
            val b = runCatching { LocalDate.parse(parts[1]) }.getOrNull() ?: return null
            return custom(a, b)
        }

        /** A valid custom range (1 to 730 days, ordered), or null. */
        fun custom(a: LocalDate, b: LocalDate): Custom? {
            val (from, to) = if (a.isAfter(b)) b to a else a to b
            val n = ChronoUnit.DAYS.between(from, to) + 1
            return if (n in 1..MAX_DAYS) Custom(from, to) else null
        }
    }
}

enum class BucketSize { DAY, WEEK, MONTH }

/** One bar in a Trends chart: a day, a week or a month. */
data class Bucket(val from: LocalDate, val to: LocalDate, val size: BucketSize) {
    val days: Long get() = ChronoUnit.DAYS.between(from, to) + 1
}

object TrendBuckets {
    fun sizeFor(days: Long): BucketSize = when {
        days <= 31 -> BucketSize.DAY
        days <= 182 -> BucketSize.WEEK
        else -> BucketSize.MONTH
    }

    /** Split [from]..[to] into buckets; weeks start on Monday. Partial weeks and months are cut to the range. */
    fun buckets(from: LocalDate, to: LocalDate): List<Bucket> {
        val size = sizeFor(ChronoUnit.DAYS.between(from, to) + 1)
        val out = ArrayList<Bucket>()
        var d = from
        while (!d.isAfter(to)) {
            val end = when (size) {
                BucketSize.DAY -> d
                BucketSize.WEEK -> d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                BucketSize.MONTH -> d.with(TemporalAdjusters.lastDayOfMonth())
            }.let { if (it.isAfter(to)) to else it }
            out += Bucket(d, end, size)
            d = end.plusDays(1)
        }
        return out
    }

    /**
     * Mean of [values] over the counted days of each bucket (null when a bucket has none).
     * [counted] is the set of days that count toward averages.
     */
    fun <T> average(buckets: List<Bucket>, values: Map<LocalDate, T>, counted: Set<LocalDate>, mean: (List<T>) -> T): List<T?> =
        buckets.map { b ->
            val inside = generateSequence(b.from) { it.plusDays(1) }.takeWhile { !it.isAfter(b.to) }
                .filter { it in counted }.mapNotNull { values[it] }.toList()
            if (inside.isEmpty()) null else mean(inside)
        }

    /** The period of the same length that ends the day before [from]. */
    fun previous(from: LocalDate, to: LocalDate): Pair<LocalDate, LocalDate> {
        val n = ChronoUnit.DAYS.between(from, to) + 1
        return from.minusDays(n) to from.minusDays(1)
    }
}
