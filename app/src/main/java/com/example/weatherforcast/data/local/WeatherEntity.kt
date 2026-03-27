package com.example.weatherforcast.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weather_forecast")
data class WeatherEntity(
    @PrimaryKey
    val id: String,                      // "{cityName}_{date}", e.g. "london_2024-01-01"
    val cityName: String,                // lowercase, used for DB queries
    val displayCityName: String,         // original casing from geocoding API
    val date: String,                    // "2024-01-01"
    val dayOfWeek: String,               // "Today", "Monday", etc.
    val temperature: Double,             // average of daily max + min
    val tempMin: Double,
    val tempMax: Double,
    val feelsLike: Double,               // average of apparent_temperature max + min
    val description: String,             // human-readable from WMO code, e.g. "Light rain"
    val weatherCode: Int,                // WMO weather interpretation code
    val precipitationProbability: Int,   // max precipitation probability (%)
    val windSpeed: Double,               // max wind speed (km/h)
    val fetchedAt: Long = System.currentTimeMillis()
)
