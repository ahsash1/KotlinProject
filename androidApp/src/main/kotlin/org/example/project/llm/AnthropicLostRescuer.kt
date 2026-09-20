package org.example.project.llm

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.example.project.guidance.GuidanceStep
import org.example.project.guidance.LostRescueResult
import org.example.project.guidance.LostRescuer
import org.example.project.guidance.ScreenElement
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Calls the Anthropic Messages API once, with a hard ~3s ceiling, to suggest
 * a target when GuidanceEngine has declared Lost. Never called on the main
 * thread's critical path -- the existing Lost banner/speech is already
 * showing by the time this fires, so a slow or failed call just means
 * nothing changes, never a blank screen.
 */
class AnthropicLostRescuer(private val apiKey: String) : LostRescuer {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    override fun rescue(
        elements: List<ScreenElement>,
        remainingSteps: List<GuidanceStep>,
        onResult: (LostRescueResult) -> Unit,
    ) {
        if (apiKey.isBlank()) {
            onResult(LostRescueResult.Failed("no API key configured"))
            return
        }
        if (remainingSteps.isEmpty()) {
            onResult(LostRescueResult.Failed("no remaining steps"))
            return
        }

        val answered = AtomicBoolean(false)
        fun complete(result: LostRescueResult) {
            if (answered.compareAndSet(false, true)) {
                handler.post { onResult(result) }
            }
        }

        // Hard ceiling independent of the HTTP client's own timeouts, so a slow DNS lookup or a
        // connection that hangs before those timeouts even start can't blow past ~3s.
        handler.postDelayed({ complete(LostRescueResult.Failed("timeout")) }, TIMEOUT_MS)

        executor.execute {
            try {
                val candidates = buildCandidates(elements)
                if (candidates.isEmpty()) {
                    complete(LostRescueResult.Unclear)
                    return@execute
                }
                val prompt = buildPrompt(candidates, remainingSteps)
                val responseBody = callAnthropic(prompt)
                complete(parseResponse(responseBody, candidates))
            } catch (t: Throwable) {
                Log.w(TAG, "rescue call failed", t)
                complete(LostRescueResult.Failed(t.message ?: "error"))
            }
        }
    }

    private fun buildCandidates(elements: List<ScreenElement>): List<ScreenElement> =
        elements
            .filter { it.isVisible && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) }
            .take(MAX_CANDIDATES)

    private fun buildPrompt(candidates: List<ScreenElement>, remainingSteps: List<GuidanceStep>): String {
        val stepsBlock = remainingSteps.mapIndexed { i, step -> "${i + 1}. ${step.instruction}" }.joinToString("\n")
        val elementsBlock = candidates.mapIndexed { i, e ->
            val text = e.text?.take(60).orEmpty()
            val desc = e.contentDescription?.take(60).orEmpty()
            "$i: text=\"$text\" desc=\"$desc\""
        }.joinToString("\n")

        return """
            You are helping guide an elderly user through a phone screen. They got lost mid-task
            and normal on-screen matching couldn't find what they need. Below is what's really on
            their screen right now, and what they still need to do, in order. Reply with ONLY
            compact JSON, nothing else, no markdown.

            Remaining steps (they need step 1 next):
            $stepsBlock

            Screen elements:
            $elementsBlock

            If one element clearly matches step 1, reply:
            {"index": <number>, "say": "<short spoken instruction, under 12 words>"}
            If nothing on screen looks right for step 1, reply:
            {"unclear": true}
        """.trimIndent()
    }

    private fun callAnthropic(prompt: String): String {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
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
                throw IOException("Anthropic API returned $status: ${responseText.take(200)}")
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(responseBody: String, candidates: List<ScreenElement>): LostRescueResult {
        val text = JSONObject(responseBody)
            .optJSONArray("content")
            ?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
            ?.firstOrNull { it.optString("type") == "text" }
            ?.optString("text")
            ?: return LostRescueResult.Unclear

        val jsonText = extractJsonObject(text) ?: return LostRescueResult.Unclear
        val parsed = JSONObject(jsonText)
        if (parsed.optBoolean("unclear", false)) return LostRescueResult.Unclear

        val index = parsed.optInt("index", -1)
        val say = parsed.optString("say").ifBlank { "This might be it." }
        val element = candidates.getOrNull(index) ?: return LostRescueResult.Unclear
        return LostRescueResult.Found(element, say)
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null
        return text.substring(start, end + 1)
    }

    companion object {
        private const val TAG = "AnthropicLostRescuer"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_CANDIDATES = 60
        private const val TIMEOUT_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val READ_TIMEOUT_MS = 2_500
    }
}
