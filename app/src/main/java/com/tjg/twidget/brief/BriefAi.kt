package com.tjg.twidget.brief

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.tjg.twidget.core.HttpTransport
import com.tjg.twidget.widget.TwidgetBriefWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val localModelName: String?,
    val localTokenLimit: Int?,
    val savedProvider: BriefProviderUsed,
    val lastAttemptAt: Long,
    val lastAttemptedProvider: String?,
    val lastOutcome: String?,
    val lastLocalFailure: String?,
    val lastLocalDetail: String?,
    val cloudConfigured: Boolean,
)

object BriefAiCoordinator {
    private val generationMutex = Mutex()

    suspend fun enrich(
        context: Context,
        source: BriefSnapshot,
        force: Boolean = false,
    ): BriefAiResult = withContext(Dispatchers.IO) {
        generationMutex.withLock {
            val mode = BriefSettingsStore.provider(context)
            val cached = if (force) {
                source
            } else {
                BriefAiCachePolicy.retain(BriefStore.read(context, source.username), source)
            }
            if (!force && cachedProviderMatches(mode, cached.providerUsed)) {
                if (cached !== source) BriefStore.write(context, cached)
                return@withLock BriefAiResult(
                    cached,
                    if (cached.providerUsed == BriefProviderUsed.LOCAL) {
                        BriefLocalStatus.AVAILABLE
                    } else {
                        BriefLocalStatus.UNAVAILABLE
                    },
                )
            }
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
                return@withLock BriefAiResult(source, localStatus)
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
                            providerMessage = "Gemini Nano couldn’t complete this Brief; used Gemini Cloud with your API key",
                        )
                    }
                }
            } ?: source.copy(providerMessage = fallbackMessage(mode, localStatus, context))

            BriefAiDiagnosticsStore.outcome(context, generated.providerUsed, generated.providerMessage)
            BriefStore.write(context, generated)
            TwidgetBriefWidget.updateAll(context)
            BriefAiResult(generated, localStatus)
        }
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
        mode == BriefProviderMode.LOCAL && localStatus == BriefLocalStatus.AVAILABLE ->
            "Gemini Nano is available, but couldn’t complete this Brief"
        mode == BriefProviderMode.LOCAL -> "Gemini Nano isn’t available on this device"
        mode == BriefProviderMode.CLOUD && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "Add a Gemini API key in Settings to enable cloud writing"
        mode == BriefProviderMode.AUTO && localStatus == BriefLocalStatus.DOWNLOADABLE ->
            "Gemini Nano is ready to download; using private factual copy for now"
        mode == BriefProviderMode.AUTO && BriefSettingsStore.cloudApiKey(context).isBlank() ->
            "No AI provider is ready; using private factual copy"
        else -> "AI wasn’t reachable; using private factual copy"
    }

    private fun cachedProviderMatches(
        mode: BriefProviderMode,
        provider: BriefProviderUsed,
    ): Boolean = when (mode) {
        BriefProviderMode.AUTO -> provider == BriefProviderUsed.LOCAL ||
            provider == BriefProviderUsed.CLOUD
        BriefProviderMode.LOCAL -> provider == BriefProviderUsed.LOCAL
        BriefProviderMode.CLOUD -> provider == BriefProviderUsed.CLOUD
    }
}

private data class NanoProbe(
    val status: BriefLocalStatus,
    val error: String?,
    val modelName: String? = null,
    val tokenLimit: Int? = null,
)

