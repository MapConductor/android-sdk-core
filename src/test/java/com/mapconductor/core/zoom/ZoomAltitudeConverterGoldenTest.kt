package com.mapconductor.core.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebMercatorZoomAltitudeConverter] が移行前の各プロバイダ実装と**1 ビットも違わない**
 * ことを検証する。
 *
 * `zoom/ZoomAltitudeConverter.kt` は 6 プロバイダでほぼ同一（差はズームオフセットだけ）
 * だったので、コアの 1 本にまとめた。ただしズーム換算のズレは
 * **目視では絶対に見つからない回帰**（高緯度で縮尺がわずかに違う、程度にしか見えない）
 * なので、移行前の実装が返した値を表として採取し、それに対して固定する。
 *
 * リソース `zoom-golden.txt` は移行前（2026-08-10）の android-for-* を実際に走らせて
 * 採取したもの。zoom 8 点 × 緯度 8 点 × tilt 5 点 = 320 点 × 6 プロバイダ。
 *
 * ## HERE と ArcGIS を含めていない理由
 *
 * この 2 つは**式が本質的に違う**のでコアの実装に寄せていない。
 *  - HERE: 緯度の ±85° クランプと tilt の [0,90] クランプが無い。高緯度で値が分岐する。
 *  - ArcGIS: viewport 高さでスケールする独自式（161 行）。
 * 自前実装のまま残すこと。
 */
class ZoomAltitudeConverterGoldenTest {
    /** 移行前の各プロバイダの構成。ここが実装との唯一の対応表。 */
    private fun converterFor(provider: String): AbstractZoomAltitudeConverter =
        when (provider) {
            // 256px タイル基準。ネイティブズーム == 統一ズーム。
            "googlemaps", "longdo" -> WebMercatorZoomAltitudeConverter(zoomOffset = 0.0)
            // 512px タイルのベクタエンジン。統一ズーム = ネイティブズーム + 1。
            "maplibre", "mapbox", "maptiler" -> WebMercatorZoomAltitudeConverter(zoomOffset = 1.0)
            // グラウンドスケール基準。オフセットが緯度依存。
            "tomtom" -> GroundScaleZoomAltitudeConverter(baseZoomOffset = 1.76)
            else -> error("未知のプロバイダ: $provider")
        }

    private data class Row(
        val provider: String,
        val zoom: Double,
        val latitude: Double,
        val tilt: Double,
        val altitude: Double,
        val roundTrip: Double,
    )

    private fun goldenRows(): List<Row> {
        val stream =
            javaClass.getResourceAsStream("/zoom-golden.txt")
                ?: error("zoom-golden.txt が見つかりません")
        return stream.bufferedReader().useLines { lines ->
            lines
                .filterNot { it.startsWith("#") || it.isBlank() }
                .map { line ->
                    val f = line.split("|")
                    Row(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                }.toList()
        }
    }

    @Test
    fun `ゴールデン表が想定の件数ある`() {
        val rows = goldenRows()
        assertEquals("6 プロバイダ x 320 点", 1920, rows.size)
        assertEquals(6, rows.map { it.provider }.distinct().size)
    }

    @Test
    fun `zoomLevelToAltitude が移行前と完全に一致する`() {
        val failures = mutableListOf<String>()
        goldenRows().forEach { row ->
            val actual = converterFor(row.provider).zoomLevelToAltitude(row.zoom, row.latitude, row.tilt)
            // 丸め誤差の許容ではなく完全一致を要求する。式を 1 本にまとめただけなので、
            // 差が出たらそれは実装の変化であって数値誤差ではない。
            if (actual != row.altitude) {
                failures += "${row.provider} z=${row.zoom} lat=${row.latitude} tilt=${row.tilt}: " +
                    "expected=${row.altitude} actual=$actual"
            }
        }
        assertTrue(
            "移行前と値が変わった箇所が ${failures.size} 件:\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `altitudeToZoomLevel が移行前と完全に一致する`() {
        val failures = mutableListOf<String>()
        goldenRows().forEach { row ->
            val actual = converterFor(row.provider).altitudeToZoomLevel(row.altitude, row.latitude, row.tilt)
            if (actual != row.roundTrip) {
                failures += "${row.provider} alt=${row.altitude} lat=${row.latitude} tilt=${row.tilt}: " +
                    "expected=${row.roundTrip} actual=$actual"
            }
        }
        assertTrue(
            "移行前と値が変わった箇所が ${failures.size} 件:\n" + failures.take(10).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ── 性質テスト（ゴールデン表とは独立に式の健全性を押さえる） ──────────

    @Test
    fun `クランプ域を除けば往復して元のズームに戻る`() {
        val converter = WebMercatorZoomAltitudeConverter(zoomOffset = 1.0)
        for (zoom in listOf(2.0, 5.0, 10.0, 14.0, 18.0)) {
            for (lat in listOf(-60.0, 0.0, 35.7, 60.0)) {
                val altitude = converter.zoomLevelToAltitude(zoom, lat, tilt = 0.0)
                val back = converter.altitudeToZoomLevel(altitude, lat, tilt = 0.0)
                assertEquals("zoom=$zoom lat=$lat", zoom, back, 1e-9)
            }
        }
    }

    @Test
    fun `ズームが増えると高度は単調に減る`() {
        val converter = WebMercatorZoomAltitudeConverter(zoomOffset = 0.0)
        var previous = Double.MAX_VALUE
        for (zoom in 1..20) {
            val altitude = converter.zoomLevelToAltitude(zoom.toDouble(), latitude = 35.7, tilt = 0.0)
            assertTrue("zoom=$zoom で単調減少が崩れた", altitude < previous)
            previous = altitude
        }
    }

    @Test
    fun `tilt が 90 度でも発散しない`() {
        val converter = WebMercatorZoomAltitudeConverter(zoomOffset = 0.0)
        val altitude = converter.zoomLevelToAltitude(10.0, latitude = 0.0, tilt = 90.0)
        assertTrue(altitude.isFinite())
        assertTrue(altitude >= AbstractZoomAltitudeConverter.MIN_ALTITUDE)
    }

    @Test
    fun `極付近でも発散しない`() {
        val converter = WebMercatorZoomAltitudeConverter(zoomOffset = 0.0)
        listOf(-90.0, -89.9, 89.9, 90.0).forEach { lat ->
            val altitude = converter.zoomLevelToAltitude(10.0, lat, tilt = 0.0)
            assertTrue("lat=$lat", altitude.isFinite() && altitude > 0.0)
        }
    }

    @Test
    fun `オフセットは統一ズームとネイティブズームの間で往復する`() {
        val converter = WebMercatorZoomAltitudeConverter(zoomOffset = 1.0)
        assertEquals(11.0, converter.toUnifiedZoom(10.0), 1e-12)
        assertEquals(10.0, converter.toNativeZoom(11.0), 1e-12)
    }

    @Test
    fun `グラウンドスケール版のオフセットは緯度に依存する`() {
        val converter = GroundScaleZoomAltitudeConverter(baseZoomOffset = 1.76)
        val atEquator = converter.toUnifiedZoom(10.0, latitude = 0.0)
        val atHighLat = converter.toUnifiedZoom(10.0, latitude = 60.0)
        // cos(60°) = 0.5 なので log2 で -1.0 ぶんずれる。
        assertEquals(11.76, atEquator, 1e-9)
        assertEquals(10.76, atHighLat, 1e-9)
    }
}
