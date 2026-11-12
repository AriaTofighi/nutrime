package com.nutrime.server.ai

import com.nutrime.app.model.N
import com.nutrime.app.model.NutrientMap
import com.nutrime.app.model.Serving
import com.nutrime.server.AiProposalUndo
import com.nutrime.server.AiProposals
import com.nutrime.server.Records
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import com.nutrime.server.ApiException
import com.nutrime.server.SyncChange
import com.nutrime.server.SyncRequest
import com.nutrime.server.sync
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import com.nutrime.server.food.displayFoodName

/** One change the assistant wants to make. Applied only after the user confirms. */
@Serializable
sealed class Action {
    @Serializable @SerialName("log_food")
    data class LogFood(
        val date: String,
        val meal: Int,
        val foodId: String,
        val foodName: String,
        val brand: String? = null,
        val servingLabel: String,
        val servingGrams: Double,
        val quantity: Double,
        /** Per 100 g. */
        val nutrients: Map<Int, Double>,
        /** Also save the food to the user's foods (used for online foods so they show up offline). */
        val food: FoodRecord? = null,
        /** Calories the user gave for this item (e.g. from another app's diary), shown on the card. */
        val expectedKcal: Double? = null,
        /** What the user named when this is a stand-in (e.g. a brand that isn't in the database). */
        val substituteFor: String? = null,
        /** Where the numbers come from, e.g. "USDA", "NutriMe", "Open Food Facts". */
        val source: String? = null,
        /** Other matches from the same search, so the user can swap on the card. */
        val alternatives: List<Alternative> = emptyList(),
    ) : Action()

    @Serializable @SerialName("quick_add")
    data class QuickAdd(
        val date: String,
        val meal: Int,
        val name: String,
        /** Totals for the whole amount. */
        val nutrients: Map<Int, Double>,
        /** What the estimate is for, e.g. "1 bowl" or "125 g". */
        val amount: String? = null,
        val grams: Double? = null,
    ) : Action()

    @Serializable @SerialName("edit_entry")
    data class EditEntry(
        val entryId: String,
        val foodName: String,
        val meal: Int,
        val servingLabel: String,
        val servingGrams: Double,
        val quantity: Double,
        val kcalBefore: Double,
        val kcalAfter: Double,
        /** Set when the entry switches to another food. */
        val newFood: SwapFood? = null,
        /** Set when the entry moves to another day. */
        val date: String? = null,
    ) : Action()

    @Serializable @SerialName("delete_entry")
    data class DeleteEntry(val entryId: String, val foodName: String, val kcal: Double) : Action()

    @Serializable @SerialName("log_weight")
    data class LogWeight(val date: String, val weightKg: Double, val bodyFatPct: Double? = null, val imperial: Boolean = false) : Action()

    /** A recipe from ingredients: new, an edit of a recipe, or a custom food turned into a recipe (same id). */
    @Serializable @SerialName("save_recipe")
    data class SaveRecipe(
        val foodId: String,
        val name: String,
        val servings: Double,
        val ingredients: List<IngredientRecord>,
        /** The name of the custom food or recipe this replaces; null for a new recipe. */
        val replaces: String? = null,
        /** Diary entries of the replaced food that take the recipe's nutrients (their amounts are kept). */
        val updateEntryIds: List<String> = emptyList(),
    ) : Action() {
        val totalGrams: Double get() = ingredients.sumOf { it.grams }
        val per100: Map<Int, Double> get() {
            val total = totalGrams.coerceAtLeast(0.0001)
            val sums = HashMap<Int, Double>()
            ingredients.forEach { i -> i.nutrients.forEach { (k, v) -> sums[k] = (sums[k] ?: 0.0) + v * i.grams / 100.0 } }
            return sums.mapValues { it.value * 100.0 / total }
        }
    }

    /** Change a custom food: name, brand, serving, or nutrients (per 100 g, replacing the old ones). */
    @Serializable @SerialName("update_food")
    data class UpdateFood(
        val foodId: String,
        val name: String,
        val oldName: String,
        val brand: String? = null,
        val serving: Serving? = null,
        val nutrients: Map<Int, Double>? = null,
    ) : Action()

