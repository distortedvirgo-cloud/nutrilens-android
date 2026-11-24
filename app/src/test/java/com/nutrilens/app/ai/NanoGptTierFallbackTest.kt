package com.nutrilens.app.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private val unavailable = NanoGptHttpException(503, """
        {"error":{"message":"google/gemini-3.8-flash is temporarily unavailable. Please try again later or choose another model.","status":503,"type":"service_unavailable","param":"model"}}
    """)

    @Test
    fun `senior starts on standard route and switches directly to backup on model outage`() = runBlocking {
        assertNull(defaultNanoServiceTier(NANO_MODEL_SENIOR))
        assertEquals("flex", defaultNanoServiceTier(NANO_MODEL_JUNIOR))
        val standard = JsonObject(request - "service_tier")
        val sent = mutableListOf<JsonObject>()
        val result = withNanoGptTierFallback(standard, NANO_MODEL_SENIOR_BACKUP) {
            sent += it
            if (sent.size == 1) throw unavailable
            "answer"
        }
        assertEquals("answer", result)
        assertEquals(listOf(standard, JsonObject(standard + ("model" to JsonPrimitive(NANO_MODEL_SENIOR_BACKUP)))), sent)
    }

    @Test
    fun `unavailable model recovers on the standard route`() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val answer = withNanoGptTierFallback(request, NANO_MODEL_SENIOR_BACKUP) {
            sent += it
            if (sent.size == 1) throw unavailable
            "answer"
        }
        assertEquals("answer", answer)
        assertEquals(listOf(request, JsonObject(request - "service_tier")), sent)
    }

    @Test
    fun `persistently unavailable model switches once and preserves the meal payload`() = runBlocking {
        val sent = mutableListOf<JsonObject>()
        val answer = withNanoGptTierFallback(request, NANO_MODEL_SENIOR_BACKUP) {
            sent += it
            if (sent.size < 3) throw unavailable
            "answer"
        }
        val standard = JsonObject(request - "service_tier")
        val fallback = JsonObject(standard + ("model" to JsonPrimitive(NANO_MODEL_SENIOR_BACKUP)))
        assertEquals("answer", answer)
        assertEquals(listOf(request, standard, fallback), sent)
    }

    @Test
    fun `unavailable fallback returns a readable error after three requests`() = runBlocking {
        var count = 0
        try {
            withNanoGptTierFallback(request, NANO_MODEL_SENIOR_BACKUP) { count++; throw unavailable }
            fail("Expected model unavailability")
        } catch (actual: RuntimeException) {
            assertEquals("Модели NanoGPT временно недоступны. Повторите чуть позже.", actual.message)
            assertSame(unavailable, actual.cause)
        }
        assertEquals(3, count)
    }

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
            NanoGptHttpException(503, """{"error":{"type":"service_unavailable"}}"""),
            SocketTimeoutException("timed out"),
            IOException("connection reset")
        )
        for (failure in failures) {
            var count = 0
            try {
                withNanoGptTierFallback(request, NANO_MODEL_SENIOR_BACKUP) { count++; throw failure }
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
