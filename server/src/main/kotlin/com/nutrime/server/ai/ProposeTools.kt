package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.FoodSource
import com.nutrime.app.model.N
import com.nutrime.app.model.Serving
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.roundToInt

// Assistant tools that build card actions. Nothing here saves data: the user confirms the card first.

/** Items whose calories differ from the user's expected_kcal by more than [KCAL_REJECT] (and 25 kcal). Smaller differences are shown on the card. */
internal fun kcalMismatches(actions: List<Action>): List<String> = actions.mapNotNull { action ->
    val a = action as? Action.LogFood ?: return@mapNotNull null
    val expected = a.expectedKcal?.takeIf { it > 0 } ?: return@mapNotNull null
    val kcal = (a.nutrients[N.ENERGY] ?: 0.0) * a.servingGrams * a.quantity / 100.0
    val diff = kcal - expected
    if (kotlin.math.abs(diff) <= 25 || kotlin.math.abs(diff) / expected <= KCAL_REJECT) return@mapNotNull null
    "${a.foodName}: ${r(kcal, 0).toInt()} kcal, expected ${r(expected, 0).toInt()} (${if (diff > 0) "+" else ""}${(diff / expected * 100).toInt()}%)"
}

/** Above this difference from the user's calories, the model must check the item before it reaches the card. */
const val KCAL_REJECT = 0.25

private fun resolveServing(food: Food, label: String?, quantity: Double?, grams: Double?): Pair<Serving, Double> {
    if (grams != null) {
        if (grams <= 0 || grams > 5000) throw ToolError("grams must be between 0 and 5000")
        return Serving("g", 1.0) to grams
    }
    val servings = food.allServings
    val serving = if (label.isNullOrBlank()) food.defaultServing else {
        val l = label.trim().lowercase()
        servings.firstOrNull { it.label.lowercase() == l }
            ?: servings.firstOrNull { it.label.lowercase().startsWith(l) || l.startsWith(it.label.lowercase()) }
            ?: throw ToolError("'${food.name}' has no serving '$label'. Servings: ${servings.joinToString { it.label }}. Or pass grams.")
    }
    val q = quantity ?: if (label.isNullOrBlank()) food.defaultQuantity else 1.0
    if (q <= 0 || serving.grams * q > 5000) throw ToolError("quantity out of range")
    return serving to q
}

internal suspend fun proposeLogFoods(ctx: ToolContext, items: List<JsonObject>): List<Action> = items.map { it ->
    val id = it.s("food_id") ?: throw ToolError("food_id is required")
    val food = ctx.foods.get(ctx.data, id) ?: throw ToolError("Unknown food_id $id. Use search_foods first.")
    val (serving, q) = resolveServing(food, it.s("serving_label"), it.n("quantity"), it.n("grams"))
    logFoodAction(ctx.foods, ctx.data, food, date(ctx, it.s("date")), meal(it.s("meal")), serving, q).copy(
        expectedKcal = it.n("expected_kcal")?.takeIf { k -> k > 0 },
        substituteFor = it.s("substitute_for")?.trim()?.takeIf { s -> s.isNotEmpty() },
        alternatives = ctx.alternatives[food.id].orEmpty().map { a -> Alternative(a.id, a.name, a.brand, a.nutrients[N.ENERGY] ?: 0.0) },
    )
}

/** A diary entry for [food]. Online foods are saved to the user's foods too (like the app does), so they work offline. */
suspend fun logFoodAction(foods: FoodSearch, data: UserData, food: Food, date: String, meal: Int, serving: Serving, quantity: Double): Action.LogFood {
    val save = if (data.food(food.id) == null && !foods.isBundled(food.id)) FoodRecord(
        id = food.id,
        kind = when (food.source) { FoodSource.OPEN_FOOD_FACTS -> "off"; FoodSource.USDA_BRANDED -> "usda_branded"; else -> "usda" },
        name = food.name, brand = food.brand, barcode = food.barcode,
        nutrientsJson = encodeNutrients(food.nutrients), servingsJson = encodeServings(food.servings), updatedAt = 0,
    ) else null
    return Action.LogFood(date, meal, food.id, food.name, food.brand, serving.label, serving.grams, quantity, food.nutrients, save, source = sourceLabel(food))
}

