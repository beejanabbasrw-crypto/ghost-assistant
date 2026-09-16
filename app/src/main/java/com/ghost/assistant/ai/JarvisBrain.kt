package com.ghost.assistant.ai

import android.content.Context
import com.ghost.assistant.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isJarvis: Boolean
)

class JarvisBrain(private val context: Context) {

    fun getInitialGreeting(): ChatMessage {
        val name = ThemeManager.getUserName(context)
        val timeGreeting = when (SimpleDateFormat("H", Locale.getDefault()).format(Date()).toIntOrNull() ?: 12) {
            in 4..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        val text = "$timeGreeting, $name. J.A.R.V.I.S. online. All workshop diagnostics nominal. How may I assist you today?"
        return ChatMessage(sender = "J.A.R.V.I.S.", message = text, isJarvis = true)
    }

    suspend fun getResponse(userPrompt: String): String = withContext(Dispatchers.IO) {
        val name = ThemeManager.getUserName(context)
        val clean = userPrompt.trim().lowercase(Locale.US)

        // 1. Check custom AI endpoint if configured
        val customEndpoint = ThemeManager.getAiCustomEndpoint(context)
        if (customEndpoint.isNotBlank()) {
            val customReply = queryCustomEndpoint(customEndpoint, userPrompt, name)
            if (customReply != null) {
                return@withContext customReply
            }
        }

        // 2. Intelligent local Jarvis tactical engine
        val localResponse = resolveLocalIntelligence(clean, name)
        if (localResponse != null) {
            return@withContext localResponse
        }

        // 3. Live intelligence lookup (Wikipedia / DuckDuckGo)
        val webBrief = queryWebIntelligence(userPrompt)
        if (webBrief != null) {
            return@withContext "According to tactical archives, $name: $webBrief"
        }

        // 4. In-character Jarvis fallback
        "I have analyzed the parameter, $name, but require additional telemetry to provide an actionable synthesis. Shall I re-scan the archives?"
    }

    private fun resolveLocalIntelligence(clean: String, name: String): String? {
        return when {
            clean.matches(Regex("^(hello|hi|hey|greetings|jarvis).*")) -> {
                "Always at your service, $name. Workshop power is steady and awaiting your commands."
            }
            clean.contains("who are you") || clean.contains("what are you") || clean.contains("your name") -> {
                "I am J.A.R.V.I.S. — Just A Rather Very Intelligent System. Configured specifically as your tactical assistant in the G.H.O.S.T. terminal, $name."
            }
            clean.contains("what can you do") || clean == "help" || clean.contains("commands") -> {
                "I can toggle tactical illumination, inspect power cells, launch installed applications, modulate audio frequencies, dispatch comm alerts, and fetch intelligence, $name."
            }
            clean.contains("status") || clean.contains("how are you") || clean.contains("diagnostics") -> {
                "All systems are functioning within optimal tolerances, $name. Arc reactor telemetry is stable at 100%."
            }
            clean.contains("armor") || clean.contains("suit") || clean.contains("mark") -> {
                "The armor configurations are standing by in the vault, $name. Nanotech dispersion algorithms are fully synchronized."
            }
            clean.contains("time") -> {
                val timeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                "The current local time is precisely $timeStr, $name."
            }
            clean.contains("date") || clean.contains("today") -> {
                val dateStr = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                "Today is $dateStr, $name."
            }
            clean.contains("creator") || clean.contains("who made you") -> {
                "I was designed inspired by Tony Stark's workshop architecture, tailored specifically for you, $name."
            }
            clean.contains("protocol") -> {
                "House Party protocol is currently locked down, $name. Local defensive perimeter remains active."
            }
            clean.contains("stark") || clean.contains("industries") -> {
                "Stark Industries engineering standards applied, $name. Precision over speculation."
            }
            clean.contains("thank") -> {
                "A pleasure as always, $name."
            }
            else -> null
        }
    }

    private fun queryWebIntelligence(query: String): String? {
        val cleanQuery = query.replace(Regex("^(what is|who is|search for|tell me about|define)\\s+", RegexOption.IGNORE_CASE), "").trim()

        // Wikipedia REST API
        try {
            val encoded = URLEncoder.encode(cleanQuery.replace(" ", "_"), "UTF-8")
            val url = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "GHOST-Jarvis/1.0 (Android; Linux)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                val extract = json.optString("extract")
                if (extract.isNotBlank()) {
                    val sentence = extract.split(". ").firstOrNull()?.let { if (!it.endsWith(".")) "$it." else it }
                    if (!sentence.isNullOrBlank()) return sentence
                }
            }
        } catch (_: Exception) {}

        // DuckDuckGo API
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"

            val code = conn.responseCode
            if (code == 200 || code == 202) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                val abstractText = json.optString("AbstractText")
                if (abstractText.isNotBlank()) {
                    return abstractText.split(". ").firstOrNull()?.let { if (!it.endsWith(".")) "$it." else it } ?: abstractText
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun queryCustomEndpoint(endpointUrl: String, prompt: String, name: String): String? {
        try {
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doOutput = true

            val systemPrompt = "You are J.A.R.V.I.S., Tony Stark's tactical AI assistant inside the G.H.O.S.T. mobile terminal. You address the user respectfully as '$name' or 'Sir'. Respond in a calm, dry-witted, formal, concise, and highly competent manner. Never break character."
            val body = JSONObject().apply {
                put("prompt", prompt)
                put("system", systemPrompt)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(response)
                return json.optString("response").ifBlank { json.optString("text") }.ifBlank { null }
            }
        } catch (_: Exception) {}
        return null
    }
}
