package com.mapconductor.core.marker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.map.CameraBearing
import com.mapconductor.core.map.MapCameraPosition
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 地図の向きに合わせてマーカーを並べ直してもらう側。
 *
 * マーカーのコントローラが実装し、[com.mapconductor.core.controller.BaseMapViewController]
 * が向きの変化を配る。
 */
@InternalMapConductorApi
interface MarkerDrawOrderReceiver {
    suspend fun onDrawOrderFrameChanged(frame: MarkerDrawOrder.Frame)
}

/**
 * カメラの向きを見て、[MarkerDrawOrder.Frame] が変わったら一度だけ知らせる。
 *
 * 回している間は毎フレームのカメラが来るので、向きが [settle] のあいだ止まってから
 * 知らせる（大量のマーカーを毎フレーム並べ直さない）。パンやズームでは前後関係が
 * 変わらないので知らせない。
 */
@InternalMapConductorApi
class MarkerDrawOrderTracker(
    private val scope: CoroutineScope,
    private val settle: Duration = 150.milliseconds,
    private val onChanged: suspend (MarkerDrawOrder.Frame) -> Unit,
) {
    /** 今マーカーを並べている向き。新しく足すマーカーもこれで並べる。 */
    @Volatile
    var frame: MarkerDrawOrder.Frame = MarkerDrawOrder.Frame.NorthUp
        private set

    private var target: MarkerDrawOrder.Frame = frame
    private var pending: Job? = null

    fun onCameraChanged(camera: MapCameraPosition) {
        val next = MarkerDrawOrder.Frame(CameraBearing.toNativeHeading(camera.bearing), camera.position.longitude)
        synchronized(this) {
            if (!next.differsEnoughFrom(target)) return
            target = next
            pending?.cancel()
            pending =
                scope.launch {
                    delay(settle)
                    frame = next
                    onChanged(next)
                }
        }
    }
}

/**
 * Compose でマーカーを描くドライバー向けの [MarkerDrawOrderTracker]。
 *
 * 後に置いたものほど上に描かれるので、`markers.sortedBy { MarkerDrawOrder.of(..., frame) }`
 * の `frame` にこれを渡す。向きが変わって [settle] だけ止まったときだけ値が変わる
 * （パン・ズームや回転の途中では組み直さない）。
 */
@InternalMapConductorApi
@Composable
fun rememberMarkerDrawOrderFrame(
    settle: Duration = 150.milliseconds,
    camera: () -> MapCameraPosition,
): MarkerDrawOrder.Frame {
    val frame = remember { mutableStateOf(MarkerDrawOrder.Frame.NorthUp) }
    val latestCamera by rememberUpdatedState(camera)
    LaunchedEffect(settle) {
        snapshotFlow {
            val c = latestCamera()
            MarkerDrawOrder.Frame(CameraBearing.toNativeHeading(c.bearing), c.position.longitude)
        }.collectLatest { next ->
            if (!next.differsEnoughFrom(frame.value)) return@collectLatest
            delay(settle)
            frame.value = next
        }
    }
    return frame.value
}
