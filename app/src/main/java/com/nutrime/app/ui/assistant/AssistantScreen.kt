package com.nutrime.app.ui.assistant

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.nutrime.app.data.remote.ProposalItem
import com.nutrime.app.data.remote.ProposalItemEdit
import com.nutrime.app.ui.components.NTextField
import com.nutrime.app.ui.components.Segmented
import com.nutrime.app.ui.components.formatQty
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.nutrime.app.data.AssistantState
import com.nutrime.app.data.remote.ChatBlock
import com.nutrime.app.data.remote.ChatMessage
import com.nutrime.app.data.remote.Proposal
import com.nutrime.app.ui.Routes
import com.nutrime.app.ui.SubPage
import com.nutrime.app.ui.appContainer
import com.nutrime.app.ui.components.ButtonKind
import com.nutrime.app.ui.components.ButtonSize
import com.nutrime.app.ui.components.EmptyState
import com.nutrime.app.ui.components.Hairline
import com.nutrime.app.ui.components.NButton
import com.nutrime.app.ui.components.NChip
import com.nutrime.app.ui.components.NIconButton
import com.nutrime.app.ui.components.Spinner
import com.nutrime.app.ui.theme.DesignTokens.Radius
import com.nutrime.app.ui.theme.DesignTokens.Space
import com.nutrime.app.ui.theme.Lucide
import com.nutrime.app.ui.theme.NutriTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val MAX_PHOTOS = 4


