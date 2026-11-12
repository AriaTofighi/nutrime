package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.N
import com.nutrime.app.model.Nutrients
import com.nutrime.app.model.Serving
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ---------------------------------------------------------------------------------------------
// Function declarations (Gemini's OpenAPI-style schema subset)
// ---------------------------------------------------------------------------------------------

private fun schema(type: String, description: String? = null) = buildJsonObject {
    put("type", type)
    description?.let { put("description", it) }
}

private fun str(d: String? = null) = schema("STRING", d)
private fun num(d: String? = null) = schema("NUMBER", d)
private fun int(d: String? = null) = schema("INTEGER", d)
private fun enumStr(values: List<String>, d: String? = null) = buildJsonObject {
    put("type", "STRING"); put("format", "enum"); d?.let { put("description", it) }
    putJsonArray("enum") { values.forEach { add(it) } }
}
private fun arr(items: JsonObject, d: String? = null) = buildJsonObject {
    put("type", "ARRAY"); d?.let { put("description", it) }; put("items", items)
}
private fun obj(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList(), d: String? = null) = buildJsonObject {
    put("type", "OBJECT"); d?.let { put("description", it) }
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
}

private fun fn(name: String, description: String, params: JsonObject? = null) = buildJsonObject {
    put("name", name)
    put("description", description)
    params?.let { put("parameters", it) }
}

private val DATE = str("Date as YYYY-MM-DD. Defaults to today.")
private val MEAL = enumStr(MEALS, "Diary meal")

/** Nutrients a nutrition label lists, as create_food parameters (amount per serving). */
internal val LABEL_FIELDS = listOf(
    "calories" to N.ENERGY, "fat_g" to N.FAT, "saturated_fat_g" to N.SAT_FAT, "trans_fat_g" to N.TRANS_FAT,
    "cholesterol_mg" to N.CHOLESTEROL, "sodium_mg" to N.SODIUM, "carbs_g" to N.CARBS, "fiber_g" to N.FIBER,
    "sugars_g" to N.SUGARS, "added_sugars_g" to N.ADDED_SUGARS, "protein_g" to N.PROTEIN, "vitamin_d_mcg" to N.VIT_D,
    "calcium_mg" to N.CALCIUM, "iron_mg" to N.IRON, "potassium_mg" to N.POTASSIUM,
)

