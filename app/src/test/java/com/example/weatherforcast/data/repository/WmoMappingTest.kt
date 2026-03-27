package com.example.weatherforcast.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class WmoMappingTest {

    // ── wmoDescription ────────────────────────────────────────────────────────

    @Test
    fun `wmoDescription maps all defined WMO codes to correct descriptions`() {
        val expected = mapOf(
            0  to "Clear sky",
            1  to "Mainly clear",
            2  to "Partly cloudy",
            3  to "Overcast",
            45 to "Fog",
            48 to "Rime fog",
            51 to "Light drizzle",
            53 to "Drizzle",
            55 to "Heavy drizzle",
            61 to "Light rain",
            63 to "Moderate rain",
            65 to "Heavy rain",
            71 to "Light snow",
            73 to "Moderate snow",
            75 to "Heavy snow",
            77 to "Snow grains",
            80 to "Light showers",
            81 to "Showers",
            82 to "Heavy showers",
            85 to "Snow showers",
            86 to "Heavy snow showers",
            95 to "Thunderstorm",
            96 to "Thunderstorm w/ hail",
            99 to "Thunderstorm w/ hail"
        )
        expected.forEach { (code, description) ->
            assertEquals("Wrong description for WMO code $code", description, wmoDescription(code))
        }
    }

    @Test
    fun `wmoDescription codes 96 and 99 share the same description`() {
        assertEquals(wmoDescription(96), wmoDescription(99))
    }

    @Test
    fun `wmoDescription returns Unknown for unrecognized positive code`() {
        assertEquals("Unknown", wmoDescription(100))
        assertEquals("Unknown", wmoDescription(50))  // gap between 48 and 51
        assertEquals("Unknown", wmoDescription(999))
    }

    @Test
    fun `wmoDescription returns Unknown for negative code`() {
        assertEquals("Unknown", wmoDescription(-1))
        assertEquals("Unknown", wmoDescription(Int.MIN_VALUE))
    }

    @Test
    fun `wmoDescription returns Unknown for gap codes not in WMO spec`() {
        // Codes between defined groups that are not in the WMO spec
        listOf(4, 10, 20, 30, 40, 49, 56, 57, 66, 67, 70, 76, 83, 84, 87, 88, 89, 90, 91, 94, 97, 98)
            .forEach { code ->
                assertEquals("Unknown for gap code $code", "Unknown", wmoDescription(code))
            }
    }

    // ── wmoEmoji ──────────────────────────────────────────────────────────────

    @Test
    fun `wmoEmoji maps all defined WMO codes to correct emoji`() {
        val expected = mapOf(
            0  to "☀️",
            1  to "🌤",
            2  to "⛅",
            3  to "☁️",
            45 to "🌫️",
            48 to "🌫️",
            51 to "🌦️",
            53 to "🌦️",
            55 to "🌦️",
            61 to "🌧️",
            63 to "🌧️",
            65 to "🌧️",
            71 to "❄️",
            73 to "❄️",
            75 to "❄️",
            77 to "❄️",
            80 to "🌦️",
            81 to "🌦️",
            82 to "⛈️",
            85 to "🌨️",
            86 to "🌨️",
            95 to "⛈️",
            96 to "⛈️",
            99 to "⛈️"
        )
        expected.forEach { (code, emoji) ->
            assertEquals("Wrong emoji for WMO code $code", emoji, wmoEmoji(code))
        }
    }

    @Test
    fun `wmoEmoji fog codes 45 and 48 share the same emoji`() {
        assertEquals(wmoEmoji(45), wmoEmoji(48))
    }

    @Test
    fun `wmoEmoji drizzle codes 51 53 55 share the same emoji`() {
        assertEquals(wmoEmoji(51), wmoEmoji(53))
        assertEquals(wmoEmoji(53), wmoEmoji(55))
    }

    @Test
    fun `wmoEmoji rain codes 61 63 65 share the same emoji`() {
        assertEquals(wmoEmoji(61), wmoEmoji(63))
        assertEquals(wmoEmoji(63), wmoEmoji(65))
    }

    @Test
    fun `wmoEmoji snow codes 71 73 75 77 share the same emoji`() {
        assertEquals(wmoEmoji(71), wmoEmoji(73))
        assertEquals(wmoEmoji(73), wmoEmoji(75))
        assertEquals(wmoEmoji(75), wmoEmoji(77))
    }

    @Test
    fun `wmoEmoji thunderstorm codes 95 96 99 share the same emoji`() {
        assertEquals(wmoEmoji(95), wmoEmoji(96))
        assertEquals(wmoEmoji(96), wmoEmoji(99))
    }

    @Test
    fun `wmoEmoji returns default thermometer emoji for unrecognized code`() {
        assertEquals("🌡️", wmoEmoji(100))
        assertEquals("🌡️", wmoEmoji(-1))
        assertEquals("🌡️", wmoEmoji(999))
        assertEquals("🌡️", wmoEmoji(Int.MIN_VALUE))
    }

    @Test
    fun `wmoEmoji returns default for gap codes not in WMO spec`() {
        listOf(4, 10, 49, 56, 66, 70, 83, 90, 94, 97, 98).forEach { code ->
            assertEquals("Default emoji expected for gap code $code", "🌡️", wmoEmoji(code))
        }
    }
}
