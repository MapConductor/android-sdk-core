package com.mapconductor.core

import androidx.compose.ui.unit.dp
import com.mapconductor.core.circle.CircleState
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import com.mapconductor.core.groundimage.GroundImageState
import com.mapconductor.core.polygon.PolygonState
import com.mapconductor.core.polyline.PolylineState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 図形・地表画像の既定値。コンポーネント形（android-sdk-compose の `Circle(center, ...)` など）と
 * state 形で同じ値になるよう 3 プラットフォームで揃えている:
 * Circle / Polygon / Polyline の strokeWidth = 1.dp、GroundImage の opacity = 1.0f。
 * js-sdk-core の `ShapeDefaults.test.mjs`、ios-sdk の `ShapeDefaultsTests` と同じ観点。
 */
class ShapeDefaultsTest {
    private val p1 = GeoPoint.fromLatLong(35.0, 139.0)
    private val p2 = GeoPoint.fromLatLong(35.0, 140.0)
    private val p3 = GeoPoint.fromLatLong(36.0, 140.0)

    @Test
    fun circleStrokeWidthDefaultsTo1dp() {
        assertEquals(1.dp, CircleState(center = p1, radiusMeters = 100.0).strokeWidth)
    }

    @Test
    fun polygonStrokeWidthDefaultsTo1dp() {
        assertEquals(1.dp, PolygonState(points = listOf(p1, p2, p3)).strokeWidth)
    }

    @Test
    fun polylineStrokeWidthDefaultsTo1dp() {
        assertEquals(1.dp, PolylineState(points = listOf(p1, p2)).strokeWidth)
    }

    @Test
    fun groundImageOpacityDefaultsTo1() {
        val bounds =
            GeoRectBounds().apply {
                extend(p1)
                extend(p3)
            }
        assertEquals(1.0f, GroundImageState(bounds = bounds, image = FakeDrawable()).opacity)
    }

    /** GroundImageState は Drawable を要求するが、テストでは中身を使わない。 */
    private class FakeDrawable : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = Unit

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }
}
