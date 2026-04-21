package com.mapconductor.core.polyline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapconductor.core.MapViewScope
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.features.GeoPointInterface
import com.mapconductor.core.features.GeoRectBounds
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapViewScope.Polyline(state: PolylineState) {
    val collector = LocalPolylineCollector.current
    LaunchedEffect(state) {
        collector.add(state)
    }

    DisposableEffect(state.id) {
        onDispose {
            collector.remove(state.id)
        }
    }
}

@Composable
fun MapViewScope.Polylines(states: List<PolylineState>) {
    val collector = LocalPolylineCollector.current
    val prevIdsState = remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(states) {
        withContext(Dispatchers.Default) {
            prevIdsState.value = states.asSequence().map { it.id }.toSet()
            collector.flow.value = states.associateBy { it.id }.toMutableMap()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            collector.flow.value = mutableMapOf()
            prevIdsState.value = emptySet()
        }
    }
}

@Composable
fun MapViewScope.Polyline(
    points: List<GeoPointInterface>,
    id: String? = null,
    strokeColor: Color = Color.Black,
    strokeWidth: Dp = 1.dp,
    geodesic: Boolean = false,
    zIndex: Int = 0,
    extra: Serializable? = null,
    onClick: OnPolylineEventHandler? = null,
) {
    val state =
        PolylineState(
            points = points,
            id = id,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
            geodesic = geodesic,
            zIndex = zIndex,
            extra = extra,
            onClick = onClick,
        )
    Polyline(state)
}

@Composable
fun MapViewScope.Polyline(
    bounds: GeoRectBounds,
    id: String? = null,
    strokeColor: Color = Color.Black,
    strokeWidth: Dp = 1.dp,
    geodesic: Boolean = false,
    zIndex: Int = 0,
    extra: Serializable? = null,
    onClick: OnPolylineEventHandler? = null,
) {
    bounds.northEast?.let { ne ->
        bounds.southWest?.let { sw ->
            val points =
                listOf(
                    ne,
                    GeoPoint.fromLatLong(sw.latitude, ne.longitude),
                    sw,
                    GeoPoint.fromLatLong(ne.latitude, sw.longitude),
                    ne,
                )
            Polyline(
                points = points,
                id = id,
                strokeColor = strokeColor,
                strokeWidth = strokeWidth,
                geodesic = geodesic,
                zIndex = zIndex,
                extra = extra,
                onClick = onClick,
            )
        }
    }
}
