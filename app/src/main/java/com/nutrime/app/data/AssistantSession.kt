package com.nutrime.app.data

import com.nutrime.app.BuildConfig
import com.nutrime.app.data.remote.AiStatus
import com.nutrime.app.data.remote.AssistantClient
import com.nutrime.app.data.remote.ChatBlock
import com.nutrime.app.data.remote.ChatEvent
import com.nutrime.app.data.remote.ChatImage
import com.nutrime.app.data.remote.ChatMessage
import com.nutrime.app.data.remote.ChatRequest
import com.nutrime.app.data.remote.ConversationSummary
import com.nutrime.app.data.remote.HttpException
import com.nutrime.app.data.remote.Proposal
import com.nutrime.app.data.remote.ProposalItemEdit
import com.nutrime.app.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AssistantState(
    val conversationId: String? = null,
    val title: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    /** A reply is streaming in. */
    val streaming: Boolean = false,
    /** The current step while streaming, e.g. "Searching foods…". */
    val activity: String? = null,
    val error: String? = null,
    val status: AiStatus? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val loading: Boolean = false,
    /** Proposal ids being confirmed or dismissed. */
    val busyProposals: Set<String> = emptySet(),
)

/**
 * The assistant chat, kept in the app container so a reply keeps streaming while you look at
 * other screens. The server runs the agent; this holds the conversation and talks to it.
 */
