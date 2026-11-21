package com.nutrilens.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NanoYouTubeModelsTest {
    private val unavailableVideoRoute = NanoGptHttpException(
        400,
        """{"error":{"message":"No available route for this model supports video input.","status":400,"type":"invalid_request_error"}}"""
    )
    private val rejectedLink = NanoGptHttpException(
        400,
        """{"error":{"message":"No permitted provider supports this model and media input.","status":400,"type":"invalid_request_error"}}"""
    )

    @Test
    fun `the second model can open a link rejected by the first`() = runBlocking {
        val models = mutableListOf<String>()
        val answer = withNanoYouTubeModels { model ->
            models += model
            if (models.size == 1) throw unavailableVideoRoute
            "recipe"
        }
        assertEquals("recipe", answer)
        assertEquals(listOf("google/gemini-flash-latest", "google/gemini-3.5-flash"), models)
    }

    @Test
    fun `two media routing rejections signal that the importer should download the video`() {
        var attempts = 0
        val failure = assertThrows(NanoYouTubeLinkException::class.java) {
            runBlocking {
                withNanoYouTubeModels {
                    attempts++
                    throw if (attempts == 1) unavailableVideoRoute else rejectedLink
                }
            }
        }
        assertSame(rejectedLink, failure.cause)
        assertEquals(2, attempts)
    }

    @Test
    fun `downloaded video retries with an alternative model after a video routing rejection`() = runBlocking {
        val models = mutableListOf<String>()
        val answer = withNanoRecipeVideoModel { model ->
            models += model
            if (models.size == 1) throw unavailableVideoRoute
            "recipe"
        }
        assertEquals("recipe", answer)
        assertEquals(listOf("google/gemini-3.1-flash-lite", "qwen/qwen3.8-omni-flash"), models)
    }

    @Test
    fun `downloaded video does not retry successful or unrelated requests`() = runBlocking {
        val models = mutableListOf<String>()
        assertEquals("recipe", withNanoRecipeVideoModel { model ->
            models += model
            "recipe"
        })
        val failure = NanoGptHttpException(429, unavailableVideoRoute.responseBody)
        val actual = assertThrows(NanoGptHttpException::class.java) {
            runBlocking {
                withNanoRecipeVideoModel { model ->
                    models += model
                    throw failure
                }
            }
        }
        assertSame(failure, actual)
        assertEquals(List(2) { "google/gemini-3.1-flash-lite" }, models)
    }

    @Test
    fun `unrelated failures stop the attempts without requesting a download`() {
        val failures = listOf(
            NanoGptHttpException(400, """{"error":{"message":"Invalid JSON schema"}}"""),
            NanoGptHttpException(401, rejectedLink.responseBody),
            NanoGptHttpException(402, rejectedLink.responseBody),
            NanoGptHttpException(429, rejectedLink.responseBody),
            CancellationException()
        )
        for (failure in failures) {
            var attempts = 0
            val actual = assertThrows(failure.javaClass) {
                runBlocking {
                    withNanoYouTubeModels {
                        attempts++
                        throw failure
                    }
                }
            }
            assertSame(failure, actual)
            assertEquals(1, attempts)
        }
    }
}
