package com.example.weatherforcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.weatherforcast.data.local.WeatherEntity
import com.example.weatherforcast.data.remote.GeoLocation
import com.example.weatherforcast.data.repository.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeatherUiState(
    val isLoading: Boolean = false,
    val city: String = "",
    val displayCityName: String = "",
    val forecasts: List<WeatherEntity> = emptyList(),
    val error: String? = null,
    val isOfflineData: Boolean = false,
    val suggestions: List<GeoLocation> = emptyList(),
    val showSuggestions: Boolean = false
)

class WeatherViewModel(private val repository: WeatherRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(WeatherUiState())
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    // Bug fix #4: cancel any in-flight fetch before starting a new one
    private var fetchJob: Job? = null
    private var cacheObserverJob: Job? = null
    private var suggestionsJob: Job? = null

    fun onCityChange(city: String) {
        _uiState.update { it.copy(city = city, error = null, showSuggestions = false) }
        suggestionsJob?.cancel()
        if (city.trim().length >= 2) {
            suggestionsJob = viewModelScope.launch {
                delay(300)
                val results = repository.getSuggestions(city.trim())
                _uiState.update { it.copy(suggestions = results, showSuggestions = results.isNotEmpty()) }
            }
        } else {
            _uiState.update { it.copy(suggestions = emptyList()) }
        }
    }

    fun onSuggestionSelected(suggestion: GeoLocation) {
        suggestionsJob?.cancel()
        _uiState.update { it.copy(city = suggestion.name, suggestions = emptyList(), showSuggestions = false) }
        fetchWeather()
    }

    fun clearSuggestions() {
        _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
    }

    fun fetchWeather() {
        val city = _uiState.value.city.trim()
        if (city.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a city name") }
            return
        }

        // Bug fix #4: cancel the previous fetch so rapid searches don't race
        fetchJob?.cancel()
        suggestionsJob?.cancel()
        _uiState.update { it.copy(suggestions = emptyList(), showSuggestions = false) }

        fetchJob = viewModelScope.launch {
            // Bug fix #3: clear stale forecasts immediately so the spinner is
            // the only thing visible while the new city loads
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    forecasts = emptyList(),
                    displayCityName = "",
                    isOfflineData = false
                )
            }

            repository.fetchAndCacheForecast(city)
                .onSuccess { entities ->
                    // Bug fix #2: set forecasts directly from the response —
                    // don't wait for the Room Flow to emit (eliminates the delay)
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isOfflineData = false,
                            forecasts = entities,
                            displayCityName = entities.firstOrNull()?.displayCityName ?: city,
                            error = null
                        )
                    }
                    // Keep observing Room so any future cache updates (e.g. background
                    // refresh) are picked up automatically
                    startObservingCache(city)
                }
                .onFailure { error ->
                    // Try Room cache for this exact city
                    val cached = repository.getCachedForecast(city).first()
                    if (cached.isNotEmpty()) {
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                forecasts = cached,
                                displayCityName = cached.first().displayCityName,
                                isOfflineData = true,
                                error = "No connection — showing cached data"
                            )
                        }
                    } else {
                        _uiState.update { state ->
                            state.copy(
                                isLoading = false,
                                isOfflineData = false,
                                error = error.message ?: "Failed to fetch weather"
                            )
                        }
                    }
                }
        }
    }

    private fun startObservingCache(city: String) {
        cacheObserverJob?.cancel()
        cacheObserverJob = viewModelScope.launch {
            repository.getCachedForecast(city).collect { forecasts ->
                if (forecasts.isNotEmpty()) {
                    _uiState.update { state ->
                        state.copy(
                            forecasts = forecasts,
                            displayCityName = forecasts.first().displayCityName
                        )
                    }
                }
            }
        }
    }
}

class WeatherViewModelFactory(private val repository: WeatherRepository) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        WeatherViewModel(repository) as T
}