class AssistantSession(
    private val settings: SettingsStore,
    private val sync: SyncManager,
    private val client: AssistantClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state
    private var job: Job? = null
    private val base = BuildConfig.SYNC_SERVER_URL

    private fun today() = LocalDate.now().toString()

    private suspend fun token(): String? = settings.current().token

    fun refresh() {
        scope.launch {
            val token = token() ?: return@launch
            runCatching { client.status(base, token) }.onSuccess { s -> _state.update { it.copy(status = s) } }
            runCatching { client.conversations(base, token) }.onSuccess { list -> _state.update { it.copy(conversations = list) } }
        }
    }

    fun newChat() {
        job?.cancel()
        _state.update { AssistantState(status = it.status, conversations = it.conversations) }
    }

    fun open(id: String) {
        job?.cancel()
        _state.update { AssistantState(conversationId = id, loading = true, status = it.status, conversations = it.conversations) }
        scope.launch {
            val token = token() ?: return@launch
            runCatching { client.conversation(base, token, id, today()) }
                .onSuccess { msgs ->
                    val title = _state.value.conversations.firstOrNull { it.id == id }?.title
                    _state.update { if (it.conversationId == id) it.copy(messages = msgs, title = title, loading = false) else it }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = message(e)) } }
        }
    }

    fun delete(id: String) {
        scope.launch {
            val token = token() ?: return@launch
            runCatching { client.delete(base, token, id) }
            if (_state.value.conversationId == id) newChat()
            _state.update { s -> s.copy(conversations = s.conversations.filter { it.id != id }) }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Screenshot tests: show a canned conversation. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(state: AssistantState) {
        _state.value = state
    }

    fun send(text: String, images: List<ChatImage>) {
        if (_state.value.streaming || (text.isBlank() && images.isEmpty())) return
        val userBlocks = images.map { ChatBlock.Image(it.mimeType, it.data) } + listOfNotNull(text.trim().takeIf { it.isNotEmpty() }?.let { ChatBlock.Text(it) })
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage("user", userBlocks) + ChatMessage("assistant", emptyList()),
                streaming = true, activity = null, error = null,
            )
        }
        job = scope.launch {
            try {
                val token = token() ?: throw IllegalStateException("Sign in to use the assistant")
                // Push unsynced changes first, so the assistant sees what was just logged.
                sync.syncNow()
                val request = ChatRequest(
                    conversationId = _state.value.conversationId,
                    text = text.trim(),
                    images = images,
                    today = today(),
                    localTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                    country = java.util.Locale.getDefault().country.takeIf { it.length == 2 },
                )
                client.chat(base, token, request).collect { e -> handle(e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = message(e)) }
            } finally {
                _state.update { s ->
                    // Drop an assistant message that never got content.
                    val msgs = if (s.messages.lastOrNull()?.let { !it.isUser && it.blocks.isEmpty() } == true) s.messages.dropLast(1) else s.messages
                    s.copy(messages = msgs, streaming = false, activity = null)
                }
                refresh()
            }
        }
    }

    private fun handle(e: ChatEvent) {
        when (e) {
            is ChatEvent.Conversation -> _state.update { it.copy(conversationId = e.id, title = e.title) }
            is ChatEvent.Text -> appendToReply { blocks ->
                val last = blocks.lastOrNull()
                if (last is ChatBlock.Text) blocks.dropLast(1) + ChatBlock.Text(last.text + e.delta) else blocks + ChatBlock.Text(e.delta)
            }.also { _state.update { it.copy(activity = null) } }
            is ChatEvent.Tool -> {
                appendToReply { it + ChatBlock.Tool(e.name, e.label) }
                _state.update { it.copy(activity = e.label) }
            }
            // One card per message: replace it if it's already shown.
            is ChatEvent.ProposalEvent -> appendToReply { blocks ->
                if (blocks.any { it is ChatBlock.ProposalCard && it.proposal.id == e.proposal.id }) {
                    blocks.map { if (it is ChatBlock.ProposalCard && it.proposal.id == e.proposal.id) ChatBlock.ProposalCard(e.proposal) else it }
                } else blocks + ChatBlock.ProposalCard(e.proposal)
            }
            is ChatEvent.Error -> _state.update { it.copy(error = e.message) }
            ChatEvent.Done -> Unit
        }
    }

    private fun appendToReply(change: (List<ChatBlock>) -> List<ChatBlock>) = _state.update { s ->
        val last = s.messages.lastOrNull()
        if (last == null || last.isUser) s else s.copy(messages = s.messages.dropLast(1) + last.copy(blocks = change(last.blocks)))
    }

    /** Change one row of a pending card; [onDone] gets true when it worked (errors are shown in the chat). */
    fun editItem(proposalId: String, index: Int, edit: ProposalItemEdit, onDone: (Boolean) -> Unit = {}) {
        if (proposalId in _state.value.busyProposals) return
        _state.update { it.copy(busyProposals = it.busyProposals + proposalId) }
        scope.launch {
            val ok = try {
                val token = token() ?: throw IllegalStateException("Sign in to use the assistant")
                replaceProposal(client.editItem(base, token, proposalId, index, edit, today()))
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = message(e)) }
                false
            } finally {
                _state.update { it.copy(busyProposals = it.busyProposals - proposalId) }
            }
            onDone(ok)
        }
    }

    fun confirm(proposalId: String) = resolve(proposalId, apply = true)
    fun dismiss(proposalId: String) = resolve(proposalId, apply = false)
    /** Take a confirmed card's changes back out of the diary; the card becomes pending again. */
    fun undo(proposalId: String) = resolve(proposalId, apply = false, undo = true)

    private fun resolve(proposalId: String, apply: Boolean, undo: Boolean = false) {
        if (proposalId in _state.value.busyProposals) return
        _state.update { it.copy(busyProposals = it.busyProposals + proposalId) }
        scope.launch {
            try {
                val token = token() ?: return@launch
                val updated = when {
                    undo -> client.undo(base, token, proposalId, today())
                    apply -> client.apply(base, token, proposalId, today())
                    else -> client.dismiss(base, token, proposalId, today())
                }
                replaceProposal(updated)
                // Pull the changes into the diary right away.
                if (apply || undo) sync.syncNow()
            } catch (e: Exception) {
                _state.update { it.copy(error = message(e)) }
            } finally {
                _state.update { it.copy(busyProposals = it.busyProposals - proposalId) }
            }
        }
    }

    private fun replaceProposal(p: Proposal) = _state.update { s ->
        s.copy(messages = s.messages.map { m ->
            m.copy(blocks = m.blocks.map { b -> if (b is ChatBlock.ProposalCard && b.proposal.id == p.id) ChatBlock.ProposalCard(p) else b })
        })
    }

    private fun message(e: Throwable): String = when (e) {
        is HttpException -> e.message ?: "Server error"
        is java.io.IOException -> "Couldn't reach the server. Check your connection."
        else -> e.message ?: "Something went wrong"
    }
}
