package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Target
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import kotlin.math.roundToInt

// Read-only assistant tools: the diary, reports, trends and food lookups.

/** Nutrients below target (worst first) and limits exceeded, for a total over [days] days. */
fun gapsAndExcesses(totals: NutrientMap, targets: Map<Int, Target>, days: Int = 1): Pair<List<String>, List<String>> {
    val gaps = mutableListOf<Triple<Int, Double, Double>>()
    val over = mutableListOf<String>()
    Nutrients.all.forEach { def ->
        val t = targets[def.id] ?: return@forEach
        val have = (totals[def.id] ?: 0.0) / days
        t.min?.let { min -> if (have < min && def.id != N.ENERGY) gaps += Triple(def.id, have, min) }
        t.max?.let { max -> if (have > max) over += "${nutrientKey(def.id)}: ${r(have)} (limit ${r(max)})" }
    }
    return gaps.sortedBy { it.second / it.third }
        .map { (id, have, min) -> "${nutrientKey(id)}: ${r(have)} of ${r(min)} (${(have / min * 100).toInt()}%)" } to over
}

fun getDay(ctx: ToolContext, date: String): JsonObject {
    val entries = ctx.data.day(date)
    val totals = ctx.data.totals(entries)
    val t = ctx.data.targets
    val (gaps, over) = gapsAndExcesses(totals, t)
    return buildJsonObject {
        put("date", date)
        putJsonArray("entries") {
            entries.forEach { e ->
                addJsonObject {
                    put("entry_id", e.id)
                    put("meal", MEALS.getOrElse(e.meal) { "snacks" })
                    put("food", e.foodName + (e.brand?.let { " ($it)" } ?: ""))
                    put("food_id", e.foodId)
                    put("amount", if (e.servingLabel == "g") "${r(e.quantity)} g" else "${r(e.quantity, 2)} × ${e.servingLabel}")
                    put("grams", r(e.grams))
                    macros(e.nutrients).forEach { (k, v) -> put(k, v) }
                }
            }
        }
        put("totals", macros(totals).let { m -> JsonObject(m + ("fiber_g" to JsonPrimitive(r(totals[N.FIBER] ?: 0.0)))) })
        putJsonObject("targets") {
            put("kcal", r(t[N.ENERGY]?.min ?: 0.0, 0))
            put("protein_g", r(t[N.PROTEIN]?.min ?: 0.0, 0))
            put("carbs_g", r(t[N.CARBS]?.min ?: 0.0, 0))
            put("fat_g", r(t[N.FAT]?.min ?: 0.0, 0))
            put("fiber_g", r(t[N.FIBER]?.min ?: 0.0, 0))
        }
        if (entries.isNotEmpty()) {
            putJsonArray("below_target") { gaps.take(10).forEach { add(it) } }
            putJsonArray("over_limit") { over.forEach { add(it) } }
        }
    }
}

internal fun nutrientReport(ctx: ToolContext, start: String, end: String): JsonObject {
    val (from, to) = if (start <= end) start to end else end to start
    val entries = ctx.data.diary.filter { it.date in from..to }
    val days = entries.map { it.date }.distinct().size
    if (days == 0) return buildJsonObject { put("days_logged", 0); put("note", "No food logged between $from and $to") }
    val totals = ctx.data.totals(entries)
    val t = ctx.data.targets
    return buildJsonObject {
        put("start_date", from); put("end_date", to); put("days_logged", days)
        putJsonArray("daily_average") {
            Nutrients.all.forEach { def ->
                val avg = (totals[def.id] ?: 0.0) / days
                val target = t[def.id]
                add(buildString {
                    append("${nutrientKey(def.id)}: ${r(avg)}")
                    target?.min?.let { append(" / target ${r(it)} (${(avg / it * 100).toInt()}%)") }
                    target?.max?.let { append(" / limit ${r(it)}") }
                })
            }
        }
    }
}

