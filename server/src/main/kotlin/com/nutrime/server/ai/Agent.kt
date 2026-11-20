package com.nutrime.server.ai

import com.nutrime.app.model.BurnMode
import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Sex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import kotlin.math.roundToInt

/** Something to show the user while the assistant works. */
sealed interface AgentEvent {
    data class Text(val delta: String) : AgentEvent
    data class Tool(val name: String, val label: String) : AgentEvent
    data class Proposal(val view: ProposalView) : AgentEvent
}

/** Tool calls the model may make in one turn before it has to wrap up. */
const val MAX_TOOL_CALLS = 30
/** Model calls in one turn before it has to wrap up, however few tools each one calls. */
const val MAX_ROUNDS = 10
/** Extra model calls after the limit, with only the propose_* tools, to turn what it found into a proposal and a reply. */
const val WRAP_UP_ROUNDS = 3

private const val WRAP_UP_NOTE = "Step limit for this message reached: searching, web lookups and other lookups are now off. With what " +
    "you already have, call the propose_* functions now (best matches; propose_create_food for web results you found; generic " +
    "substitutes only for plain foods under a brand; propose_quick_add estimates for the rest), then tell the user in a short reply " +
    "which entries are substitutes, web results or estimates. If this wasn't a logging request, just answer."

private val argsJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
private val JsonObject.type: String? get() = str("type")
private fun JsonObject.steps(): List<JsonObject> = (this["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
private fun stepsContent(steps: List<JsonObject>) = buildJsonObject { put("steps", JsonArray(steps)) }
private fun textStep(text: String) = buildJsonObject {
    put("type", "model_output"); putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
}

/**
 * The tool loop over the Gemini Interactions API, stateless (`store: false`): send the conversation as steps,
 * stream the model's steps, run the functions it calls, send back the results, and repeat until it answers
 * without calling anything. The model's steps go back exactly as received (thought steps carry the signatures
 * Gemini needs). Every new message is handed to [persist] as soon as it's complete.
 *
 * Each turn has a budget ([MAX_TOOL_CALLS], [MAX_ROUNDS]). When it runs out the model isn't cut off:
 * it gets [WRAP_UP_ROUNDS] more calls in which only the propose_* tools run, so it still delivers a proposal.
 */
class Agent(
    private val llm: LlmClient,
    private val ctx: ToolContext,
    /** The fixed instructions ([SYSTEM_PROMPT]); the same for every request, so Gemini can cache them. */
    private val system: String,
    /** The Interactions `generation_config`, or null for the model's defaults. */
    private val generationConfig: JsonObject?,
    private val persist: (kind: String, content: JsonObject) -> Unit,
    private val emit: suspend (AgentEvent) -> Unit,
    /** The app's context for this message (date, profile, today's totals), put before the user's last message. */
    private val context: String? = null,
) {
    suspend fun run(history: List<JsonObject>) {
        try {
            loop(history)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { showCard() }
            throw e
        }
        showCard()
    }

    /** The message's one card, shown once the reply is complete (see propose in Tools.kt). */
    private suspend fun showCard() {
        val id = ctx.draftId ?: return
        ctx.proposals.views(ctx.data.userId, listOf(id), ctx.today)[id]?.let { emit(AgentEvent.Proposal(it)) }
    }

    private suspend fun loop(history: List<JsonObject>) {
        val input = withContext(history).toMutableList()
        // Searches from earlier messages (e.g. before "continue") count as already done.
        history.filter { it.type == "function_call" && it.str("name") == "search_foods" }.forEach { call ->
            searchQueries(call["arguments"] as? JsonObject ?: JsonObject(emptyMap())).forEach { ctx.searched += normalizeQuery(it) }
        }
        var toolCalls = 0
        var rounds = 0
        var wrapUpRounds = -1 // -1 until the budget runs out
        while (wrapUpRounds < WRAP_UP_ROUNDS) {
            val wrappingUp = wrapUpRounds >= 0
            val request = buildJsonObject {
                put("system_instruction", system)
                // The same tools every round (they aren't stored with the interaction, and a turn's tools must not
                // change). While wrapping up, calls to other tools get an error result instead.
                put("tools", TOOL_DECLARATIONS)
                put("input", JsonArray(input.toList()))
                generationConfig?.let { put("generation_config", it) }
                put("stream", true)
                // Nothing is kept on Google's side: the conversation lives in our database.
                put("store", false)
            }
            val (steps, status) = collectSteps(request)
            val calls = steps.filter { it.type == "function_call" }
            if (steps.none { it.type == "model_output" } && calls.isEmpty()) {
                val blocked = status != null && status !in setOf("completed", "requires_action")
                emit(AgentEvent.Text(if (blocked) "Sorry, I can't help with that one." else "Sorry, I didn't get a response. Please try again."))
                return
            }
            input += steps
            persist("model", stepsContent(steps))
            if (calls.isEmpty()) return

            // Show every step first, then run them all at once (they don't depend on each other).
            val named = calls.map { call ->
                val name = call.str("name").orEmpty()
                val args = call["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                emit(AgentEvent.Tool(name, toolLabel(name, args)))
                Triple(call, name, args)
            }
            val durations = LongArray(named.size)
            val results = coroutineScope {
                named.mapIndexed { i, (_, name, args) ->
                    async {
                        if (wrappingUp && !name.startsWith("propose_")) {
                            buildJsonObject { put("error", "No more searches or lookups this message. Propose with what you have.") }
                        } else {
                            val started = System.nanoTime()
                            val r = runCatching { runTool(ctx, name, args).first }
                                .getOrElse { e -> buildJsonObject { put("error", e.message ?: "Failed") } }
                            durations[i] = (System.nanoTime() - started) / 1_000_000
                            r
                        }
                    }
                }.awaitAll()
            }
            toolCalls += calls.size
            rounds++
            val startWrapUp = !wrappingUp && (toolCalls >= MAX_TOOL_CALLS || rounds >= MAX_ROUNDS)
            val resultSteps = named.zip(results).mapIndexed { i, (n, result) ->
                val (call, name, _) = n
                val r = if (startWrapUp && i == named.lastIndex) JsonObject(result + ("system_note" to JsonPrimitive(WRAP_UP_NOTE))) else result
                buildJsonObject {
                    put("type", "function_result")
                    put("name", name)
                    put("call_id", call["id"] ?: JsonPrimitive(""))
                    put("result", r)
                    if ("error" in result) put("is_error", true)
                }
            }
            input += resultSteps
            // How long each tool took, for the admin trace only (removed before history goes to the model).
            persist("tool", JsonObject(stepsContent(resultSteps) + (DURATIONS_KEY to buildJsonArray { durations.forEach { add(it) } })))
            if (startWrapUp) wrapUpRounds = 0 else if (wrappingUp) wrapUpRounds++
        }
        emit(AgentEvent.Text("\n\nI ran out of steps before finishing. Tell me what's still missing and I'll pick up from there."))
    }

    /** The app's context goes first in the last user message: only this request sees it, so the history before it stays cacheable. */
    private fun withContext(history: List<JsonObject>): List<JsonObject> {
        val text = context ?: return history
        val i = history.indexOfLast { it.type == "user_input" }
        if (i < 0) return history
        val content = (history[i]["content"] as? JsonArray).orEmpty()
        val note = buildJsonObject { put("type", "text"); put("text", text) }
        return history.toMutableList().also { it[i] = JsonObject(history[i] + ("content" to JsonArray(listOf(note) + content))) }
    }

    /** One model call: the steps it streamed (text is shown as it comes) and the interaction's final status. */
    private suspend fun collectSteps(request: JsonObject): Pair<List<JsonObject>, String?> {
        val builders = sortedMapOf<Int, StepBuilder>()
        var status: String? = null
        llm.stream(request).collect { event ->
            when (event.str("event_type")) {
                "step.start" -> {
                    val index = (event["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: builders.size
                    builders[index] = StepBuilder(event["step"] as? JsonObject ?: JsonObject(emptyMap()))
                }
                "step.delta" -> {
                    val index = (event["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@collect
                    val delta = event["delta"] as? JsonObject ?: return@collect
                    val b = builders.getOrPut(index) { StepBuilder(JsonObject(emptyMap())) }
                    b.add(delta)?.let { if (b.type == "model_output") emit(AgentEvent.Text(it)) }
                }
                "interaction.completed", "interaction.status_update" -> {
                    val interaction = event["interaction"] as? JsonObject
                    (interaction?.str("status") ?: event.str("status"))?.let { status = it }
                }
            }
        }
        return builders.values.mapNotNull { it.build() } to status
    }
}

/** Puts one streamed step back together from its `step.start` and `step.delta` events. */
private class StepBuilder(private val start: JsonObject) {
    val type: String? = start.type
    private val text = StringBuilder()
    private val arguments = StringBuilder()
    private var signature: JsonElement? = null
    private val summary = mutableListOf<JsonElement>()

    /** Adds a delta; returns the text it added to a model_output step, if any. */
    fun add(delta: JsonObject): String? {
        when (delta.type) {
            "text" -> delta.str("text")?.let { text.append(it); return it }
            "arguments_delta" -> delta.str("arguments")?.let { arguments.append(it) }
            "thought_signature" -> delta["signature"]?.let { signature = it }
            "thought_summary" -> delta["content"]?.let { summary += it }
        }
        return null
    }

    fun build(): JsonObject? = when (type) {
        "model_output" -> if (text.isEmpty()) null else JsonObject(start + textStep(text.toString()))
        "function_call" -> {
            val args = arguments.toString().takeIf { it.isNotBlank() }
                ?.let { runCatching { argsJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            JsonObject(start + ("arguments" to (args ?: start["arguments"] as? JsonObject ?: JsonObject(emptyMap()))))
        }
        "thought" -> {
            val out = start.toMutableMap()
            signature?.let { out["signature"] = it }
            if (summary.isNotEmpty()) out["summary"] = JsonArray((start["summary"] as? JsonArray).orEmpty() + summary)
            JsonObject(out)
        }
        null -> null
        else -> start
    }
}

// ---------------------------------------------------------------------------------------------
// System prompt
// ---------------------------------------------------------------------------------------------

/**
 * The assistant's fixed instructions. They don't change between users or messages, so they form a
 * prefix Gemini can cache; the user's data goes in [messageContext].
 */
val SYSTEM_PROMPT = """
You are NutriMe's nutrition assistant inside the user's food-tracking app (Android and web). Functions let you read and change their food diary, foods, recipes, weights and targets. The user's last message starts with a context block from the app (date, profile, today's totals); the user didn't write it.

## Steps
- Get facts about their data from functions; don't guess. get_day lists a day's entries with the entry_id you need to edit or remove one.
- Work in few steps. Call independent functions together. Put every food in ONE search_foods call (one query per food). Don't repeat a search (earlier results are in the conversation); reword a query at most once.
- Food providers and web lookups have shared limits. If a result has provider_warnings, retry_after_seconds or a limit status, don't retry or reword it this turn.
- Never say you can't do something these functions can do. If something truly isn't possible, say in one sentence what you can do instead.

## Logging food
Find each food in this order and stop at the first good match:
1. Their own foods and history: in the first step, call search_foods (which covers their foods and recipes) together with search_diary or get_frequent_foods. Prefer a food they logged before.
2. NutriMe and USDA foods, which search_foods ranks first. For whole foods (produce, meat, fish, eggs, grains, plain dairy) prefer them over branded "label only" products, unless the user names a packaged brand.
3. Saved catalog products (branded foods others logged), when they name a brand.
4. Branded or restaurant foods not found (confidence low or none, or missing_words is the brand): in the second step, ONE web_nutrition_lookup call for all of them, then propose_create_food with the per-serving numbers, serving_grams, source_url and source_note, and log it.
5. A home-made dish with no match: find its ingredients and use propose_save_recipe.
6. Only when nothing above worked: propose_quick_add (an estimate), with the amount in `amount`, not in the name.

- Pick foods by NAME: the food must be what the user wrote, never another product or language because its calories fit better. Use realistic portions; when unsure, log by grams. A food with "weight: not published" is logged by serving and quantity, never by grams.
- substitute_for is only for a PLAIN food under a brand that isn't found (e.g. "Strawberries, raw" for "Kirkland Signature Whole Strawberries"), never for prepared, packaged or restaurant foods: look those up on the web.
- Copying a list or screenshot (e.g. another app's diary): keep each item's exact amount and serving, prefer the named brand, pass the calories shown as expected_kcal, and keep one entry per item. Use the food's own matching serving; never pick grams to make the calories match.
- kcal_mismatches from propose_log_foods: check the serving and amount (cooked vs raw, the right serving). If the food and amount are right, propose again with allow_kcal_mismatch: true.
- A dish you save as a recipe (e.g. a bowl built from a restaurant's ingredients) is logged with propose_save_recipe's log_quantity, as ONE entry; never also log its ingredients. Log the ingredients as separate entries (propose_log_foods, no recipe) only when they ask.
- Meal photos: name each food, estimate grams, say how sure you are, then search and propose. Ask only when something important is truly unclear.
- Label photos: propose_create_food with the per-serving values as printed (and log it if they ate it), without source_url.
- Never invent nutrient values: they come from the database, a label, or web_nutrition_lookup. A quick add is an estimate, and the card says so.
- The diary is one list. Each entry has a meal tag (breakfast, lunch, dinner, snacks) that the app uses only for suggestions. If no meal is given, pick one from the time of day, and don't mention meals unless the user does. Dates like "yesterday" are relative to today.

## Proposals
- Every change is a proposal. Each message has ONE card: all its propose_* calls add to it, and the app shows it under your reply, where the user can edit, remove or swap items and then tap Confirm. Find everything first, then propose it together.
- After proposing, say in one or two short sentences what's on the card and which items are substitutes, web results or estimates. Don't list every item again, and never say it's already saved.

## More you can do
- Their foods and recipes: get_my_foods, propose_save_recipe, propose_update_food, propose_delete_foods. Diary entries: propose_edit_entries (amount, meal, day, or another food) and propose_copy_entries. search_diary finds foods logged on any day.
- Recipes: find each ingredient (their own or past foods first, then search_foods, all in one step). Use the amounts they give; otherwise use realistic amounts that add up to the food being replaced (its serving weight and calories) and say so. To turn a custom food into a recipe, use replace_food_id (keeps its id); ask before update_diary_entries unless they asked to fix past logs.
- Weight and burn: get_burn_estimate. Talk about the TREND weight, not single weigh-ins (which move about 1 kg with water and food). The learned burn comes from their log and trend weight: if they always log low, it is low by the same amount and their target is still right. get_trends gives averages over a period and the change from the period before; get_top_sources answers "where does my X come from".
- Goals: propose_update_targets sets the goal weight, the weekly rate (lose up to 1 kg, gain up to 0.5 kg a week), protein in g per kg, and which burn the targets use (learned, formula, or a value they give). Warn when a rate is above 1% of body weight a week.

## Answers
- Short and specific to their numbers. Plain language, light markdown (short lists, **bold** key numbers), no tables.
- You're not a doctor: for medical conditions, medications, pregnancy or eating disorders, suggest a professional. Never encourage eating under ~1200 kcal/day or extreme restriction.
""".trim()

/** The app's context for one message: the date and time, the user's profile and targets, and today's totals. */
fun messageContext(data: UserData, today: String, localTime: String?): String {
    val p = data.profile
    val t = data.targets
    val date = LocalDate.parse(today)
    val todays = data.day(today)
    val totals = data.totals(todays)
    val (gaps, over) = gapsAndExcesses(totals, t)
    val week = data.diary.filter { it.date > date.minusDays(7).toString() && it.date <= today }
    val weekDays = week.map { it.date }.distinct().size
    val weekTotals = data.totals(week)
    fun target(id: Int) = (t[id]?.min ?: 0.0).roundToInt()

    return buildString {
        appendLine("[App context, not written by the user]")
        appendLine("Today is $today (${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})" + (localTime?.let { ", local time $it" } ?: "") + ".")
        if (!p.configured) appendLine("Profile not set up yet, so targets use defaults (suggest Profile → Targets if relevant).")
        appendLine("User: ${if (p.sex == Sex.MALE) "male" else "female"}, ${p.age} y, ${p.heightCm.roundToInt()} cm, ${"%.1f".format(p.weightKg)} kg, ${p.activity.label.lowercase()}; " +
            "goal: ${goal(p)}; units: ${if (p.imperial) "imperial (show weights in lb)" else "metric"}")
        appendLine("Daily targets: ${target(N.ENERGY)} kcal, protein ${target(N.PROTEIN)} g, carbs ${target(N.CARBS)} g, fat ${target(N.FAT)} g, fiber ${target(N.FIBER)} g")
        data.weights.lastOrNull()?.let { appendLine("Last weigh-in: ${"%.1f".format(it.weightKg)} kg on ${it.date}") }
        p.goalWeightKg?.let { appendLine("Goal weight: ${"%.1f".format(it)} kg") }
        appendLine("Daily burn used for targets: ${p.tdee.roundToInt()} kcal (${when (p.burnMode) {
            BurnMode.ADAPTIVE -> if (p.adaptiveBurnKcal != null) "learned from their log and weigh-ins" else "formula, until the learned value is accepted"
            BurnMode.MANUAL -> "typed by the user"
            BurnMode.FORMULA -> "Mifflin-St Jeor × activity"
        }})")
        if (todays.isEmpty()) appendLine("Today so far: nothing logged.") else {
            appendLine("Today so far: ${totals.kcal().roundToInt()} kcal, protein ${(totals[N.PROTEIN] ?: 0.0).roundToInt()} g, carbs ${(totals[N.CARBS] ?: 0.0).roundToInt()} g, fat ${(totals[N.FAT] ?: 0.0).roundToInt()} g (${todays.size} entries)")
            if (gaps.isNotEmpty()) appendLine("Furthest below target: ${gaps.take(5).joinToString("; ")}")
            if (over.isNotEmpty()) appendLine("Over limit: ${over.joinToString("; ")}")
        }
        if (weekDays > 0) appendLine("Last 7 days: $weekDays days logged, averaging ${(weekTotals.kcal() / weekDays).roundToInt()} kcal and ${((weekTotals[N.PROTEIN] ?: 0.0) / weekDays).roundToInt()} g protein")
    }.trimEnd()
}

private fun goal(p: Profile): String = when {
    p.goalKgPerWeek < 0 -> "lose ${"%.2f".format(-p.goalKgPerWeek)} kg/week"
    p.goalKgPerWeek > 0 -> "gain ${"%.2f".format(p.goalKgPerWeek)} kg/week"
    else -> "maintain weight"
}

// ---------------------------------------------------------------------------------------------
// Stored messages
// ---------------------------------------------------------------------------------------------

/**
 * One stored message: kind "user", "model" or "tool", with content {"steps": [...]} in the Interactions
 * format (user_input; thought, model_output and function_call; function_result).
 * Messages saved before the Interactions API have content {"role", "parts"} instead; [steps] converts them.
 */
data class StoredMessage(val kind: String, val content: JsonObject) {
    /** Saved in the old generateContent format. */
    val legacy: Boolean get() = "parts" in content && "steps" !in content
}

/** The message as Interactions steps. Old messages are converted; their thought signatures are dropped. */
fun steps(m: StoredMessage, index: Int = 0): List<JsonObject> {
    if (!m.legacy) return m.content.steps()
    val parts = (m.content["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    // Old function calls had no ids: number them by message and position, the same for the call and its response.
    fun id(i: Int) = "legacy-${index - (if (m.kind == "tool") 1 else 0)}-$i"
    return when (m.kind) {
        "user" -> listOf(buildJsonObject {
            put("type", "user_input")
            putJsonArray("content") {
                parts.forEach { p ->
                    val inline = p["inlineData"] as? JsonObject
                    when {
                        inline != null -> add(buildJsonObject { put("type", "image"); put("mime_type", inline["mimeType"] ?: JsonPrimitive("image/jpeg")); put("data", inline["data"] ?: JsonPrimitive("")) })
                        p["text"] != null -> add(buildJsonObject { put("type", "text"); put("text", p["text"]!!) })
                    }
                }
            }
        })
        "model" -> buildList {
            var calls = 0
            parts.forEach { p ->
                val text = p.str("text")
                val call = p["functionCall"] as? JsonObject
                when {
                    (p["thought"] as? JsonPrimitive)?.content == "true" -> Unit
                    call != null -> add(buildJsonObject {
                        put("type", "function_call"); put("id", id(calls++)); put("name", call["name"] ?: JsonPrimitive(""))
                        put("arguments", call["args"] as? JsonObject ?: JsonObject(emptyMap()))
                    })
                    !text.isNullOrEmpty() -> {
                        val last = lastOrNull()
                        if (last?.type == "model_output") set(size - 1, textStep(outputText(last) + text)) else add(textStep(text))
                    }
                }
            }
        }
        "tool" -> parts.mapIndexedNotNull { i, p ->
            val fr = p["functionResponse"] as? JsonObject ?: return@mapIndexedNotNull null
            buildJsonObject {
                put("type", "function_result"); put("name", fr["name"] ?: JsonPrimitive("")); put("call_id", id(i))
                put("result", fr["response"] as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
        else -> emptyList()
    }
}

private fun outputText(step: JsonObject): String =
    (step["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { c -> c.type == "text" }?.str("text") }.joinToString("")

// ---------------------------------------------------------------------------------------------
// History shaping
// ---------------------------------------------------------------------------------------------

/**
 * What to send the model, as Interactions input steps: the most recent messages (starting at a user
 * message, so function calls and results stay paired), with images only kept for the last few user messages.
 * Messages in the old format keep only their text: their function calls can't be sent without the thoughts
 * that came with them.
 */
fun historyForModel(messages: List<StoredMessage>, maxMessages: Int = 60, keepImages: Int = 3): List<JsonObject> {
    val clean = repair(messages.mapIndexed { i, m -> m to steps(m, i) }
        .map { (m, s) -> m.kind to if (m.legacy) s.filter { it.type == "user_input" || it.type == "model_output" } else s }
        .filter { (_, s) -> s.isNotEmpty() })
    var start = (clean.size - maxMessages).coerceAtLeast(0)
    while (start < clean.size && clean[start].first != "user") start++
    val window = clean.subList(start, clean.size)
    val userIdx = window.indices.filter { window[it].first == "user" }
    val imageCutoff = userIdx.takeLast(keepImages).firstOrNull() ?: 0
    return window.flatMapIndexed { i, (kind, s) -> if (kind != "user" || i >= imageCutoff) s else s.map(::stripImages) }
}

/**
 * Fix up history left by an interrupted turn (stopped reply, dropped connection, server error):
 * a model message whose function results were never saved keeps only its text, and consecutive
 * user messages are merged, so the model always gets a valid conversation.
 */
private fun repair(messages: List<Pair<String, List<JsonObject>>>): List<Pair<String, List<JsonObject>>> {
    val out = mutableListOf<Pair<String, List<JsonObject>>>()
    messages.forEachIndexed { i, (kind, s) ->
        var steps = s
        if (kind == "model" && steps.any { it.type == "function_call" } && messages.getOrNull(i + 1)?.first != "tool") {
            steps = steps.filter { it.type == "model_output" }
            if (steps.isEmpty()) return@forEachIndexed
        }
        if (kind == "tool" && out.lastOrNull()?.first != "model") return@forEachIndexed
        val prev = out.lastOrNull()
        if (kind == "user" && prev?.first == "user") {
            val content = (prev.second + steps).flatMap { (it["content"] as? JsonArray).orEmpty() }
            out[out.size - 1] = "user" to listOf(buildJsonObject { put("type", "user_input"); put("content", JsonArray(content)) })
        } else {
            out += kind to steps
        }
    }
    return out
}

private fun stripImages(step: JsonObject): JsonObject {
    val content = (step["content"] as? JsonArray ?: return step).map { c ->
        if ((c as? JsonObject)?.type == "image") buildJsonObject { put("type", "text"); put("text", "[photo shared earlier]") } else c
    }
    return JsonObject(step + ("content" to JsonArray(content)))
}

// ---------------------------------------------------------------------------------------------
// Display: what the apps show for a stored conversation
// ---------------------------------------------------------------------------------------------

/**
 * Turn stored messages into chat messages: {role: "user"|"assistant", blocks: [...]}, where a block
 * is {type: "text", text}, {type: "image", mimeType, data}, {type: "tool", name, label} or
 * {type: "proposal", proposal}. Model turns and tool results merge into one assistant message.
 */
fun displayMessages(messages: List<StoredMessage>, proposals: Map<String, ProposalView>): JsonArray = buildJsonArray {
    var assistant: MutableList<JsonElement>? = null
    // A message's card goes after its text (as it does live), once, however many calls added to it.
    val cards = LinkedHashSet<String>()
    fun flush() {
        if (cards.isNotEmpty()) {
            val blocks = assistant ?: mutableListOf<JsonElement>().also { assistant = it }
            cards.forEach { id -> proposals[id]?.let { view -> blocks += buildJsonObject { put("type", "proposal"); put("proposal", proposalJson(view)) } } }
            cards.clear()
        }
        assistant?.takeIf { it.isNotEmpty() }?.let { blocks ->
            add(buildJsonObject { put("role", "assistant"); put("blocks", JsonArray(blocks)) })
        }
        assistant = null
    }
    messages.forEachIndexed { index, m ->
        val steps = steps(m, index)
        when (m.kind) {
            "user" -> {
                flush()
                val blocks = steps.flatMap { (it["content"] as? JsonArray).orEmpty() }.mapNotNull { c ->
                    val item = c as? JsonObject ?: return@mapNotNull null
                    when (item.type) {
                        "image" -> buildJsonObject { put("type", "image"); put("mimeType", item["mime_type"]!!); put("data", item["data"]!!) }
                        "text" -> buildJsonObject { put("type", "text"); put("text", item["text"]!!) }
                        else -> null
                    }
                }
                add(buildJsonObject { put("role", "user"); put("blocks", JsonArray(blocks)) })
            }
            "model" -> {
                val blocks = assistant ?: mutableListOf<JsonElement>().also { assistant = it }
                steps.forEach { s ->
                    when (s.type) {
                        "model_output" -> {
                            val text = outputText(s)
                            if (text.isBlank()) return@forEach
                            val last = blocks.lastOrNull() as? JsonObject
                            if (last != null && last.type == "text") {
                                blocks[blocks.size - 1] = buildJsonObject { put("type", "text"); put("text", last.str("text").orEmpty() + text) }
                            } else blocks += buildJsonObject { put("type", "text"); put("text", text) }
                        }
                        "function_call" -> {
                            val name = s.str("name").orEmpty()
                            blocks += buildJsonObject {
                                put("type", "tool"); put("name", name)
                                put("label", toolLabel(name, s["arguments"] as? JsonObject ?: JsonObject(emptyMap())))
                            }
                        }
                    }
                }
            }
            "tool" -> steps.forEach { s -> (s["result"] as? JsonObject)?.str("proposal_id")?.let { cards += it } }
        }
    }
    flush()
}

fun proposalJson(v: ProposalView): JsonElement = actionJson.encodeToJsonElement(ProposalView.serializer(), v)

/** Proposal ids referenced by function results in [messages]. */
fun proposalIds(messages: List<StoredMessage>): List<String> = messages.withIndex().filter { it.value.kind == "tool" }
    .flatMap { (i, m) -> steps(m, i).mapNotNull { (it["result"] as? JsonObject)?.str("proposal_id") } }
    .distinct()

/** Show the model each proposal's current status (the user may have confirmed or dismissed it since). */
fun withProposalStatus(messages: List<StoredMessage>, statuses: Map<String, String>): List<StoredMessage> = messages.map { m ->
    if (m.kind != "tool" || m.legacy) return@map m
    val steps = m.content.steps().map { s ->
        val result = s["result"] as? JsonObject ?: return@map s
        val id = result.str("proposal_id") ?: return@map s
        val status = when (statuses[id]) {
            "applied" -> "confirmed by the user and saved"
            "dismissed" -> "dismissed by the user; nothing was saved"
            else -> return@map s
        }
        JsonObject(s + ("result" to JsonObject(result - "note" + ("status" to JsonPrimitive(status)))))
    }
    m.copy(content = JsonObject(m.content + ("steps" to JsonArray(steps))))
}

/** Key of a stored tool message's per-call durations in milliseconds (admin trace only). */
const val DURATIONS_KEY = "durations_ms"

/**
 * Stored messages as a readable list of steps for the admin trace: user text, model text,
 * each function call with its arguments, and each result. Photos are replaced by a placeholder.
 */
fun traceSteps(messages: List<StoredMessage>): JsonArray = buildJsonArray {
    messages.forEachIndexed { index, m ->
        val durations = (m.content[DURATIONS_KEY] as? JsonArray).orEmpty()
        var results = 0
        steps(m, index).forEach { s ->
            when (s.type) {
                "user_input" -> (s["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { c ->
                    add(buildJsonObject {
                        if (c.type == "image") { put("type", "image"); put("role", m.kind) }
                        else { put("type", "text"); put("role", m.kind); put("text", c.str("text").orEmpty()) }
                    })
                }
                "model_output" -> add(buildJsonObject { put("type", "text"); put("role", m.kind); put("text", outputText(s)) })
                "function_call" -> add(buildJsonObject { put("type", "call"); put("name", s["name"] ?: JsonPrimitive("")); put("data", s["arguments"] ?: JsonObject(emptyMap())) })
                "function_result" -> add(buildJsonObject {
                    put("type", "result"); put("name", s["name"] ?: JsonPrimitive("")); put("data", s["result"] ?: JsonObject(emptyMap()))
                    durations.getOrNull(results++)?.let { put("duration_ms", it) }
                })
                "thought" -> Unit // only a signature; nothing to read
                else -> add(buildJsonObject { put("type", "other"); put("role", m.kind) })
            }
        }
    }
}
