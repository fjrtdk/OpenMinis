package com.openminis.app.provider.magpie

import com.openminis.app.data.model.LLMModel

/**
 * Defaults for the Magpie OpenAI-compatible gateway preset.
 *
 * [DEFAULT_BASE_URL] is the public HTTPS endpoint for client devices,
 * confirmed by Magpie Ops on 24 Nov 2026. Change this one constant if the
 * hostname moves. Never put a gateway key in this file.
 */
object MagpieGateway {
    const val DEFAULT_BASE_URL = "https://magpie.rebel.army/v1"
    const val DEFAULT_MODEL_ID = "kiro/claude-sonnet-4.5"
    const val FALLBACK_MODEL_ID = "auto/minimax-m3.1-flash"

    val defaultModel = LLMModel(
        id = DEFAULT_MODEL_ID,
        displayName = "Kiro Claude Sonnet 4.5",
        provider = "Magpie",
    )
    val fallbackModel = LLMModel(
        id = FALLBACK_MODEL_ID,
        displayName = "MiniMax M3.1 Flash",
        provider = "Magpie",
    )

    /** Seed / fallback catalog: default first, then the fallback model. */
    val builtInModels: List<LLMModel> = listOf(defaultModel, fallbackModel)

    /** Trimmed custom URL, or [DEFAULT_BASE_URL] when the user left it blank. */
    fun resolvedBaseUrl(customBaseURL: String?): String {
        val trimmed = customBaseURL?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        return trimmed ?: DEFAULT_BASE_URL.trimEnd('/')
    }

    /** OpenAI-compatible models endpoint: `{base}/models`. */
    fun modelsUrl(customBaseURL: String?): String = "${resolvedBaseUrl(customBaseURL)}/models"

    /**
     * Keep the live catalog, but surface the preset default and fallback
     * first when the gateway actually listed them.
     */
    fun preferBuiltIns(models: List<LLMModel>): List<LLMModel> {
        val preferred = listOf(DEFAULT_MODEL_ID, FALLBACK_MODEL_ID)
        val preferredSet = preferred.toSet()
        val byId = models.associateBy { it.id }
        val head = preferred.mapNotNull { byId[it] }
        val rest = models.filter { it.id !in preferredSet }
        return head + rest
    }
}
