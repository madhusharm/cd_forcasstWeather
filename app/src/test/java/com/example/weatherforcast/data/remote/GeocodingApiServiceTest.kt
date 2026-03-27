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

class GeocodingApiServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: GeocodingApiService

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        service = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GeocodingApiService::class.java)
    }

    @After
    fun tearDown() = server.shutdown()

    // ── Successful parsing ────────────────────────────────────────────────────

    @Test
    fun `parses full response with all fields`() = runTest {
        server.enqueue(successResponse("""
            {
              "results": [{
                "name": "London",
                "latitude": 51.5085,
                "longitude": -0.1257,
                "country": "United Kingdom",
                "country_code": "GB"
              }]
            }
        """))

        val response = service.searchCity("London")
        val loc = response.results!!.first()

        assertEquals("London", loc.name)
        assertEquals(51.5085, loc.latitude, 0.0001)
        assertEquals(-0.1257, loc.longitude, 0.0001)
        assertEquals("United Kingdom", loc.country)
        assertEquals("GB", loc.countryCode)
    }

    @Test
    fun `parses multiple results and returns all`() = runTest {
        server.enqueue(successResponse("""
            {
              "results": [
                {"name": "Springfield", "latitude": 39.8, "longitude": -89.6},
                {"name": "Springfield", "latitude": 37.2, "longitude": -93.3}
              ]
            }
        """))

        val response = service.searchCity("Springfield")

        assertEquals(2, response.results!!.size)
    }

    // ── Optional / nullable fields ────────────────────────────────────────────

    @Test
    fun `countryCode is null when field is absent from response`() = runTest {
        server.enqueue(successResponse("""
            {
              "results": [{
                "name": "SomeCity",
                "latitude": 10.0,
                "longitude": 20.0
              }]
            }
        """))

        val response = service.searchCity("SomeCity")

        assertNull(response.results!!.first().countryCode)
    }

    @Test
    fun `country is null when field is absent from response`() = runTest {
        server.enqueue(successResponse("""
            {
              "results": [{
                "name": "SomeCity",
                "latitude": 10.0,
                "longitude": 20.0
              }]
            }
        """))

        val response = service.searchCity("SomeCity")

        assertNull(response.results!!.first().country)
    }

    // ── City-not-found responses ──────────────────────────────────────────────

    @Test
    fun `results is null when response body has no results field`() = runTest {
        server.enqueue(successResponse("{}"))

        val response = service.searchCity("Xyznonexistentcity")

        assertNull(response.results)
    }

    @Test
    fun `results is empty list when API returns empty array`() = runTest {
        server.enqueue(successResponse("""{"results": []}"""))

        val response = service.searchCity("Xyznonexistentcity")

        assertTrue(response.results!!.isEmpty())
    }

    // ── HTTP error codes ──────────────────────────────────────────────────────

    @Test
    fun `throws HttpException with code 400 on bad request`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

        val result = runCatching { service.searchCity("London") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HttpException)
        assertEquals(400, (result.exceptionOrNull() as HttpException).code())
    }

    @Test
    fun `throws HttpException with code 404 on not found`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runCatching { service.searchCity("London") }

        assertTrue(result.isFailure)
        assertEquals(404, (result.exceptionOrNull() as HttpException).code())
    }

    @Test
    fun `throws HttpException with code 500 on server error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = runCatching { service.searchCity("London") }

        assertTrue(result.isFailure)
        assertEquals(500, (result.exceptionOrNull() as HttpException).code())
    }

    // ── Query parameters ──────────────────────────────────────────────────────

    @Test
    fun `sends city name as name query parameter`() = runTest {
        server.enqueue(successResponse("{}"))

        service.searchCity("Berlin")

        val url = server.takeRequest().requestUrl!!
        assertEquals("Berlin", url.queryParameter("name"))
    }

    @Test
    fun `sends default count language and format parameters`() = runTest {
        server.enqueue(successResponse("{}"))

        service.searchCity("Berlin")

        val url = server.takeRequest().requestUrl!!
        assertEquals("1", url.queryParameter("count"))
        assertEquals("en", url.queryParameter("language"))
        assertEquals("json", url.queryParameter("format"))
    }

    @Test
    fun `city name with spaces is URL-encoded correctly`() = runTest {
        server.enqueue(successResponse("{}"))

        service.searchCity("New York")

        val request = server.takeRequest()
        // MockWebServer decodes the URL, so we check the decoded value
        assertEquals("New York", request.requestUrl!!.queryParameter("name"))
    }

    @Test
    fun `uses GET method`() = runTest {
        server.enqueue(successResponse("{}"))

        service.searchCity("London")

        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `hits the search endpoint`() = runTest {
        server.enqueue(successResponse("{}"))

        service.searchCity("London")

        assertTrue(server.takeRequest().path!!.startsWith("/search"))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun successResponse(body: String) =
        MockResponse().setResponseCode(200).setBody(body.trimIndent())
}
