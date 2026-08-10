package com.mapconductor.core.conformance

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.controller.OverlayHitResolver
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.SlottedOverlayController
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.map.MapCapability
import com.mapconductor.core.map.MapCapabilityStatus
import com.mapconductor.core.map.MapServiceRegistry
import com.mapconductor.core.zoom.AbstractZoomAltitudeConverter
import com.mapconductor.core.zoom.WebMercatorZoomAltitudeConverter
import kotlin.math.abs

/**
 * 地図SDKドライバーの適合チェック。
 *
 * 外部の作者が自分のドライバーのユニットテストから呼んで、コアが前提にしている
 * 契約を満たしているかを機械的に確かめるためのもの。**JUnit に依存しない**
 * （素の関数と例外だけ）ので、どのテストランナーからでも使える。
 *
 * ```kotlin
 * class MyDriverConformanceTest {
 *     @Test fun `zoom conversion round-trips`() {
 *         MapDriverConformance.checkZoomConverter(MyZoomConverter())
 *     }
 *     @Test fun `every overlay kind is slotted`() {
 *         MapDriverConformance.checkOverlaySlots(myController.registeredOverlayControllers())
 *     }
 * }
 * ```
 *
 * ## ここに入れられないもの
 *
 * マーカーの描画は `BitmapIcon`（`android.graphics.Bitmap`）を通るため、素の JVM では
 * 動かない。マーカーの当たり判定やドラッグは**実機で確かめるしかない**。
 * このチェックが緑でも実機確認は省略しないこと。
 */
@InternalMapConductorApi
object MapDriverConformance {
    /** 適合していないときに投げる。 */
    @InternalMapConductorApi
    class ViolationException(
        message: String,
    ) : AssertionError(message)

    private fun require(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw ViolationException(message())
    }

    /**
     * ズームの往復換算が壊れていないか。
     *
     * ドライバーは SDK の生ズームと統一ズーム（Google 準拠）を相互変換する。
     * ここがずれると、当たり判定の許容量（metersPerPixel × tapTolerance）が
     * 実際の縮尺と食い違い、**線や円をタップしても反応しない**という形で表面化する。
     * android-for-arcgis の 3D で実際にそれが起きている。
     *
     * @param converter ドライバーのコンバータ。
     * @param latitudes 検査する緯度。高緯度で分岐するプロバイダがあるので端も入れる。
     */
    fun checkZoomConverter(
        converter: WebMercatorZoomAltitudeConverter,
        latitudes: List<Double> = listOf(0.0, 35.0, 60.0, 85.0, -85.0),
        zooms: List<Double> = listOf(0.0, 1.0, 5.5, 10.0, 15.25, 22.0),
    ) {
        latitudes.forEach { latitude ->
            zooms.forEach { zoom ->
                val native = converter.toNativeZoom(zoom, latitude)
                val roundTrip = converter.toUnifiedZoom(native, latitude)
                // 端はクランプされるので、クランプ範囲内でのみ往復を要求する。
                val clampedInput =
                    zoom.coerceIn(
                        AbstractZoomAltitudeConverter.MIN_ZOOM_LEVEL,
                        AbstractZoomAltitudeConverter.MAX_ZOOM_LEVEL,
                    )
                if (native > AbstractZoomAltitudeConverter.MIN_ZOOM_LEVEL &&
                    native < AbstractZoomAltitudeConverter.MAX_ZOOM_LEVEL
                ) {
                    require(abs(roundTrip - clampedInput) < ZOOM_TOLERANCE) {
                        "zoom round-trip failed at zoom=$zoom latitude=$latitude: " +
                            "toNativeZoom=$native toUnifiedZoom=$roundTrip"
                    }
                }
            }
        }

        // 単調性。統一ズームを上げたら生ズームも上がること。
        latitudes.forEach { latitude ->
            var previous = Double.NEGATIVE_INFINITY
            (0..22).forEach { step ->
                val native = converter.toNativeZoom(step.toDouble(), latitude)
                require(native >= previous) {
                    "toNativeZoom is not monotonic at latitude=$latitude (zoom=$step)"
                }
                previous = native
            }
        }

        // クランプ。範囲外を渡しても [0, 22] に収まること。
        listOf(-100.0, 1_000.0).forEach { extreme ->
            val unified = converter.toUnifiedZoom(extreme)
            require(
                unified >= AbstractZoomAltitudeConverter.MIN_ZOOM_LEVEL &&
                    unified <= AbstractZoomAltitudeConverter.MAX_ZOOM_LEVEL,
            ) { "toUnifiedZoom($extreme) = $unified is out of [0, 22]" }
        }
    }