    @Serializable @SerialName("delete_food")
    data class DeleteFood(val foodId: String, val name: String) : Action()

    /** Change the user's targets: calorie target, weekly goal, macro split, activity level. */
    @Serializable @SerialName("update_profile")
    data class UpdateProfile(
        val calorieTarget: Int? = null,
        /** Use the suggested calorie target again (clears a custom one). */
        val useSuggestedCalories: Boolean = false,
        val goalKgPerWeek: Double? = null,
        val proteinPct: Int? = null,
        val carbsPct: Int? = null,
        val fatPct: Int? = null,
        val activity: String? = null,
        val goalWeightKg: Double? = null,
        val clearGoalWeight: Boolean = false,
        val proteinGPerKg: Double? = null,
        /** "PERCENT" or "PER_KG". */
        val proteinMode: String? = null,
        /** "FORMULA", "ADAPTIVE" or "MANUAL". */
        val burnMode: String? = null,
        val manualBurnKcal: Int? = null,
        /** What changes, for the card, e.g. "Calories 2,000 → 1,800 kcal". */
        val summary: List<String> = emptyList(),
    ) : Action()

    @Serializable @SerialName("create_food")
    data class CreateFood(
        val foodId: String,
        val name: String,
        val brand: String? = null,
        val serving: Serving,
        /** Per 100 g. */
        val nutrients: Map<Int, Double>,
    ) : Action()
}

/** The food an entry switches to. Online foods are saved to the user's foods too, as when logging. */
@Serializable
data class SwapFood(val foodId: String, val name: String, val brand: String? = null, val nutrients: Map<Int, Double>, val food: FoodRecord? = null)

@Serializable
data class Alternative(val foodId: String, val name: String, val brand: String? = null, val kcalPer100: Double)

@Serializable
data class ProposalAlternative(val foodId: String, val name: String, val kcal: Double)

/**
 * One row of a proposal card. [title] and [detail] are complete on their own (older apps show only
 * those); newer apps use the structured fields to group by meal, show labels and edit the row.
 */
@Serializable
data class ProposalItem(
    val title: String,
    val detail: String,
    /** "log_food", "quick_add", "edit_entry", "delete_entry", "log_weight", "create_food". */
    val kind: String = "",
    val name: String? = null,
    val amount: String? = null,
    val meal: Int? = null,
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val quantity: Double? = null,
    val servingLabel: String? = null,
    val servingGrams: Double? = null,
    val source: String? = null,
    /** "substitute", "estimate", "match" or "differs". */
    val badge: String? = null,
    val note: String? = null,
    val alternatives: List<ProposalAlternative> = emptyList(),
    /** Amount and meal can be changed on the card. */
    val editable: Boolean = false,
)

@Serializable
data class ProposalView(
    val id: String,
    val title: String,
    val items: List<ProposalItem>,
    /** "pending", "applied", "dismissed". */
    val status: String,
    /** Total macros of the food in the card, e.g. "P 120 g · C 210 g · F 60 g". */
    val summary: String? = null,
)

/** A change the user makes to one row of a pending card. */
@Serializable
data class ProposalItemEdit(
    val remove: Boolean = false,
    val meal: Int? = null,
    val quantity: Double? = null,
    val grams: Double? = null,
    val foodId: String? = null,
)

val actionJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
private val actionsSerializer = ListSerializer(Action.serializer())

private fun fmt1(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d).trimEnd('0').trimEnd('.')
private fun kcalOf(per100: Map<Int, Double>, grams: Double) = (per100[N.ENERGY] ?: 0.0) * grams / 100.0
fun mealName(meal: Int) = MEALS.getOrNull(meal)?.replaceFirstChar { it.uppercase() } ?: "Snacks"

