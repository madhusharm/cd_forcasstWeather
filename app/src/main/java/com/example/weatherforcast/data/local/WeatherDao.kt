package com.example.weatherforcast.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeatherDao {

    @Query("SELECT * FROM weather_forecast WHERE cityName = :city ORDER BY date ASC LIMIT 3")
    fun getForecastForCity(city: String): Flow<List<WeatherEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecasts(forecasts: List<WeatherEntity>)

    @Query("DELETE FROM weather_forecast WHERE cityName = :city")
    suspend fun deleteForCity(city: String)

    @Query("SELECT DISTINCT cityName FROM weather_forecast ORDER BY fetchedAt DESC LIMIT 1")
    suspend fun getLastSearchedCity(): String?
}
