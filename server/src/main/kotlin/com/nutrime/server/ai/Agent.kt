package com.nutrime.server.ai

import com.nutrime.app.model.N
import com.nutrime.app.model.Profile
import com.nutrime.app.model.Sex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

private val WRAP_UP_DECLARATIONS = JsonArray(TOOL_DECLARATIONS.filter {
    ((it as JsonObject)["name"] as JsonPrimitive).content.startsWith("propose_")
})

private const val WRAP_UP_NOTE = "Step limit for this message reached: searching and lookups are now off. With what you already " +
    "have, call the propose_* functions now (best matches; generic substitutes where a brand wasn't found), then tell the " +
    "user in a short reply which entries are substitutes or uncertain. If this wasn't a logging request, just answer."

/**
 * The tool loop: send the conversation to the model, stream its text, run the functions it calls,
 * send back the results, and repeat until it answers without calling anything.
 * Every new Content is handed to [persist] as soon as it's complete.
 *
 * Each turn has a budget ([MAX_TOOL_CALLS], [MAX_ROUNDS]). When it runs out the model isn't cut off:
 * it gets [WRAP_UP_ROUNDS] more calls with only the propose_* tools, so it still delivers a proposal.
 */
class Agent(
    private val llm: LlmClient,
    private val ctx: ToolContext,
    private val system: String,
    private val generationConfig: JsonObject?,
    private val persist: (kind: String, content: JsonObject) -> Unit,
    private val emit: suspend (AgentEvent) -> Unit,
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
        val contents = history.toMutableList()
        // Searches from earlier messages (e.g. before "continue") count as already done.
        history.filter { (it["role"] as? JsonPrimitive)?.content == "model" }.forEach { c ->
            (c["parts"] as? JsonArray).orEmpty().forEach { p ->
                val call = (p as? JsonObject)?.get("functionCall") as? JsonObject ?: return@forEach
                if ((call["name"] as? JsonPrimitive)?.content == "search_foods") {
                    searchQueries(call["args"] as? JsonObject ?: JsonObject(emptyMap())).forEach { ctx.searched += normalizeQuery(it) }
                }
            }
        }
        var toolCalls = 0
        var rounds = 0
        var wrapUpRounds = -1 // -1 until the budget runs out
        while (wrapUpRounds < WRAP_UP_ROUNDS) {
            val wrappingUp = wrapUpRounds >= 0
            val request = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
                put("contents", JsonArray(contents.toList()))
                putJsonArray("tools") { add(buildJsonObject { put("functionDeclarations", if (wrappingUp) WRAP_UP_DECLARATIONS else TOOL_DECLARATIONS) }) }
                generationConfig?.let { put("generationConfig", it) }
            }
            val parts = mutableListOf<JsonObject>()
            var blocked: String? = null
            llm.stream(request).collect { chunk ->
                (chunk["promptFeedback"] as? JsonObject)?.get("blockReason")?.let { blocked = (it as JsonPrimitive).content }
                val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@collect
                val finish = (candidate["finishReason"] as? JsonPrimitive)?.content
                if (finish != null && finish !in setOf("STOP", "MAX_TOKENS", "FINISH_REASON_UNSPECIFIED")) blocked = finish
                val newParts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
                for (p in newParts) {
                    val part = p as? JsonObject ?: continue
                    if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
                    val text = (part["text"] as? JsonPrimitive)?.content
                    if (text != null) {
                        if (text.isNotEmpty()) emit(AgentEvent.Text(text))
                        mergeText(parts, part, text)
                    } else {
                        parts += part
                    }
                }
            }
            if (parts.isEmpty()) {
                emit(AgentEvent.Text(if (blocked != null) "Sorry, I can't help with that one." else "Sorry, I didn't get a response. Please try again."))
                return
            }
            val modelContent = buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) }
            contents += modelContent
            persist("model", modelContent)

            val calls = parts.mapNotNull { it["functionCall"] as? JsonObject }
            if (calls.isEmpty()) return

            // Show every step first, then run them all at once (they don't depend on each other).
            val named = calls.map { call ->
                val name = (call["name"] as? JsonPrimitive)?.content.orEmpty()
                val args = call["args"] as? JsonObject ?: JsonObject(emptyMap())
                emit(AgentEvent.Tool(name, toolLabel(name, args)))
                Triple(call, name, args)
            }
            val results = coroutineScope {
                named.map { (_, name, args) ->
                    async {
                        if (wrappingUp && !name.startsWith("propose_")) {
                            buildJsonObject { put("error", "No more searches or lookups this message. Propose with what you have.") } to null
                        } else runCatching { runTool(ctx, name, args) }
                            .getOrElse { e -> buildJsonObject { put("error", e.message ?: "Failed") } to null }
                    }
                }.awaitAll()
            }
            toolCalls += calls.size
            rounds++
            val startWrapUp = !wrappingUp && (toolCalls >= MAX_TOOL_CALLS || rounds >= MAX_ROUNDS)
            val responses = named.zip(results).mapIndexed { i, (n, r) ->
                val (call, name, _) = n
                val (result, _) = r
                val response = if (startWrapUp && i == named.lastIndex) JsonObject(result + ("system_note" to JsonPrimitive(WRAP_UP_NOTE))) else result
                buildJsonObject {
                    putJsonObject("functionResponse") {
                        put("name", name)
                        call["id"]?.let { put("id", it) }
                        put("response", response)
                    }
                }
            }
            val toolContent = buildJsonObject { put("role", "user"); put("parts", JsonArray(responses)) }
            contents += toolContent
            persist("tool", toolContent)
            if (startWrapUp) wrapUpRounds = 0 else if (wrappingUp) wrapUpRounds++
        }
        emit(AgentEvent.Text("\n\nI ran out of steps before finishing. Tell me what's still missing and I'll pick up from there."))
    }

    /** Streamed text arrives in many small parts; keep one part per run of text, with its thought signature. */
    private fun mergeText(parts: MutableList<JsonObject>, part: JsonObject, text: String) {
        val last = parts.lastOrNull()
        val lastText = (last?.get("text") as? JsonPrimitive)?.content
        if (last != null && lastText != null) {
            val merged = last.toMutableMap()
            merged["text"] = JsonPrimitive(lastText + text)
            part["thoughtSignature"]?.let { merged["thoughtSignature"] = it }
            parts[parts.size - 1] = JsonObject(merged)
        } else {
            parts += part
        }
    }
}

