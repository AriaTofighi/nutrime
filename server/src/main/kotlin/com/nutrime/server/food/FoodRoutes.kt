package com.nutrime.server.food

import com.nutrime.server.ApiException
import com.nutrime.server.RateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Installed inside authenticate("auth"); never exposes a generic upstream proxy. */
fun Route.foodRoutes(catalog: FoodCatalog) {
    val limiter = RateLimiter(60, 60_000)
    fun check(uid: String) {
        if (!limiter.allow(uid)) throw ApiException(HttpStatusCode.TooManyRequests, "Too many food lookups. Try again in a minute.")
    }
    get("/api/foods/search") {
        check(call.principal<JWTPrincipal>()!!.payload.subject)
        val query = call.request.queryParameters["q"]?.trim().orEmpty()
        if (query.length !in 1..200) throw ApiException(HttpStatusCode.BadRequest, "Enter a food name of at most 200 characters.")
        val result = catalog.search(query)
        // Best matches first; among them, foods with full nutrient data (USDA analyses) before label-only products,
        // and products sold in the user's country (from the app's region) before others.
        val country = countryName(call.request.queryParameters["country"])
        result.retryAfterSeconds?.let { call.response.header("Retry-After", it.toString()) }
        call.respond(result.copy(foods = com.nutrime.app.model.FoodQuality.rank(result.foods, query, country)))
    }
    get("/api/foods/barcode/{code}") {
        check(call.principal<JWTPrincipal>()!!.payload.subject)
        val code = call.parameters["code"].orEmpty()
        if (!code.matches(Regex("[0-9]{4,14}"))) throw ApiException(HttpStatusCode.BadRequest, "Invalid barcode.")
        val result = catalog.barcode(code)
        result.retryAfterSeconds?.let { call.response.header("Retry-After", it.toString()) }
        call.respond(result)
    }
    get("/api/foods/item") {
        check(call.principal<JWTPrincipal>()!!.payload.subject)
        val id = call.request.queryParameters["id"].orEmpty()
        if (!id.matches(Regex("(off:[0-9]{4,14}|usda:[0-9]{1,12})")))
            throw ApiException(HttpStatusCode.BadRequest, "Invalid provider food id.")
        val result = catalog.get(id)
        result.retryAfterSeconds?.let { call.response.header("Retry-After", it.toString()) }
        call.respond(result)
    }
}