val TOOL_DECLARATIONS: JsonArray = buildJsonArray {
    add(fn("get_day", "The user's food diary for one day: every entry (with entry_id), totals, targets, nutrients still below target and limits exceeded.",
        obj("date" to DATE)))
    add(fn("get_nutrient_report", "Average daily intake of every tracked nutrient over a date range (only days with food logged), compared with the user's targets.",
        obj("start_date" to str("YYYY-MM-DD"), "end_date" to str("YYYY-MM-DD"), required = listOf("start_date", "end_date"))))
    add(fn("get_weight_history", "Logged body weights (kg) and body fat, oldest first.",
        obj("days" to int("How many days back to include. Default 90."))))
    add(fn("get_frequent_foods", "Foods the user logs most often recently, with the amount they usually have. Prefer these when they match.",
        obj("limit" to int("Default 15"))))
    add(fn("search_foods", "Search foods: the user's own foods and recipes, the USDA database, and branded products (USDA, Open Food Facts). " +
        "Pass every food you need in one call. For each query returns food_id, calories and macros per 100 g and serving sizes, " +
        "plus missing_words: query words no result contains (usually a brand that isn't in the database).",
        obj("queries" to arr(str(), "One query per food, e.g. ['greek yogurt plain', 'kirkland coconut water', 'basmati rice cooked']"), required = listOf("queries"))))
    add(fn("get_food", "All nutrients (per 100 g) and serving sizes of one food. For the user's recipes also the ingredients.",
        obj("food_id" to str(), required = listOf("food_id"))))
    add(fn("get_my_foods", "The user's own custom foods and recipes (recipes with their ingredients), and online foods they saved by logging them. " +
        "Use it to find a food they mean by name, or before changing or converting one.",
        obj("query" to str("Optional words the name must contain, e.g. 'smoothie'. Omit to list all."),
            "kind" to enumStr(listOf("custom", "recipe", "saved", "meal", "all"), "Default all. 'meal': saved meals (foods eaten together)"))))
    add(fn("search_diary", "Search the user's whole diary history (any date) for foods they logged, by name. Returns each matching food " +
        "once with food_id, how often and when it was logged, the usual amount and calories. Use it for 'what did I have…', 'the X I logged " +
        "before', or to find the exact foods to reuse (e.g. as recipe ingredients) before searching the database.",
        obj("queries" to arr(str(), "One query per food, e.g. ['strawberries', 'banana', 'coconut water', 'whey']"),
            "start_date" to str("Optional YYYY-MM-DD"), "end_date" to str("Optional YYYY-MM-DD"), required = listOf("queries"))))
    add(fn("propose_log_foods", "Propose diary entries for foods from search_foods/get_frequent_foods/get_day. The user sees a card and confirms before anything is saved.",
        obj("items" to arr(obj(
            "food_id" to str(),
            "meal" to MEAL,
            "date" to DATE,
            "serving_label" to str("One of the food's serving labels. Omit and give grams to log by weight."),
            "quantity" to num("Number of servings. Default 1."),
            "grams" to num("Amount in grams, instead of serving_label/quantity."),
            "expected_kcal" to num("Calories the user gave for this item (e.g. from a screenshot of another app), to check the entry against."),
            "substitute_for" to str("When this food stands in for something the user named that isn't in the database (usually a brand), the name they gave, e.g. 'Kirkland Signature Whole Strawberries'."),
            required = listOf("food_id", "meal"),
        )), "allow_kcal_mismatch" to schema("BOOLEAN", "Propose even if entries differ from expected_kcal (only after you've checked and the difference is expected)."),
            required = listOf("items"))))
    add(fn("propose_quick_add", "Propose calorie/macro estimates for dishes with no reasonable database food (e.g. a restaurant dish). " +
        "Not for a brand that isn't found: log the closest plain food with substitute_for instead.",
        obj("items" to arr(obj(
            "name" to str("The dish, without the amount"), "amount" to str("What the estimate is for, e.g. '1 bowl'"),
            "grams" to num("Weight in grams, if known"), "meal" to MEAL, "date" to DATE, "calories" to num("Total for the amount"),
            "protein_g" to num(), "carbs_g" to num(), "fat_g" to num(),
            required = listOf("name", "meal", "calories"),
        )), required = listOf("items"))))
    add(fn("propose_edit_entries", "Propose changing existing diary entries (entry_id from get_day): amount, meal, day, or the food itself " +
        "(food_id switches the entry to another food and keeps its grams unless you give an amount).",
        obj("edits" to arr(obj(
            "entry_id" to str(), "meal" to MEAL, "quantity" to num("New number of servings of the entry's current serving"),
            "grams" to num("New amount in grams"), "date" to str("Move the entry to this day (YYYY-MM-DD)"),
            "food_id" to str("Switch the entry to this food (from search_foods, search_diary or get_my_foods)"),
            required = listOf("entry_id"),
        )), required = listOf("edits"))))
    add(fn("propose_copy_entries", "Propose copying existing diary entries (entry_id from get_day) to another day and/or meal, e.g. " +
        "'log yesterday's breakfast again'. Amounts stay the same.",
        obj("entry_ids" to arr(str()), "date" to DATE, "meal" to schema("STRING", "Optional: put every copy in this meal (breakfast, lunch, dinner, snacks); default keeps each entry's meal"),
            required = listOf("entry_ids"))))
    add(fn("propose_delete_entries", "Propose removing diary entries (entry_id from get_day).",
        obj("entry_ids" to arr(str()), required = listOf("entry_ids"))))
    add(fn("propose_log_weight", "Propose logging a body weight.",
        obj("weight_kg" to num("Weight in kilograms (convert from lb: lb × 0.45359)"), "body_fat_pct" to num(), "date" to DATE, required = listOf("weight_kg"))))
    add(fn("propose_save_recipe", "Propose saving a recipe made of foods (food_id from search_foods, search_diary, get_my_foods or get_frequent_foods). " +
        "To turn one of the user's custom foods into a recipe, pass its food_id as replace_food_id: the recipe keeps that id, so diary entries " +
        "and favourites still point to it. To change an existing recipe, pass its food_id as replace_food_id too.",
        obj(
            "name" to str("Recipe name. Defaults to the replaced food's name."),
            "servings" to num("How many servings the whole recipe makes. Default 1."),
            "ingredients" to arr(obj(
                "food_id" to str(),
                "serving_label" to str("One of the food's serving labels. Omit and give grams to use a weight."),
                "quantity" to num("Number of servings. Default 1."),
                "grams" to num("Amount in grams, instead of serving_label/quantity."),
                required = listOf("food_id"),
            )),
            "replace_food_id" to str("A custom food or recipe of the user's that this recipe replaces (keeps its id)."),
            "update_diary_entries" to schema("BOOLEAN", "With replace_food_id: also give the replaced food's diary entries the recipe's " +
                "nutrients (their amounts stay). Use when the user wants past logs corrected."),
            required = listOf("ingredients"),
        )))
    add(fn("propose_update_food", "Propose changing one of the user's custom foods (not recipes or database foods): name, brand, serving, " +
        "or its nutrients from a label (per serving as printed; the ones you give replace the label values).",
        obj(
            "food_id" to str(), "name" to str(), "brand" to str(),
            "serving_label" to str(), "serving_grams" to num("Serving size in grams"),
            *LABEL_FIELDS.map { (k, _) -> k to num() }.toTypedArray(),
            required = listOf("food_id"),
        )))
    add(fn("propose_delete_foods", "Propose deleting custom foods or recipes of the user's (diary entries stay as they are).",
        obj("food_ids" to arr(str()), required = listOf("food_ids"))))
    add(fn("propose_update_targets", "Propose changing the user's targets: a custom daily calorie target (or back to the suggested one), " +
        "weekly weight goal, macro split, or activity level. The suggested calories follow from their body, activity and goal.",
        obj(
            "calorie_target" to int("Custom kcal/day (800–10000)"),
            "use_suggested_calories" to schema("BOOLEAN", "Drop the custom calorie target and use the suggested one"),
            "goal_kg_per_week" to num("Weekly change in kg: negative to lose (e.g. -0.5), 0 to maintain, positive to gain. From lb: × 0.4536"),
            "protein_pct" to int("Share of calories, with carbs_pct and fat_pct adding up to 100"),
            "carbs_pct" to int(), "fat_pct" to int(),
            "activity_level" to enumStr(com.nutrime.app.model.ActivityLevel.entries.map { it.name.lowercase() }),
            "goal_weight_kg" to num("Goal weight in kg (from lb: × 0.4536)"),
            "clear_goal_weight" to schema("BOOLEAN", "Remove the goal weight"),
            "protein_g_per_kg" to num("Protein target in grams per kg of body weight (0.8–3.0). Sets protein_mode per_kg."),
            "protein_mode" to enumStr(listOf("percent", "per_kg"), "Protein as a share of calories, or g per kg (carbs then take the rest)"),
            "burn_mode" to enumStr(listOf("formula", "adaptive", "manual"), "Which daily burn the targets use: formula, the one learned from their log (adaptive), or manual_burn_kcal"),
            "manual_burn_kcal" to int("Daily burn they give (1000–6000); sets burn_mode manual"),
        )))
    add(fn("get_burn_estimate", "The user's daily burn learned from their food log and weigh-ins, how sure it is, the formula value, their trend weight (smoothed), the current rate of change, and when they reach their goal weight.",
        obj()))
    add(fn("get_trends", "Averages over a date range: calories and macros per day, by week or month for long ranges, days logged, and the same averages for the period of equal length before.",
        obj("start_date" to str("YYYY-MM-DD"), "end_date" to str("YYYY-MM-DD"), required = listOf("start_date", "end_date"))))
    add(fn("get_top_sources", "The foods that gave the most of one nutrient over a date range, with their share.",
        obj("nutrient" to str("Nutrient name, e.g. 'sodium', 'protein', 'vitamin D', 'fiber'"),
            "start_date" to str("YYYY-MM-DD"), "end_date" to str("YYYY-MM-DD"), required = listOf("nutrient"))))
    add(fn("propose_create_food", "Propose saving a custom food from a nutrition label (amounts per serving as printed), optionally logging it too.",
        obj(
            "name" to str(), "brand" to str(),
            "serving_label" to str("e.g. '1 bar' or '2/3 cup'"),
            "serving_grams" to num("Serving size in grams (or ml for drinks)"),
            *LABEL_FIELDS.map { (k, _) -> k to num() }.toTypedArray(),
            "log_meal" to MEAL,
            "log_quantity" to num("Servings to log; omit to only save the food"),
            "log_date" to DATE,
            required = listOf("name", "serving_label", "serving_grams", "calories"),
        )))
}