// ---------------------------------------------------------------------------------------------
// System prompt
// ---------------------------------------------------------------------------------------------

fun systemPrompt(data: UserData, today: String, localTime: String?): String {
    val p = data.profile
    val t = data.targets
    val date = LocalDate.parse(today)
    val todays = data.day(today)
    val totals = data.totals(todays)
    val (gaps, over) = gapsAndExcesses(totals, t)
    val week = data.diary.filter { it.date > date.minusDays(7).toString() && it.date <= today }
    val weekDays = week.map { it.date }.distinct().size
    val weekTotals = data.totals(week)
    val latestWeight = data.weights.lastOrNull()
    fun target(id: Int) = (t[id]?.min ?: 0.0).roundToInt()

    return buildString {
        appendLine("You are NutriMe's nutrition assistant, inside the user's food-tracking app (Android and web).")
        appendLine("You can see and act on their food diary, foods, weights and targets through functions.")
        appendLine()
        appendLine("Today is $today (${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }})" + (localTime?.let { ", local time $it" } ?: "") + ".")
        appendLine()
        appendLine("## The user")
        if (!p.configured) appendLine("(They haven't set up their profile yet, so targets use defaults. Suggest Profile → Targets if relevant.)")
        appendLine("- ${if (p.sex == Sex.MALE) "Male" else "Female"}, ${p.age} y, ${p.heightCm.roundToInt()} cm, ${"%.1f".format(p.weightKg)} kg, ${p.activity.label.lowercase()}")
        appendLine("- Goal: ${goal(p)}; units: ${if (p.imperial) "imperial (lb, oz) — show weights in lb" else "metric"}")
        appendLine("- Daily targets: ${target(N.ENERGY)} kcal, protein ${target(N.PROTEIN)} g, carbs ${target(N.CARBS)} g, fat ${target(N.FAT)} g, fiber ${target(N.FIBER)} g")
        latestWeight?.let { appendLine("- Last weigh-in: ${"%.1f".format(it.weightKg)} kg on ${it.date}") }
        p.goalWeightKg?.let { appendLine("- Goal weight: ${"%.1f".format(it)} kg") }
        appendLine("- Daily burn used for targets: ${p.tdee.roundToInt()} kcal (${when (p.burnMode) {
            com.nutrime.app.model.BurnMode.ADAPTIVE -> if (p.adaptiveBurnKcal != null) "learned from their log and weigh-ins" else "formula, until the learned value is accepted"
            com.nutrime.app.model.BurnMode.MANUAL -> "typed by the user"
            com.nutrime.app.model.BurnMode.FORMULA -> "Mifflin-St Jeor × activity"
        }})")
        appendLine()
        appendLine("## Today so far")
        if (todays.isEmpty()) appendLine("Nothing logged yet.") else {
            appendLine("- ${totals.kcal().roundToInt()} kcal, protein ${(totals[N.PROTEIN] ?: 0.0).roundToInt()} g, carbs ${(totals[N.CARBS] ?: 0.0).roundToInt()} g, fat ${(totals[N.FAT] ?: 0.0).roundToInt()} g (${todays.size} entries)")
            if (gaps.isNotEmpty()) appendLine("- Furthest below target: ${gaps.take(5).joinToString("; ")}")
            if (over.isNotEmpty()) appendLine("- Over limit: ${over.joinToString("; ")}")
        }
        if (weekDays > 0) appendLine("- Last 7 days: $weekDays days logged, averaging ${(weekTotals.kcal() / weekDays).roundToInt()} kcal and ${((weekTotals[N.PROTEIN] ?: 0.0) / weekDays).roundToInt()} g protein")
        appendLine()
        appendLine(
            """
            ## How to work
            - Use functions for facts about their data; don't guess. Call get_day for a specific day's entries (you need entry_id to edit or remove).
            - Work in few steps. Call independent functions together in one step, and put every food in ONE search_foods call (one query per food). Don't search the same thing twice: earlier results are in the conversation.
            - Logging food: search_foods (or get_frequent_foods for things they eat often), pick the best match, then propose_log_foods. Prefer the user's own foods, then NutriMe foods (source "NutriMe": checked entries under everyday names), then other plain database foods; use branded ones when they name a brand. Use realistic portions; when unsure, log by grams.
            - Pick foods by NAME: the food must be what the user wrote. Never pick a food in another language or a different product because its calories fit better.
            - Whole foods (produce, meat, fish, eggs, rice, grains, plain dairy): prefer NutriMe and USDA foods, which list vitamins and minerals (nutrients_listed is high), over branded products marked data "label only", unless the user names the brand of a packaged product. Results are already ordered this way.
            - Provider requests share server-enforced limits across all users and tools: Open Food Facts search 8/minute and product lookups 12/minute; USDA 900/hour (demo key 30/hour and 50/day). Saved results do not spend this budget. If search returns provider_warnings or retry_after_seconds, do not retry or reword queries to evade the limit in this turn. Use saved/local foods, disclose substitutes, or ask for a package label.
            - When a result has missing_words, that word (usually a brand) isn't in the database. Try at most ONE different wording for that food, then log the closest plain food at the stated amount with substitute_for set to the name the user gave (e.g. "Strawberries, raw" with substitute_for "Kirkland Signature Whole Strawberries"). Don't use propose_quick_add for it.
            - Copying a list or screenshot (e.g. another app's diary): keep each item's exact amount and serving ("1 scoop", "2.5 × 1/2 cup", "520 g"), prefer the named brand, and pass the calories shown as expected_kcal. Use the food's own serving when it has a matching one; never pick grams just to make calories match. Keep one entry per item: don't split it across meals unless asked. If no meals are shown, use one meal that fits the time of day (or what they say).
            - If propose_log_foods returns kcal_mismatches, check the serving and amount of those items first (cooked vs raw, the right serving). Keep a food whose name matches; if the amount is right too, propose again with allow_kcal_mismatch: true. The card shows the user both numbers.
            - Never invent nutrient values for a food the database has. Use propose_quick_add only for dishes with no reasonable database food (e.g. a restaurant dish), with the amount in `amount`, not in the name.
            - All changes are proposals. Each message has ONE card: every propose_* call in a message adds to it, and the app shows it under your reply, where the user can edit, remove or swap items and then tap Confirm. Propose everything together once you've found it all. After proposing, say in one or two short sentences what's on the card and which items are substitutes or estimates; don't list every item again, and never claim it's already saved.
            - Meal photos: name each food you see and estimate grams, say how confident you are, then search and propose entries. Ask only if something important is truly ambiguous.
            - Nutrition label photos/screenshots: use propose_create_food with the per-serving values exactly as printed (and log it if they ate it).
            - You can manage more than the diary. Their own foods and recipes: get_my_foods, propose_save_recipe, propose_update_food, propose_delete_foods. Their history: search_diary finds foods they logged on any day. Their targets: propose_update_targets. Diary entries: propose_edit_entries (amount, meal, day, or switch to another food) and propose_copy_entries.
            - Recipes: find each ingredient first, preferring foods they logged before (search_diary) or their own foods, then search_foods for the rest (all in one step). Use the amounts they give; if they give none, use realistic amounts that add up to the food being replaced (e.g. its serving weight and calories) and say so. To turn a custom food into a recipe, use replace_food_id (keeps its id); ask before update_diary_entries unless they asked to fix past logs.
            - Weight and burn: use get_burn_estimate. Talk about the TREND weight (smoothed), not single weigh-ins, which move about 1 kg with water and food. The learned burn comes from their log and trend weight; if they always log low, it is low by the same amount and their target is still right. Use get_trends for averages over a period and the comparison with the period before, and get_top_sources for "where does my X come from".
            - Goals: propose_update_targets can set a goal weight, the weekly rate (lose up to 1 kg, gain up to 0.5 kg a week), protein in g per kg, and which daily burn the targets use (learned, formula, or a value they give). Warn when a rate is above 1% of body weight a week.
            - Never say you can't do something these functions can do. If something truly isn't possible, say what you can do instead in one sentence.
            - If no meal is given, pick it from the time of day. Dates like "yesterday" are relative to today.
            - Answers: short and specific to their numbers. Plain language, light markdown (short lists, **bold** key numbers). No tables.
            - You're not a doctor: for medical conditions, medications, pregnancy or eating disorders, suggest a professional. Never encourage eating under ~1200 kcal/day or extreme restriction.
            """.trimIndent()
        )
    }
}