/** Chat with the assistant: ask about your data, or describe/photograph food to log it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(nav: NavController, prompt: String? = null) {
    val container = appContainer()
    val session = container.assistant
    val state by session.state.collectAsStateWithLifecycle()
    val account by container.settings.account.collectAsStateWithLifecycle(initialValue = null)
    var showHistory by remember { mutableStateOf(false) }
    // Opened with a prepared question (the weekly review): ask it once, in a new chat.
    var asked by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prompt, account?.loggedIn, state.status?.enabled) {
        if (!asked && !prompt.isNullOrBlank() && account?.loggedIn == true && state.status?.enabled != false) {
            asked = true
            session.newChat()
            session.send(prompt, emptyList())
        }
    }

    LaunchedEffect(account?.loggedIn) { if (account?.loggedIn == true) session.refresh() }

    SubPage(
        title = state.title.orEmpty(),
        onBack = { nav.popBackStack() },
        actions = {
            if (account?.loggedIn == true) {
                NIconButton(Lucide.MessagesSquare, "Past chats", { showHistory = true })
                NIconButton(Lucide.SquarePen, "New chat", { session.newChat() }, enabled = state.messages.isNotEmpty() || state.conversationId != null)
            }
        },
        plain = true,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(NutriTheme.colors.surface)) {
            when {
                account == null -> Unit
                account?.loggedIn != true -> EmptyState(
                    null, "Sign in to use the assistant",
                    "The assistant runs on your NutriMe server and works with your synced diary.",
                ) { NButton("Sync & account", { nav.navigate(Routes.ACCOUNT) }, kind = ButtonKind.Primary) }
                state.status?.enabled == false -> EmptyState(
                    null, "The assistant isn't set up yet",
                    "Add a GEMINI_API_KEY to your NutriMe server's environment and redeploy it.",
                )
                else -> Chat(
                    state, onSend = session::send, onStop = session::stop, onConfirm = session::confirm, onDismiss = session::dismiss,
                    onEdit = session::editItem, onUndo = session::undo, onClearError = session::clearError,
                )
            }
        }
    }

    if (showHistory) {
        HistorySheet(
            state,
            onDismiss = { showHistory = false },
            onOpen = { showHistory = false; session.open(it) },
            onDelete = { session.delete(it) },
        )
    }
}

private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when {
        h < 5 -> "Late snack?"
        h < 11 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
private fun Chat(
    state: AssistantState,
    onSend: (String, List<com.nutrime.app.data.remote.ChatImage>) -> Unit,
    onStop: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onEdit: (String, Int, ProposalItemEdit, (Boolean) -> Unit) -> Unit,
    onUndo: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val c = NutriTheme.colors
    // The newest message is item 0 of a reversed list, so the chat opens at the bottom without scrolling there.
    val list = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty() && list.firstVisibleItemIndex > 0) list.animateScrollToItem(0) }
    var text by rememberSaveable { mutableStateOf("") }
    val images = remember { mutableStateListOf<PendingImage>() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading) {
                Spinner(Modifier.size(22.dp).align(Alignment.Center))
            } else if (state.messages.isEmpty()) {
                Text(
                    greeting(), style = NutriTheme.type.title.copy(fontSize = 28.sp, fontWeight = FontWeight.Medium), color = c.text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = Space.S6).padding(bottom = Space.S8),
                )
            } else {
                val msgs = state.messages
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Space.S4, vertical = Space.S4),
                    verticalArrangement = Arrangement.spacedBy(Space.S5, Alignment.Bottom),
                ) {
                    item(key = "error") {
                        state.error?.let { err ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.Md)).background(c.dangerSoft).clickable(onClick = onClearError)
                                    .padding(horizontal = Space.S3, vertical = Space.S2),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(err, style = NutriTheme.type.small, color = c.danger, modifier = Modifier.weight(1f))
                                Icon(Lucide.X, "Dismiss", tint = c.danger, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    items(msgs.size, key = { msgs.lastIndex - it }) { r ->
                        val i = msgs.lastIndex - r
                        val m = msgs[i]
                        val live = state.streaming && i == msgs.lastIndex
                        if (m.isUser) UserMessage(m) else AssistantMessage(m, live, state.busyProposals, onConfirm, onDismiss, onEdit, onUndo)
                    }
                }
            }
        }
        Composer(
            text = text,
            onText = { text = it },
            images = images,
            streaming = state.streaming,
            empty = state.messages.isEmpty(),
            onSend = {
                onSend(text, images.map { it.image })
                text = ""
                images.clear()
            },
            onStop = onStop,
        )
    }
}

@Composable
private fun UserMessage(m: ChatMessage) {
    val c = NutriTheme.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        val imgs = m.blocks.filterIsInstance<ChatBlock.Image>()
        if (imgs.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                imgs.forEach { img ->
                    val bmp = remember(img.data) { decodeBase64Image(img.data) }
                    if (bmp != null) Image(bmp, "Photo", Modifier.size(96.dp).clip(RoundedCornerShape(Radius.Lg)), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.height(Space.S1))
        }
        m.blocks.filterIsInstance<ChatBlock.Text>().forEach { t ->
            Text(
                t.text, style = NutriTheme.type.body, color = c.text,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(Radius.Xl)).background(c.accentSoft)
                    .padding(horizontal = Space.S3, vertical = Space.S2),
            )
        }
    }
}

/** One steps row for the whole reply, then the text, then the message's card. */
@Composable
private fun AssistantMessage(
    m: ChatMessage,
    live: Boolean,
    busy: Set<String>,
    onConfirm: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onEdit: (String, Int, ProposalItemEdit, (Boolean) -> Unit) -> Unit,
    onUndo: (String) -> Unit,
) {
    val c = NutriTheme.colors
    val steps = m.blocks.filterIsInstance<ChatBlock.Tool>()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S2)) {
        if (steps.isNotEmpty()) ToolSteps(steps, running = live && m.blocks.last() is ChatBlock.Tool)
        m.blocks.filterIsInstance<ChatBlock.Text>().forEach { b -> Markdown(b.text, NutriTheme.type.body, c.text, c.bgSunken) }
        m.blocks.filterIsInstance<ChatBlock.ProposalCard>().forEach { b ->
            ProposalCard(b.proposal, b.proposal.id in busy, onConfirm, onDismiss, onUndo = onUndo) { index, edit, done -> onEdit(b.proposal.id, index, edit, done) }
        }
        if (live && m.blocks.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(Modifier.size(12.dp), c.textTertiary)
                Spacer(Modifier.width(Space.S2))
                Text("Thinking…", style = NutriTheme.type.caption, color = c.textTertiary)
            }
        }
    }
}

/** A short summary of what a run of steps did, e.g. "Looked up foods, checked your diary · 4 steps". */
private fun stepsSummary(steps: List<ChatBlock.Tool>): String {
    if (steps.size == 1) return steps[0].label
    val names = steps.map { it.name }.toSet()
    val did = buildList {
        if (names.any { it in setOf("search_foods", "get_food", "get_frequent_foods") }) add("looked up foods")
        if (names.any { it in setOf("get_day", "get_nutrient_report", "get_weight_history") }) add("checked your diary")
        if (names.any { it.startsWith("propose_") }) add("prepared changes")
    }.ifEmpty { listOf("worked") }
    return did.joinToString(", ").replaceFirstChar { it.uppercase() } + " · ${steps.size} steps"
}