/** Apply one change the user made on the card. Returns null when the row is removed. */
suspend fun editAction(foods: FoodSearch, data: UserData, a: Action, e: ProposalItemEdit): Action? {
    if (e.remove) return null
    e.meal?.let { if (it !in MEALS.indices) throw ToolError("Unknown meal") }
    e.grams?.let { if (it <= 0 || it > 5000) throw ToolError("Amount must be between 0 and 5000 g") }
    e.quantity?.let { if (it <= 0 || it > 100) throw ToolError("Amount is out of range") }
    return when (a) {
        is Action.LogFood -> {
            var out: Action.LogFood = a
            e.foodId?.takeIf { it != a.foodId }?.let { newId ->
                val alt = a.alternatives.firstOrNull { it.foodId == newId } ?: throw ToolError("That food isn't one of the other matches")
                val food = foods.get(data, newId) ?: throw ToolError("Couldn't load ${alt.name}")
                val grams = a.servingGrams * a.quantity
                val same = food.allServings.firstOrNull { it.label.equals(a.servingLabel, ignoreCase = true) }
                val (serving, q) = if (a.servingLabel != "g" && same != null) same to a.quantity else Serving("g", 1.0) to grams
                val old = Alternative(a.foodId, a.foodName, a.brand, a.nutrients[N.ENERGY] ?: 0.0)
                out = logFoodAction(foods, data, food, a.date, a.meal, serving, q).copy(
                    expectedKcal = a.expectedKcal, substituteFor = a.substituteFor,
                    alternatives = listOf(old) + a.alternatives.filter { it.foodId != newId },
                )
            }
            when {
                e.grams != null -> out = out.copy(servingLabel = "g", servingGrams = 1.0, quantity = e.grams)
                e.quantity != null -> out = out.copy(quantity = e.quantity)
            }
            if (out.servingGrams * out.quantity > 5000) throw ToolError("Amount is out of range")
            e.meal?.let { out = out.copy(meal = it) }
            out
        }
        is Action.QuickAdd -> {
            var out: Action.QuickAdd = a
            val newGrams = e.grams ?: e.quantity
            if (newGrams != null && a.grams != null && a.grams > 0) {
                val f = newGrams / a.grams
                out = out.copy(grams = newGrams, nutrients = a.nutrients.mapValues { it.value * f })
            }
            e.meal?.let { out = out.copy(meal = it) }
            out
        }
        else -> a
    }
}

internal fun quickAdd(ctx: ToolContext, o: JsonObject): Action {
    val kcal = o.n("calories") ?: throw ToolError("calories is required")
    if (kcal < 0 || kcal > 10000) throw ToolError("calories out of range")
    val n = buildMap {
        put(N.ENERGY, kcal)
        o.n("protein_g")?.let { put(N.PROTEIN, it) }
        o.n("carbs_g")?.let { put(N.CARBS, it) }
        o.n("fat_g")?.let { put(N.FAT, it) }
    }
    val grams = o.n("grams")?.takeIf { it > 0 && it <= 5000 }
    return Action.QuickAdd(date(ctx, o.s("date")), meal(o.s("meal")), o.s("name")?.trim()?.takeIf { it.isNotBlank() } ?: "Quick add", n,
        amount = o.s("amount")?.trim()?.takeIf { it.isNotEmpty() } ?: grams?.let { "${r(it, 0).toInt()} g" }, grams = grams)
}