private fun goal(p: Profile): String = when {
    p.goalKgPerWeek < 0 -> "lose ${"%.2f".format(-p.goalKgPerWeek)} kg/week"
    p.goalKgPerWeek > 0 -> "gain ${"%.2f".format(p.goalKgPerWeek)} kg/week"
    else -> "maintain weight"
}

// ---------------------------------------------------------------------------------------------
// History shaping
// ---------------------------------------------------------------------------------------------

data class StoredMessage(val kind: String, val content: JsonObject)

/**
 * What to send the model: the most recent messages (starting at a user message, so function calls
 * and responses stay paired), with images only kept for the last few user messages.
 */
fun historyForModel(messages: List<StoredMessage>, maxMessages: Int = 60, keepImages: Int = 3): List<JsonObject> {
    val clean = repair(messages)
    var start = (clean.size - maxMessages).coerceAtLeast(0)
    while (start < clean.size && clean[start].kind != "user") start++
    val window = clean.subList(start, clean.size)
    val userIdx = window.indices.filter { window[it].kind == "user" }
    val imageCutoff = userIdx.takeLast(keepImages).firstOrNull() ?: 0
    return window.mapIndexed { i, m ->
        if (m.kind != "user" || i >= imageCutoff) m.content else stripImages(m.content)
    }
}