    /**
     * 6 種別すべてがスロットに参加しているか。
     *
     * ## これが最重要のチェック
     *
     * [SlottedOverlayController] を実装し忘れたコントローラは、
     * `compositionXxx` / `hasXxx` / クリックカスケードから**黙って漏れる**。
     * ビルドも apiCheck も既存のユニットテストも緑のまま、
     *
     *   - マーカーが 1 つも表示されない（AbstractMarkerController に kind を付け忘れ）
     *   - ポリゴン単体の状態更新が捨てられる（MapLibrePolygonConductor が非 Slotted）
     *
     * という形で出る。どちらも実際に作り込んだ。
     *
     * @param controllers `registerOverlayController` に渡したものすべて。
     * @param expected 期待する種別。ラスターレイヤを持たないドライバーは外してよい。
     */
    fun checkOverlaySlots(
        controllers: List<Any>,
        expected: Set<OverlayKind> = OverlayKind.entries.toSet(),
    ) {
        val slotted = controllers.filterIsInstance<SlottedOverlayController<*, *>>()
        val declared = slotted.map { it.kind }.toSet()
        val missing = expected - declared
        require(missing.isEmpty()) {
            "these overlay kinds are not reachable from the Capable facade or the click cascade: " +
                "$missing — the controller is probably not a SlottedOverlayController " +
                "(registered controllers: ${controllers.map { it::class.java.simpleName }})"
        }
    }

    /**
     * クリックカスケードの探索順が正準どおりか。
     *
     * ドライバーが [OverlayHitResolver.CANONICAL_ORDER] を差し替えている場合に、
     * 意図した順になっているかを確かめる。
     */
    fun checkCascadeOrder(order: List<OverlayKind> = OverlayHitResolver.CANONICAL_ORDER) {
        require(OverlayKind.Marker !in order) {
            "marker must not be in the overlay cascade; it goes through dispatchMarkerTap " +
                "because the hit test needs screen projection"
        }
        val circle = order.indexOf(OverlayKind.Circle)
        val polygon = order.indexOf(OverlayKind.Polygon)
        require(circle in 0 until polygon || polygon < 0) {
            "circle must be probed before polygon (small overlays win): $order"
        }
    }

    /**
     * capability の宣言が意味の通る形か。
     *
     * ## Unknown を非対応と混同しないこと
     *
     * 宣言が無い（[MapCapabilityStatus.Unknown]）は「まだ宣言していない」であって
     * 「使えない」ではない。地図の初期化途中もここに入る。
     * **[MapCapabilityStatus.Unsupported] は「その機能が動かない」ときだけ**。
     * ホルダーに同期変換が無くても別経路で動いているなら
     * [MapCapabilityStatus.Degraded] にすること。`Unsupported` にすると
     * コアが**動いている機能を止める**（android-for-longdo で踏みかけた）。
     */
    fun checkCapabilityDeclarations(registry: MapServiceRegistry) {
        MapCapability.entries.forEach { capability ->
            val status = registry.capabilityStatus(capability)
            if (status.isKnownUnsupported) {
                require(!status.reason.isNullOrBlank()) {
                    "$capability is declared Unsupported without a reason; the diagnostic log " +
                        "would tell the app developer nothing about why the feature stopped"
                }
            }
        }
    }

    /** ホルダーの投影が往復するか。同期変換を持つドライバーだけ呼ぶこと。 */
    fun checkProjectionRoundTrip(
        toScreen: (GeoPointInterface) -> androidx.compose.ui.geometry.Offset?,
        fromScreen: (androidx.compose.ui.geometry.Offset) -> GeoPointInterface?,
        samples: List<GeoPointInterface>,
    ) {
        samples.forEach { point ->
            val screen = toScreen(point) ?: return@forEach
            val back =
                fromScreen(screen)
                    ?: throw ViolationException(
                        "fromScreenOffsetSync returned null for a point it just projected: $point",
                    )
            require(
                abs(back.latitude - point.latitude) < COORD_TOLERANCE &&
                    abs(back.longitude - point.longitude) < COORD_TOLERANCE,
            ) { "projection round-trip failed: $point -> $screen -> $back" }
        }
    }

    private const val ZOOM_TOLERANCE = 1e-6

    // 画面座標は Float なので往復の誤差はズームに比例して残る。
    // 1e-5 度 ≒ 1m。投影が「壊れている」ことを見るには十分細かい。
    private const val COORD_TOLERANCE = 1e-5
}
