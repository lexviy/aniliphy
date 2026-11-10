package com.aniliphy.core

import com.aniliphy.source.BotCheckException
import com.aniliphy.source.SiteChangedException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteAccessTest {
    private fun response(code: Int, body: String, header: Pair<String, String>? = null) =
        Response.Builder()
            .request(Request.Builder().url("https://site.test/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { header?.let { header(it.first, it.second) } }
            .body(body.toResponseBody())
            .build()

    @Test
    fun robotChecksAreToldFromPlainErrors() {
        assertTrue(BotCheck.isCheck(response(403, "<html><head><title>Just a moment...</title>")))
        assertTrue(BotCheck.isCheck(response(503, "", "cf-mitigated" to "challenge")))
        assertFalse(BotCheck.isCheck(response(404, "<title>Just a moment...</title>")))
        assertFalse(BotCheck.isCheck(response(503, "Service unavailable")))
    }

    @Test
    fun aPassedCheckIsSentBackWithItsUserAgent() {
        SiteCookies.clear()
        SiteCookies.fromBrowser("https://site.test/watch", "cf_clearance=abc; other=1", "Browser/1")
        val sent = SiteCookies.loadForRequest("https://site.test/api/x".toHttpUrl()).associate { it.name to it.value }
        assertEquals(mapOf("cf_clearance" to "abc", "other" to "1"), sent)
        assertEquals("Browser/1", SiteCookies.agentFor("site.test"))
        assertTrue(SiteCookies.loadForRequest("https://elsewhere.test/".toHttpUrl()).isEmpty())
        assertNull(SiteCookies.agentFor("elsewhere.test"))
        SiteCookies.clear()
    }

    @Test
    fun changedSitesSaySo() {
        assertTrue(SiteChangedException("No video on the page").message!!.startsWith("No video on the page. The site may have changed"))
        assertEquals("site.test wants to check you're not a robot. Pass the check, then try again", BotCheckException("https://site.test/x").message)
    }
}