/** Short, user-facing status for a running tool. */
fun toolLabel(name: String, args: JsonObject): String = when (name) {
    "get_day" -> "Reading your diary"
    "get_nutrient_report" -> "Reviewing your nutrients"
    "get_weight_history" -> "Checking your weight"
    "get_burn_estimate" -> "Checking your daily burn"
    "get_trends" -> "Looking at your trends"
    "get_top_sources" -> "Finding where it comes from"
    "get_frequent_foods" -> "Looking at foods you eat often"
    "search_foods" -> searchQueries(args).let { q ->
        when (q.size) {
            0 -> "Searching foods"
            1 -> "Searching foods for “${q[0]}”"
            else -> "Searching ${q.size} foods"
        }
    }
    "get_food" -> "Looking up a food"
    "get_my_foods" -> "Checking your foods"
    "search_diary" -> "Searching your diary"
    "propose_copy_entries" -> "Preparing copies"
    "propose_save_recipe" -> "Preparing recipe"
    "propose_update_food" -> "Preparing food changes"
    "propose_delete_foods" -> "Preparing removal"
    "propose_update_targets" -> "Preparing new targets"
    "propose_log_foods", "propose_quick_add" -> "Preparing entries"
    "propose_edit_entries" -> "Preparing changes"
    "propose_delete_entries" -> "Preparing removal"
    "propose_log_weight" -> "Preparing weigh-in"
    "propose_create_food" -> "Preparing new food"
    else -> name
}
