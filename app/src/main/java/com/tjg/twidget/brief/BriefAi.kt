package com.tjg.twidget.brief

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.widget.TwidgetBriefWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class BriefLocalStatus { AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

data class BriefAiResult(
    val snapshot: BriefSnapshot,
    val localStatus: BriefLocalStatus,
)

data class BriefAiDiagnostics(
    val mode: BriefProviderMode,
    val runtimePresent: Boolean,
    val localStatus: BriefLocalStatus,
    val statusError: String?,
    val savedProvider: BriefProviderUsed,
    val lastAttemptAt: Long,
    val lastAttemptedProvider: String?,
    val lastOutcome: String?,
    val lastLocalFailure: String?,
    val cloudConfigured: Boolean,
)

object BriefAiCoordinator {
    suspend fun enrich(
        context: Context,
        source: BriefSnapshot,
        force: Boolean = false,
    ): BriefAiResult = withContext(Dispatchers.IO) {
        val mode = BriefSettingsStore.provider(context)
        val localProbe = GeminiNanoBriefProvider.probe(context)
        val localStatus = localProbe.status
        val resultMatchesMode = when (mode) {
            BriefProviderMode.LOCAL -> source.providerUsed == BriefProviderUsed.LOCAL
            BriefProviderMode.CLOUD -> source.providerUsed == BriefProviderUsed.CLOUD
            BriefProviderMode.AUTO -> when (localStatus) {
                BriefLocalStatus.AVAILABLE -> source.providerUsed == BriefProviderUsed.LOCAL
                else -> source.providerUsed == BriefProviderUsed.CLOUD
            }
        }
        if (!force && resultMatchesMode) {
            return@withContext BriefAiResult(source, localStatus)
        }

        val generated = when (mode) {
            BriefProviderMode.LOCAL -> {
                BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                GeminiNanoBriefProvider.generate(context, source)?.copy(
                    providerMessage = "Written privately with Gemini Nano on this device",
                )
            }
            BriefProviderMode.CLOUD -> {
                BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                GeminiCloudBriefProvider.generate(context, source)?.copy(
                    providerMessage = "Written with Gemini Cloud using your API key",
                )
            }
            BriefProviderMode.AUTO -> {
                BriefAiDiagnosticsStore.attempt(context, "Gemini Nano")
                GeminiNanoBriefProvider.generate(context, source)?.copy(
                    providerMessage = "Written privately with Gemini Nano on this device",
                ) ?: run {
                    BriefAiDiagnosticsStore.attempt(context, "Gemini Cloud")
                    GeminiCloudBriefProvider.generate(context, source)?.copy(
                        providerMessage = "Gemini Nano wasn’t available; used Gemini Cloud with your API key",
                    )
                }
            }
        } ?: source.copy(providerMessage = fallbackMessage(mode, localStatus, context))

        BriefAiDiagnosticsStore.outcome(context, generated.providerUsed, generated.providerMessage)
        BriefStore.write(context, generated)
        TwidgetBriefWidget.updateAll(context)
        BriefAiResult(generated, localStatus)
    }

    suspend fun downloadLocalModel(onStatus: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        GeminiNanoBriefProvider.download(onStatus)
    }

    suspend fun diagnostics(context: Context, username: String): BriefAiDiagnostics = withContext(Dispatchers.IO) {
        val probe = GeminiNanoBriefProvider.probe(context)
        val saved = BriefStore.read(context, username)?.providerUsed ?: BriefProviderUsed.TEMPLATE
        BriefAiDiagnosticsStore.read(context, BriefSettingsStore.provider(context), probe, saved)
    }

    private fun fallbackMessage(
        mode: BriefProviderMode,
        localStatus: BriefLocalStatus,
        context: Context,
    ): String = when {
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADABLE ->
            "Gemini Nano is supported and ready to download"
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.DOWNLOADING ->
            "Gemini Nano is still downloading"
        mode == BriefProviderMode.LOCAL -> "Gemini Nano isn’t available on this device"
        mode == BriefProviderMode.CLOUD && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "Add a Gemini API key in Settings to enable cloud writing"
        mode == BriefProviderMode.AUTO && localStatus == BriefLocalStatus.DOWNLOADABLE ->
            "Gemini Nano is ready to download; using private factual copy for now"
        mode == BriefProviderMode.AUTO && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "No AI provider is ready; using private factual copy"
        else -> "AI wasn’t reachable; using private factual copy"
    }
}

private data class NanoProbe(val status: BriefLocalStatus, val error: String?)

private object GeminiNanoBriefProvider {
    suspend fun probe(context: Context): NanoProbe = runCatching {
        val model = Generation.getClient()
        try {
            NanoProbe(when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> BriefLocalStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> BriefLocalStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> BriefLocalStatus.DOWNLOADING
                else -> BriefLocalStatus.UNAVAILABLE
            }, null)
        } finally {
            model.close()
        }
    }.getOrElse { error ->
        val reason = "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim()
        BriefAiDiagnosticsStore.localFailure(context, reason)
        NanoProbe(BriefLocalStatus.UNAVAILABLE, reason)
    }

    suspend fun generate(context: Context, source: BriefSnapshot): BriefSnapshot? {
        val probe = probe(context)
        if (probe.status != BriefLocalStatus.AVAILABLE) {
            BriefAiDiagnosticsStore.localFailure(context, "Feature status: ${probe.status}")
            return null
        }
        return runCatching {
            val model = Generation.getClient()
            try {
                val request = generateContentRequest(
                    TextPart("$SYSTEM_INSTRUCTION\n\n${promptFor(source)}"),
                ) {
                    temperature = 0.25f
                    topK = 3
                    // ML Kit's Gemini Nano prompt API currently accepts at most
                    // 256 output tokens. Larger values pass compilation but are
                    // rejected by AICore when the request is executed.
                    maxOutputTokens = 256
                }
                val response = model.generateContent(request)
                applyGeneratedCards(
                    source,
                    response.candidates.firstOrNull()?.text.orEmpty(),
                    BriefProviderUsed.LOCAL,
                )
            } finally {
                model.close()
            }
        }.onFailure { error ->
            BriefAiDiagnosticsStore.localFailure(
                context,
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}".trim(),
            )
        }.getOrNull()
    }

    suspend fun download(onStatus: (String) -> Unit): Boolean = runCatching {
        val model = Generation.getClient()
        try {
            var complete = false
            var total = 0L
            model.download().collect { event ->
                when (event) {
                    is DownloadStatus.DownloadStarted -> {
                        total = event.bytesToDownload
                        onStatus("Downloading on-device model…")
                    }
                    is DownloadStatus.DownloadProgress -> {
                        val percent = if (total > 0) event.totalBytesDownloaded * 100 / total else 0
                        onStatus("Downloading on-device model… $percent%")
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        complete = true
                        onStatus("On-device model is ready")
                    }
                    is DownloadStatus.DownloadFailed -> onStatus("The on-device model couldn’t be downloaded")
                }
            }
            complete
        } finally {
            model.close()
        }
    }.getOrElse {
        onStatus("The on-device model isn’t available")
        false
    }
}