internal fun weightHistory(ctx: ToolContext, days: Int): JsonObject {
    val since = LocalDate.parse(ctx.today).minusDays(days.toLong().coerceIn(1, 3650)).toString()
    val list = ctx.data.weights.filter { it.date >= since }
    return buildJsonObject {
        put("unit_preference", if (ctx.data.profile.imperial) "lb" else "kg")
        putJsonArray("weights") {
            list.forEach { w -> addJsonObject { put("date", w.date); put("kg", r(w.weightKg, 2)); w.bodyFatPct?.let { put("body_fat_pct", it) } } }
        }
        if (list.size >= 2) put("change_kg", r(list.last().weightKg - list.first().weightKg, 2))
        ctx.data.burn?.let { e ->
            put("trend_kg", r(e.trendKg, 2))
            put("trend_rate_kg_per_week", r(e.ratePerWeekKg, 2))
            put("note", "Prefer the trend weight: single weigh-ins move about 1 kg with water and food.")
        }
    }
}

internal fun burnEstimate(ctx: ToolContext): JsonObject {
    val p = ctx.data.profile
    val e = ctx.data.burn
    return buildJsonObject {
        put("burn_used_for_targets_kcal", p.tdee.roundToInt())
        put("burn_mode", p.burnMode.name.lowercase())
        put("formula_kcal", p.formulaTdee.roundToInt())
        if (e == null) {
            put("learned", false)
            put("note", "No weigh-ins yet: NutriMe learns the burn from weigh-ins and the food log.")
            return@buildJsonObject
        }
        put("learned_kcal", e.burnKcal)
        put("learned_sd_kcal", e.burnSd)
        put("confidence", e.confidence.name.lowercase())
        if (e.confidence == com.nutrime.app.model.Confidence.LEARNING) put("still_needed", e.missing.joinToString(", "))
        put("fully_logged_days_used", e.knownDays)
        put("weigh_ins_used", e.weighIns)
        put("trend_weight_kg", r(e.trendKg, 2))
        put("rate_kg_per_week", r(e.ratePerWeekKg, 2))
        p.goalWeightKg?.let { g ->
            put("goal_weight_kg", r(g, 1))
            com.nutrime.app.model.Goal.projectedDate(LocalDate.parse(ctx.today), e.trendKg, g, e.ratePerWeekKg)?.let { put("goal_date_at_current_rate", it.toString()) }
            com.nutrime.app.model.Goal.projectedDate(LocalDate.parse(ctx.today), e.trendKg, g, p.goalKgPerWeek)?.let { put("goal_date_at_goal_rate", it.toString()) }
        }
    }
}

private fun dayTotalsIn(ctx: ToolContext, from: String, to: String): Map<LocalDate, com.nutrime.app.model.NutrientMap> =
    ctx.data.diary.filter { it.date in from..to }.groupBy { it.date }
        .mapNotNull { (d, l) -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to ctx.data.totals(l) } }.toMap()

internal fun trends(ctx: ToolContext, start: String, end: String): JsonObject {
    val from = LocalDate.parse(minOf(start, end))
    val to = LocalDate.parse(maxOf(start, end))
    if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 3650) throw ToolError("Range too long")
    val today = LocalDate.parse(ctx.today)
    val totals = dayTotalsIn(ctx, from.toString(), to.toString())
    val counted = totals.keys.filter { it != today }.ifEmpty { totals.keys }.toSet()
    val (pf, pt) = com.nutrime.app.model.TrendBuckets.previous(from, to)
    val prev = dayTotalsIn(ctx, pf.toString(), pt.toString())
    fun avg(m: Map<LocalDate, com.nutrime.app.model.NutrientMap>, days: Collection<LocalDate>, id: Int) =
        if (days.isEmpty()) null else days.sumOf { m[it]?.get(id) ?: 0.0 } / days.size
    val ids = listOf("kcal" to N.ENERGY, "protein_g" to N.PROTEIN, "carbs_g" to N.CARBS, "fat_g" to N.FAT, "fiber_g" to N.FIBER)
    return buildJsonObject {
        put("days_in_range", java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1)
        put("days_logged", counted.size)
        putJsonObject("daily_average") { ids.forEach { (k, id) -> avg(totals, counted, id)?.let { put(k, r(it)) } } }
        if (prev.size >= 3) putJsonObject("previous_period_average") {
            put("from", pf.toString()); put("to", pt.toString()); put("days_logged", prev.size)
            ids.forEach { (k, id) -> avg(prev, prev.keys, id)?.let { put(k, r(it)) } }
        }
        put("calorie_target", ctx.data.profile.calorieTarget)
        val buckets = com.nutrime.app.model.TrendBuckets.buckets(from, to)
        if (buckets.first().size != com.nutrime.app.model.BucketSize.DAY) putJsonArray("by_${buckets.first().size.name.lowercase()}") {
            buckets.forEach { b ->
                val days = counted.filter { !it.isBefore(b.from) && !it.isAfter(b.to) }
                addJsonObject {
                    put("from", b.from.toString()); put("to", b.to.toString()); put("days_logged", days.size)
                    avg(totals, days, N.ENERGY)?.let { put("kcal", r(it)) }
                    avg(totals, days, N.PROTEIN)?.let { put("protein_g", r(it)) }
                }
            }
        }
    }
}

