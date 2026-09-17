package com.ghost.assistant.features

import android.content.Context
import android.util.Log
import com.ghost.assistant.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CompanionManager {

    /**
     * Dispatches a companion command to the configured laptop/desktop companion server.
     */
    suspend fun executeCompanionDirective(
        context: Context,
        commandType: String,
        parameter: String,
        userName: String
    ): String = withContext(Dispatchers.IO) {
        val host = ThemeManager.getCompanionHost(context)
        val port = ThemeManager.getCompanionPort(context)

        if (host.isBlank()) {
            return@withContext "Laptop companion host is not configured. Please set the desktop IP in Config screen, $userName."
        }

        try {
            val endpoint = "http://$host:$port/api/command"
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.doOutput = true

            val payload = JSONObject().apply {
                put("command", commandType)
                put("param", parameter)
                put("source", "ghost_mobile")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

            if (conn.responseCode in 200..204) {
                val responseText = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(responseText)
                val replyMsg = json.optString("message").ifBlank { json.optString("output") }.ifBlank { "Command acknowledged" }
                return@withContext "Laptop companion executed: $replyMsg, $userName."
            } else {
                return@withContext "Laptop companion returned error code ${conn.responseCode}, $userName."
            }
        } catch (e: Exception) {
            Log.e("CompanionManager", "Error connecting to companion: ${e.message}", e)
            return@withContext "Laptop companion is unreachable at $host:$port. Ensure desktop companion is active, $userName."
        }
    }
}
