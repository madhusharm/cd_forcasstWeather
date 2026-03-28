package com.example.weatherforcast.data.repository

import com.example.weatherforcast.data.local.WeatherDao
import com.example.weatherforcast.data.local.WeatherEntity
import com.example.weatherforcast.data.remote.GeocodingApiService
import com.example.weatherforcast.data.remote.OpenMeteoApiService
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherRepository(
    private val geocodingApi: GeocodingApiService,
    private val weatherApi: OpenMeteoApiService,
    private val dao: WeatherDao
) {

    fun getCachedForecast(city: String): Flow<List<WeatherEntity>> =
        dao.getForecastForCity(city.lowercase())

    suspend fun getLastSearchedCity(): String? = dao.getLastSearchedCity()

    suspend fun getSuggestions(query: String): List<com.example.weatherforcast.data.remote.GeoLocation> =
        try {
            geocodingApi.searchCity(name = query, count = 5).results ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * 1. Geocode [city] → lat/lon + canonical city name
     * 2. Fetch 3-day daily forecast from Open-Meteo
     * 3. Persist to Room and return the entities
     */
    suspend fun fetchAndCacheForecast(city: String): Result<List<WeatherEntity>> {
        return try {
            // Step 1: resolve city name to coordinates
            val geoResponse = geocodingApi.searchCity(city)
            val location = geoResponse.results?.firstOrNull()
                ?: return Result.failure(Exception("City \"$city\" not found. Check the spelling and try again."))

            // Step 2: fetch forecast using coordinates
            val forecast = weatherApi.getDailyForecast(
                latitude = location.latitude,
                longitude = location.longitude
            )

            // Step 3: map API response → Room entities
            val displayName = buildDisplayName(location.name, location.countryCode)
            val entities = buildEntities(city, displayName, forecast.daily)

            dao.deleteForCity(city.lowercase())
            dao.insertForecasts(entities)
            Result.success(entities)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildDisplayName(name: String, countryCode: String?): String =
        if (countryCode != null) "$name, $countryCode" else name

    private fun buildEntities(
        searchCity: String,          // original user input — used as the DB key
        displayCityName: String,     // canonical name from API, e.g. "Paris, FR"
        daily: com.example.weatherforcast.data.remote.DailyData
    ): List<WeatherEntity> {
        val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        return daily.time.indices.take(3).map { i ->
            val parsedDate: Date = runCatching { dateFormat.parse(daily.time[i]) }.getOrNull() ?: Date()
            val dayOfWeek = if (i == 0) "Today" else dayFormat.format(parsedDate)

            val tMax = daily.temperatureMax[i]
            val tMin = daily.temperatureMin[i]
            val atMax = daily.apparentTemperatureMax[i]
            val atMin = daily.apparentTemperatureMin[i]
            val code = daily.weathercode[i]

            WeatherEntity(
                id = "${searchCity.lowercase()}_${daily.time[i]}",
                cityName = searchCity.lowercase(),   // must match deleteForCity / getCachedForecast
                displayCityName = displayCityName,
                date = daily.time[i],
                dayOfWeek = dayOfWeek,
                temperature = (tMax + tMin) / 2.0,
                tempMin = tMin,
                tempMax = tMax,
                feelsLike = (atMax + atMin) / 2.0,
                description = wmoDescription(code),
                weatherCode = code,
                precipitationProbability = daily.precipitationProbabilityMax?.getOrNull(i) ?: 0,
                windSpeed = daily.windspeedMax[i]
            )
        }
    }
}

// ── WMO weather interpretation codes → human-readable description ─────────────
// https://open-meteo.com/en/docs#weathervariables

fun wmoDescription(code: Int): String = when (code) {
    0 -> "Clear sky"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45 -> "Fog"
    48 -> "Rime fog"
    51 -> "Light drizzle"
    53 -> "Drizzle"
    55 -> "Heavy drizzle"
    61 -> "Light rain"
    63 -> "Moderate rain"
    65 -> "Heavy rain"
    71 -> "Light snow"
    73 -> "Moderate snow"
    75 -> "Heavy snow"
    77 -> "Snow grains"
    80 -> "Light showers"
    81 -> "Showers"
    82 -> "Heavy showers"
    85 -> "Snow showers"
    86 -> "Heavy snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Thunderstorm w/ hail"
    else -> "Unknown"
}

fun wmoEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55 -> "🌦️"
    61, 63, 65 -> "🌧️"
    71, 73, 75, 77 -> "❄️"
    80, 81 -> "🌦️"
    82 -> "⛈️"
    85, 86 -> "🌨️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