private object BriefAiDiagnosticsStore {
    private const val PREFS = "brief_ai_diagnostics"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_ATTEMPTED = "attempted"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_LOCAL_FAILURE = "local_failure"

    fun attempt(context: Context, provider: String) {
        prefs(context).edit()
            .putLong(KEY_ATTEMPT_AT, System.currentTimeMillis())
            .putString(KEY_ATTEMPTED, provider)
            .apply()
    }

    fun outcome(context: Context, provider: BriefProviderUsed, message: String) {
        prefs(context).edit().apply {
            putString(KEY_OUTCOME, "$provider · $message")
            if (provider == BriefProviderUsed.LOCAL) remove(KEY_LOCAL_FAILURE)
        }.apply()
    }

    fun localFailure(context: Context, reason: String) {
        prefs(context).edit().putString(KEY_LOCAL_FAILURE, reason).apply()
    }

    fun read(
        context: Context,
        mode: BriefProviderMode,
        probe: NanoProbe,
        savedProvider: BriefProviderUsed,
    ): BriefAiDiagnostics {
        val prefs = prefs(context)
        return BriefAiDiagnostics(
            mode = mode,
            runtimePresent = runCatching { Class.forName("com.google.mlkit.genai.prompt.Generation") }.isSuccess,
            localStatus = probe.status,
            statusError = probe.error,
            savedProvider = savedProvider,
            lastAttemptAt = prefs.getLong(KEY_ATTEMPT_AT, 0L),
            lastAttemptedProvider = prefs.getString(KEY_ATTEMPTED, null),
            lastOutcome = prefs.getString(KEY_OUTCOME, null),
            lastLocalFailure = prefs.getString(KEY_LOCAL_FAILURE, null),
            cloudConfigured = BriefSettingsStore.cloudApiKey(context).isNotBlank(),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private object GeminiCloudBriefProvider {
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent"

    fun generate(context: Context, source: BriefSnapshot): BriefSnapshot? {
        val key = BriefSettingsStore.cloudApiKey(context)
        if (key.isBlank()) return null
        return runCatching {
            val request = JSONObject().apply {
                put("systemInstruction", JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)),
                ))
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", promptFor(source))))
                }))
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 600)
                    put("responseMimeType", "application/json")
                })
            }
            val response = HttpTransport.post(
                ENDPOINT,
                request.toString(),
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-goog-api-key" to key,
                ),
                readTimeoutMs = 30_000,
            )
            val root = JSONObject(HttpTransport.requireSuccess(response, "Gemini"))
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            applyGeneratedCards(source, text, BriefProviderUsed.CLOUD)
        }.getOrNull()
    }
}

