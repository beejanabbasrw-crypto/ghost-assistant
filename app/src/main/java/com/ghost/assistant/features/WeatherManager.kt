package com.ghost.assistant.features

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object WeatherManager {

    /**
     * Queries online weather lookup for a location or default city.
     * Uses wttr.in JSON format (free, no API key required).
     */
    suspend fun getWeatherSummary(location: String?, userName: String): String = withContext(Dispatchers.IO) {
        val targetCity = location?.trim()?.ifBlank { null }
        val encodedCity = if (targetCity != null) URLEncoder.encode(targetCity, "UTF-8") else ""
        val urlString = if (encodedCity.isNotBlank()) "https://wttr.in/$encodedCity?format=j1" else "https://wttr.in/?format=j1"

        try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "GHOST-Assistant/1.0 (Android; Mobile)")
            conn.connectTimeout = 4000
            conn.readTimeout = 4000

            if (conn.responseCode == 200) {
                val responseText = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                val json = JSONObject(responseText)

                val currentCond = json.getJSONArray("current_condition").getJSONObject(0)
                val tempC = currentCond.getString("temp_C")
                val tempF = currentCond.getString("temp_F")
                val humidity = currentCond.getString("humidity")
                val weatherDesc = currentCond.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
                val windKmph = currentCond.getString("windspeedKmph")

                val areaName = try {
                    val area = json.getJSONArray("nearest_area").getJSONObject(0)
                    area.getJSONArray("areaName").getJSONObject(0).getString("value")
                } catch (_: Exception) {
                    targetCity ?: "your current location"
                }

                return@withContext "Weather in $areaName is currently $tempC°C ($tempF°F) with $weatherDesc. Humidity is at $humidity%, wind speed $windKmph km/h, $userName."
            }
        } catch (e: Exception) {
            Log.e("WeatherManager", "Error fetching weather: ${e.message}", e)
        }

        val cityMention = if (targetCity != null) "for $targetCity" else "at your location"
        return@withContext "Unable to retrieve meteorological data $cityMention right now. Check network connection, $userName."
    }
}
