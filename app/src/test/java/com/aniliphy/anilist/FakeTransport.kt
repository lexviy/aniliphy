package com.aniliphy.anilist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeTransport(private val answer: (Request) -> HttpReply) : GraphQlTransport {
    data class Request(val query: String, val variables: JsonObject, val token: String?)

    val requests = mutableListOf<Request>()

    override suspend fun post(body: String, token: String?): HttpReply {
        val json = Json.parseToJsonElement(body).jsonObject
        val request = Request(
            query = json.getValue("query").jsonPrimitive.content,
            variables = json.getValue("variables").jsonObject,
            token = token,
        )
        requests += request
        return answer(request)
    }

    val mutations get() = requests.filter { it.query.trimStart().startsWith("mutation") }
}

fun ok(data: String) = HttpReply(200, """{"data":$data}""")
