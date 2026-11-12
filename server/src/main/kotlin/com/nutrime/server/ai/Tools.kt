package com.nutrime.server.ai

import com.nutrime.app.model.Food
import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Nutrients
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.math.round

// ---------------------------------------------------------------------------------------------
// Execution
// ---------------------------------------------------------------------------------------------

class ToolContext(
    val data: UserData,
    val foods: FoodSearch,
    val proposals: ProposalStore,
    val conversationId: String,
    val today: String,
    /** The user's country as Open Food Facts names it ("canada"), from the app's region. */
    val country: String? = null,
) {
    /** This message's card: every propose_* call in one turn adds to it. */
    @Volatile var draftId: String? = null
    val draftLock = kotlinx.coroutines.sync.Mutex()
    /** Other results of the search that found each food, for swapping on the card. */
    val alternatives: MutableMap<String, List<Food>> = java.util.concurrent.ConcurrentHashMap()
    /** Normalized search queries already run in this conversation, to catch the model repeating itself. */
    val searched: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
}

fun normalizeQuery(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }.sorted().joinToString(" ")

/** search_foods queries: `queries`, or the older single `query`. */
fun searchQueries(args: JsonObject): List<String> {
    val list = (args["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.orEmpty()
    return (list + listOfNotNull(args.s("query")?.trim())).filter { it.isNotEmpty() }.distinct()
}

class ToolError(message: String) : Exception(message)

/** Runs one function call. Returns the response object and, for propose_* tools, the new proposal id. */
suspend fun runTool(ctx: ToolContext, name: String, args: JsonObject): Pair<JsonObject, String?> = try {
    when (name) {
        "get_day" -> getDay(ctx, date(ctx, args.s("date"))) to null
        "get_nutrient_report" -> nutrientReport(ctx, date(ctx, args.s("start_date")), date(ctx, args.s("end_date"))) to null
        "get_weight_history" -> weightHistory(ctx, args.n("days")?.toInt() ?: 90) to null
        "get_burn_estimate" -> burnEstimate(ctx) to null
        "get_trends" -> trends(ctx, date(ctx, args.s("start_date")), date(ctx, args.s("end_date"))) to null
        "get_top_sources" -> topSources(ctx, args.s("nutrient") ?: throw ToolError("nutrient is required"),
            args.s("start_date")?.let { date(ctx, it) } ?: LocalDate.parse(ctx.today).minusDays(29).toString(), date(ctx, args.s("end_date"))) to null
        "get_frequent_foods" -> frequentFoods(ctx, args.n("limit")?.toInt() ?: 15) to null
        "search_foods" -> searchFoods(ctx, searchQueries(args).ifEmpty { throw ToolError("queries is required") }) to null
        "get_food" -> getFood(ctx, args.s("food_id") ?: throw ToolError("food_id is required")) to null
        "propose_log_foods" -> {
            val items = args.list("items")
            val actions = proposeLogFoods(ctx, items)
            val mismatches = kcalMismatches(actions)
            if (mismatches.isNotEmpty() && (args["allow_kcal_mismatch"] as? JsonPrimitive)?.content != "true") {
                buildJsonObject {
                    put("status", "not_proposed")
                    putJsonArray("kcal_mismatches") { mismatches.forEach { add(it) } }
                    put("note", "Nothing was added yet. First check each item's serving and amount (e.g. '2.5 × 1/2 cup', cooked vs raw). " +
                        "Keep the food whose NAME matches what the user wrote; never switch to another food only because its calories " +
                        "are closer. If the name and amount are right, call propose_log_foods again with ALL items and " +
                        "allow_kcal_mismatch: true; the card shows the user both numbers.")
                } to null
            } else propose(ctx, actions)
        }
        "propose_quick_add" -> propose(ctx, args.list("items").map { quickAdd(ctx, it) })
        "propose_edit_entries" -> propose(ctx, args.list("edits").map { editEntry(ctx, it) })
        "propose_delete_entries" -> propose(ctx, args.strings("entry_ids").map { id ->
            val e = ctx.data.entry(id) ?: throw ToolError("No diary entry with id $id")
            Action.DeleteEntry(id, e.foodName, e.nutrients.kcal())
        })
        "propose_log_weight" -> {
            val kg = args.n("weight_kg") ?: throw ToolError("weight_kg is required")
            if (kg !in 20.0..400.0) throw ToolError("weight_kg looks wrong: $kg")
            propose(ctx, listOf(Action.LogWeight(date(ctx, args.s("date")), kg, args.n("body_fat_pct"), ctx.data.profile.imperial)))
        }
        "propose_create_food" -> propose(ctx, createFood(ctx, args))
        "get_my_foods" -> myFoods(ctx, args.s("query"), args.s("kind")) to null
        "search_diary" -> searchDiary(ctx, searchQueries(args).ifEmpty { throw ToolError("queries is required") },
            args.s("start_date")?.let { date(ctx, it) }, args.s("end_date")?.let { date(ctx, it) }) to null
        "propose_copy_entries" -> propose(ctx, copyEntries(ctx, args))
        "propose_save_recipe" -> propose(ctx, listOf(saveRecipe(ctx, args)))
        "propose_update_food" -> propose(ctx, listOf(updateFood(ctx, args)))
        "propose_delete_foods" -> propose(ctx, args.strings("food_ids").map { id ->
            val f = ctx.data.food(id)?.takeIf { it.kind == "custom" || it.kind == "recipe" } ?: throw ToolError("No custom food or recipe with id $id")
            Action.DeleteFood(id, f.name)
        })
        "propose_update_targets" -> propose(ctx, listOf(updateTargets(ctx, args)))
        else -> buildJsonObject { put("error", "Unknown function $name") } to null
    }
} catch (e: ToolError) {
    buildJsonObject { put("error", e.message) } to null
}

/**
 * Add [actions] to this message's card. One message gets one card, shown when the reply ends;
 * an item for the same food, meal and day (or the same entry) replaces the earlier one.
 */
private suspend fun propose(ctx: ToolContext, actions: List<Action>): Pair<JsonObject, String> {
    if (actions.isEmpty()) throw ToolError("Nothing to propose")
    return ctx.draftLock.withLock {
        val uid = ctx.data.userId
        val existing = ctx.draftId?.let { ctx.proposals.pendingActions(uid, it) }
        val merged = if (existing == null) actions else mergeActions(existing, actions)
        val id = if (existing != null && ctx.proposals.replace(uid, ctx.draftId!!, merged)) ctx.draftId!!
            else ctx.proposals.create(uid, ctx.conversationId, merged).also { ctx.draftId = it }
        val all = if (id == ctx.draftId && existing != null) merged else actions
        buildJsonObject {
            put("proposal_id", id)
            put("status", "pending")
            put("note", "Added to this message's card, which the user sees when you finish replying, with Confirm/Dismiss " +
                "and can edit per item. Nothing is saved until they confirm, so don't say it's logged. More propose_* calls in " +
                "this message add to the same card.")
            putJsonArray("card_items") { all.forEach { a -> add(a.item(ctx.today).let { "${it.title}: ${it.detail}" }) } }
        } to id
    }
}

private fun actionKey(a: Action): String? = when (a) {
    is Action.LogFood -> "log:${a.date}:${a.meal}:${a.foodId}"
    is Action.QuickAdd -> "quick:${a.date}:${a.name.lowercase()}"
    is Action.EditEntry -> "edit:${a.entryId}"
    is Action.DeleteEntry -> "delete:${a.entryId}"
    is Action.LogWeight -> "weight:${a.date}"
    is Action.CreateFood -> null
    is Action.SaveRecipe -> "recipe:${a.foodId}"
    is Action.UpdateFood -> "food:${a.foodId}"
    is Action.DeleteFood -> "delete-food:${a.foodId}"
    is Action.UpdateProfile -> "profile"
}

/** The names an item logs, so a food and an estimate for the same thing replace each other. */
private fun actionNames(a: Action): Set<String> = when (a) {
    is Action.LogFood -> setOfNotNull(a.substituteFor?.lowercase())
    is Action.QuickAdd -> setOf(a.name.lowercase())
    else -> emptySet()
}

fun mergeActions(existing: List<Action>, added: List<Action>): List<Action> {
    val keys = added.mapNotNull { actionKey(it) }.toSet()
    val names = added.flatMap { actionNames(it) }.toSet()
    return existing.filter { a -> actionKey(a) !in keys && actionNames(a).none { it in names } } + added
}

internal fun date(ctx: ToolContext, s: String?): String {
    if (s.isNullOrBlank()) return ctx.today
    return runCatching { LocalDate.parse(s.trim()).toString() }.getOrElse { throw ToolError("Invalid date '$s', use YYYY-MM-DD") }
}

internal fun meal(s: String?): Int = MEALS.indexOf(s?.lowercase()?.trim()).takeIf { it >= 0 }
    ?: if (s == "snack") 3 else throw ToolError("meal must be one of $MEALS")

internal fun r(d: Double, places: Int = 1): Double {
    val f = Math.pow(10.0, places.toDouble())
    return round(d * f) / f
}

internal fun macros(n: NutrientMap): JsonObject = buildJsonObject {
    put("kcal", r(n.kcal(), 0))
    put("protein_g", r(n[N.PROTEIN] ?: 0.0))
    put("carbs_g", r(n[N.CARBS] ?: 0.0))
    put("fat_g", r(n[N.FAT] ?: 0.0))
}

internal fun nutrientKey(id: Int): String {
    val def = Nutrients.byId[id] ?: return id.toString()
    return "${def.name} (${def.unit})"
}

// Loose argument access: models sometimes send numbers as strings.
fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { (this[k] as JsonPrimitive).isString || it != "null" }
fun JsonObject.n(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
internal fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }
    ?.takeIf { it.isNotEmpty() } ?: throw ToolError("$k must be a non-empty list")
internal fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
    ?.takeIf { it.isNotEmpty() } ?: throw ToolError("$k must be a non-empty list")
