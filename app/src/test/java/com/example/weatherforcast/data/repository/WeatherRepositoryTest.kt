package com.example.weatherforcast.data.repository

import com.example.weatherforcast.data.local.WeatherDao
import com.example.weatherforcast.data.remote.DailyData
import com.example.weatherforcast.data.remote.GeocodingApiService
import com.example.weatherforcast.data.remote.GeocodingResponse
import com.example.weatherforcast.data.remote.GeoLocation
import com.example.weatherforcast.data.remote.OpenMeteoApiService
import com.example.weatherforcast.data.remote.OpenMeteoResponse
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherRepositoryTest {

    private val geocodingApi = mockk<GeocodingApiService>()
    private val weatherApi = mockk<OpenMeteoApiService>()

    // relaxed = true: suspend fns returning Unit (insertForecasts, deleteForCity)
    // are auto-stubbed to do nothing.
    private val dao = mockk<WeatherDao>(relaxed = true)

    private lateinit var repository: WeatherRepository

    @Before
    fun setup() {
        repository = WeatherRepository(geocodingApi, weatherApi, dao)
    }

    // ── Geocoding failure paths ───────────────────────────────────────────────

    @Test
    fun `returns failure when geocoding results is null`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(results = null)

        val result = repository.fetchAndCacheForecast("Xyz")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found", ignoreCase = true))
    }

    @Test
    fun `returns failure when geocoding results is empty`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(results = emptyList())

        val result = repository.fetchAndCacheForecast("Xyz")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not found", ignoreCase = true))
    }

    @Test
    fun `error message includes the city name the user searched for`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(results = null)

        val result = repository.fetchAndCacheForecast("Atlantis")

        assertTrue(result.exceptionOrNull()!!.message!!.contains("Atlantis"))
    }

    @Test
    fun `returns failure when geocoding API throws`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } throws Exception("Network error")

        val result = repository.fetchAndCacheForecast("London")

        assertTrue(result.isFailure)
        assertEquals("Network error", result.exceptionOrNull()!!.message)
    }

    // ── Weather API failure paths ─────────────────────────────────────────────

    @Test
    fun `returns failure when weather API throws`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } throws Exception("Timeout")

        val result = repository.fetchAndCacheForecast("London")

        assertTrue(result.isFailure)
        assertEquals("Timeout", result.exceptionOrNull()!!.message)
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `returns 3 entities on successful 3-day response`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val result = repository.fetchAndCacheForecast("London")

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull()!!.size)
    }

    @Test
    fun `first entity dayOfWeek is always Today`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals("Today", entities[0].dayOfWeek)
    }

    @Test
    fun `subsequent entities show day of week name not Today`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertTrue(entities[1].dayOfWeek != "Today")
        assertTrue(entities[2].dayOfWeek != "Today")
    }

    // ── Temperature calculations ──────────────────────────────────────────────

    @Test
    fun `temperature is average of daily max and min`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        // Day 0: max=10.0, min=4.0 → avg=7.0
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals(7.0, entities[0].temperature, 0.001)
    }

    @Test
    fun `feelsLike is average of apparent temperature max and min`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        // Day 0: apparentMax=8.0, apparentMin=2.0 → avg=5.0
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals(5.0, entities[0].feelsLike, 0.001)
    }

    @Test
    fun `tempMin and tempMax are stored as-is from API`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals(4.0, entities[0].tempMin, 0.001)
        assertEquals(10.0, entities[0].tempMax, 0.001)
    }

    // ── Precipitation probability edge cases ──────────────────────────────────

    @Test
    fun `precipitationProbability defaults to 0 when field is null`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(
            precipitationProbabilityMax = null
        )

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        entities.forEach { assertEquals(0, it.precipitationProbability) }
    }

    @Test
    fun `precipitationProbability is read per-day from response`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(
            precipitationProbabilityMax = listOf(10, 75, 45)
        )

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals(10, entities[0].precipitationProbability)
        assertEquals(75, entities[1].precipitationProbability)
        assertEquals(45, entities[2].precipitationProbability)
    }

    // ── Partial API response ──────────────────────────────────────────────────

    @Test
    fun `returns 1 entity when API provides only 1 day`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(days = 1)

        val result = repository.fetchAndCacheForecast("London")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
    }

    @Test
    fun `returns 2 entities when API provides only 2 days`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(days = 2)

        val result = repository.fetchAndCacheForecast("London")

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.size)
    }

    // ── Display city name ─────────────────────────────────────────────────────

    @Test
    fun `displayCityName includes country code when present`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("London", 51.5, -0.12, "United Kingdom", "GB"))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals("London, GB", entities.first().displayCityName)
    }

    @Test
    fun `displayCityName is just city name when countryCode is null`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("Paris", 48.8, 2.3, "France", null))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("Paris").getOrNull()!!

        assertEquals("Paris", entities.first().displayCityName)
    }

    // ── Entity fields ─────────────────────────────────────────────────────────

    // ── Bug fix #1: cityName key consistency ─────────────────────────────────
    // cityName must be the search input (lowercased), NOT the display name.
    // Otherwise getCachedForecast("paris") queries "paris" but rows have "paris, fr".

    @Test
    fun `entity id uses search input not display name`() = runTest {
        // User searches "London"; API returns display name "London, GB"
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("London", 51.5, -0.12, "United Kingdom", "GB"))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        // id must use "london" (search input), not "london, gb" (display name)
        assertEquals("london_2024-01-01", entities[0].id)
    }

    @Test
    fun `entity cityName is the search input lowercased`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("BERLIN", 52.5, 13.4, "Germany", "DE"))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("berlin").getOrNull()!!

        // Must be "berlin" not "berlin, de" — otherwise cache queries never match
        assertEquals("berlin", entities.first().cityName)
    }

    @Test
    fun `cityName key matches what deleteForCity uses`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("Paris", 48.8, 2.3, "France", "FR"))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("Paris").getOrNull()!!

        // deleteForCity("Paris".lowercase()) = "paris" must equal entity.cityName
        assertEquals("paris", entities.first().cityName)
    }

    @Test
    fun `displayCityName is separate from cityName key`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("Paris", 48.8, 2.3, "France", "FR"))
        )
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        val entities = repository.fetchAndCacheForecast("Paris").getOrNull()!!

        // DB key and display name must be independent
        assertEquals("paris", entities.first().cityName)
        assertEquals("Paris, FR", entities.first().displayCityName)
    }

    @Test
    fun `weatherCode from API is stored in entity`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(
            weatherCodes = listOf(0, 61, 95)
        )

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals(0, entities[0].weatherCode)
        assertEquals(61, entities[1].weatherCode)
        assertEquals(95, entities[2].weatherCode)
    }

    @Test
    fun `description is derived from weatherCode via wmoDescription`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse(
            weatherCodes = listOf(0, 63, 95)
        )

        val entities = repository.fetchAndCacheForecast("London").getOrNull()!!

        assertEquals("Clear sky", entities[0].description)
        assertEquals("Moderate rain", entities[1].description)
        assertEquals("Thunderstorm", entities[2].description)
    }

    @Test
    fun `geocoding coordinates are forwarded to weather API`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(
            results = listOf(GeoLocation("Tokyo", 35.6762, 139.6503, "Japan", "JP"))
        )
        coEvery { weatherApi.getDailyForecast(35.6762, 139.6503) } returns validWeatherResponse()

        val result = repository.fetchAndCacheForecast("Tokyo")

        assertTrue(result.isSuccess)
    }

    // ── DAO interaction ───────────────────────────────────────────────────────

    @Test
    fun `old city data is deleted before inserting new forecast`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } returns validWeatherResponse()

        repository.fetchAndCacheForecast("London")

        coVerifyOrder {
            dao.deleteForCity(any())
            dao.insertForecasts(any())
        }
    }

    @Test
    fun `no data is saved to DAO on geocoding failure`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns GeocodingResponse(results = null)

        repository.fetchAndCacheForecast("Xyz")

        io.mockk.coVerify(exactly = 0) { dao.insertForecasts(any()) }
        io.mockk.coVerify(exactly = 0) { dao.deleteForCity(any()) }
    }

    @Test
    fun `no data is saved to DAO on weather API failure`() = runTest {
        coEvery { geocodingApi.searchCity(any()) } returns validGeoResponse()
        coEvery { weatherApi.getDailyForecast(any(), any()) } throws Exception("Timeout")

        repository.fetchAndCacheForecast("London")

        io.mockk.coVerify(exactly = 0) { dao.insertForecasts(any()) }
    }

    @Test
    fun `getCachedForecast passes lowercased city to DAO`() {
        every { dao.getForecastForCity("london") } returns flowOf(emptyList())

        repository.getCachedForecast("London")

        verify { dao.getForecastForCity("london") }
    }

    @Test
    fun `getLastSearchedCity returns value from DAO`() = runTest {
        coEvery { dao.getLastSearchedCity() } returns "london"

        val result = repository.getLastSearchedCity()

        assertEquals("london", result)
    }

    @Test
    fun `getLastSearchedCity returns null when DAO returns null`() = runTest {
        coEvery { dao.getLastSearchedCity() } returns null

        val result = repository.getLastSearchedCity()

        assertNull(result)
    }

    // ── Test data helpers ─────────────────────────────────────────────────────

    private fun validGeoResponse() = GeocodingResponse(
        results = listOf(GeoLocation("London", 51.5085, -0.1257, "United Kingdom", "GB"))
    )

    private fun validWeatherResponse(
        days: Int = 3,
        weatherCodes: List<Int> = listOf(0, 61, 80),
        precipitationProbabilityMax: List<Int>? = listOf(0, 80, 60)
    ): OpenMeteoResponse {
        val n = days
        return OpenMeteoResponse(
            latitude = 51.5,
            longitude = -0.125,
            timezone = "Europe/London",
            daily = DailyData(
                time = listOf("2024-01-01", "2024-01-02", "2024-01-03").take(n),
                weathercode = weatherCodes.take(n),
                temperatureMax = listOf(10.0, 12.0, 8.0).take(n),
                temperatureMin = listOf(4.0, 6.0, 2.0).take(n),
                apparentTemperatureMax = listOf(8.0, 10.0, 6.0).take(n),
                apparentTemperatureMin = listOf(2.0, 4.0, 0.0).take(n),
                windspeedMax = listOf(15.0, 20.0, 10.0).take(n),
                precipitationProbabilityMax = precipitationProbabilityMax?.take(n)
            )
        )
    }
}