/**
 * Fix up history left by an interrupted turn (stopped reply, dropped connection, server error):
 * a function call whose response was never saved loses its calls, and consecutive user messages
 * are merged, so the model always gets a valid conversation.
 */
private fun repair(messages: List<StoredMessage>): List<StoredMessage> {
    val out = mutableListOf<StoredMessage>()
    messages.forEachIndexed { i, m ->
        var msg = m
        if (m.kind == "model" && messages.getOrNull(i + 1)?.kind != "tool") {
            val parts = (m.content["parts"] as? JsonArray).orEmpty().filter { (it as? JsonObject)?.containsKey("functionCall") != true }
            if (parts.size != (m.content["parts"] as? JsonArray).orEmpty().size) {
                if (parts.isEmpty()) return@forEachIndexed
                msg = m.copy(content = JsonObject(m.content + ("parts" to JsonArray(parts))))
            }
        }
        if (msg.kind == "tool" && out.lastOrNull()?.kind != "model") return@forEachIndexed
        val prev = out.lastOrNull()
        if (msg.kind == "user" && prev?.kind == "user") {
            val merged = (prev.content["parts"] as JsonArray) + (msg.content["parts"] as JsonArray)
            out[out.size - 1] = prev.copy(content = JsonObject(prev.content + ("parts" to JsonArray(merged))))
        } else {
            out += msg
        }
    }
    return out
}

private fun stripImages(content: JsonObject): JsonObject {
    val parts = (content["parts"] as? JsonArray).orEmpty().map { p ->
        if ((p as? JsonObject)?.containsKey("inlineData") == true) buildJsonObject { put("text", "[photo shared earlier]") } else p
    }
    return JsonObject(content + ("parts" to JsonArray(parts)))
}

// ---------------------------------------------------------------------------------------------
// Display: what the apps show for a stored conversation
// ---------------------------------------------------------------------------------------------

