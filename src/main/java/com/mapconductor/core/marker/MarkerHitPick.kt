package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.ResourceProvider
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.settings.Settings
import kotlin.math.hypot

/**
 * タップ点に当たるマーカーを候補の中から選ぶ（ドライバー実装点）。
 *
 * 1 つのマーカーが当たるかどうかは [MarkerHitTest.hitsIcon]、どれを選ぶかはここ。
 * ios-sdk の `MarkerHitTest.pickIndex` / `pick`、react-sdk の `pickMarkerHit` と同じ規則。
 */
@InternalMapConductorApi
object MarkerHitPick {
    /**
     * アンカー点を投影した画面座標と、そのアイコンの矩形（px）。[pickIndex] の入力。
     */
    @InternalMapConductorApi
    class ScreenRect(
        val anchorScreen: Offset,
        val widthPx: Double,
        val heightPx: Double,
        val anchorX: Double,
        val anchorY: Double,
    )

    /**
     * タップ点に当たるマーカーを選ぶ規則（ios-sdk の `MarkerHitTest.pickIndex`、react-sdk の
     * `pickMarkerIndex` と同じ）。
     *
     * 1. アイコン（ラベル込み）の矩形そのものにタップ点が入っているものがあれば、その中で
     *    画面上いちばん下にアンカーがあるもの（手前に描かれるもの）を選ぶ。近くに別のマーカーの
     *    先端があっても、指の下に描かれているマーカーが勝つ。
     * 2. どの矩形にも入っていなければ、矩形 + [tolerancePx] に入るもののうち、アンカーが
     *    タップ点にいちばん近いものを選ぶ。
     */
    fun pickIndex(
        touch: Offset,
        rects: List<ScreenRect>,
        tolerancePx: Double,
    ): Int? {
        var exact: Int? = null
        var exactY = Double.NEGATIVE_INFINITY
        var near: Int? = null
        var nearDistance = Double.POSITIVE_INFINITY
        rects.forEachIndexed { index, rect ->
            val dx = (touch.x - rect.anchorScreen.x).toDouble()
            val dy = (touch.y - rect.anchorScreen.y).toDouble()
            if (MarkerHitTest.hitsIconRect(dx, dy, rect.widthPx, rect.heightPx, rect.anchorX, rect.anchorY, 0.0)) {
                if (rect.anchorScreen.y > exactY) {
                    exact = index
                    exactY = rect.anchorScreen.y.toDouble()
                }
            } else if (exact == null &&
                MarkerHitTest.hitsIconRect(dx, dy, rect.widthPx, rect.heightPx, rect.anchorX, rect.anchorY, tolerancePx)
            ) {
                val distance = hypot(dx, dy)
                if (distance < nearDistance) {
                    near = index
                    nearDistance = distance
                }
            }
        }
        return exact ?: near
    }

    /**
     * 候補を集める範囲。タップ点から画面上 [SEARCH_RADIUS_DP] 以内に先端があるマーカーを含む。
     *
     * ラベルを横に置くとアイコンはピンより大きく広がるので、最寄りの 1 つだけでは足りない。
     * px → 度の換算は、タップ点の近くを投影して局所的に求める（回転していても各軸の長さは保たれる）。
     */
    fun searchBounds(
        position: GeoPointInterface,
        touchScreen: Offset,
        project: (GeoPointInterface) -> Offset?,
        density: Double = ResourceProvider.getDensity().toDouble(),
    ): GeoRectBounds? {
        val epsilon = 1e-4
        val north = project(GeoPoint(position.latitude + epsilon, position.longitude)) ?: return null
        val east = project(GeoPoint(position.latitude, position.longitude + epsilon)) ?: return null
        val pxPerDegLat = hypot((north.x - touchScreen.x).toDouble(), (north.y - touchScreen.y).toDouble()) / epsilon
        val pxPerDegLon = hypot((east.x - touchScreen.x).toDouble(), (east.y - touchScreen.y).toDouble()) / epsilon
        if (pxPerDegLat <= 0.0 || pxPerDegLon <= 0.0) return null
        val radiusPx = SEARCH_RADIUS_DP * density
        val halfLat = radiusPx / pxPerDegLat
        val halfLon = radiusPx / pxPerDegLon
        return GeoRectBounds(
            southWest = GeoPoint((position.latitude - halfLat).coerceAtLeast(-90.0), position.longitude - halfLon),
            northEast = GeoPoint((position.latitude + halfLat).coerceAtMost(90.0), position.longitude + halfLon),
        )
    }

    /**
     * [candidates] の中からタップ点に当たるマーカーを [pickIndex] の規則で選ぶ。
     */
    fun <E : MarkerEntityInterface<*>> pick(
        touchScreen: Offset,
        candidates: List<E>,
        project: (GeoPointInterface) -> Offset?,
        defaultIcon: MarkerIconInterface = DefaultMarkerIcon(),
        density: Double = ResourceProvider.getDensity().toDouble(),
    ): E? = pickBy(touchScreen, candidates, { it.state }, project, defaultIcon, density)

    /** [pick] の、候補が [MarkerState] 以外の型で渡ってくる版。 */
    fun <T> pickBy(
        touchScreen: Offset,
        candidates: List<T>,
        stateOf: (T) -> MarkerState,
        project: (GeoPointInterface) -> Offset?,
        defaultIcon: MarkerIconInterface = DefaultMarkerIcon(),
        density: Double = ResourceProvider.getDensity().toDouble(),
    ): T? {
        val projected = ArrayList<T>(candidates.size)
        val rects = ArrayList<ScreenRect>(candidates.size)
        for (candidate in candidates) {
            val state = stateOf(candidate)
            val anchorScreen = project(state.position) ?: continue
            val icon = state.icon ?: defaultIcon
            val iconSizePx = icon.iconSize.value.toDouble() * icon.scale.toDouble() * density
            val extent = MarkerIconExtent.ratio(icon)
            projected += candidate
            rects +=
                ScreenRect(
                    anchorScreen = anchorScreen,
                    widthPx = iconSizePx * extent.width,
                    heightPx = iconSizePx * extent.height,
                    anchorX = icon.anchor.x.toDouble(),
                    anchorY = icon.anchor.y.toDouble(),
                )
        }
        val tolerancePx = Settings.Default.tapTolerance.value.toDouble() * density
        return pickIndex(touchScreen, rects, tolerancePx)?.let { projected[it] }
    }

    /**
     * 候補を探す半径（dp）。ラベルの長さの上限の目安で、これより遠くまで伸びたラベルの端は拾えない。
     */
    const val SEARCH_RADIUS_DP: Double = 256.0
}
