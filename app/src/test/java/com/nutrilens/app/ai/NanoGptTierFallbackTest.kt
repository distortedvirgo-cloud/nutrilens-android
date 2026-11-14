package com.nutrilens.app.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class NanoGptTierFallbackTest {
    private val request = Json.parseToJsonElement("""
        {"model":"google/gemini-3.8-flash","service_tier":"flex","reasoning_effort":"low",
         "messages":[{"role":"user","content":[{"type":"text","text":"Analyse meal"},
         {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,photo"}}]}]}
    """).jsonObject

    // The provider response shown in the failure notification.
    private val rejection = NanoGptHttpException(400, """
        {"error":{"type":"invalid_request_error","message":"Flex isn't available for this model with this request. Retrying it unchanged won't help: send it without service_tier flex to use the standard route."}}
    """)

    @Test
    fun `flex rejection retries once without tier and preserves entire payload`() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val result = withNanoGptTierFallback(request) {
            sent += it
            if (sent.size == 1) throw rejection
            "answer"
        }
        assertEquals("answer", result)
        assertEquals(listOf(request, JsonObject(request - "service_tier")), sent)
    }

    @Test
    fun `successful flex request is sent once`() = runBlocking {
        var count = 0
        assertEquals("answer", withNanoGptTierFallback(request) {
            count++
            assertEquals(request, it)
            "answer"
        })
        assertEquals(1, count)
    }

    @Test
    fun `unrelated failures never retry`() = runBlocking {
        val failures = listOf(
            NanoGptHttpException(400, """{"error":{"type":"invalid_request_error","message":"Invalid image"}}"""),
            NanoGptHttpException(400, "not JSON"),
            NanoGptHttpException(400, """{"error":{"message":{"invalid":"shape"}}}"""),
            NanoGptHttpException(401, rejection.responseBody),
            NanoGptHttpException(429, rejection.responseBody),
            NanoGptHttpException(503, rejection.responseBody),
            SocketTimeoutException("timed out"),
            IOException("connection reset")
        )
        for (failure in failures) {
            var count = 0
            try {
                withNanoGptTierFallback(request) { count++; throw failure }
                fail("Expected original failure")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun `ordinary and priority requests never retry flex errors`() = runBlocking {
        for (body in listOf(JsonObject(request - "service_tier"),
            Json.parseToJsonElement("""{"service_tier":"priority"}""").jsonObject)) {
            var count = 0
            try {
                withNanoGptTierFallback(body) { count++; throw rejection }
                fail("Expected original failure")
            } catch (actual: NanoGptHttpException) {
                assertSame(rejection, actual)
            }
            assertEquals(1, count)
        }
    }

    @Test
    fun `second rejection is propagated without a third request`() = runBlocking {
        var count = 0
        try {
            withNanoGptTierFallback(request) { count++; throw rejection }
            fail("Expected second failure")
        } catch (actual: NanoGptHttpException) {
            assertSame(rejection, actual)
        }
        assertEquals(2, count)
    }
}
