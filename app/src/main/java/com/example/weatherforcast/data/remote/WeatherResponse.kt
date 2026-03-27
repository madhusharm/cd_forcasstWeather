package com.example.weatherforcast.data.remote

import com.google.gson.annotations.SerializedName

// ── Geocoding API response ────────────────────────────────────────────────────
// GET https://geocoding-api.open-meteo.com/v1/search?name={city}&count=1

data class GeocodingResponse(
    val results: List<GeoLocation>?
)

data class GeoLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String?,
    @SerializedName("country_code") val countryCode: String?
)

// ── Forecast API response ─────────────────────────────────────────────────────
// GET https://api.open-meteo.com/v1/forecast?latitude=...&longitude=...&daily=...

data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val daily: DailyData
)

data class DailyData(
    val time: List<String>,
    val weathercode: List<Int>,
    @SerializedName("temperature_2m_max") val temperatureMax: List<Double>,
    @SerializedName("temperature_2m_min") val temperatureMin: List<Double>,
    @SerializedName("apparent_temperature_max") val apparentTemperatureMax: List<Double>,
    @SerializedName("apparent_temperature_min") val apparentTemperatureMin: List<Double>,
    @SerializedName("windspeed_10m_max") val windspeedMax: List<Double>,
    @SerializedName("precipitation_probability_max") val precipitationProbabilityMax: List<Int>?
)
