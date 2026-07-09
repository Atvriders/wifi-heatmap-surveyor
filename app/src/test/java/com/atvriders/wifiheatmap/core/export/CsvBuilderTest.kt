package com.atvriders.wifiheatmap.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvBuilderTest {

    private fun row(ssid: String = "Office") = CsvExportRow(
        timestampMs = 1_752_000_000_000,
        posSource = "tap",
        segmentIndex = 2,
        x = 3.25, y = 4.5,
        latitude = null, longitude = null, accuracyM = null,
        ssid = ssid, bssid = "aa:bb:cc:dd:ee:ff",
        band = "5", frequencyMhz = 5180, rssiDbm = -61, connected = true,
    )

    @Test
    fun headerAndPlainRow() {
        val csv = CsvBuilder.build(listOf(row()))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertEquals(CsvBuilder.HEADER, lines[0])
        assertTrue(lines[1].startsWith("2025-07-08T") || lines[1].startsWith("2026-"))
        assertTrue(lines[1].endsWith(",Office,aa:bb:cc:dd:ee:ff,5,5180,-61,true"))
        assertTrue(lines[1].contains(",tap,2,3.25,4.5,,,,"))
    }

    @Test
    fun ssidWithCommaIsQuoted() {
        val csv = CsvBuilder.build(listOf(row(ssid = "Cafe, Free WiFi")))
        assertTrue(csv.contains("\"Cafe, Free WiFi\""))
    }

    @Test
    fun ssidWithQuotesDoubled() {
        val csv = CsvBuilder.build(listOf(row(ssid = "The \"Best\" AP")))
        assertTrue(csv.contains("\"The \"\"Best\"\" AP\""))
    }

    @Test
    fun ssidWithNewlineQuoted() {
        val csv = CsvBuilder.build(listOf(row(ssid = "line1\nline2")))
        assertTrue(csv.contains("\"line1\nline2\""))
    }

    @Test
    fun emojiSsidPassesThrough() {
        val csv = CsvBuilder.build(listOf(row(ssid = "📶 FastNet")))
        assertTrue(csv.contains("📶 FastNet"))
    }

    @Test
    fun nullNumericFieldsAreEmpty() {
        val gps = row().copy(posSource = "gps", segmentIndex = null, x = 1.0, y = 2.0,
            latitude = 45.5, longitude = -122.6, accuracyM = 4.5f)
        val line = CsvBuilder.build(listOf(gps)).trimEnd().split("\r\n")[1]
        assertTrue(line.contains(",gps,,1.0,2.0,45.5,-122.6,4.5,"))
    }
}