/**
 * Turn stored contents into chat messages: {role: "user"|"assistant", blocks: [...]}, where a block
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
    for (m in messages) {
        val parts = (m.content["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        when (m.kind) {
            "user" -> {
                flush()
                val blocks = parts.mapNotNull { p ->
                    val inline = p["inlineData"] as? JsonObject
                    when {
                        inline != null -> buildJsonObject { put("type", "image"); put("mimeType", inline["mimeType"]!!); put("data", inline["data"]!!) }
                        p["text"] != null -> buildJsonObject { put("type", "text"); put("text", p["text"]!!) }
                        else -> null
                    }
                }
                add(buildJsonObject { put("role", "user"); put("blocks", JsonArray(blocks)) })
            }
            "model" -> {
                val blocks = assistant ?: mutableListOf<JsonElement>().also { assistant = it }
                parts.forEach { p ->
                    val text = (p["text"] as? JsonPrimitive)?.content
                    val call = p["functionCall"] as? JsonObject
                    when {
                        text != null && text.isNotBlank() -> {
                            val last = blocks.lastOrNull() as? JsonObject
                            if (last != null && (last["type"] as JsonPrimitive).content == "text") {
                                blocks[blocks.size - 1] = buildJsonObject { put("type", "text"); put("text", (last["text"] as JsonPrimitive).content + text) }
                            } else blocks += buildJsonObject { put("type", "text"); put("text", text) }
                        }
                        call != null -> {
                            val name = (call["name"] as? JsonPrimitive)?.content.orEmpty()
                            blocks += buildJsonObject {
                                put("type", "tool"); put("name", name)
                                put("label", toolLabel(name, call["args"] as? JsonObject ?: JsonObject(emptyMap())))
                            }
                        }
                    }
                }
            }
            "tool" -> {
                parts.forEach { p ->
                    val response = (p["functionResponse"] as? JsonObject)?.get("response") as? JsonObject ?: return@forEach
                    val id = (response["proposal_id"] as? JsonPrimitive)?.content ?: return@forEach
                    cards += id
                }
            }
        }
    }
    flush()
}

fun proposalJson(v: ProposalView): JsonElement = actionJson.encodeToJsonElement(ProposalView.serializer(), v)

/** Proposal ids referenced by function responses in [messages]. */
fun proposalIds(messages: List<StoredMessage>): List<String> = messages.filter { it.kind == "tool" }.flatMap { m ->
    (m.content["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
        (((p as? JsonObject)?.get("functionResponse") as? JsonObject)?.get("response") as? JsonObject)
            ?.get("proposal_id")?.let { (it as JsonPrimitive).content }
    }
}.distinct()

/** Show the model each proposal's current status (the user may have confirmed or dismissed it since). */
fun withProposalStatus(messages: List<StoredMessage>, statuses: Map<String, String>): List<StoredMessage> = messages.map { m ->
    if (m.kind != "tool") return@map m
    val parts = (m.content["parts"] as? JsonArray).orEmpty().map { p ->
        val fr = (p as? JsonObject)?.get("functionResponse") as? JsonObject ?: return@map p
        val response = fr["response"] as? JsonObject ?: return@map p
        val id = (response["proposal_id"] as? JsonPrimitive)?.content ?: return@map p
        val status = when (statuses[id]) {
            "applied" -> "confirmed by the user and saved"
            "dismissed" -> "dismissed by the user; nothing was saved"
            else -> return@map p
        }
        val newResponse = JsonObject(response - "note" + ("status" to JsonPrimitive(status)))
        JsonObject(p + ("functionResponse" to JsonObject(fr + ("response" to newResponse))))
    }
    m.copy(content = JsonObject(m.content + ("parts" to JsonArray(parts))))
}

/**
 * Stored messages as a readable list of steps for the admin trace: user text, model text,
 * each function call with its arguments, and each result. Photos are replaced by a placeholder.
 */
fun traceSteps(messages: List<StoredMessage>): JsonArray = buildJsonArray {
    messages.forEach { m ->
        (m.content["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { p ->
            val call = p["functionCall"] as? JsonObject
            val result = p["functionResponse"] as? JsonObject
            val text = (p["text"] as? JsonPrimitive)?.content
            add(buildJsonObject {
                when {
                    p.containsKey("inlineData") -> { put("type", "image"); put("role", m.kind) }
                    call != null -> { put("type", "call"); put("name", call["name"] ?: JsonPrimitive("")); put("data", call["args"] ?: JsonObject(emptyMap())) }
                    result != null -> { put("type", "result"); put("name", result["name"] ?: JsonPrimitive("")); put("data", result["response"] ?: JsonObject(emptyMap())) }
                    text != null -> { put("type", "text"); put("role", m.kind); put("text", text) }
                    else -> { put("type", "other"); put("role", m.kind) }
                }
            })
        }
    }
}
