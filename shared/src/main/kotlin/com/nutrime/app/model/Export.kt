package com.nutrime.app.model

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** CSV and ZIP writers for "Export your data". Copied in web/src/lib/export.ts. */
object Export {
    data class DiaryRow(val date: String, val meal: Int, val food: String, val brand: String?, val amount: Double, val serving: String, val grams: Double, val nutrients: NutrientMap)
    data class DayRow(val date: String, val complete: Boolean, val note: String?, val steps: Int?, val activeKcal: Double?, val target: Int?, val totals: NutrientMap)
    data class WeightRow(val date: String, val kg: Double, val bodyFatPct: Double?, val trendKg: Double?, val note: String?)

    private val MEALS = listOf("Breakfast", "Lunch", "Dinner", "Snacks")

    fun cell(v: Any?): String {
        val s = when (v) {
            null -> ""
            is Double -> if (v.isNaN()) "" else trim(v)
            else -> v.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else "%.4f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private fun line(vararg cells: Any?) = cells.joinToString(",") { cell(it) } + "\r\n"

    private fun nutrientHeaders() = Nutrients.all.map { "${it.name} (${it.unit})" }.toTypedArray()

    fun diaryCsv(rows: List<DiaryRow>): String = buildString {
        append(line("Date", "Meal", "Food", "Brand", "Amount", "Serving", "Grams", *nutrientHeaders()))
        rows.sortedWith(compareBy({ it.date }, { it.meal })).forEach { r ->
            append(line(r.date, MEALS.getOrElse(r.meal) { "" }, r.food, r.brand, r.amount, r.serving, r.grams, *Nutrients.all.map { r.nutrients[it.id] }.toTypedArray()))
        }
    }

    fun daysCsv(rows: List<DayRow>): String = buildString {
        append(line("Date", "Complete", "Note", "Steps", "Active energy (kcal)", "Calorie target (kcal)", *nutrientHeaders()))
        rows.sortedBy { it.date }.forEach { r ->
            append(line(r.date, if (r.complete) "yes" else "no", r.note, r.steps, r.activeKcal, r.target, *Nutrients.all.map { r.totals[it.id] }.toTypedArray()))
        }
    }

    fun weightsCsv(rows: List<WeightRow>): String = buildString {
        append(line("Date", "Weight (kg)", "Body fat (%)", "Trend weight (kg)", "Note"))
        rows.sortedBy { it.date }.forEach { r -> append(line(r.date, r.kg, r.bodyFatPct, r.trendKg?.let { Math.round(it * 100) / 100.0 }, r.note)) }
    }

    /** A ZIP of named text files (UTF-8). */
    fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
