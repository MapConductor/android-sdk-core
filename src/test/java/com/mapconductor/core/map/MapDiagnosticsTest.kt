package com.mapconductor.core.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [MapDiagnostics] の意味論テスト。
 *
 * 元の [MapUISettingsDiagnostics.warnIfRequested] が持っていた 2 つの性質
 * （要求されたときだけ / provider+capability ごとに 1 回だけ）を、一般化後も
 * 保っていることを押さえる。あわせて既存の warnIfRequested が同じ挙動のまま
 * であることも確認する（公開 API なので壊せない）。
 *
 * `android.util.Log` は JVM ユニットテストで使えないため、[MapDiagnostics.sink] を
 * 差し替えて検証する。
 */
class MapDiagnosticsTest {
    private val messages = mutableListOf<String>()
    private lateinit var originalSink: MapDiagnostics.Sink

    @Before
    fun setUp() {
        originalSink = MapDiagnostics.sink
        MapDiagnostics.sink = MapDiagnostics.Sink { messages.add(it) }
        MapDiagnostics.resetWarnings()
    }

    @After
    fun tearDown() {
        MapDiagnostics.sink = originalSink
        MapDiagnostics.resetWarnings()
    }

    @Test
    fun `report は 1 回だけ出力する`() {
        assertTrue(
            MapDiagnostics.report(
                MapCapability.GroundImage,
                MapDiagnosticLevel.Unsupported,
                provider = "Longdo",
                reason = "no ground overlay API",
            ),
        )
        assertFalse(
            MapDiagnostics.report(
                MapCapability.GroundImage,
                MapDiagnosticLevel.Unsupported,
                provider = "Longdo",
                reason = "no ground overlay API",
            ),
        )
        assertEquals(1, messages.size)
    }

    @Test
    fun `provider が違えばそれぞれ出力する`() {
        MapDiagnostics.report(MapCapability.GroundImage, MapDiagnosticLevel.Unsupported, "Longdo", "x")
        MapDiagnostics.report(MapCapability.GroundImage, MapDiagnosticLevel.Unsupported, "MapTiler", "y")
        assertEquals(2, messages.size)
    }

    @Test
    fun `level が違えばそれぞれ出力する`() {
        MapDiagnostics.report(MapCapability.PolygonHoles, MapDiagnosticLevel.Degraded, "HERE", "union fill")
        MapDiagnostics.report(MapCapability.PolygonHoles, MapDiagnosticLevel.Unsupported, "HERE", "union fill")
        assertEquals(2, messages.size)
    }

    @Test
    fun `reportIfRequested は要求されていなければ黙る`() {
        assertFalse(
            MapDiagnostics.reportIfRequested(
                requested = false,
                capability = MapCapability.MarkerDrag,
                level = MapDiagnosticLevel.Unsupported,
                provider = "Longdo",
                reason = "no drag",
            ),
        )
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `subject を省略すると capability の id が出る`() {
        MapDiagnostics.report(MapCapability.MarkerDrag, MapDiagnosticLevel.Unsupported, "Longdo", "no drag")
        assertTrue(messages.single().startsWith("markerDrag is not supported by Longdo (no drag)"))
    }

    @Test
    fun `resetWarnings 後は再び出力する`() {
        MapDiagnostics.report(MapCapability.Marker, MapDiagnosticLevel.Unsupported, "P", "r")
        MapDiagnostics.resetWarnings()
        MapDiagnostics.report(MapCapability.Marker, MapDiagnosticLevel.Unsupported, "P", "r")
        assertEquals(2, messages.size)
    }

    // ── 既存 API の非破壊 ───────────────────────────────────────────────

    @Test
    fun `warnIfRequested は無効化を要求されたときだけ警告する`() {
        // true = ジェスチャを有効のままにしたい → 常に達成できるので警告不要。
        MapUISettingsDiagnostics.warnIfRequested(true, MapGesture.Rotate, "MapTiler", "single recogniser")
        assertTrue(messages.isEmpty())

        // false = 無効化したいができない → 警告する。
        MapUISettingsDiagnostics.warnIfRequested(false, MapGesture.Rotate, "MapTiler", "single recogniser")
        assertEquals(1, messages.size)
    }

    @Test
    fun `warnIfRequested のメッセージは設定名を使う`() {
        MapUISettingsDiagnostics.warnIfRequested(false, MapGesture.Scroll, "MapTiler", "single recogniser")
        assertEquals(
            "scrollGesture cannot be changed on MapTiler (single recogniser); the setting is ignored.",
            messages.single(),
        )
    }

    @Test
    fun `warnIfRequested は provider とジェスチャごとに 1 回だけ`() {
        repeat(5) {
            MapUISettingsDiagnostics.warnIfRequested(false, MapGesture.Tilt, "MapTiler", "r")
        }
        MapUISettingsDiagnostics.warnIfRequested(false, MapGesture.Zoom, "MapTiler", "r")
        MapUISettingsDiagnostics.warnIfRequested(false, MapGesture.Tilt, "Longdo", "r")
        assertEquals(3, messages.size)
    }

    @Test
    fun `MapGesture は対応する capability を持つ`() {
        assertEquals(MapCapability.GestureScroll, MapGesture.Scroll.capability)
        assertEquals(MapCapability.GestureZoom, MapGesture.Zoom.capability)
        assertEquals(MapCapability.GestureRotate, MapGesture.Rotate.capability)
        assertEquals(MapCapability.GestureTilt, MapGesture.Tilt.capability)
    }
}
