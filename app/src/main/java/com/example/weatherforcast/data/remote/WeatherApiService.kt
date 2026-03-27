package com.example.weatherforcast.data.remote

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ── Geocoding ─────────────────────────────────────────────────────────────────
// Resolves a city name to latitude / longitude (no API key required).

interface GeocodingApiService {

    @GET("search")
    suspend fun searchCity(
        @Query("name") name: String,
        @Query("count") count: Int = 1,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json"
    ): GeocodingResponse

    companion object {
        fun create(context: Context): GeocodingApiService = buildRetrofit(
            baseUrl = "https://geocoding-api.open-meteo.com/v1/",
            context = context
        ).create(GeocodingApiService::class.java)
    }
}

// ── Weather forecast ──────────────────────────────────────────────────────────
// Fetches a 3-day daily forecast from Open-Meteo (no API key required).

interface OpenMeteoApiService {

    @GET("forecast")
    suspend fun getDailyForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("daily") daily: String =
            "weathercode," +
            "temperature_2m_max," +
            "temperature_2m_min," +
            "apparent_temperature_max," +
            "apparent_temperature_min," +
            "windspeed_10m_max," +
            "precipitation_probability_max",
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 3
    ): OpenMeteoResponse

    companion object {
        fun create(context: Context): OpenMeteoApiService = buildRetrofit(
            baseUrl = "https://api.open-meteo.com/v1/",
            context = context
        ).create(OpenMeteoApiService::class.java)
    }
}

// ── Shared OkHttp client with Chucker ────────────────────────────────────────

private fun buildRetrofit(baseUrl: String, context: Context): Retrofit {
    val client = OkHttpClient.Builder()
        // Chucker: visible in debug builds; replaced by no-op in release
        .addInterceptor(
            ChuckerInterceptor.Builder(context)
                .maxContentLength(250_000L)
                .alwaysReadResponseBody(true)
                .build()
        )
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}