@Composable
private fun ToolSteps(steps: List<ChatBlock.Tool>, running: Boolean) {
    val c = NutriTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(Radius.Sm)).clickable(enabled = steps.size > 1) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) Spinner(Modifier.size(12.dp), c.textTertiary) else Icon(Lucide.Check, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(Space.S2))
            val label = when {
                !running -> stepsSummary(steps)
                steps.size > 1 -> "${steps.last().label} · step ${steps.size}"
                else -> steps.last().label
            }
            Text(label, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (steps.size > 1) {
                Spacer(Modifier.width(Space.S1))
                Icon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown, null, tint = c.textTertiary, modifier = Modifier.size(12.dp))
            }
        }
        if (expanded) {
            steps.forEach { s ->
                Text(s.label, style = NutriTheme.type.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp))
            }
        }
    }
}

private val MEAL_NAMES = listOf("Breakfast", "Lunch", "Dinner", "Snacks")
private const val COLLAPSE_AFTER = 6

private fun badgeLabel(b: String) = when (b) { "substitute" -> "Substitute"; "estimate" -> "Estimate"; "match" -> "Matches"; "differs" -> "Check"; else -> b }

@Composable
private fun Badge(badge: String) {
    val c = NutriTheme.colors
    val good = badge == "match"
    val color = if (good) c.accent else c.warning
    Text(
        badgeLabel(badge), style = NutriTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** The message's card: what the assistant wants to save. Tap a row to change or remove it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProposalCard(
    p: Proposal,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onUndo: (String) -> Unit = {},
    onEdit: (Int, ProposalItemEdit, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
) {
    val c = NutriTheme.colors
    val shape = RoundedCornerShape(Radius.Lg)
    val pending = p.status == "pending"
    var expanded by remember(p.id) { mutableStateOf(false) }
    var editing by remember(p.id) { mutableStateOf<Int?>(null) }
    val rows = p.items.withIndex().toList()
    val shown = if (expanded || rows.size <= COLLAPSE_AFTER) rows else rows.take(COLLAPSE_AFTER - 1)
    val meals = rows.mapNotNull { it.value.meal }.toSet()
    val single = meals.singleOrNull()?.let { MEAL_NAMES.getOrNull(it) }
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.borderStrong, shape).background(c.surfaceRaised)) {
        Column(Modifier.padding(start = Space.S3, end = Space.S3, top = Space.S3, bottom = Space.S1)) {
            Text(p.title, style = NutriTheme.type.bodyMedium, color = c.text)
            listOfNotNull(single, p.summary).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                Text(it, style = NutriTheme.type.small, color = c.textSecondary)
            }
        }
        var lastMeal: Int? = -1
        shown.forEach { (index, item) ->
            if (meals.size > 1 && item.meal != lastMeal) {
                lastMeal = item.meal
                item.meal?.let { MEAL_NAMES.getOrNull(it) }?.let {
                    Text(it.uppercase(), style = NutriTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textTertiary,
                        modifier = Modifier.padding(start = Space.S3, end = Space.S3, top = Space.S2, bottom = 2.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().clickable(enabled = pending && !busy) { editing = index }.padding(horizontal = Space.S3, vertical = 6.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    Text(item.name ?: item.title, style = NutriTheme.type.body, color = c.text)
                    item.badge?.let { Badge(it) }
                }
                val food = item.kind == "log_food" || item.kind == "quick_add"
                val detail = if (food) listOfNotNull(item.amount, item.kcal?.let { "${it.roundToInt()} kcal" },
                    if (meals.size == 1) null else item.meal?.let { MEAL_NAMES.getOrNull(it) }).joinToString(" · ") else item.detail
                Text(detail, style = NutriTheme.type.small, color = c.textSecondary)
                item.note?.let {
                    Text(it, style = NutriTheme.type.caption, color = if (item.badge == "match" || item.badge == null) c.textTertiary else c.warning)
                }
            }
        }
        if (rows.size > shown.size) {
            Text(
                "Show ${rows.size - shown.size} more", style = NutriTheme.type.small, color = c.accent,
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = Space.S3, vertical = Space.S2),
            )
        }
        Hairline(Modifier.padding(top = Space.S2))
        Row(Modifier.fillMaxWidth().padding(Space.S2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
            when (p.status) {
                "applied" -> {
                    Icon(Lucide.Check, null, tint = c.accent, modifier = Modifier.padding(start = Space.S1).size(16.dp))
                    Text("Saved to your diary", style = NutriTheme.type.small, color = c.accent, modifier = Modifier.weight(1f))
                    NButton("Undo", { onUndo(p.id) }, kind = ButtonKind.Ghost, size = ButtonSize.Sm, loading = busy)
                }
                "dismissed" -> Text("Dismissed", style = NutriTheme.type.small, color = c.textTertiary, modifier = Modifier.padding(start = Space.S1))
                else -> {
                    Text("Tap an item to change it", style = NutriTheme.type.caption, color = c.textTertiary, modifier = Modifier.weight(1f).padding(start = Space.S1))
                    NButton("Dismiss", { onDismiss(p.id) }, kind = ButtonKind.Ghost, size = ButtonSize.Sm, enabled = !busy)
                    NButton("Confirm", { onConfirm(p.id) }, kind = ButtonKind.Primary, size = ButtonSize.Sm, icon = Lucide.Check, loading = busy)
                }
            }
        }
    }
    editing?.let { index ->
        p.items.getOrNull(index)?.let { item ->
            ItemEditorSheet(item, busy, onClose = { editing = null }) { edit -> onEdit(index, edit) { ok -> if (ok) editing = null } }
        }
    }
}

/** Change one row of a card: amount, meal, another match, or remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditorSheet(item: ProposalItem, busy: Boolean, onClose: () -> Unit, onSave: (ProposalItemEdit) -> Unit) {
    val c = NutriTheme.colors
    val byGrams = item.servingLabel == "g"
    var amount by remember { mutableStateOf(item.quantity?.let { formatQty(Math.round(it * 100) / 100.0) }.orEmpty()) }
    var meal by remember { mutableStateOf(item.meal ?: 0) }
    var foodId by remember { mutableStateOf<String?>(null) }
    val canAmount = item.editable && item.quantity != null
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Space.S5).padding(bottom = Space.S4),
            verticalArrangement = Arrangement.spacedBy(Space.S4),
        ) {
            Column {
                Text(item.name ?: item.title, style = NutriTheme.type.cardTitle, color = c.text)
                listOfNotNull(item.source, item.kcal?.let { "${it.roundToInt()} kcal" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = NutriTheme.type.small, color = c.textSecondary)
                }
                item.note?.let { Text(it, style = NutriTheme.type.small, color = if (item.badge == "match") c.textTertiary else c.warning, modifier = Modifier.padding(top = Space.S1)) }
            }
            if (canAmount) {
                NTextField(
                    amount, { amount = it }, label = "Amount", suffix = if (byGrams) "g" else "× ${item.servingLabel}",
                    keyboardType = KeyboardType.Decimal,
                )
            }
            if (item.editable && item.meal != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Meal", style = NutriTheme.type.small.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
                    Segmented(MEAL_NAMES.mapIndexed { i, n -> i to n }, meal, { meal = it }, fill = true)
                }
            }
            if (item.alternatives.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Other matches", style = NutriTheme.type.small.copy(fontWeight = FontWeight.Medium), color = c.textSecondary)
                    item.alternatives.forEach { a ->
                        val on = foodId == a.foodId
                        val shape = RoundedCornerShape(Radius.Md)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(if (on) c.accentSoft else Color.Transparent)
                                .border(1.dp, if (on) c.accent else c.border, shape)
                                .clickable { foodId = if (on) null else a.foodId }.padding(horizontal = Space.S3, vertical = Space.S2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(a.name, style = NutriTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
                            Text("${a.kcal.roundToInt()} kcal", style = NutriTheme.type.small, color = c.textSecondary)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.S2), verticalAlignment = Alignment.CenterVertically) {
                NButton("Remove", { onSave(ProposalItemEdit(remove = true)) }, kind = ButtonKind.Danger, icon = Lucide.Trash2, enabled = !busy)
                Spacer(Modifier.weight(1f))
                NButton("Save", {
                    val q = amount.replace(',', '.').toDoubleOrNull()
                    val changedAmount = canAmount && q != null && q > 0 && kotlin.math.abs(q - (item.quantity ?: 0.0)) > 1e-9
                    val edit = ProposalItemEdit(
                        meal = meal.takeIf { item.editable && item.meal != null && it != item.meal },
                        quantity = q.takeIf { changedAmount && !byGrams },
                        grams = q.takeIf { changedAmount && byGrams },
                        foodId = foodId,
                    )
                    if (edit == ProposalItemEdit()) onClose() else onSave(edit)
                }, kind = ButtonKind.Primary, icon = Lucide.Check, loading = busy)
            }
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    images: MutableList<PendingImage>,
    streaming: Boolean,
    empty: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = NutriTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var menu by remember { mutableStateOf(false) }
    fun add(uris: List<Uri>) = scope.launch {
        uris.take(MAX_PHOTOS - images.size).forEach { uri -> encodeImage(context, uri)?.let { images += it } }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> cameraUri?.takeIf { ok }?.let { add(listOf(it)) } }
    val canSend = !streaming && (text.isNotBlank() || images.isNotEmpty())
    val shape = RoundedCornerShape(20.dp)

    Box(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = Space.S3, vertical = Space.S2)) {
        Column(Modifier.fillMaxWidth().clip(shape).background(c.surfaceRaised).border(1.dp, c.borderStrong, shape).padding(start = Space.S3, end = Space.S2, top = Space.S3, bottom = Space.S2)) {
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = Space.S2), horizontalArrangement = Arrangement.spacedBy(Space.S2)) {
                    images.toList().forEach { img ->
                        Box {
                            Image(img.preview, "Attached photo", Modifier.size(64.dp).clip(RoundedCornerShape(Radius.Md)), contentScale = ContentScale.Crop)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(c.text.copy(alpha = 0.7f)).clickable { images.remove(img) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Lucide.X, "Remove photo", tint = c.surface, modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = NutriTheme.type.body.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(end = Space.S1),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) Text(if (empty) "Describe a meal or ask anything" else "Reply…", style = NutriTheme.type.body, color = c.textTertiary, maxLines = 1)
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = Space.S2), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    NIconButton(Lucide.Plus, "Add photos", { menu = true }, enabled = images.size < MAX_PHOTOS, size = 32.dp)
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(Radius.Lg), containerColor = c.surfaceRaised) {
                        DropdownMenuItem(
                            text = { Text("Take a photo", style = NutriTheme.type.body) },
                            leadingIcon = { Icon(Lucide.Camera, null, Modifier.size(16.dp), tint = c.textSecondary) },
                            onClick = {
                                menu = false
                                val uri = newCameraUri(context)
                                cameraUri = uri
                                camera.launch(uri)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Choose photos", style = NutriTheme.type.body) },
                            leadingIcon = { Icon(Lucide.ImagePlus, null, Modifier.size(16.dp), tint = c.textSecondary) },
                            onClick = {
                                menu = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                val (icon, label, enabled) = if (streaming) Triple(Lucide.Square, "Stop", true) else Triple(Lucide.ArrowUp, "Send", canSend)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(if (enabled) c.accent else c.surfaceActive)
                        .clickable(enabled = enabled) { if (streaming) onStop() else onSend() },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, label, tint = if (enabled) c.onAccent else c.textTertiary, modifier = Modifier.size(if (streaming) 12.dp else 16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(state: AssistantState, onDismiss: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    val c = NutriTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        shape = RoundedCornerShape(topStart = Radius.Xl, topEnd = Radius.Xl),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = Space.S3)) {
            Text("Past chats", style = NutriTheme.type.cardTitle, color = c.text, modifier = Modifier.padding(horizontal = Space.S5, vertical = Space.S2))
            if (state.conversations.isEmpty()) {
                Text("No chats yet", style = NutriTheme.type.small, color = c.textSecondary, modifier = Modifier.padding(horizontal = Space.S5, vertical = Space.S4))
            }
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(state.conversations, key = { _, it -> it.id }) { _, conv ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(conv.id) }.padding(start = Space.S5, end = Space.S2, top = Space.S2, bottom = Space.S2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(conv.title, style = NutriTheme.type.body, color = if (conv.id == state.conversationId) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(conv.updatedAt)), style = NutriTheme.type.caption, color = c.textTertiary)
                        }
                        NIconButton(Lucide.Trash2, "Delete chat", { onDelete(conv.id) })
                    }
                }
            }
        }
    }
}
