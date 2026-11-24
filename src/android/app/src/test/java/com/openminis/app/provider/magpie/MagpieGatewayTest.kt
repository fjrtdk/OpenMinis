package com.openminis.app.provider.magpie

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MagpieGatewayTest {

    @Test
    fun `default base URL is the public HTTPS Magpie endpoint including v1`() {
        assertEquals("https://magpie.rebel.army/v1", MagpieGateway.DEFAULT_BASE_URL)
        assertTrue(MagpieGateway.DEFAULT_BASE_URL.startsWith("https://"))
        assertTrue(MagpieGateway.DEFAULT_BASE_URL.endsWith("/v1"))
    }

    @Test
    fun `resolvedBaseUrl uses the default when the field is blank`() {
        assertEquals("https://magpie.rebel.army/v1", MagpieGateway.resolvedBaseUrl(null))
        assertEquals("https://magpie.rebel.army/v1", MagpieGateway.resolvedBaseUrl(""))
        assertEquals("https://magpie.rebel.army/v1", MagpieGateway.resolvedBaseUrl("   "))
    }

    @Test
    fun `resolvedBaseUrl keeps a user-edited URL and trims a trailing slash`() {
        assertEquals(
            "https://example.test/v1",
            MagpieGateway.resolvedBaseUrl("https://example.test/v1/"),
        )
        assertEquals(
            "https://example.test/v1",
            MagpieGateway.resolvedBaseUrl("  https://example.test/v1  "),
        )
    }

    @Test
    fun `modelsUrl is the OpenAI-compatible GET v1 models path`() {
        assertEquals(
            "https://magpie.rebel.army/v1/models",
            MagpieGateway.modelsUrl(null),
        )
        assertEquals(
            "https://example.test/v1/models",
            MagpieGateway.modelsUrl("https://example.test/v1/"),
        )
    }

    @Test
    fun `built-in catalog is default then fallback and contains no key`() {
        val models = MagpieGateway.builtInModels
        assertEquals(2, models.size)
        assertEquals(MagpieGateway.DEFAULT_MODEL_ID, models[0].id)
        assertEquals(MagpieGateway.FALLBACK_MODEL_ID, models[1].id)
        assertEquals("kiro/claude-sonnet-4.5", models[0].id)
        assertEquals("auto/minimax-m3.1-flash", models[1].id)
        assertEquals("Magpie", models[0].provider)
        assertEquals("Magpie", models[1].provider)
        // The preset must never ship a gateway key.
        for (m in models) {
            assertFalse(m.id.contains("openminis-phone", ignoreCase = true))
            assertFalse(m.displayName.contains("sk-", ignoreCase = true))
        }
        assertFalse(MagpieGateway.DEFAULT_BASE_URL.contains("key", ignoreCase = true))
    }

    @Test
    fun `preferBuiltIns moves default and fallback to the front when present`() {
        val extra = LLMModel("group/other", "Other", "Magpie")
        val ordered = MagpieGateway.preferBuiltIns(
            listOf(extra, MagpieGateway.fallbackModel, MagpieGateway.defaultModel),
        )
        assertEquals(
            listOf("kiro/claude-sonnet-4.5", "auto/minimax-m3.1-flash", "group/other"),
            ordered.map { it.id },
        )
    }

    @Test
    fun `preferBuiltIns does not invent models the gateway did not list`() {
        val onlyFallback = MagpieGateway.preferBuiltIns(listOf(MagpieGateway.fallbackModel))
        assertEquals(listOf("auto/minimax-m3.1-flash"), onlyFallback.map { it.id })
    }

    @Test
    fun `ProviderType magpie is usable with the built-in catalog`() {
        assertEquals("Magpie", ProviderType.magpie.displayName)
        assertTrue(ProviderType.magpie.isUsable)
        assertEquals(
            listOf("kiro/claude-sonnet-4.5", "auto/minimax-m3.1-flash"),
            ProviderType.magpie.builtInModels.map { it.id },
        )
    }

    @Test
    fun `a Magpie instance with the default URL does not append a second v1`() {
        val instance = ProviderInstance(
            id = "test",
            label = "Magpie",
            providerType = ProviderType.magpie,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = MagpieGateway.DEFAULT_BASE_URL,
            appendV1Suffix = false,
        )
        assertEquals("https://magpie.rebel.army/v1", instance.effectiveBaseURL)
        assertEquals(
            "https://magpie.rebel.army/v1/models",
            MagpieGateway.modelsUrl(instance.effectiveBaseURL),
        )
    }

    @Test
    fun `appending v1 to a host-only Magpie URL yields the models endpoint`() {
        val instance = ProviderInstance(
            id = "test",
            label = "Magpie",
            providerType = ProviderType.magpie,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = "https://magpie.rebel.army",
            appendV1Suffix = true,
        )
        assertEquals("https://magpie.rebel.army/v1", instance.effectiveBaseURL)
    }
}