private fun nutrientByName(name: String): com.nutrime.app.model.NutrientDef? {
    val q = name.lowercase().replace("vitamin ", "vit ").trim()
    val all = com.nutrime.app.model.Nutrients.all
    fun norm(s: String) = s.lowercase().replace("vitamin ", "vit ")
    return all.firstOrNull { norm(it.name) == q || norm(it.shortName) == q }
        ?: all.firstOrNull { norm(it.name).contains(q) || norm(it.shortName).contains(q) }
        ?: when (q) { "calories", "energy", "kcal" -> all.first { it.id == N.ENERGY }; "carbohydrate", "carb" -> all.first { it.id == N.CARBS }; "salt" -> all.first { it.id == N.SODIUM }; else -> null }
}

internal fun topSources(ctx: ToolContext, nutrient: String, start: String, end: String): JsonObject {
    val def = nutrientByName(nutrient) ?: throw ToolError("Unknown nutrient '$nutrient'")
    val from = minOf(start, end); val to = maxOf(start, end)
    val items = ctx.data.diary.filter { it.date in from..to }.mapNotNull { e ->
        runCatching { com.nutrime.app.model.LoggedItem(LocalDate.parse(e.date), e.meal, e.foodId, e.foodName, e.nutrients) }.getOrNull()
    }
    val days = items.map { it.date }.toSet().size.coerceAtLeast(1)
    return buildJsonObject {
        put("nutrient", def.name); put("unit", def.unit); put("from", from); put("to", to); put("days_logged", days)
        ctx.data.targets[def.id]?.let { t -> t.min?.let { put("daily_target", r(it, 1)) }; t.max?.let { put("daily_limit", r(it, 1)) } }
        put("daily_average", r(items.sumOf { it.nutrients[def.id] ?: 0.0 } / days, 1))
        putJsonArray("sources") {
            com.nutrime.app.model.Insights.topSources(items, def.id).forEach { s ->
                addJsonObject { put("food", s.name); put("share_pct", (s.share * 100).roundToInt()); put("per_day", r(s.amount / days, 1)); put("times_logged", s.times) }
            }
        }
    }
}

internal fun frequentFoods(ctx: ToolContext, limit: Int): JsonObject {
    val since = LocalDate.parse(ctx.today).minusDays(90).toString()
    val groups = ctx.data.diary.filter { it.date >= since && !it.foodId.startsWith("quick:") }.groupBy { it.foodId }
    return buildJsonObject {
        putJsonArray("foods") {
            groups.entries.sortedByDescending { it.value.size }.take(limit.coerceIn(1, 40)).forEach { (id, list) ->
                val last = list.maxBy { it.createdAt }
                addJsonObject {
                    put("food_id", id)
                    put("name", last.foodName + (last.brand?.let { " ($it)" } ?: ""))
                    put("times_logged", list.size)
                    put("usual_amount", if (last.servingLabel == "g") "${r(last.quantity)} g" else "${r(last.quantity, 2)} × ${last.servingLabel}")
                    put("usual_serving_label", last.servingLabel)
                    put("usual_quantity", last.quantity)
                    put("usual_kcal", r(last.nutrients.kcal(), 0))
                    put("usual_meal", MEALS.getOrElse(last.meal) { "snacks" })
                }
            }
        }
    }
}

private fun foodSummary(f: Food): JsonObject = buildJsonObject {
    put("food_id", f.id)
    put("name", com.nutrime.server.food.cleanFoodName(f.name, f.brand))
    f.brand?.let { put("brand", it) }
    put("source", f.source.label)
    put("per_100g", macros(f.nutrients))
    put("nutrients_listed", f.nutrients.size)
    // Lab analyses list vitamins and minerals; label-only products don't, which makes the diary show false gaps.
    if (f.source == FoodSource.OPEN_FOOD_FACTS || f.source == FoodSource.USDA_BRANDED)
        put("data", if (com.nutrime.app.model.FoodQuality.isComplete(f)) "full nutrients" else "label only (no vitamins/minerals)")
    putJsonArray("servings") { f.servings.take(6).forEach { s -> add("${s.label} = ${r(s.grams)} g") } }
}

