package com.mapconductor.core.spherical

import com.mapconductor.core.features.GeoPointInterface

/**
 * 非測地線（直線補間）の点列を密度化する。
 *
 * 分割数はセグメント長に応じて決める（[createInterpolatePoints] と同じ方式）。固定分割だと
 * 短いセグメントが多い多頂点ポリゴンで点数が頂点数×分割数に膨れ上がり、WebView ブリッジ経由で
 * GeoJSON を渡すプロバイダ（MapTiler/Longdo 等）の描画が極端に遅くなるため、
 * [maxSegmentLength] を超えるセグメントのみ分割する。
 */
fun createLinearInterpolatePoints(
    points: List<GeoPointInterface>,
    // 最大セグメント長（メートル）
    maxSegmentLength: Double = 10000.0,
): List<GeoPointInterface> {
    val results = mutableListOf<GeoPointInterface>()
    results.add(points[0])
    for (i in 1 until points.size) {
        val distance =
            GeographicLibCalculator.computeDistanceBetween(
                points[i - 1],
                points[i],
            )

        val numSegments = (distance / maxSegmentLength).toInt().coerceAtLeast(1)
        val step = 1.0 / numSegments

        var fraction = step
        while (fraction < 1.0) {
            val point =
                Spherical.linearInterpolate(
                    from = points[i - 1],
                    to = points[i],
                    fraction = fraction,
                )
            results.add(point)
            fraction += step
        }
        results.add(points[i])
    }
    return results
}