fun Action.item(today: String): ProposalItem = when (this) {
    is Action.LogFood -> {
        val grams = servingGrams * quantity
        val kcal = kcalOf(nutrients, grams)
        val shown = displayFoodName(foodName, brand)
        val expected = expectedKcal?.takeIf { it > 0 }
        val close = expected != null && (abs(kcal - expected) <= 15 || abs(kcal - expected) / expected <= KCAL_CLOSE)
        val notes = listOfNotNull(
            substituteFor?.let { "Stand-in for $it" },
            expected?.let { if (close) "Matches your ${it.roundToInt()} kcal" else "Your list: ${it.roundToInt()} kcal" },
        )
        ProposalItem(
            title = shown,
            detail = "${amount(quantity, servingLabel, servingGrams)} · ${kcal.roundToInt()} kcal · ${mealName(meal)}${dateSuffix(date, today)}",
            kind = "log_food", name = shown, amount = amount(quantity, servingLabel, servingGrams) + dateSuffix(date, today), meal = meal,
            kcal = kcal, protein = (nutrients[N.PROTEIN] ?: 0.0) * grams / 100, carbs = (nutrients[N.CARBS] ?: 0.0) * grams / 100,
            fat = (nutrients[N.FAT] ?: 0.0) * grams / 100, quantity = quantity, servingLabel = servingLabel, servingGrams = servingGrams,
            source = source,
            badge = when { substituteFor != null -> "substitute"; expected == null -> null; close -> "match"; else -> "differs" },
            note = notes.joinToString(" · ").ifEmpty { null },
            alternatives = alternatives.map { ProposalAlternative(it.foodId, displayFoodName(it.name, it.brand), it.kcalPer100 * grams / 100) },
            editable = true,
        )
    }
    is Action.QuickAdd -> ProposalItem(
        title = "$name (estimate)",
        detail = buildString {
            amount?.let { append("$it · ") }
            append("${(nutrients[N.ENERGY] ?: 0.0).roundToInt()} kcal")
            nutrients[N.PROTEIN]?.let { append(" · P ${it.roundToInt()} g") }
            nutrients[N.CARBS]?.let { append(" · C ${it.roundToInt()} g") }
            nutrients[N.FAT]?.let { append(" · F ${it.roundToInt()} g") }
            append(" · ${mealName(meal)}${dateSuffix(date, today)}")
        },
        kind = "quick_add", name = name, amount = (amount ?: grams?.let { "${fmt1(it)} g" })?.plus(dateSuffix(date, today)), meal = meal,
        kcal = nutrients[N.ENERGY] ?: 0.0, protein = nutrients[N.PROTEIN], carbs = nutrients[N.CARBS], fat = nutrients[N.FAT],
        quantity = grams, servingLabel = if (grams != null) "g" else null, servingGrams = if (grams != null) 1.0 else null,
        source = "Estimate", badge = "estimate", note = "Estimate: not from the food database", editable = true,
    )
    is Action.EditEntry -> ProposalItem(
        if (newFood != null) "Change $foodName → ${displayFoodName(newFood.name, newFood.brand)}" else "Change $foodName",
        "${amount(quantity, servingLabel, servingGrams)} · ${kcalBefore.roundToInt()} → ${kcalAfter.roundToInt()} kcal · ${mealName(meal)}" +
            (date?.let { dateSuffix(it, today) } ?: ""),
        kind = "edit_entry",
    )
    is Action.SaveRecipe -> ProposalItem(
        when {
            replaces == null -> "New recipe: $name"
            replaces.equals(name, ignoreCase = true) -> "Make $name a recipe"
            else -> "Make $replaces a recipe: $name"
        },
        "${ingredients.size} ingredients · ${fmt1(totalGrams)} g · ${kcalOf(per100, totalGrams).roundToInt()} kcal" +
            (if (servings != 1.0) " · ${fmt1(servings)} servings" else "") +
            (if (updateEntryIds.isNotEmpty()) " · updates ${updateEntryIds.size} diary ${if (updateEntryIds.size == 1) "entry" else "entries"}" else "") +
            " · " + ingredients.joinToString(", ") { "${it.name} ${fmt1(it.grams)} g" },
        kind = "save_recipe",
    )
    is Action.UpdateFood -> ProposalItem(
        if (name != oldName) "Rename $oldName → $name" else "Update $name",
        listOfNotNull(
            serving?.let { "${it.label} = ${fmt1(it.grams)} g" },
            nutrients?.let { n -> "${(n[N.ENERGY] ?: 0.0).roundToInt()} kcal per 100 g" },
            brand?.let { "brand $it" },
        ).joinToString(" · ").ifEmpty { "New name" },
        kind = "update_food",
    )
    is Action.DeleteFood -> ProposalItem("Delete food: $name", "Diary entries stay as they are", kind = "delete_food")
    is Action.UpdateProfile -> ProposalItem("Update targets", summary.joinToString(" · "), kind = "update_profile")
    is Action.DeleteEntry -> ProposalItem("Remove $foodName", "${kcal.roundToInt()} kcal", kind = "delete_entry")
    is Action.LogWeight -> ProposalItem(
        "Weight",
        (if (imperial) "${fmt1(weightKg / 0.45359237)} lb" else "${fmt1(weightKg)} kg") +
            (bodyFatPct?.let { " · ${fmt1(it)}% body fat" } ?: "") + dateSuffix(date, today),
        kind = "log_weight",
    )
    is Action.CreateFood -> ProposalItem(
        "New food: " + displayFoodName(name, brand),
        "${serving.label} = ${fmt1(serving.grams)} g · ${kcalOf(nutrients, serving.grams).roundToInt()} kcal per serving",
        kind = "create_food",
    )
}

