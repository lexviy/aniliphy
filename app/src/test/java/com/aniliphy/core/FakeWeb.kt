package com.aniliphy.core

/** A [WebCall] that answers from [answer] and remembers what was asked. */
class FakeWeb(private val answer: (method: String, url: String, body: ByteArray?) -> WebReply) : WebCall {
    data class Call(val method: String, val url: String, val body: ByteArray?)

    val calls = mutableListOf<Call>()

    override fun get(url: String, headers: Map<String, String>): WebReply {
        calls += Call("GET", url, null)
        return answer("GET", url, null)
    }

    override fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): WebReply {
        calls += Call("POST", url, body)
        return answer("POST", url, body)
    }

    companion object {
        fun json(text: String, code: Int = 200) = WebReply(code, text.encodeToByteArray(), "application/json")
        fun html(text: String, code: Int = 200) = WebReply(code, text.encodeToByteArray(), "text/html")
        fun resource(name: String): String = FakeWeb::class.java.getResourceAsStream(name)!!.readBytes().decodeToString()
    }
}