private const val SYSTEM_INSTRUCTION =
    "You write a concise social analytics brief. You may only reword and rank the supplied factual cards. " +
        "Never add numbers, names, causes, predictions, or claims. Keep titles under 45 characters and bodies " +
        "under 150 characters. Be warm and direct, never shaming. Return only a JSON array."

private fun promptFor(source: BriefSnapshot): String {
    val input = JSONArray().apply {
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("id", card.id)
                put("title", card.title)
                put("body", card.body)
                put("priority", card.score)
            })
        }
    }
    return "Rewrite and order these cards. Keep each id unchanged. Return objects with exactly id, title, and body: $input"
}

private fun applyGeneratedCards(
    source: BriefSnapshot,
    raw: String,
    provider: BriefProviderUsed,
): BriefSnapshot? {
    val start = raw.indexOf('[')
    val end = raw.lastIndexOf(']')
    if (start < 0 || end <= start) return null
    val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull() ?: return null
    val originals = source.cards.associateBy(BriefCard::id)
    val seen = mutableSetOf<String>()
    val rewritten = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val original = originals[id] ?: continue
            if (!seen.add(id)) continue
            val title = item.optString("title").trim().takeIf { it.length in 1..60 } ?: original.title
            val body = item.optString("body").trim().takeIf { it.length in 1..180 } ?: original.body
            val factual = numericFacts("${original.title} ${original.body}") == numericFacts("$title $body")
            add(if (factual) original.copy(title = title, body = body) else original)
        }
        source.cards.filterNot { it.id in seen }.forEach(::add)
    }
    if (rewritten.isEmpty()) return null
    return source.copy(
        generatedAt = System.currentTimeMillis(),
        cards = rewritten,
        providerUsed = provider,
    )
}

private fun numericFacts(value: String): List<String> =
    Regex("[+-]?\\d+(?:[.,]\\d+)*%?").findAll(value).map { it.value }.sorted().toList()
