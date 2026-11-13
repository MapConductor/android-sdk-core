package com.mapconductor.core.map

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoRectBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import android.os.Bundle
import android.os.Parcel

/**
 * 回転などの構成変更で state が Saver を通って作り直されるときに、値が落ちないこと。
 *
 * [Bundle] は JVM の単体テストでは中身の無いスタブなので、実機で確かめる。保存した
 * Bundle は一度 [Parcel] に書いて読み戻す（プロセスが落ちたあとの復元と同じ経路で、
 * Bundle に入れてはいけない型が混じっていればここで落ちる）。
 */
@RunWith(AndroidJUnit4::class)
class MapViewSaverRoundTripTest {
    private val rules =
        listOf(
            AttributionRule("© plain"),
            AttributionRule(
                attribution = "© ranged",
                minZoom = 3,
                maxZoom = 12,
                bounds = GeoRectBounds(GeoPoint(35.0, 139.0), GeoPoint(36.0, 140.5)),
            ),
            AttributionRule(attribution = "© empty bounds", bounds = GeoRectBounds()),
        )

    @Test
    fun attributionRulesSurviveParcel() {
        val bundle = Bundle()
        MapViewStateBundle.putAttributionRules(bundle, rules)

        val restored = MapViewStateBundle.getAttributionRules(throughParcel(bundle))

        assertEquals(rules.size, restored.size)
        rules.zip(restored).forEach { (expected, actual) ->
            assertEquals(expected.attribution, actual.attribution)
            assertEquals(expected.minZoom, actual.minZoom)
            assertEquals(expected.maxZoom, actual.maxZoom)
            assertEquals(expected.bounds?.southWest, actual.bounds?.southWest)
            assertEquals(expected.bounds?.northEast, actual.bounds?.northEast)
            assertEquals(expected.bounds?.isEmpty, actual.bounds?.isEmpty)
        }
    }

    @Test
    fun missingAttributionRulesReadAsEmpty() {
        assertEquals(emptyList<AttributionRule>(), MapViewStateBundle.getAttributionRules(Bundle()))
        assertEquals(emptyList<AttributionRule>(), MapViewStateBundle.getAttributionRules(null))
    }

    @Test
    fun saverKeepsUiSettingsCameraAndDesign() {
        val state =
            FakeState(
                id = "state-1",
                design = FakeDesign("custom-design", rules),
                camera =
                    MapCameraPosition(
                        position = GeoPoint(35.68, 139.76, 12.0),
                        zoom = 14.5,
                        bearing = 30.0,
                        tilt = 45.0,
                        paddings = MapPaddings(top = 10.0, left = 20.0, bottom = 30.0, right = 40.0),
                    ),
            )
        state.uiSettings =
            MapUISettings(
                scrollGesture = false,
                zoomGesture = true,
                rotateGesture = false,
                tiltGesture = false,
                paddings = MapPaddings(top = 1.0, left = 2.0, bottom = 3.0, right = 4.0),
            )

        val restored = roundTrip(FakeSaver().createSaver(), state)

        assertEquals("state-1", restored.id)
        assertEquals("custom-design", restored.mapDesignType.id)
        assertEquals(rules.map { it.attribution }, restored.mapDesignType.attributionRules.map { it.attribution })

        val ui = restored.uiSettings
        assertEquals(false, ui.scrollGesture)
        assertEquals(true, ui.zoomGesture)
        assertEquals(false, ui.rotateGesture)
        assertEquals(false, ui.tiltGesture)
        assertPaddings(MapPaddings(1.0, 2.0, 3.0, 4.0), ui.paddings)

        val camera = restored.cameraPosition
        assertEquals(35.68, camera.position.latitude, 1e-9)
        assertEquals(139.76, camera.position.longitude, 1e-9)
        assertEquals(12.0, camera.position.altitude, 1e-9)
        assertEquals(14.5, camera.zoom, 1e-9)
        assertEquals(30.0, camera.bearing, 1e-9)
        assertEquals(45.0, camera.tilt, 1e-9)
        assertPaddings(MapPaddings(10.0, 20.0, 30.0, 40.0), camera.paddings!!)
    }

