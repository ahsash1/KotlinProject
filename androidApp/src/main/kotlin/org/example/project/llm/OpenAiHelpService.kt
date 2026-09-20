package org.example.project.llm

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.example.project.guidance.GuidanceStep
import org.example.project.guidance.ScreenElement
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// Key is no longer hardcoded here -- moved to local.properties / BuildConfig.OPENAI_API_KEY,
// same pattern as the Anthropic Lost-rescue key, so it isn't published to source control.

/**
 * Result of a user-initiated Help request. [PerformType]/[PerformClick] are only ever returned
 * when the user's own words explicitly asked the AI to perform the action -- the prompt tells
 * the model that distinction matters. [consequential] on those two is the model's own judgment
 * (defaults to true, the safe direction, if the field is missing); the caller additionally
 * checks a human-verified per-task list before ever skipping confirmation -- see
 * GuidanceAccessibilityService.handlePerformableAction.
 */
sealed interface HelpResult {
    data class Pointed(val element: ScreenElement, val instruction: String) : HelpResult
    data class TextOnly(val instruction: String) : HelpResult
    data class PerformType(val element: ScreenElement, val text: String, val instruction: String, val consequential: Boolean) : HelpResult
    data class PerformClick(val element: ScreenElement, val instruction: String, val consequential: Boolean) : HelpResult
    data class Failed(val reason: String) : HelpResult
}

/**
 * User-initiated "Help": captures one spoken question (via a caller-supplied transcript),
 * sends the current screen plus remaining steps to OpenAI, and returns either a specific
 * element to point at or a text-only spoken answer. Purely additive -- never touches
 * GuidanceEngine, matchers, or step-advance logic; the caller decides what "resume normally"
 * means on failure.
 */
class OpenAiHelpService(private val apiKey: String) {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun ask(
        question: String,
        elements: List<ScreenElement>,
        remainingSteps: List<GuidanceStep>,
        onResult: (HelpResult) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            onResult(HelpResult.Failed("no API key configured"))
            return
        }
        if (question.isBlank()) {
            onResult(HelpResult.Failed("empty question"))
            return
        }

        val answered = AtomicBoolean(false)
        fun complete(result: HelpResult) {
            if (answered.compareAndSet(false, true)) {
                handler.post { onResult(result) }
            }
        }

        // Hard ceiling independent of the HTTP client's own timeouts -- see AnthropicLostRescuer
        // for why this matters (a hang before those timeouts even start could otherwise exceed
        // the promised ~4s).
        handler.postDelayed({ complete(HelpResult.Failed("timeout")) }, TIMEOUT_MS)

        executor.execute {
            try {
                val candidates = buildCandidates(elements)
                val prompt = buildPrompt(question, candidates, remainingSteps)
                val responseBody = callOpenAi(prompt)
                complete(parseResponse(responseBody, candidates))
            } catch (t: Throwable) {
                Log.w(TAG, "help call failed", t)
                complete(HelpResult.Failed(t.message ?: "error"))
            }
        }
    }

    private fun buildCandidates(elements: List<ScreenElement>): List<ScreenElement> =
        elements
            .filter { it.isVisible && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) }
            .take(MAX_CANDIDATES)

    private fun buildPrompt(
        question: String,
        candidates: List<ScreenElement>,
        remainingSteps: List<GuidanceStep>,
    ): String {
        val stepsBlock = remainingSteps.mapIndexed { i, step -> "${i + 1}. ${step.instruction}" }.joinToString("\n")
        val elementsBlock = candidates.mapIndexed { i, e ->
            val text = e.text?.take(60).orEmpty()
            val desc = e.contentDescription?.take(60).orEmpty()
            val bounds = "${e.left},${e.top},${e.right},${e.bottom}"
            "$i: text=\"$text\" desc=\"$desc\" bounds=$bounds clickable=${e.isClickable}"
        }.joinToString("\n")

        return """
            You are guiding an elderly user through a phone screen. They just spoke a question or
            request out loud because they're stuck. Their words: "$question"

            DEFAULT BEHAVIOR: tell them what to do themselves. Be concrete, not vague. Bad: "look
            for the option you need." Good: "Tap the search box at the top, then type where you're
            going." Name the exact button/field label or its visible text/description from the
            list below, not a general area of the screen. If they ask you to enter a location, set
            a time, or fill something in, but did NOT explicitly ask you to do it FOR them, your
            job is still just to name the exact field and tell them exactly what to type or select
            into it themselves.

            EXCEPTION -- only if their words explicitly ask you to perform the action yourself
            (e.g. "type that for me", "can you fill that in", "do it for me", "tap it"), you may
            request the action directly instead of just describing it. Set "consequential": true
            if this action is irreversible or has a real-world effect (submitting a payment,
            requesting/booking a ride, confirming an order, sending a message) -- when unsure, set
            it true. Never assume you already did something; only the app performs it, and only
            after you request it this way.

            What they still need to do, in order (they need step 1 next):
            $stepsBlock

            What's really on their screen right now (index: text/description/bounds/clickable):
            $elementsBlock

            Reply with ONLY compact JSON, nothing else, no markdown fencing. Pick exactly one shape:

            Just telling them what to do (the default):
            {"index": <number, optional>, "say": "<precise spoken instruction, under 15 words>"}

            Only if explicitly asked to perform it themselves -- type text into a field:
            {"action": "type", "index": <number>, "text": "<exact text to type>", "say": "<what you're about to do, under 12 words>", "consequential": <true/false>}

            Only if explicitly asked to perform it themselves -- tap something:
            {"action": "tap", "index": <number>, "say": "<what you're about to do, under 12 words>", "consequential": <true/false>}
        """.trimIndent()
    }

    private fun callOpenAi(prompt: String): String {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("content-type", "application/json")

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", 200)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        },
                    ),
                )
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) {
                throw IOException("OpenAI API returned $status: ${responseText.take(200)}")
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(responseBody: String, candidates: List<ScreenElement>): HelpResult {
        val text = JSONObject(responseBody)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: return HelpResult.Failed("empty response")

        val jsonText = extractJsonObject(text) ?: return HelpResult.TextOnly(text.take(200))
        val parsed = JSONObject(jsonText)
        val say = parsed.optString("say").ifBlank { return HelpResult.Failed("no answer in response") }

        val index = parsed.optInt("index", -1)
        val element = candidates.getOrNull(index)
        // Default true (the safe direction) if the model omits the field entirely.
        val consequential = if (parsed.has("consequential")) parsed.optBoolean("consequential", true) else true

        return when (parsed.optString("action")) {
            "type" -> {
                val typeText = parsed.optString("text")
                if (element != null && typeText.isNotBlank()) {
                    HelpResult.PerformType(element, typeText, say, consequential)
                } else {
                    // Couldn't map to a real field to type into -- fall back to just telling them.
                    HelpResult.TextOnly(say)
                }
            }
            "tap" -> {
                if (element != null) HelpResult.PerformClick(element, say, consequential) else HelpResult.TextOnly(say)
            }
            else -> if (element != null) HelpResult.Pointed(element, say) else HelpResult.TextOnly(say)
        }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return text.substring(start, end + 1)
    }

    companion object {
        private const val TAG = "OpenAiHelpService"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o-mini"
        private const val MAX_CANDIDATES = 60
        private const val TIMEOUT_MS = 4_000L
        private const val CONNECT_TIMEOUT_MS = 3_500
        private const val READ_TIMEOUT_MS = 3_500
    }
}