private object GeminiNanoBriefProvider {
    suspend fun probe(context: Context): NanoProbe = runCatching {
        val model = Generation.getClient()
        try {
            val status = when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> BriefLocalStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> BriefLocalStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> BriefLocalStatus.DOWNLOADING
                else -> BriefLocalStatus.UNAVAILABLE
            }
            NanoProbe(
                status = status,
                error = null,
                modelName = if (status == BriefLocalStatus.AVAILABLE) {
                    runCatching { model.getBaseModelName() }.getOrNull()
                } else null,
                tokenLimit = if (status == BriefLocalStatus.AVAILABLE) {
                    runCatching { model.getTokenLimit() }.getOrNull()
                } else null,
            )
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
                    TextPart("$SYSTEM_INSTRUCTION\n\n${localPromptFor(source)}"),
                ) {
                    temperature = 0.25f
                    topK = 3
                    // ML Kit's Gemini Nano prompt API currently accepts at most
                    // 256 output tokens. Larger values pass compilation but are
                    // rejected by AICore when the request is executed.
                    maxOutputTokens = 256
                }
                val inputTokens = runCatching { model.countTokens(request).totalTokens }.getOrNull()
                val response = model.generateContent(request)
                val candidate = response.candidates.firstOrNull()
                if (candidate == null) {
                    BriefAiDiagnosticsStore.localFailure(context, "Generation returned no candidate")
                    BriefAiDiagnosticsStore.localDetail(context, "candidate=missing")
                    return@runCatching null
                }
                val finishReason = finishReasonName(candidate.finishReason)
                val parsed = BriefAiCardResponse.apply(
                    source,
                    candidate.text,
                    BriefProviderUsed.LOCAL,
                )
                BriefAiDiagnosticsStore.localDetail(
                    context,
                    buildString {
                        append("finish=$finishReason")
                        inputTokens?.let { append(" · input=$it tokens") }
                        append(" · response=${candidate.text.length} chars · cards=${parsed.appliedCards}")
                    },
                )
                if (parsed.snapshot == null) {
                    BriefAiDiagnosticsStore.localFailure(
                        context,
                        "${parsed.failure ?: "Response parsing failed"} (finish=$finishReason, ${candidate.text.length} chars)",
                    )
                }
                parsed.snapshot
            } finally {
                model.close()
            }
        }.onFailure { error ->
            BriefAiDiagnosticsStore.localFailure(
                context,
                describeLocalError(error),
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
    private const val KEY_LOCAL_DETAIL = "local_detail"

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

    fun localDetail(context: Context, detail: String) {
        prefs(context).edit().putString(KEY_LOCAL_DETAIL, detail).apply()
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
            localModelName = probe.modelName,
            localTokenLimit = probe.tokenLimit,
            savedProvider = savedProvider,
            lastAttemptAt = prefs.getLong(KEY_ATTEMPT_AT, 0L),
            lastAttemptedProvider = prefs.getString(KEY_ATTEMPTED, null),
            lastOutcome = prefs.getString(KEY_OUTCOME, null),
            lastLocalFailure = prefs.getString(KEY_LOCAL_FAILURE, null),
            lastLocalDetail = prefs.getString(KEY_LOCAL_DETAIL, null),
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
            BriefAiCardResponse.apply(source, text, BriefProviderUsed.CLOUD).snapshot
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

private fun localPromptFor(source: BriefSnapshot): String {
    val outputCount = minOf(3, source.cards.size)
    val input = JSONArray().apply {
        source.cards.forEach { card ->
            put(JSONObject().apply {
                put("i", card.id)
                put("t", card.title)
                put("b", card.body)
                put("p", card.score)
            })
        }
    }
    return """
        ## TASK
        Rank the cards and rewrite only the best $outputCount.
        ## RULES
        Keep every id and numeric fact unchanged. Title max 32 characters. Body max 80 characters.
        ## OUTPUT
        JSON array only, using exactly these keys: [{"i":"id","t":"title","b":"body"}]
        ## CARDS
        $input
    """.trimIndent()
}

internal object BriefAiCardResponse {
    data class Result(
        val snapshot: BriefSnapshot?,
        val appliedCards: Int,
        val failure: String? = null,
    )

    fun apply(source: BriefSnapshot, raw: String, provider: BriefProviderUsed): Result {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return Result(null, 0, "Response did not contain a complete JSON array")
        val array = runCatching { JSONArray(raw.substring(start, end + 1)) }.getOrNull()
            ?: return Result(null, 0, "Response JSON was malformed")
        val originals = source.cards.associateBy(BriefCard::id)
        val seen = mutableSetOf<String>()
        var applied = 0
        val rewritten = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").ifBlank { item.optString("i") }
                val original = originals[id] ?: continue
                if (!seen.add(id)) continue
                val title = item.optString("title").ifBlank { item.optString("t") }
                    .trim().takeIf { it.length in 1..60 } ?: original.title
                val body = item.optString("body").ifBlank { item.optString("b") }
                    .trim().takeIf { it.length in 1..180 } ?: original.body
                val factual = numericFacts("${original.title} ${original.body}") == numericFacts("$title $body")
                add(if (factual) original.copy(title = title, body = body) else original)
                applied++
            }
            source.cards.filterNot { it.id in seen }.forEach(::add)
        }
        if (applied == 0) return Result(null, 0, "Response contained no recognised card ids")
        val generatedAt = System.currentTimeMillis()
        return Result(
            snapshot = source.copy(
                generatedAt = generatedAt,
                cards = rewritten,
                providerUsed = provider,
                aiGeneratedAt = generatedAt,
            ),
            appliedCards = applied,
        )
    }
}

private fun finishReasonName(reason: Int?): String = when (reason) {
    Candidate.FinishReason.STOP -> "STOP"
    Candidate.FinishReason.MAX_TOKENS -> "MAX_TOKENS"
    Candidate.FinishReason.OTHER -> "OTHER"
    null -> "UNKNOWN"
    else -> reason.toString()
}

private fun describeLocalError(error: Throwable): String = buildString {
    append(error.javaClass.simpleName)
    if (error is GenAiException) append(" code=${error.errorCode}")
    error.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
    error.cause?.takeIf { it !== error }?.let { cause ->
        append("; caused by ${cause.javaClass.simpleName}")
        cause.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
    }
}

private fun numericFacts(value: String): List<String> =
    Regex("[+-]?\\d+(?:[.,]\\d+)*%?").findAll(value).map { it.value }.sorted().toList()

internal object BriefAiCachePolicy {
    const val LOCAL_TTL_MS = 4 * 60 * 60 * 1000L
    const val CLOUD_TTL_MS = 2 * 60 * 60 * 1000L

    fun isFresh(snapshot: BriefSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        if (snapshot.providerUsed == BriefProviderUsed.TEMPLATE) return true
        val generatedAt = snapshot.aiGeneratedAt.takeIf { it > 0L } ?: snapshot.generatedAt
        val ttl = when (snapshot.providerUsed) {
            BriefProviderUsed.LOCAL -> LOCAL_TTL_MS
            BriefProviderUsed.CLOUD -> CLOUD_TTL_MS
            BriefProviderUsed.TEMPLATE -> return true
        }
        return generatedAt > 0L && now >= generatedAt && now - generatedAt < ttl
    }

    fun retain(
        previous: BriefSnapshot?,
        refreshed: BriefSnapshot,
        now: Long = System.currentTimeMillis(),
    ): BriefSnapshot {
        previous ?: return refreshed
        if (!previous.username.equals(refreshed.username, ignoreCase = true)) return refreshed
        if (previous.providerUsed == BriefProviderUsed.TEMPLATE || !isFresh(previous, now)) {
            return refreshed
        }

        val currentById = refreshed.cards.associateBy(BriefCard::id)
        val previousById = previous.cards.associateBy(BriefCard::id)
        val orderedIds = buildList {
            previous.cards.forEach { if (it.id in currentById) add(it.id) }
            refreshed.cards.forEach { if (it.id !in this) add(it.id) }
        }
        if (orderedIds.none { it in previousById && it in currentById }) return refreshed

        val mergedCards = orderedIds.mapNotNull { id ->
            val current = currentById[id] ?: return@mapNotNull null
            val cached = previousById[id] ?: return@mapNotNull current
            val factsStillMatch = cached.type == current.type &&
                numericFacts("${cached.title} ${cached.body}") ==
                numericFacts("${current.title} ${current.body}")
            if (factsStillMatch) {
                current.copy(title = cached.title, body = cached.body)
            } else {
                current
            }
        }
        val aiGeneratedAt = previous.aiGeneratedAt.takeIf { it > 0L } ?: previous.generatedAt
        return refreshed.copy(
            generatedAt = previous.generatedAt,
            cards = mergedCards,
            providerUsed = previous.providerUsed,
            providerMessage = previous.providerMessage,
            aiGeneratedAt = aiGeneratedAt,
        )
    }
}