const val MAX_QUERIES = 15

internal suspend fun searchFoods(ctx: ToolContext, queries: List<String>): JsonObject {
    if (queries.any { it.length > 200 }) throw ToolError("Food queries must be at most 200 characters")
    if (queries.size > MAX_QUERIES) throw ToolError("At most $MAX_QUERIES queries per call")
    // Fewer results per query when searching many foods at once, to keep the response small.
    val limit = if (queries.size == 1) 10 else 6
    val results = coroutineScope { queries.map { q -> async { q to ctx.foods.searchDetailed(ctx.data, q, limit, ctx.country) } }.awaitAll() }
    return buildJsonObject {
        putJsonArray("results") {
            results.forEach { (q, r) ->
                addJsonObject {
                    put("query", q)
                    if (!ctx.searched.add(normalizeQuery(q))) {
                        put("repeated", "You already searched this. Don't search it again: pick from these results or use a fallback.")
                    }
                    putJsonArray("foods") { r.foods.forEach { add(foodSummary(it)) } }
                    r.foods.forEach { f -> ctx.alternatives[f.id] = r.foods.filter { it.id != f.id }.take(3) }
                    if (r.missingWords.isNotEmpty()) {
                        putJsonArray("missing_words") { r.missingWords.forEach { add(it) } }
                        put("note", "No result contains ${r.missingWords.joinToString(", ") { "'$it'" }}. If that's a brand, it isn't in the " +
                            "database: use the closest plain food at the stated amount and tell the user it's a substitute, rather than searching again.")
                    }
                    if (r.foods.isEmpty()) put("note", "No matches. Try one simpler name, or use a fallback.")
                    if (r.warnings.isNotEmpty()) putJsonArray("provider_warnings") { r.warnings.forEach { add(it) } }
                    r.retryAfterSeconds?.let { put("retry_after_seconds", it) }
                    if (r.onlineFailed) put("online", "Some providers are unavailable or rate limited. Use these saved/local results; do not retry this turn.")
                }
            }
        }
    }
}

internal suspend fun getFood(ctx: ToolContext, id: String): JsonObject {
    val f = ctx.foods.get(ctx.data, id) ?: throw ToolError("Unknown food_id $id. Use search_foods first.")
    val recipe = ctx.data.food(id)?.takeIf { it.kind == "recipe" }
    val extra = if (recipe == null) emptyMap() else mapOf(
        "recipe_servings" to JsonPrimitive(recipe.recipeServings ?: 1.0),
        "ingredients" to buildJsonArray { decodeIngredients(recipe.ingredientsJson).forEach { i -> add("${i.name}: ${r(i.grams)} g (food_id ${i.foodId})") } },
    )
    return JsonObject(foodSummary(f) + extra + ("nutrients_per_100g" to buildJsonArray {
        Nutrients.all.forEach { def -> f.nutrients[def.id]?.let { add("${nutrientKey(def.id)}: ${r(it, 2)}") } }
    }) + ("servings" to buildJsonArray { f.allServings.forEach { add("${it.label} = ${r(it.grams)} g") } }))
}

fun sourceLabel(f: Food): String = when (f.source) {
    FoodSource.NUTRIME -> "NutriMe"
    FoodSource.OPEN_FOOD_FACTS -> "Open Food Facts"
    FoodSource.USDA_BRANDED -> "USDA branded"
    FoodSource.USDA -> "USDA"
    FoodSource.CUSTOM, FoodSource.RECIPE -> "Your food"
}