internal suspend fun editEntry(ctx: ToolContext, o: JsonObject): Action {
    val id = o.s("entry_id") ?: throw ToolError("entry_id is required")
    val e = ctx.data.entry(id) ?: throw ToolError("No diary entry with id $id")
    val newFood = o.s("food_id")?.takeIf { it != e.foodId }?.let { fid ->
        ctx.foods.get(ctx.data, fid) ?: throw ToolError("Unknown food_id $fid. Use search_foods first.")
    }
    val grams = o.n("grams")
    val (label, sg, q) = when {
        grams != null -> Triple("g", 1.0, grams)
        // A new food keeps the amount by weight unless it has the same serving.
        newFood != null -> newFood.allServings.firstOrNull { it.label.equals(e.servingLabel, ignoreCase = true) && e.servingLabel != "serving" }
            ?.let { Triple(it.label, it.grams, o.n("quantity") ?: e.quantity) }
            ?: Triple("g", 1.0, e.grams * (o.n("quantity")?.let { it / e.quantity } ?: 1.0))
        else -> Triple(e.servingLabel, e.servingGrams, o.n("quantity") ?: e.quantity)
    }
    if (q <= 0 || sg * q > 5000) throw ToolError("amount out of range")
    val m = o.s("meal")?.let { meal(it) } ?: e.meal
    val per100 = newFood?.nutrients ?: decodeNutrients(e.nutrientsJson)
    val swap = newFood?.let { f ->
        val save = logFoodAction(ctx.foods, ctx.data, f, e.date, m, Serving("g", 1.0), 1.0).food
        SwapFood(f.id, f.name, f.brand, f.nutrients, save)
    }
    val day = o.s("date")?.let { date(ctx, it) }?.takeIf { it != e.date }
    return Action.EditEntry(id, e.foodName, m, label, sg, q, e.nutrients.kcal(), (per100[N.ENERGY] ?: 0.0) * sg * q / 100.0, swap, day)
}

internal fun copyEntries(ctx: ToolContext, o: JsonObject): List<Action> {
    val day = date(ctx, o.s("date"))
    val toMeal = o.s("meal")?.takeIf { it.isNotBlank() }?.let { meal(it) }
    return o.strings("entry_ids").map { id ->
        val e = ctx.data.entry(id) ?: throw ToolError("No diary entry with id $id")
        Action.LogFood(day, toMeal ?: e.meal, e.foodId, e.foodName, e.brand, e.servingLabel, e.servingGrams, e.quantity,
            decodeNutrients(e.nutrientsJson), source = if (e.foodId.startsWith("quick:")) "Estimate" else "Your diary")
    }
}

internal suspend fun saveRecipe(ctx: ToolContext, o: JsonObject): Action.SaveRecipe {
    val replaceId = o.s("replace_food_id")?.trim()?.takeIf { it.isNotEmpty() }
    val replaced = replaceId?.let { id ->
        ctx.data.food(id)?.takeIf { it.kind == "custom" || it.kind == "recipe" }
            ?: throw ToolError("$id isn't one of the user's custom foods or recipes (see get_my_foods)")
    }
    val name = o.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: replaced?.name ?: throw ToolError("name is required")
    val servings = o.n("servings") ?: replaced?.recipeServings ?: 1.0
    if (servings <= 0 || servings > 100) throw ToolError("servings must be between 0 and 100")
    val ingredients = o.list("ingredients").map { i ->
        val id = i.s("food_id") ?: throw ToolError("food_id is required for each ingredient")
        if (id.startsWith("quick:")) throw ToolError("$id is a quick-add estimate, not a food; search for the food instead")
        if (id == replaceId) throw ToolError("A recipe can't contain itself")
        val food = ctx.foods.get(ctx.data, id) ?: throw ToolError("Unknown food_id $id. Use search_foods first.")
        val (serving, q) = resolveServing(food, i.s("serving_label"), i.n("quantity"), i.n("grams"))
        IngredientRecord(food.id, com.nutrime.server.food.displayFoodName(food.name, food.brand), serving.label, serving.grams, q, food.nutrients)
    }
    if (ingredients.size > 60) throw ToolError("At most 60 ingredients")
    val updateEntries = if (replaceId != null && (o["update_diary_entries"] as? JsonPrimitive)?.content == "true")
        ctx.data.diary.filter { it.foodId == replaceId }.map { it.id } else emptyList()
    return Action.SaveRecipe(replaceId ?: "custom:${UUID.randomUUID()}", name, servings, ingredients, replaced?.name, updateEntries)
}

