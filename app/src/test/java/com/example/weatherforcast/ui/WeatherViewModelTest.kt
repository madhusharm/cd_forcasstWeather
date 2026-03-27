package com.example.weatherforcast.ui

import com.example.weatherforcast.data.local.WeatherEntity
import com.example.weatherforcast.data.repository.WeatherRepository
import io.mockk.coEvery
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
        // Default: no last city, empty cache for any query
        coEvery { repository.getLastSearchedCity() } returns null
        every { repository.getCachedForecast(any()) } returns flowOf(emptyList())
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

    // ── Init: last city restored ──────────────────────────────────────────────

    @Test
    fun `init restores last searched city into the text field`() = runTest {
        coEvery { repository.getLastSearchedCity() } returns "london"
        every { repository.getCachedForecast("london") } returns
            flowOf(sampleEntities("London", "London, GB"))

        // Recreate ViewModel so init runs with our stub
        val vm = WeatherViewModel(repository)
        advanceUntilIdle()

        assertEquals("london", vm.uiState.value.city)
    }

    @Test
    fun `init shows cached forecast for last searched city without fetching`() = runTest {
        val cached = sampleEntities("London", "London, GB")
        coEvery { repository.getLastSearchedCity() } returns "london"
        every { repository.getCachedForecast("london") } returns flowOf(cached)

        val vm = WeatherViewModel(repository)
        advanceUntilIdle()

        assertEquals(cached, vm.uiState.value.forecasts)
        io.mockk.coVerify(exactly = 0) { repository.fetchAndCacheForecast(any()) }
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
