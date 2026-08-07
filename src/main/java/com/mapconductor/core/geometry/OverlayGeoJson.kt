package com.mapconductor.core.geometry

import com.mapconductor.core.features.GeoPointInterface

/**
 * WebView 系ドライバー（MapTiler／Longdo 等）向けの GeoJSON Feature 文字列ビルダー。
 *
 * 座標は経度・緯度の順。properties は常に空オブジェクトで、数値座標のみを扱うため
 * エスケープは不要。出力形式は従来の各ドライバー実装と互換。
 */
object OverlayGeoJson {
    /** MultiLineString の Feature を生成する。セグメントが空なら null。 */
    fun multiLineStringFeature(segments: List<List<GeoPointInterface>>): String? {
        if (segments.isEmpty()) return null
        val coordinates =
            segments.joinToString(separator = ",") { segment ->
                segment.joinToString(separator = ",", prefix = "[", postfix = "]") { point ->
                    "[${point.longitude},${point.latitude}]"
                }
            }
        return "{\"type\":\"Feature\",\"geometry\":" +
            "{\"type\":\"MultiLineString\",\"coordinates\":[$coordinates]},\"properties\":{}}"
    }

    /**
     * Polygon（外周 1 つ＋穴）または MultiPolygon（外周複数、穴なし）の Feature を生成する。
     * 各リングは自動的に閉じる。外周が空なら null。
     */
    fun polygonFeature(rings: PolygonRings): String? {
        val outerRings = rings.outerRings
        if (outerRings.isEmpty()) return null
        return if (outerRings.size == 1) {
            val ringJsons = mutableListOf(ringToJson(outerRings.first()))
            rings.holeRings.forEach { ringJsons.add(ringToJson(it)) }
            val coordinates = ringJsons.joinToString(separator = ",", prefix = "[", postfix = "]")
            "{\"type\":\"Feature\",\"geometry\":" +
                "{\"type\":\"Polygon\",\"coordinates\":$coordinates},\"properties\":{}}"
        } else {
            val polygons = outerRings.joinToString(separator = ",") { "[${ringToJson(it)}]" }
            "{\"type\":\"Feature\",\"geometry\":" +
                "{\"type\":\"MultiPolygon\",\"coordinates\":[$polygons]},\"properties\":{}}"
        }
    }

    /** 穴のないリング列（円の分割結果等）を Polygon／MultiPolygon の Feature へ変換する。 */
    fun ringsFeature(rings: List<List<GeoPointInterface>>): String? =
        polygonFeature(PolygonRings(outerRings = rings, holeRings = emptyList()))

    private fun ringToJson(ring: List<GeoPointInterface>): String =
        closeRing(ring).joinToString(separator = ",", prefix = "[", postfix = "]") { point ->
            "[${point.longitude},${point.latitude}]"
        }
}
