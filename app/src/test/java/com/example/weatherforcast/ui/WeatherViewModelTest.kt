package com.example.weatherforcast.ui

import com.example.weatherforcast.data.local.WeatherEntity
import com.example.weatherforcast.data.remote.GeoLocation
import com.example.weatherforcast.data.repository.WeatherRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository = mockk<WeatherRepository>(relaxed = true)
    private lateinit var viewModel: WeatherViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // Default stubs — individual tests override as needed
        coEvery { repository.getLastSearchedCity() } returns null
        every { repository.getCachedForecast(any()) } returns flowOf(emptyList())
        coEvery { repository.getSuggestions(any()) } returns emptyList()
        viewModel = WeatherViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Bug fix #3: stale data cleared on new search ──────────────────────────

    @Test
    fun `forecasts are cleared immediately when a new search starts`() = runTest {
        // Pre-populate state with London data
        coEvery { repository.fetchAndCacheForecast("London") } returns
            Result.success(sampleEntities("London", "London, GB"))
        every { repository.getCachedForecast("london") } returns
            flowOf(sampleEntities("London", "London, GB"))

        viewModel.onCityChange("London")
        viewModel.fetchWeather()
        advanceUntilIdle()

        // Now search a new city — forecasts must be empty before the response arrives
        coEvery { repository.fetchAndCacheForecast("Paris") } coAnswers {
            // Suspend here — we want to observe the intermediate "loading" state
            kotlinx.coroutines.delay(1_000)
            Result.success(sampleEntities("Paris", "Paris, FR"))
        }
        viewModel.onCityChange("Paris")
        viewModel.fetchWeather()

        // At this point the fetch is suspended — we should see empty forecasts, not London's
        val loadingState = viewModel.uiState.value
        assertTrue("isLoading should be true", loadingState.isLoading)
        assertTrue("forecasts should be empty while loading new city", loadingState.forecasts.isEmpty())
    }

    @Test
    fun `displayCityName is cleared immediately when a new search starts`() = runTest {
        coEvery { repository.fetchAndCacheForecast("London") } returns
            Result.success(sampleEntities("London", "London, GB"))
        every { repository.getCachedForecast("london") } returns
            flowOf(sampleEntities("London", "London, GB"))

        viewModel.onCityChange("London")
        viewModel.fetchWeather()
        advanceUntilIdle()

        coEvery { repository.fetchAndCacheForecast("Paris") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Result.success(sampleEntities("Paris", "Paris, FR"))
        }
        viewModel.onCityChange("Paris")
        viewModel.fetchWeather()

        assertEquals("", viewModel.uiState.value.displayCityName)
    }

    // ── Bug fix #2: data shown immediately, not after cache observer ──────────

    @Test
    fun `forecasts are set directly from API response without waiting for cache`() = runTest {
        val parisEntities = sampleEntities("Paris", "Paris, FR")
        coEvery { repository.fetchAndCacheForecast("Paris") } returns Result.success(parisEntities)
        // Cache observer returns empty — data must still appear from the direct assignment
        every { repository.getCachedForecast("paris") } returns flowOf(emptyList())

        viewModel.onCityChange("Paris")
        viewModel.fetchWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(parisEntities, state.forecasts)
        assertEquals("Paris, FR", state.displayCityName)
    }

    // ── Bug fix #4: rapid searches — only last result wins ────────────────────

    @Test
    fun `second search cancels the first — only second city data appears`() = runTest {
        // London takes a long time
        coEvery { repository.fetchAndCacheForecast("London") } coAnswers {
            kotlinx.coroutines.delay(5_000)
            Result.success(sampleEntities("London", "London, GB"))
        }
        // Paris is fast
        coEvery { repository.fetchAndCacheForecast("Paris") } returns
            Result.success(sampleEntities("Paris", "Paris, FR"))
        every { repository.getCachedForecast("paris") } returns
            flowOf(sampleEntities("Paris", "Paris, FR"))

        viewModel.onCityChange("London")
        viewModel.fetchWeather()         // starts London fetch (slow)

        viewModel.onCityChange("Paris")
        viewModel.fetchWeather()         // cancels London, starts Paris fetch

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Should not be loading", state.isLoading)
        assertEquals("Paris, FR", state.displayCityName)
        assertEquals("paris", state.forecasts.firstOrNull()?.cityName)
    }

    // ── Blank city validation ─────────────────────────────────────────────────

    @Test
    fun `fetchWeather with blank city sets error and does not call repository`() = runTest {
        viewModel.onCityChange("   ")
        viewModel.fetchWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Please enter a city name", state.error)
        io.mockk.coVerify(exactly = 0) { repository.fetchAndCacheForecast(any()) }
    }

    @Test
    fun `fetchWeather with empty string sets error`() = runTest {
        viewModel.onCityChange("")
        viewModel.fetchWeather()
        advanceUntilIdle()

        assertEquals("Please enter a city name", viewModel.uiState.value.error)
    }

    // ── Offline fallback ──────────────────────────────────────────────────────

    @Test
    fun `shows cached data with offline banner when network fails and cache exists`() = runTest {
        val cached = sampleEntities("Paris", "Paris, FR")
        coEvery { repository.fetchAndCacheForecast("Paris") } returns
            Result.failure(Exception("No internet"))
        every { repository.getCachedForecast("paris") } returns flowOf(cached)

        viewModel.onCityChange("Paris")
        viewModel.fetchWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.isOfflineData)
        assertEquals(cached, state.forecasts)
        assertEquals("Paris, FR", state.displayCityName)
    }

    @Test
    fun `shows error message when network fails and no cache exists`() = runTest {
        coEvery { repository.fetchAndCacheForecast("Unknown") } returns
            Result.failure(Exception("City not found"))
        every { repository.getCachedForecast("unknown") } returns flowOf(emptyList())

        viewModel.onCityChange("Unknown")
        viewModel.fetchWeather()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertFalse(state.isOfflineData)
        assertEquals("City not found", state.error)
        assertTrue(state.forecasts.isEmpty())
    }

    // ── Loading state ─────────────────────────────────────────────────────────

    @Test
    fun `isLoading is true while fetch is in progress`() = runTest {
        coEvery { repository.fetchAndCacheForecast("London") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Result.success(sampleEntities("London", "London, GB"))
        }

        viewModel.onCityChange("London")
        viewModel.fetchWeather()

        assertTrue(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `isLoading is false after successful fetch`() = runTest {
        coEvery { repository.fetchAndCacheForecast("London") } returns
            Result.success(sampleEntities("London", "London, GB"))
        every { repository.getCachedForecast("london") } returns
            flowOf(sampleEntities("London", "London, GB"))

        viewModel.onCityChange("London")
        viewModel.fetchWeather()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `isLoading is false after failed fetch`() = runTest {
        coEvery { repository.fetchAndCacheForecast("X") } returns
            Result.failure(Exception("Error"))
        every { repository.getCachedForecast("x") } returns flowOf(emptyList())

        viewModel.onCityChange("X")
        viewModel.fetchWeather()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
    }

    // ── City text field ───────────────────────────────────────────────────────

    @Test
    fun `onCityChange updates city and clears error`() = runTest {
        // Put an error in state first
        viewModel.onCityChange("")
        viewModel.fetchWeather()
        advanceUntilIdle()

        viewModel.onCityChange("Rome")

        val state = viewModel.uiState.value
        assertEquals("Rome", state.city)
        assertNull(state.error)
    }

    // ── App startup ───────────────────────────────────────────────────────────

    @Test
    fun `city field is empty when app starts`() = runTest {
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.city)
    }

    @Test
    fun `no fetch is triggered on startup`() = runTest {
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.fetchAndCacheForecast(any()) }
    }

    // ── Suggestions ───────────────────────────────────────────────────────────

    @Test
    fun `onCityChange with 2 or more chars triggers suggestions after debounce`() = runTest {
        val results = listOf(GeoLocation("London", 51.5, -0.1, "United Kingdom", "GB"))
        coEvery { repository.getSuggestions("Lo") } returns results

        viewModel.onCityChange("Lo")
        // Before debounce fires, suggestions should be empty
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        assertFalse(viewModel.uiState.value.showSuggestions)

        advanceUntilIdle()

        assertEquals(results, viewModel.uiState.value.suggestions)
        assertTrue(viewModel.uiState.value.showSuggestions)
    }

    @Test
    fun `onCityChange with 1 char does not call getSuggestions`() = runTest {
        viewModel.onCityChange("L")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSuggestions(any()) }
        assertFalse(viewModel.uiState.value.showSuggestions)
    }

    @Test
    fun `onCityChange with empty string does not call getSuggestions`() = runTest {
        viewModel.onCityChange("")
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getSuggestions(any()) }
    }

    @Test
    fun `showSuggestions is false when getSuggestions returns empty list`() = runTest {
        coEvery { repository.getSuggestions(any()) } returns emptyList()

        viewModel.onCityChange("Xyzzy")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showSuggestions)
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    @Test
    fun `onCityChange clears existing suggestions immediately on each keystroke`() = runTest {
        // First load some suggestions
        coEvery { repository.getSuggestions("Lo") } returns
            listOf(GeoLocation("London", 51.5, -0.1, "United Kingdom", "GB"))
        viewModel.onCityChange("Lo")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSuggestions)

        // New keystroke hides suggestions immediately before debounce fires
        coEvery { repository.getSuggestions("Lon") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            emptyList()
        }
        viewModel.onCityChange("Lon")

        assertFalse(viewModel.uiState.value.showSuggestions)
    }

    @Test
    fun `rapid typing cancels previous debounce and only calls getSuggestions once`() = runTest {
        viewModel.onCityChange("Lo")
        viewModel.onCityChange("Lon")
        viewModel.onCityChange("Lond")
        advanceUntilIdle()

        // Only the final input should have triggered an API call
        coVerify(exactly = 1) { repository.getSuggestions(any()) }
        coVerify(exactly = 1) { repository.getSuggestions("Lond") }
    }

    @Test
    fun `onSuggestionSelected sets the city name in the text field`() = runTest {
        val suggestion = GeoLocation("Paris", 48.8, 2.3, "France", "FR")
        coEvery { repository.fetchAndCacheForecast("Paris") } returns
            Result.success(sampleEntities("Paris", "Paris, FR"))
        every { repository.getCachedForecast("paris") } returns
            flowOf(sampleEntities("Paris", "Paris, FR"))

        viewModel.onSuggestionSelected(suggestion)
        advanceUntilIdle()

        assertEquals("Paris", viewModel.uiState.value.city)
    }

    @Test
    fun `onSuggestionSelected clears suggestions and hides dropdown`() = runTest {
        val suggestion = GeoLocation("Paris", 48.8, 2.3, "France", "FR")
        coEvery { repository.fetchAndCacheForecast("Paris") } returns
            Result.success(sampleEntities("Paris", "Paris, FR"))

        viewModel.onSuggestionSelected(suggestion)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        assertFalse(viewModel.uiState.value.showSuggestions)
    }

    @Test
    fun `onSuggestionSelected triggers weather fetch for that city`() = runTest {
        val suggestion = GeoLocation("Tokyo", 35.6, 139.6, "Japan", "JP")
        coEvery { repository.fetchAndCacheForecast("Tokyo") } returns
            Result.success(sampleEntities("Tokyo", "Tokyo, JP"))
        every { repository.getCachedForecast("tokyo") } returns
            flowOf(sampleEntities("Tokyo", "Tokyo, JP"))

        viewModel.onSuggestionSelected(suggestion)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.fetchAndCacheForecast("Tokyo") }
        assertEquals("Tokyo, JP", viewModel.uiState.value.displayCityName)
    }

    @Test
    fun `clearSuggestions empties suggestions and sets showSuggestions to false`() = runTest {
        coEvery { repository.getSuggestions("Be") } returns
            listOf(GeoLocation("Berlin", 52.5, 13.4, "Germany", "DE"))
        viewModel.onCityChange("Be")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSuggestions)

        viewModel.clearSuggestions()

        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
        assertFalse(viewModel.uiState.value.showSuggestions)
    }

    @Test
    fun `fetchWeather clears suggestions before the fetch starts`() = runTest {
        // Load suggestions first
        coEvery { repository.getSuggestions("Ro") } returns
            listOf(GeoLocation("Rome", 41.9, 12.5, "Italy", "IT"))
        viewModel.onCityChange("Ro")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showSuggestions)

        // Manually trigger a fetch
        viewModel.onCityChange("Rome")
        coEvery { repository.fetchAndCacheForecast("Rome") } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Result.success(sampleEntities("Rome", "Rome, IT"))
        }
        viewModel.fetchWeather()

        // Suggestions should be gone even before fetch completes
        assertFalse(viewModel.uiState.value.showSuggestions)
        assertTrue(viewModel.uiState.value.suggestions.isEmpty())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sampleEntities(searchCity: String, displayName: String) = listOf(
        WeatherEntity(
            id = "${searchCity.lowercase()}_2024-01-01",
            cityName = searchCity.lowercase(),
            displayCityName = displayName,
            date = "2024-01-01",
            dayOfWeek = "Today",
            temperature = 7.0,
            tempMin = 4.0,
            tempMax = 10.0,
            feelsLike = 5.0,
            description = "Clear sky",
            weatherCode = 0,
            precipitationProbability = 0,
            windSpeed = 15.0
        )
    )
}