/** Calories within this share of what the user gave count as a match. */
const val KCAL_CLOSE = 0.10

/** Total macros of the food a card logs, or null when it logs no food. */
fun proposalSummary(actions: List<Action>): String? {
    var p = 0.0; var c = 0.0; var f = 0.0; var any = false
    actions.forEach { a ->
        when (a) {
            is Action.LogFood -> {
                val g = a.servingGrams * a.quantity / 100
                p += (a.nutrients[N.PROTEIN] ?: 0.0) * g; c += (a.nutrients[N.CARBS] ?: 0.0) * g; f += (a.nutrients[N.FAT] ?: 0.0) * g; any = true
            }
            is Action.QuickAdd -> { p += a.nutrients[N.PROTEIN] ?: 0.0; c += a.nutrients[N.CARBS] ?: 0.0; f += a.nutrients[N.FAT] ?: 0.0; any = true }
            else -> Unit
        }
    }
    if (!any || actions.size < 2) return null
    return "P ${p.roundToInt()} g · C ${c.roundToInt()} g · F ${f.roundToInt()} g"
}

private fun amount(quantity: Double, label: String, grams: Double): String =
    if (label == "g") "${fmt1(quantity)} g" else "${fmt1(quantity)} × $label (${fmt1(grams * quantity)} g)"

private fun dateSuffix(date: String, today: String) = if (date == today) "" else " · $date"

fun proposalTitle(actions: List<Action>): String {
    val logs = actions.count { it is Action.LogFood || it is Action.QuickAdd }
    val kcal = actions.sumOf {
        when (it) {
            is Action.LogFood -> kcalOf(it.nutrients, it.servingGrams * it.quantity)
            is Action.QuickAdd -> it.nutrients[N.ENERGY] ?: 0.0
            else -> 0.0
        }
    }
    return when {
        actions.all { it is Action.LogFood || it is Action.QuickAdd } ->
            "Log $logs ${if (logs == 1) "item" else "items"} · ${kcal.roundToInt()} kcal"
        actions.all { it is Action.EditEntry } -> "Update ${actions.size} ${if (actions.size == 1) "entry" else "entries"}"
        actions.all { it is Action.DeleteEntry } -> "Remove ${actions.size} ${if (actions.size == 1) "entry" else "entries"}"
        actions.all { it is Action.LogWeight } -> "Log weight"
        actions.any { it is Action.CreateFood } -> if (logs > 0) "Create food and log it" else "Create food"
        actions.all { it is Action.SaveRecipe } -> if (actions.size == 1) "Save recipe" else "Save ${actions.size} recipes"
        actions.all { it is Action.UpdateProfile } -> "Update targets"
        actions.all { it is Action.UpdateFood || it is Action.DeleteFood } -> "Change your foods"
        else -> "Apply ${actions.size} changes"
    }
}

