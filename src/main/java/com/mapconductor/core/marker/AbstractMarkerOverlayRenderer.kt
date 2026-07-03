package com.mapconductor.core.marker

import androidx.compose.ui.geometry.Offset
import com.mapconductor.core.features.GeoPoint
import com.mapconductor.core.map.MapViewHolderInterface
import com.mapconductor.core.projection.Earth
import com.mapconductor.settings.Settings
import kotlin.math.min
import kotlin.math.pow
import android.os.SystemClock
import android.view.animation.BounceInterpolator
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

abstract class AbstractMarkerOverlayRenderer<
    MapViewHolderType : MapViewHolderInterface<*, *>,
    ActualMarker,
>(
    val holder: MapViewHolderType,
    val coroutine: CoroutineScope,
    val dropAnimateDuration: Long = Settings.Default.markerDropAnimateDuration,
    val bounceAnimateDuration: Long = Settings.Default.markerBounceAnimateDuration,
) : MarkerOverlayRendererInterface<ActualMarker> {
    override var animateStartListener: OnMarkerEventHandler? = null
    override var animateEndListener: OnMarkerEventHandler? = null

    /**
     * Set by the Compose layer; when present and [supportsAnimationOverlay]
     * is true, marker animations run in screen space above the map view
     * instead of interpolating geographic coordinates (which breaks on
     * tilted/rotated/globe projections).
     */
    var animationOverlayHost: MarkerAnimationOverlayHost? = null

    /**
     * Renderers that can hide/show their native marker (see
     * [setMarkerVisible]) opt into the screen-space animation overlay.
     */
    open val supportsAnimationOverlay: Boolean = false

    /** Hide/show the native marker while the overlay animation runs. */
    open fun setMarkerVisible(
        markerEntity: MarkerEntityInterface<ActualMarker>,
        visible: Boolean,
    ) {
    }

    abstract fun setMarkerPosition(
        markerEntity: MarkerEntityInterface<ActualMarker>,
        position: GeoPoint,
    )

    override suspend fun onAnimate(entity: MarkerEntityInterface<ActualMarker>) {
        val animation = entity.state.getAnimation()
        val host = animationOverlayHost
        if (animation != null && host != null && supportsAnimationOverlay) {
            animateOnOverlay(host, entity, animation)
            return
        }
        when (animation) {
            MarkerAnimation.Drop ->
                animateMarkerDrop(
                    entity = entity,
                    duration = dropAnimateDuration,
                )
            MarkerAnimation.Bounce ->
                animateMarkerBounce(
                    entity = entity,
                    duration = bounceAnimateDuration,
                )
            else -> throw IllegalArgumentException("No animation is available: $animation")
        }
    }

    private fun animateOnOverlay(
        host: MarkerAnimationOverlayHost,
        entity: MarkerEntityInterface<ActualMarker>,
        animation: MarkerAnimation,
    ) {
        val duration =
            when (animation) {
                MarkerAnimation.Drop -> dropAnimateDuration
                MarkerAnimation.Bounce -> bounceAnimateDuration
            }
        val icon = entity.state.icon?.toBitmapIcon() ?: DefaultMarkerIcon().toBitmapIcon()
        setMarkerVisible(entity, false)
        animateStartListener?.invoke(entity.state)
        host.start(
            MarkerAnimationOverlayEntry(
                id = entity.state.id,
                state = entity.state,
                icon = icon,
                animation = animation,
                durationMillis = duration,
                onFinished = {
                    coroutine.launch {
                        setMarkerVisible(entity, true)
                        entity.state.animate(null)
                        animateEndListener?.invoke(entity.state)
                    }
                },
            ),
        )
    }

    fun zoomToMetersPerPixel(
        zoom: Double,
        tileSize: Int,
    ): Double = Earth.CIRCUMFERENCE_METERS / (tileSize * 2.0.pow(zoom))

    fun animateMarkerDrop(
        entity: MarkerEntityInterface<ActualMarker>,
        duration: Long,
    ) {
        coroutine.launch {
            // アニメーションの最終的な目標地点(地理座標)
            val target = entity.state.position

            // 線形補間
            val interpolator = LinearInterpolator()

            // 開始地点:x座標はMarkerと同じ、y座標は画面上端。なければreturn
            val startPoint = holder.toScreenOffset(target)?.let { Offset(it.x, 0f) } ?: return@launch

            animateStartListener?.invoke(entity.state)

            flow {
                val startTime = SystemClock.uptimeMillis()
                var t = 0f
                while (t < 1f) {
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    t = min(1f, elapsed.toFloat() / duration)
                    emit(interpolator.getInterpolation(t))
                    delay(16L)
                }
            }.onEach { t: Float ->
                // 開始時の画面座標から緯度経度に戻す(垂直方向アニメーション起点)
                val startLatLng = holder.fromScreenOffset(startPoint) ?: return@onEach

                // 緯度・経度を線形補間
                val interpolatedLatitude = t * target.latitude + (1f - t) * startLatLng.latitude
                val interpolatedLongitude = t * target.longitude + (1f - t) * startLatLng.longitude

                // 現在の座標をマーカーに適用
                val newPosition = GeoPoint.fromLatLong(interpolatedLatitude, interpolatedLongitude)
                setMarkerPosition(entity, newPosition)
            }.onCompletion {
                entity.state.position = target
                entity.state.animate(null)
                animateEndListener?.invoke(entity.state)
            }.launchIn(coroutine)
        }
    }

    fun animateMarkerBounce(
        entity: MarkerEntityInterface<ActualMarker>,
        duration: Long,
    ) {
        coroutine.launch {
            val target = entity.state.position
            val interpolator = BounceInterpolator()
            val startPoint = holder.toScreenOffset(target)?.let { Offset(it.x, 0f) } ?: return@launch

            animateStartListener?.invoke(entity.state)
            flow {
                val startTime = SystemClock.uptimeMillis()
                var t = 0f
                while (t < 1f) {
                    val elapsed = SystemClock.uptimeMillis() - startTime
                    t = interpolator.getInterpolation(min(1f, elapsed.toFloat() / duration))
                    emit(t)
                    delay(16L)
                }
            }.onEach { t ->
                val startLatLng = holder.fromScreenOffset(startPoint) ?: return@onEach
                val interpolatedLongitude = t * target.longitude + (1f - t) * startLatLng.longitude
                val interpolatedLatitude = t * target.latitude + (1f - t) * startLatLng.latitude

                // 現在の座標をマーカーに適用
                val newPosition = GeoPoint.fromLatLong(interpolatedLatitude, interpolatedLongitude)
                setMarkerPosition(entity, newPosition)
            }.onCompletion {
                // 最終的にマーカー位置を正確な着地点に戻す（補間誤差などを吸収）
                entity.state.position = target
                entity.state.animate(null)
                animateEndListener?.invoke(entity.state)
            }.launchIn(coroutine)
        }
    }
}
