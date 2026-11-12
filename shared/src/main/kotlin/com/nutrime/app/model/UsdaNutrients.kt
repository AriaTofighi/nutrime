package com.nutrime.app.model

/** USDA values converted to NutriMe units, per 100 g. */
object UsdaNutrients {
    private val FALLBACKS = mapOf(
        N.ENERGY to listOf(1008, 2048, 2047),
        N.FAT to listOf(1004, 1085),
        N.CARBS to listOf(1005, 1050),
        N.SUGARS to listOf(2000, 1063),
        N.FOLATE to listOf(1190, 1177),
        N.OMEGA3_ALA to listOf(1404, 1270),
        N.OMEGA6_LA to listOf(1316, 1269),
    )

    /** Map USDA nutrient ids/units to the app's canonical ids and units. */
    fun canonicalize(raw: Map<Int, Pair<Double, String>>): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        com.nutrime.app.model.Nutrients.all.forEach { def ->
            val ids = FALLBACKS[def.id] ?: listOf(def.id)
            for (id in ids) {
                val (v, unit) = raw[id] ?: continue
                if (def.id == N.ENERGY && unit == "KJ") continue
                out[def.id] = v
                break
            }
        }
        if (out[N.ENERGY] == null) raw[1062]?.let { (kj, _) -> out[N.ENERGY] = kj / 4.184 }
        if (out[N.VIT_D] == null) raw[1110]?.let { (iu, _) -> out[N.VIT_D] = iu / 40.0 }
        return out
    }

    fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)

    /** Branded descriptions are SHOUTED; turn them into Title Case. */
    fun prettify(s: String, branded: Boolean): String {
        if (!branded || s.any { it.isLowerCase() }) return s.trim()
        return s.trim().lowercase().split(" ").joinToString(" ") { w ->
            w.replaceFirstChar { it.titlecase() }
        }
    }
}