    @Test
    fun defaultUiSettingsStayDefault() {
        val restored = roundTrip(FakeSaver().createSaver(), FakeState("s", FakeDesign("d", emptyList())))
        assertEquals(MapUISettings.Default.scrollGesture, restored.uiSettings.scrollGesture)
        assertEquals(MapUISettings.Default.rotateGesture, restored.uiSettings.rotateGesture)
        assertEquals(emptyList<AttributionRule>(), restored.mapDesignType.attributionRules)
    }

    /** 以前の形式（uiSettings も camera の paddings も無い）の Bundle も読めること。 */
    @Test
    fun readsBundleWrittenBeforeTheFix() {
        val legacy =
            Bundle().apply {
                putString("stateId", "old")
                putBundle("mapDesign", Bundle().apply { putString("id", "old-design") })
                putBundle(
                    "camera",
                    Bundle().apply {
                        putDouble("zoom", 5.0)
                        putDouble("tilt", 0.0)
                        putDouble("bearing", 0.0)
                        putDouble("latitude", 1.0)
                        putDouble("longitude", 2.0)
                    },
                )
            }

        val restored = FakeSaver().createSaver().restore(throughParcel(legacy))!!

        assertEquals("old", restored.id)
        assertEquals("old-design", restored.mapDesignType.id)
        assertEquals(MapUISettings.Default, restored.uiSettings)
        assertEquals(5.0, restored.cameraPosition.zoom, 1e-9)
        assertNull(restored.cameraPosition.paddings)
    }

    private fun assertPaddings(
        expected: MapPaddingsInterface,
        actual: MapPaddingsInterface,
    ) {
        assertEquals(expected.top, actual.top, 1e-9)
        assertEquals(expected.left, actual.left, 1e-9)
        assertEquals(expected.bottom, actual.bottom, 1e-9)
        assertEquals(expected.right, actual.right, 1e-9)
    }

    private class FakeDesign(
        override val id: String,
        override val attributionRules: List<AttributionRule>,
    ) : MapDesignTypeInterface<String> {
        override fun getValue(): String = id
    }

    private class FakeState(
        override val id: String,
        design: FakeDesign,
        camera: MapCameraPosition = MapCameraPosition.Default,
    ) : MapViewState<FakeDesign>(camera) {
        override var mapDesignType: FakeDesign = design
    }

    private class FakeSaver : BaseMapViewSaver<FakeState>() {
        override fun saveMapDesign(
            state: FakeState,
            bundle: Bundle,
        ) {
            bundle.putString("id", state.mapDesignType.id)
            MapViewStateBundle.putAttributionRules(bundle, state.mapDesignType.attributionRules)
        }

        override fun createState(
            stateId: String,
            mapDesignBundle: Bundle?,
            cameraPosition: MapCameraPosition,
        ): FakeState =
            FakeState(
                id = stateId,
                design =
                    FakeDesign(
                        id = mapDesignBundle?.getString("id") ?: "",
                        attributionRules = MapViewStateBundle.getAttributionRules(mapDesignBundle),
                    ),
                camera = cameraPosition,
            )

        override fun getStateId(state: FakeState): String = state.id
    }

    private companion object {
        fun throughParcel(bundle: Bundle): Bundle {
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                parcel.setDataPosition(0)
                return parcel.readBundle(MapViewSaverRoundTripTest::class.java.classLoader)!!
            } finally {
                parcel.recycle()
            }
        }

        fun <T : Any> roundTrip(
            saver: Saver<T, Bundle>,
            value: T,
        ): T {
            val scope = SaverScope { true }
            val saved = with(saver) { scope.save(value) }!!
            return saver.restore(throughParcel(saved))!!
        }
    }
}
