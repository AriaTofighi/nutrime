package com.nutrime.server

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** Resolve access from the signed-in user's stored account, never from client-supplied email. */
class AdminAccess(private val config: Config, private val db: Database) {
    fun isAdmin(userId: String): Boolean {
        if (config.adminEmails.isEmpty()) return false
        val email = transaction(db) {
            Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.email)
        }
        return email?.lowercase() in config.adminEmails
    }

    fun requireAdmin(userId: String) {
        if (!isAdmin(userId)) throw ApiException(HttpStatusCode.Forbidden, "Administrator access is required")
    }
}
