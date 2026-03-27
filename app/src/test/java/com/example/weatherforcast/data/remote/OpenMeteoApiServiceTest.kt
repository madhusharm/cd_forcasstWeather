package com.example.weatherforcast.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class OpenMeteoApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: OpenMeteoApiService

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoApiService::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Successful parsing ────────────────────────────────────────────────────

    @Test
    fun `parses full 3-day response correctly`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val response = service.getDailyForecast(51.5, -0.125)

        assertEquals(51.5, response.latitude, 0.01)
        assertEquals(-0.125, response.longitude, 0.001)
        assertEquals("Europe/London", response.timezone)
        assertEquals(3, response.daily.time.size)
        assertEquals(listOf("2024-01-01", "2024-01-02", "2024-01-03"), response.daily.time)
    }

    @Test
    fun `parses weathercode array correctly`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertEquals(listOf(0, 61, 80), daily.weathercode)
    }

    @Test
    fun `parses temperature arrays with correct SerializedName mapping`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertEquals(listOf(10.0, 12.0, 8.0), daily.temperatureMax)
        assertEquals(listOf(4.0, 6.0, 2.0), daily.temperatureMin)
    }

    @Test
    fun `parses apparent temperature arrays with correct SerializedName mapping`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertEquals(listOf(8.0, 10.0, 6.0), daily.apparentTemperatureMax)
        assertEquals(listOf(2.0, 4.0, 0.0), daily.apparentTemperatureMin)
    }

    @Test
    fun `parses windspeedMax with correct SerializedName mapping`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertEquals(listOf(15.0, 20.0, 10.0), daily.windspeedMax)
    }

    @Test
    fun `parses precipitationProbabilityMax when present`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertEquals(listOf(0, 80, 60), daily.precipitationProbabilityMax)
    }

    // ── Nullable field ────────────────────────────────────────────────────────

    @Test
    fun `precipitationProbabilityMax is null when field is absent`() = runTest {
        server.enqueue(successResponse(NO_PRECIPITATION_JSON))

        val daily = service.getDailyForecast(51.5, -0.125).daily

        assertNull(daily.precipitationProbabilityMax)
    }

    // ── HTTP error codes ──────────────────────────────────────────────────────

    @Test
    fun `throws HttpException with code 400 on bad request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = runCatching { service.getDailyForecast(51.5, -0.125) }

        assertTrue(result.isFailure)
        assertEquals(400, (result.exceptionOrNull() as HttpException).code())
    }

    @Test
    fun `throws HttpException with code 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runCatching { service.getDailyForecast(51.5, -0.125) }

        assertTrue(result.isFailure)
        assertEquals(404, (result.exceptionOrNull() as HttpException).code())
    }

    @Test
    fun `throws HttpException with code 500 on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = runCatching { service.getDailyForecast(51.5, -0.125) }

        assertTrue(result.isFailure)
        assertEquals(500, (result.exceptionOrNull() as HttpException).code())
    }

    // ── Query parameters ──────────────────────────────────────────────────────

    @Test
    fun `sends latitude and longitude as query parameters`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(latitude = 48.8566, longitude = 2.3522)

        val url = server.takeRequest().requestUrl!!
        assertEquals("48.8566", url.queryParameter("latitude"))
        assertEquals("2.3522", url.queryParameter("longitude"))
    }

    @Test
    fun `sends timezone=auto by default`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(51.5, -0.125)

        assertEquals("auto", server.takeRequest().requestUrl!!.queryParameter("timezone"))
    }

    @Test
    fun `sends forecast_days=3 by default`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(51.5, -0.125)

        assertEquals("3", server.takeRequest().requestUrl!!.queryParameter("forecast_days"))
    }

    @Test
    fun `sends all required daily variables in one comma-separated query param`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(51.5, -0.125)

        val daily = server.takeRequest().requestUrl!!.queryParameter("daily") ?: ""
        assertTrue("weathercode missing", daily.contains("weathercode"))
        assertTrue("temperature_2m_max missing", daily.contains("temperature_2m_max"))
        assertTrue("temperature_2m_min missing", daily.contains("temperature_2m_min"))
        assertTrue("apparent_temperature_max missing", daily.contains("apparent_temperature_max"))
        assertTrue("apparent_temperature_min missing", daily.contains("apparent_temperature_min"))
        assertTrue("windspeed_10m_max missing", daily.contains("windspeed_10m_max"))
        assertTrue("precipitation_probability_max missing", daily.contains("precipitation_probability_max"))
    }

    @Test
    fun `uses GET method`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(51.5, -0.125)

        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `hits the forecast endpoint`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(51.5, -0.125)

        assertTrue(server.takeRequest().path!!.startsWith("/forecast"))
    }

    // ── Negative / extreme coordinates ────────────────────────────────────────

    @Test
    fun `handles southern hemisphere negative latitude`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(latitude = -33.8688, longitude = 151.2093) // Sydney

        val url = server.takeRequest().requestUrl!!
        assertEquals("-33.8688", url.queryParameter("latitude"))
        assertEquals("151.2093", url.queryParameter("longitude"))
    }

    @Test
    fun `handles western hemisphere negative longitude`() = runTest {
        server.enqueue(successResponse(FULL_3_DAY_JSON))

        service.getDailyForecast(latitude = 40.7128, longitude = -74.0060) // New York

        val url = server.takeRequest().requestUrl!!
        assertEquals("-74.006", url.queryParameter("longitude"))
    }

    // ── Test data ─────────────────────────────────────────────────────────────

    private fun successResponse(body: String) =
        MockResponse().setResponseCode(200).setBody(body.trimIndent())

    companion object {
        private val FULL_3_DAY_JSON = """
            {
              "latitude": 51.5,
              "longitude": -0.125,
              "timezone": "Europe/London",
              "daily": {
                "time": ["2024-01-01", "2024-01-02", "2024-01-03"],
                "weathercode": [0, 61, 80],
                "temperature_2m_max": [10.0, 12.0, 8.0],
                "temperature_2m_min": [4.0, 6.0, 2.0],
                "apparent_temperature_max": [8.0, 10.0, 6.0],
                "apparent_temperature_min": [2.0, 4.0, 0.0],
                "windspeed_10m_max": [15.0, 20.0, 10.0],
                "precipitation_probability_max": [0, 80, 60]
              }
            }
        """.trimIndent()

        private val NO_PRECIPITATION_JSON = """
            {
              "latitude": 51.5,
              "longitude": -0.125,
              "timezone": "Europe/London",
              "daily": {
                "time": ["2024-01-01"],
                "weathercode": [0],
                "temperature_2m_max": [10.0],
                "temperature_2m_min": [4.0],
                "apparent_temperature_max": [8.0],
                "apparent_temperature_min": [2.0],
                "windspeed_10m_max": [15.0]
              }
            }
        """.trimIndent()
    }
}