internal fun updateFood(ctx: ToolContext, o: JsonObject): Action.UpdateFood {
    val id = o.s("food_id") ?: throw ToolError("food_id is required")
    val f = ctx.data.food(id) ?: throw ToolError("No food of the user's with id $id (see get_my_foods)")
    if (f.kind != "custom") throw ToolError("Only custom foods can be edited this way" + if (f.kind == "recipe") "; use propose_save_recipe with replace_food_id" else "")
    val oldServing = decodeServings(f.servingsJson).firstOrNull()
    val grams = o.n("serving_grams")
    if (grams != null && (grams <= 0 || grams > 5000)) throw ToolError("serving_grams out of range")
    val label = o.s("serving_label")?.trim()?.takeIf { it.isNotEmpty() }
    val serving = if (grams != null || label != null) Serving(label ?: oldServing?.label ?: "serving", grams ?: oldServing?.grams ?: 100.0) else null
    val perServing = LABEL_FIELDS.mapNotNull { (k, nid) -> o.n(k)?.let { nid to it } }.toMap()
    val nutrients = if (perServing.isEmpty()) null else {
        val g = (serving ?: oldServing)?.grams ?: 100.0
        decodeNutrients(f.nutrientsJson) + per100(perServing, g)
    }
    val name = o.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: f.name
    if (name == f.name && serving == null && nutrients == null && o.s("brand") == null) throw ToolError("Nothing to change")
    return Action.UpdateFood(id, name, f.name, o.s("brand")?.trim()?.takeIf { it.isNotEmpty() }, serving, nutrients)
}