class ProposalStore(private val db: Database) {
    fun create(userId: String, conversationId: String, actions: List<Action>): String {
        val id = UUID.randomUUID().toString()
        transaction(db) {
            AiProposals.insert {
                it[AiProposals.id] = id
                it[AiProposals.userId] = userId
                it[AiProposals.conversationId] = conversationId
                it[AiProposals.actions] = actionJson.encodeToString(actionsSerializer, actions)
                it[status] = "pending"
                it[createdAt] = System.currentTimeMillis()
            }
        }
        return id
    }

    fun views(userId: String, ids: Collection<String>, today: String): Map<String, ProposalView> {
        if (ids.isEmpty()) return emptyMap()
        return transaction(db) {
            AiProposals.selectAll().where { (AiProposals.userId eq userId) and (AiProposals.id inList ids) }.associate { row ->
                val actions = actionJson.decodeFromString(actionsSerializer, row[AiProposals.actions])
                row[AiProposals.id] to view(row[AiProposals.id], actions, row[AiProposals.status], today)
            }
        }
    }

    /** The actions of a card that is still pending, or null. */
    fun pendingActions(userId: String, id: String): List<Action>? = transaction(db) {
        AiProposals.selectAll().where { (AiProposals.id eq id) and (AiProposals.userId eq userId) and (AiProposals.status eq "pending") }
            .singleOrNull()?.let { actionJson.decodeFromString(actionsSerializer, it[AiProposals.actions]) }
    }

    /** Replace the actions of a pending card. Returns false when it isn't pending any more. */
    fun replace(userId: String, id: String, actions: List<Action>): Boolean = transaction(db) {
        AiProposals.update({ (AiProposals.id eq id) and (AiProposals.userId eq userId) and (AiProposals.status eq "pending") }) {
            it[AiProposals.actions] = actionJson.encodeToString(actionsSerializer, actions)
            if (actions.isEmpty()) it[status] = "dismissed"
        } > 0
    }

    fun dismiss(userId: String, id: String, today: String): ProposalView {
        transaction(db) {
            val n = AiProposals.update({ (AiProposals.id eq id) and (AiProposals.userId eq userId) and (AiProposals.status eq "pending") }) {
                it[status] = "dismissed"
            }
            if (n == 0) ensureExists(userId, id)
        }
        return views(userId, listOf(id), today).getValue(id)
    }

    private fun ensureExists(userId: String, id: String) {
        val exists = AiProposals.selectAll().where { (AiProposals.id eq id) and (AiProposals.userId eq userId) }.any()
        if (!exists) throw ApiException(HttpStatusCode.NotFound, "Proposal not found")
    }

