package com.example.weatherforcast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.weatherforcast.data.local.WeatherDatabase
import com.example.weatherforcast.data.remote.GeocodingApiService
import com.example.weatherforcast.data.remote.OpenMeteoApiService
import com.example.weatherforcast.data.repository.WeatherRepository
import com.example.weatherforcast.ui.WeatherScreen
import com.example.weatherforcast.ui.WeatherViewModel
import com.example.weatherforcast.ui.WeatherViewModelFactory
import com.example.weatherforcast.ui.theme.WeatherForcastTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WeatherViewModel by viewModels {
        val db = WeatherDatabase.getDatabase(applicationContext)
        val repository = WeatherRepository(
            geocodingApi = GeocodingApiService.create(applicationContext),
            weatherApi = OpenMeteoApiService.create(applicationContext),
            dao = db.weatherDao()
        )
        WeatherViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherForcastTheme {
                WeatherScreen(viewModel = viewModel)
            }
        }
    }
}