internal fun updateTargets(ctx: ToolContext, o: JsonObject): Action.UpdateProfile {
    val p = ctx.data.profile
    val summary = mutableListOf<String>()
    val kcal = o.n("calorie_target")?.toInt()
    if (kcal != null && kcal !in 800..10000) throw ToolError("calorie_target must be between 800 and 10000")
    val suggested = (o["use_suggested_calories"] as? JsonPrimitive)?.content == "true"
    val goal = o.n("goal_kg_per_week")
    if (goal != null && goal !in -com.nutrime.app.model.Goal.MAX_LOSS..com.nutrime.app.model.Goal.MAX_GAIN) throw ToolError("goal_kg_per_week must be between -1 and 0.5")
    val goalWeight = o.n("goal_weight_kg")?.also { if (it !in 20.0..400.0) throw ToolError("goal_weight_kg out of range") }
    val clearGoalWeight = (o["clear_goal_weight"] as? JsonPrimitive)?.content == "true"
    val gPerKg = o.n("protein_g_per_kg")?.also { if (it !in 0.5..3.5) throw ToolError("protein_g_per_kg must be between 0.5 and 3.5") }
    val proteinMode = (o.s("protein_mode")?.uppercase() ?: if (gPerKg != null) "PER_KG" else null)
    val manualBurn = o.n("manual_burn_kcal")?.toInt()?.also { if (it !in 1000..6000) throw ToolError("manual_burn_kcal must be between 1000 and 6000") }
    val burnMode = o.s("burn_mode")?.uppercase() ?: if (manualBurn != null) "MANUAL" else null
    if (burnMode != null && burnMode !in setOf("FORMULA", "ADAPTIVE", "MANUAL")) throw ToolError("Unknown burn_mode")
    val macros = listOf(o.n("protein_pct"), o.n("carbs_pct"), o.n("fat_pct"))
    val split = if (macros.any { it != null }) {
        if (macros.any { it == null }) throw ToolError("Give protein_pct, carbs_pct and fat_pct together")
        val (pr, cb, ft) = macros.map { it!!.toInt() }
        if (pr + cb + ft != 100 || listOf(pr, cb, ft).any { it < 0 }) throw ToolError("protein_pct + carbs_pct + fat_pct must be 100")
        Triple(pr, cb, ft)
    } else null
    val activity = o.s("activity_level")?.uppercase()?.let { a ->
        com.nutrime.app.model.ActivityLevel.entries.firstOrNull { it.name == a } ?: throw ToolError("Unknown activity_level $a")
    }
    // The new profile, to show the resulting calorie target on the card.
    var np = p
    if (suggested) np = np.copy(calorieOverride = null)
    kcal?.let { np = np.copy(calorieOverride = it) }
    goal?.let { np = np.copy(goalKgPerWeek = it) }
    split?.let { np = np.copy(proteinPct = it.first, carbsPct = it.second, fatPct = it.third) }
    activity?.let { np = np.copy(activity = it) }
    val extras = Action.UpdateProfile(goalWeightKg = goalWeight, clearGoalWeight = clearGoalWeight, proteinGPerKg = gPerKg,
        proteinMode = proteinMode, burnMode = burnMode, manualBurnKcal = manualBurn)
    if (burnMode == "ADAPTIVE" && p.adaptiveBurnKcal == null) {
        ctx.data.burn?.takeIf { it.confidence != com.nutrime.app.model.Confidence.LEARNING }?.let { e ->
            np = np.copy(adaptiveBurnKcal = e.burnKcal)
        }
    }
    np = applyProfileExtras(np, extras)
    fun kg(v: Double) = if (p.imperial) "${r(v / 0.45359237)} lb" else "${r(v, 2)} kg"
    fun goalText(v: Double) = when { v < 0 -> "lose ${kg(-v)}/week"; v > 0 -> "gain ${kg(v)}/week"; else -> "maintain" }
    if (np.calorieTarget != p.calorieTarget) summary += "Calories ${"%,d".format(p.calorieTarget)} → ${"%,d".format(np.calorieTarget)} kcal" +
        if (np.calorieOverride == null) " (suggested)" else ""
    else if (suggested || kcal != null) summary += "Calories ${"%,d".format(np.calorieTarget)} kcal" + if (np.calorieOverride == null) " (suggested)" else ""
    if (goal != null && goal != p.goalKgPerWeek) summary += "Goal: ${goalText(p.goalKgPerWeek)} → ${goalText(goal)}"
    split?.let { summary += "Macros ${p.proteinPct}/${p.carbsPct}/${p.fatPct} → ${it.first}/${it.second}/${it.third}% (protein/carbs/fat)" }
    activity?.takeIf { it != p.activity }?.let { summary += "Activity: ${p.activity.label} → ${it.label}" }
    if (clearGoalWeight && p.goalWeightKg != null) summary += "Remove goal weight"
    goalWeight?.let { summary += "Goal weight ${kg(it)}" }
    if (proteinMode == "PER_KG") summary += "Protein ${r(np.proteinGPerKg, 1)} g/kg (${com.nutrime.app.model.Dri.macroGrams(np).protein.roundToInt()} g)"
    else if (proteinMode == "PERCENT" && p.proteinMode != com.nutrime.app.model.ProteinMode.PERCENT) summary += "Protein as a share of calories"
    burnMode?.let { m ->
        summary += "Daily burn: " + when (m) {
            "MANUAL" -> "${"%,d".format(np.tdee.roundToInt())} kcal (your value)"
            "ADAPTIVE" -> "learned from your log (${"%,d".format(np.tdee.roundToInt())} kcal)"
            else -> "formula (${"%,d".format(np.formulaTdee.roundToInt())} kcal)"
        }
    }
    if (np.calorieTarget != p.calorieTarget && summary.none { it.startsWith("Calories") }) summary += "Calories ${"%,d".format(p.calorieTarget)} → ${"%,d".format(np.calorieTarget)} kcal"
    if (summary.isEmpty()) throw ToolError("Nothing to change")
    return Action.UpdateProfile(kcal, suggested, goal, split?.first, split?.second, split?.third, activity?.name,
        goalWeight, clearGoalWeight, gPerKg, proteinMode, burnMode, manualBurn, summary)
}

internal fun createFood(ctx: ToolContext, o: JsonObject): List<Action> {
    val name = o.s("name")?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolError("name is required")
    val grams = o.n("serving_grams") ?: throw ToolError("serving_grams is required")
    if (grams <= 0 || grams > 5000) throw ToolError("serving_grams out of range")
    val perServing = LABEL_FIELDS.mapNotNull { (k, id) -> o.n(k)?.let { id to it } }.toMap()
    if (perServing[N.ENERGY] == null) throw ToolError("calories is required")
    val serving = Serving(o.s("serving_label")?.takeIf { it.isNotBlank() } ?: "serving", grams)
    val create = Action.CreateFood("custom:${UUID.randomUUID()}", name, o.s("brand")?.takeIf { it.isNotBlank() }, serving, per100(perServing, grams))
    val logQ = o.n("log_quantity")
    if (logQ == null || logQ <= 0) return listOf(create)
    return listOf(create, Action.LogFood(
        date(ctx, o.s("log_date")), meal(o.s("log_meal") ?: "snacks"), create.foodId, name, create.brand,
        serving.label, serving.grams, logQ, create.nutrients,
    ))
}