internal fun myFoods(ctx: ToolContext, query: String?, kind: String?): JsonObject {
    val words = query?.let { com.nutrime.app.model.FoodQuality.words(it) }.orEmpty()
    val kinds = when (kind?.lowercase()) {
        "custom" -> setOf("custom"); "recipe" -> setOf("recipe"); "saved" -> setOf("off", "usda_branded", "usda"); "meal" -> emptySet()
        else -> null
    }
    val meals = if (kinds == null || kind?.lowercase() == "meal") ctx.data.meals.filter { m ->
        words.isEmpty() || words.all { w -> com.nutrime.app.model.FoodQuality.words(m.name).any { it.startsWith(w) } }
    } else emptyList()
    val list = ctx.data.foods
        .filter { kinds == null || it.kind in kinds }
        .filter { f -> words.isEmpty() || com.nutrime.app.model.FoodQuality.matchedWords(words, f.toFood()) == words.size }
        .sortedByDescending { it.updatedAt }
    return buildJsonObject {
        putJsonArray("foods") {
            list.take(40).forEach { f ->
                addJsonObject {
                    put("food_id", f.id)
                    put("kind", when (f.kind) { "custom" -> "custom food"; "recipe" -> "recipe"; else -> "saved online food" })
                    put("name", f.name + (f.brand?.let { " ($it)" } ?: ""))
                    val n = decodeNutrients(f.nutrientsJson)
                    val serving = decodeServings(f.servingsJson).firstOrNull()
                    serving?.let { s -> put("serving", "${s.label} = ${r(s.grams)} g, ${r((n[N.ENERGY] ?: 0.0) * s.grams / 100, 0).toInt()} kcal") }
                    put("per_100g", macros(n))
                    if (f.kind == "recipe") {
                        put("servings", f.recipeServings ?: 1.0)
                        putJsonArray("ingredients") { decodeIngredients(f.ingredientsJson).forEach { i -> add("${i.name}: ${r(i.grams)} g (food_id ${i.foodId})") } }
                    }
                    val logged = ctx.data.diary.count { it.foodId == f.id }
                    if (logged > 0) put("times_logged", logged)
                }
            }
        }
        if (list.size > 40) put("note", "${list.size - 40} more; pass a query to narrow it down")
        if (meals.isNotEmpty()) {
            putJsonArray("saved_meals") {
                meals.take(20).forEach { m ->
                    addJsonObject {
                        put("name", m.name)
                        m.defaultMeal?.let { put("usual_meal", MEALS.getOrElse(it) { "snacks" }) }
                        putJsonArray("items") {
                            m.items.forEach { i ->
                                addJsonObject {
                                    put("food_id", i.foodId); put("name", i.name)
                                    if (i.servingLabel == "g") put("grams", r(i.quantity)) else { put("serving_label", i.servingLabel); put("quantity", i.quantity) }
                                }
                            }
                        }
                    }
                }
            }
            put("saved_meals_note", "To log a saved meal, call propose_log_foods with every item as given.")
        }
        if (list.isEmpty()) put("note", if (words.isEmpty()) "The user has no foods of this kind" else "No own food matches; try search_diary or search_foods")
    }
}

internal fun searchDiary(ctx: ToolContext, queries: List<String>, from: String?, to: String?): JsonObject {
    if (queries.size > MAX_QUERIES) throw ToolError("At most $MAX_QUERIES queries per call")
    val entries = ctx.data.diary.filter { (from == null || it.date >= from) && (to == null || it.date <= to) }
    return buildJsonObject {
        putJsonArray("results") {
            queries.forEach { q ->
                val words = com.nutrime.app.model.FoodQuality.words(q)
                val groups = entries.filter { e ->
                    words.isNotEmpty() && com.nutrime.app.model.FoodQuality.words("${e.foodName} ${e.brand.orEmpty()}")
                        .let { ws -> words.all { t -> ws.any { w -> w.startsWith(t) || (w.length >= 4 && t.startsWith(w)) } } }
                }.groupBy { it.foodId }
                addJsonObject {
                    put("query", q)
                    putJsonArray("foods") {
                        groups.entries.sortedByDescending { g -> g.value.size }.take(8).forEach { (id, list) ->
                            val last = list.maxBy { it.date + it.createdAt }
                            addJsonObject {
                                put("food_id", id)
                                put("name", last.foodName + (last.brand?.let { " ($it)" } ?: ""))
                                put("times_logged", list.size)
                                put("last_logged", last.date)
                                put("last_entry_id", last.id)
                                put("usual_amount", if (last.servingLabel == "g") "${r(last.quantity)} g" else "${r(last.quantity, 2)} × ${last.servingLabel} (${r(last.grams)} g)")
                                put("usual_kcal", r(last.nutrients.kcal(), 0))
                                if (id.startsWith("quick:")) put("note", "A quick-add estimate, not a food: can't be a recipe ingredient")
                            }
                        }
                    }
                    if (groups.isEmpty()) put("note", "Never logged. Use search_foods.")
                }
            }
        }
    }
}
