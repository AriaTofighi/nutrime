package com.nutrime.server

import com.nutrime.server.food.CatalogFoods
import com.nutrime.server.food.CatalogQueries
import com.nutrime.server.food.ProviderBudgets
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction

object Users : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 320).uniqueIndex()
    val passwordHash = varchar("password_hash", 100)
    val createdAt = long("created_at")
    /** Monotonic per-user change counter used as the sync cursor. */
    val seq = long("seq").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Records : Table("records") {
    val userId = varchar("user_id", 36).references(Users.id)
    val collection = varchar("collection", 32)
    val recordId = varchar("record_id", 200)
    val data = text("data")
    val updatedAt = long("updated_at")
    val deleted = bool("deleted").default(false)
    val seq = long("seq")
    override val primaryKey = PrimaryKey(userId, collection, recordId)

    init {
        index(false, userId, seq)
    }
}

/** AI assistant conversations. */
object AiConversations : Table("ai_conversations") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val title = varchar("title", 200)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId, updatedAt)
    }
}

/**
 * One Gemini `Content` per row, stored verbatim (including thought signatures, which Gemini
 * requires back on later turns). kind: "user" (a message typed by the user), "model", or "tool"
 * (function responses sent back to the model).
 */
object AiMessages : Table("ai_messages") {
    val id = long("id").autoIncrement()
    val conversationId = varchar("conversation_id", 36).references(AiConversations.id)
    val userId = varchar("user_id", 36).references(Users.id)
    val kind = varchar("kind", 8)
    val content = text("content")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, conversationId, id)
        index(false, userId, kind, createdAt)
    }
}

/** Changes the assistant proposed; nothing is written to the user's data until they confirm. */
object AiProposals : Table("ai_proposals") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val conversationId = varchar("conversation_id", 36).references(AiConversations.id)
    val actions = text("actions")
    /** "pending", "applied" or "dismissed". */
    val status = varchar("status", 16)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** What a confirmed card changed, as the sync changes that put each record back (see ProposalStore.undo). */
object AiProposalUndo : Table("ai_proposal_undo") {
    val proposalId = varchar("proposal_id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val changes = text("changes")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(proposalId)
}

fun connectDatabase(config: Config): Database {
    val hikari = HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        config.databaseUser?.let { username = it }
        config.databasePassword?.let { password = it }
        maximumPoolSize = 5
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        initializationFailTimeout = 60_000
    }
    val db = Database.connect(HikariDataSource(hikari))
    transaction(db) { SchemaUtils.create(Users, Invitations, Records, AiConversations, AiMessages, AiProposals, AiProposalUndo, CatalogFoods, CatalogQueries, ProviderBudgets) }
    return db
}
