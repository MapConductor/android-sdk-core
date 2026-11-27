package com.mapconductor.core

import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 図形の `copy()` は id を引き継がず、新しい中身から計算し直す（`id` を渡したときだけそれを使う）。
 * 描画側は図形を id で区別するので、引き継ぐと copy で増やした図形が元の図形を上書きし、
 * 1 つにしか見えなかった。MarkerState と同じ意味論。
 */
class ShapeStateCopyIdTest {
    private val a = GeoPoint(35.0, 139.0)
    private val b = GeoPoint(36.0, 140.0)
    private val c = GeoPoint(35.0, 140.0)

    @Test
    fun circle() {
        val base = CircleState(center = a, radiusMeters = 100.0, id = "c1")
        val moved = base.copy(center = b)

        assertNotEquals("c1", moved.id)
        assertEquals(CircleState(center = b, radiusMeters = 100.0).id, moved.id)
        assertEquals("c1", base.copy(id = "c1", center = b).id)
    }

    @Test
    fun polyline() {
        val base = PolylineState(points = listOf(a, b), id = "l1")
        val moved = base.copy(points = listOf(a, c))

        assertNotEquals("l1", moved.id)
        assertEquals(PolylineState(points = listOf(a, c)).id, moved.id)
        assertEquals("l1", base.copy(id = "l1", points = listOf(a, c)).id)
    }

    @Test
    fun polygon() {
        val base = PolygonState(points = listOf(a, b, c), id = "p1")
        val moved = base.copy(points = listOf(a, b, c, a))

        assertNotEquals("p1", moved.id)
        assertEquals(PolygonState(points = listOf(a, b, c, a)).id, moved.id)
        assertEquals("p1", base.copy(id = "p1", points = listOf(a, c, b)).id)
    }

    @Test
    fun groundImage() {
        val image = FakeDrawable()
        val area = bounds(a, b)
        val base = GroundImageState(bounds = area, image = image, id = "g1")
        val moved = base.copy(opacity = 0.25f)

        assertNotEquals("g1", moved.id)
        assertEquals(GroundImageState(bounds = area, image = image, opacity = 0.25f).id, moved.id)
        assertEquals("g1", base.copy(id = "g1", opacity = 0.25f).id)
    }

    private fun bounds(
        sw: GeoPoint,
        ne: GeoPoint,
    ) = GeoRectBounds().apply {
        extend(sw)
        extend(ne)
    }

    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