    /** Write the proposal's changes as sync records, so every device picks them up on its next sync. */
    fun apply(userId: String, id: String, today: String): ProposalView {
        val actions = transaction(db) {
            val row = AiProposals.selectAll().where { (AiProposals.id eq id) and (AiProposals.userId eq userId) }.forUpdate().singleOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Proposal not found")
            if (row[AiProposals.status] != "pending") return@transaction null
            AiProposals.update({ AiProposals.id eq id }) { it[status] = "applied" }
            actionJson.decodeFromString(actionsSerializer, row[AiProposals.actions])
        }
        if (actions != null) {
            try {
                val changes = toChanges(UserData(db, userId), actions)
                if (changes.isNotEmpty()) {
                    val undo = inverse(userId, changes)
                    sync(db, userId, SyncRequest(since = Long.MAX_VALUE, changes = changes))
                    transaction(db) {
                        AiProposalUndo.deleteWhere { AiProposalUndo.proposalId eq id }
                        AiProposalUndo.insert {
                            it[proposalId] = id
                            it[AiProposalUndo.userId] = userId
                            it[AiProposalUndo.changes] = actionJson.encodeToString(ListSerializer(UndoChange.serializer()), undo)
                            it[createdAt] = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: Exception) {
                transaction(db) { AiProposals.update({ AiProposals.id eq id }) { it[status] = "pending" } }
                throw e
            }
        }
        return views(userId, listOf(id), today).getValue(id)
    }

    /** For each change, the change that puts the record back as it is now: deleted if it's new, else its current data. */
    private fun inverse(userId: String, changes: List<SyncChange>): List<UndoChange> = transaction(db) {
        changes.map { c ->
            val row = Records.selectAll().where {
                (Records.userId eq userId) and (Records.collection eq c.collection) and (Records.recordId eq c.id)
            }.singleOrNull()
            val back = if (row == null) {
                val data = (c.data as? JsonObject)?.let { o -> if ("deleted" in o) JsonObject(o + ("deleted" to JsonPrimitive(true))) else o }
                c.copy(deleted = true, data = data)
            } else {
                SyncChange(c.collection, c.id, row[Records.updatedAt], row[Records.deleted], recordJson.parseToJsonElement(row[Records.data]))
            }
            UndoChange(back, c.updatedAt)
        }
    }

    /**
     * Put back what a confirmed card changed, and make the card pending again so it can be edited and confirmed.
     * A record changed since (on any device) is left as it is.
     */
    fun undo(userId: String, id: String, today: String): ProposalView {
        val undo = transaction(db) {
            val row = AiProposals.selectAll().where { (AiProposals.id eq id) and (AiProposals.userId eq userId) }.forUpdate().singleOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "Proposal not found")
            if (row[AiProposals.status] != "applied") throw ApiException(HttpStatusCode.Conflict, "This card isn't saved")
            val stored = AiProposalUndo.selectAll().where { AiProposalUndo.proposalId eq id }.singleOrNull()
                ?: throw ApiException(HttpStatusCode.Conflict, "This card can't be undone")
            val list = actionJson.decodeFromString(ListSerializer(UndoChange.serializer()), stored[AiProposalUndo.changes])
            var t = System.currentTimeMillis()
            val changes = list.filter { u ->
                val current = Records.selectAll().where {
                    (Records.userId eq userId) and (Records.collection eq u.change.collection) and (Records.recordId eq u.change.id)
                }.singleOrNull()
                current != null && current[Records.updatedAt] == u.appliedAt
            }.map { u -> t += 1; u.change.copy(updatedAt = t).withUpdatedAt(t) }
            AiProposals.update({ AiProposals.id eq id }) { it[status] = "pending" }
            AiProposalUndo.deleteWhere { AiProposalUndo.proposalId eq id }
            changes
        }
        if (undo.isNotEmpty()) sync(db, userId, SyncRequest(since = Long.MAX_VALUE, changes = undo))
        return views(userId, listOf(id), today).getValue(id)
    }

    private fun toChanges(data: UserData, actions: List<Action>): List<SyncChange> {
        var t = System.currentTimeMillis()
        val out = mutableListOf<SyncChange>()
        fun diary(r: DiaryRecord) = SyncChange("diary", r.id, r.updatedAt, r.deleted, recordJson.encodeToJsonElement(DiaryRecord.serializer(), r))
        fun food(r: FoodRecord) = SyncChange("foods", r.id, r.updatedAt, r.deleted, recordJson.encodeToJsonElement(FoodRecord.serializer(), r))
        var latestWeight: WeightRecord? = data.weights.maxByOrNull { it.date }
        var profileOverride: com.nutrime.app.model.Profile? = null
        for (a in actions) {
            t += 1
            when (a) {
                is Action.LogFood -> {
                    a.food?.takeIf { data.food(it.id) == null && out.none { c -> c.collection == "foods" && c.id == it.id } }
                        ?.let { out += food(it.copy(updatedAt = t)) }
                    val entryId = UUID.randomUUID().toString()
                    out += diary(DiaryRecord(
                        id = entryId, date = a.date, meal = a.meal, foodId = if (a.foodId.startsWith("quick:")) "quick:$entryId" else a.foodId,
                        foodName = a.foodName, brand = a.brand, servingLabel = a.servingLabel, servingGrams = a.servingGrams,
                        quantity = a.quantity, nutrientsJson = encodeNutrients(a.nutrients), createdAt = t, updatedAt = t,
                    ))
                }
                is Action.QuickAdd -> {
                    val id = UUID.randomUUID().toString()
                    out += diary(DiaryRecord(
                        id = id, date = a.date, meal = a.meal, foodId = "quick:$id",
                        foodName = if (a.grams == null && a.amount != null) "${a.name} (${a.amount})" else a.name,
                        // With grams, store per-100 g values like any food; otherwise one "serving" of 100 g holds the totals (as the app does).
                        servingLabel = if (a.grams != null) "g" else "serving",
                        servingGrams = if (a.grams != null) 1.0 else 100.0,
                        quantity = a.grams ?: 1.0,
                        nutrientsJson = encodeNutrients(a.grams?.let { g -> per100(a.nutrients, g) } ?: a.nutrients), createdAt = t, updatedAt = t,
                    ))
                }
                is Action.EditEntry -> {
                    val e = data.entry(a.entryId) ?: throw ApiException(HttpStatusCode.Conflict, "\"${a.foodName}\" was already removed from your diary")
                    var edited = e.copy(meal = a.meal, servingLabel = a.servingLabel, servingGrams = a.servingGrams, quantity = a.quantity,
                        date = a.date ?: e.date, updatedAt = t)
                    a.newFood?.let { f ->
                        f.food?.takeIf { data.food(it.id) == null && out.none { c -> c.collection == "foods" && c.id == it.id } }
                            ?.let { out += food(it.copy(updatedAt = t)) }
                        edited = edited.copy(foodId = f.foodId, foodName = f.name, brand = f.brand, nutrientsJson = encodeNutrients(f.nutrients))
                    }
                    out += diary(edited)
                }
                is Action.SaveRecipe -> {
                    val per100 = a.per100
                    val total = a.totalGrams.coerceAtLeast(0.0001)
                    out += food(FoodRecord(
                        id = a.foodId, kind = "recipe", name = a.name,
                        nutrientsJson = encodeNutrients(per100),
                        servingsJson = encodeServings(listOf(Serving("serving", total / a.servings.coerceAtLeast(0.0001)), Serving("whole recipe", total))),
                        ingredientsJson = encodeIngredients(a.ingredients), recipeServings = a.servings, updatedAt = t,
                    ))
                    a.updateEntryIds.forEach { id ->
                        val e = data.entry(id) ?: return@forEach
                        t += 1
                        out += diary(e.copy(foodName = a.name, nutrientsJson = encodeNutrients(per100), updatedAt = t))
                    }
                }
                is Action.UpdateFood -> {
                    val f = data.food(a.foodId) ?: throw ApiException(HttpStatusCode.Conflict, "\"${a.oldName}\" was already deleted")
                    out += food(f.copy(
                        name = a.name, brand = a.brand ?: f.brand,
                        servingsJson = a.serving?.let { encodeServings(listOf(it)) } ?: f.servingsJson,
                        nutrientsJson = a.nutrients?.let { encodeNutrients(it) } ?: f.nutrientsJson,
                        updatedAt = t,
                    ))
                }
                is Action.DeleteFood -> {
                    val f = data.food(a.foodId) ?: continue
                    out += food(f.copy(deleted = true, updatedAt = t))
                }
                is Action.UpdateProfile -> {
                    var p = profileOverride ?: data.profile
                    if (a.useSuggestedCalories) p = p.copy(calorieOverride = null)
                    a.calorieTarget?.let { p = p.copy(calorieOverride = it) }
                    a.goalKgPerWeek?.let { p = p.copy(goalKgPerWeek = it) }
                    if (a.proteinPct != null && a.carbsPct != null && a.fatPct != null) p = p.copy(proteinPct = a.proteinPct, carbsPct = a.carbsPct, fatPct = a.fatPct)
                    a.activity?.let { name -> com.nutrime.app.model.ActivityLevel.entries.firstOrNull { it.name == name }?.let { p = p.copy(activity = it) } }
                    p = applyProfileExtras(p, a)
                    if (a.burnMode == "ADAPTIVE" && p.adaptiveBurnKcal == null) {
                        data.burn?.takeIf { it.confidence != com.nutrime.app.model.Confidence.LEARNING }?.let {
                            p = p.copy(adaptiveBurnKcal = it.burnKcal, adaptiveBurnDate = java.time.LocalDate.now().toString())
                        }
                    }
                    profileOverride = p.copy(configured = true)
                }
                is Action.DeleteEntry -> {
                    val e = data.entry(a.entryId) ?: continue
                    out += diary(e.copy(deleted = true, updatedAt = t))
                }
                is Action.LogWeight -> {
                    val w = WeightRecord(UUID.randomUUID().toString(), a.date, a.weightKg, a.bodyFatPct, updatedAt = t)
                    out += SyncChange("weights", w.id, t, false, recordJson.encodeToJsonElement(WeightRecord.serializer(), w))
                    if (latestWeight == null || w.date >= latestWeight.date) latestWeight = w
                }
                is Action.CreateFood -> out += food(FoodRecord(
                    id = a.foodId, kind = "custom", name = a.name, brand = a.brand,
                    nutrientsJson = encodeNutrients(a.nutrients), servingsJson = encodeServings(listOf(a.serving)), updatedAt = t,
                ))
            }
        }
        // Like the app: the profile's weight (used for calorie targets) follows the latest weigh-in.
        val lw = latestWeight
        val newWeight = lw?.takeIf { actions.any { it is Action.LogWeight } && it.weightKg != data.profile.weightKg }
        if (newWeight != null || profileOverride != null) {
            t += 1
            val p = (profileOverride ?: data.profile).let { if (newWeight != null) it.copy(weightKg = newWeight.weightKg) else it }
            val rec = ProfileRecord(json = recordJson.encodeToString(com.nutrime.app.model.Profile.serializer(), p), updatedAt = t)
            out += SyncChange("profile", "profile", t, false, recordJson.encodeToJsonElement(ProfileRecord.serializer(), rec))
        }
        return out
    }
}

fun view(id: String, actions: List<Action>, status: String, today: String) =
    ProposalView(id, proposalTitle(actions), actions.map { it.item(today) }, status, proposalSummary(actions))

/** A change that undoes part of a confirmed card, and the time the card wrote that record. */
@Serializable
data class UndoChange(val change: SyncChange, val appliedAt: Long)

/** Records carry their own updatedAt in the data too; keep both the same. */
private fun SyncChange.withUpdatedAt(t: Long): SyncChange {
    val o = data as? JsonObject ?: return this
    return if ("updatedAt" in o) copy(data = JsonObject(o + ("updatedAt" to JsonPrimitive(t)))) else this
}

/** Per-100 g nutrients from amounts in one serving. */
fun per100(perServing: NutrientMap, servingGrams: Double): Map<Int, Double> =
    perServing.mapValues { it.value * 100.0 / servingGrams }

/** Goal weight, protein per kg and burn settings from an UpdateProfile action. */
fun applyProfileExtras(p0: com.nutrime.app.model.Profile, a: Action.UpdateProfile): com.nutrime.app.model.Profile {
    var p = p0
    if (a.clearGoalWeight) p = p.copy(goalWeightKg = null)
    a.goalWeightKg?.let { p = p.copy(goalWeightKg = it) }
    a.proteinGPerKg?.let { p = p.copy(proteinGPerKg = it) }
    a.proteinMode?.let { m -> com.nutrime.app.model.ProteinMode.entries.firstOrNull { it.name == m }?.let { p = p.copy(proteinMode = it) } }
    a.manualBurnKcal?.let { p = p.copy(manualBurnKcal = it) }
    a.burnMode?.let { m ->
        com.nutrime.app.model.BurnMode.entries.firstOrNull { it.name == m }?.let { p = p.copy(burnMode = it, burnModeChosen = true) }
    }
    return p
}
