package com.mapconductor.core.marker

import androidx.annotation.RestrictTo
import com.mapconductor.core.map.MapCameraPosition
import com.mapconductor.core.spherical.expandBounds
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 拡張ビューポート内のマーカーが少ないときだけ、タイル担当のマーカーをネイティブマーカーへ
 * 昇格させる切り替え器。閾値は [MarkerViewportPolicy]。
 *
 * **開発中の機能で、公開 API ではない。** 有効化の口は [MarkerTilingOptions.viewport] だけで、
 * そこは読み取り専用の [MarkerViewportPolicy.Disabled] 固定なので、SDK 利用者の設定では
 * 動かない。プロバイダモジュールから参照するためだけに public になっている。
 *
 * ### 何を触り、何を触らないか
 *
 * 触るのは「ネイティブマーカーを出す／消す」ことと「マーカータイルのラスターレイヤを
 * 見せる／隠す」ことだけで、**どの entity がタイル担当か（[MarkerEntityInterface.tiling]）は
 * 変えない**。タイルの中身が変わらないので [MarkerTileRenderer] のキャッシュもタイル URL も
 * そのままでよく、カメラを動かすたびにタイルを取り直す羽目にならない。
 *
 * ドラッグ可能／アニメーション付きのマーカーは元から `tiling = false` でネイティブに出ている。
 * ここが扱うのは `tiling = true` の entity だけなので、それらには一切触れない。
 *
 * ### 呼び出し側の責務
 *
 * - カメライベントで [onCameraChanged] を呼ぶ（debounce は内部で行う）。
 * - マーカー集合を作り直す前（[MarkerIngestionEngine.ingest] の前）に [retract] を呼び、
 *   後で [requestReapply] を呼ぶ。ingest はタイル担当 entity を `marker = null` で
 *   登録し直すため、先に戻しておかないとネイティブマーカーの参照が迷子になる。
 * - [retract] は自前の semaphore を取るので、コントローラ側の `withPermit` の**外**で呼ぶこと。
 * - 破棄時に [destroy]。
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class MarkerViewportSwitch<ActualMarker : Any>(
    private val markerManager: MarkerManager<ActualMarker>,
    private val renderer: MarkerOverlayRendererInterface<ActualMarker>,
    private val defaultMarkerIcon: BitmapIcon,
    private val semaphore: Semaphore,
    private val policy: MarkerViewportPolicy = MarkerViewportPolicy.Default,
    /** マーカータイルのラスターレイヤの表示を切り替える。プロバイダ固有。 */
    private val setTileLayerVisible: suspend (Boolean) -> Unit,
    /**
     * マーカータイルを描き直させる。プロバイダ固有（既存の `updateRasterLayerSource()`）。
     *
     * 昇格中はタイル担当から外れているので、その間に焼かれたタイルには昇格分が入っていない。
     * タイルへ戻すときに一度だけ呼んで捨てる。**戻すときだけ**なので、パンのたびに
     * 取り直すことにはならない。
     */
    private val invalidateTiles: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate),
) {
    enum class Mode {
        /** マーカータイル（ラスターレイヤ）で描いている。 */
        Tile,

        /** タイル担当マーカーをネイティブマーカーへ昇格させ、ラスターレイヤを隠している。 */
        Native,
    }

    @Volatile
    var mode: Mode = Mode.Tile
        private set

    /** いま昇格させているマーカー id。タイルへ戻すときの対象でもある。 */
    private val nativeIds = LinkedHashSet<String>()

    private var pendingJob: Job? = null

    @Volatile
    private var lastCamera: MapCameraPosition? = null

    private var notifiedMode: Mode = Mode.Tile
    private var notifiedCount: Int = 0

    /** テスト・デバッグ用。いま昇格中の件数。 */
    val nativeCount: Int get() = nativeIds.size

    /** [id] が昇格中か。1 件だけ触る前に [release] が要るかの判断に使う。 */
    fun isPromoted(id: String): Boolean = nativeIds.contains(id)

    /**
     * 1 件だけ昇格を取り下げる。
     *
     * そのマーカーの state を差し替える直前に呼ぶ。差し替え側は `marker = null` で
     * 登録し直すので、先に外しておかないとネイティブマーカーが取り残される。
     * [retract] と同じくコントローラ側の `withPermit` の外で呼ぶこと。
     */
    suspend fun release(id: String) {
        if (!nativeIds.contains(id)) return
        semaphore.withPermit {
            demote(listOf(id))
            renderer.onPostProcess()
        }
    }

    fun onCameraChanged(camera: MapCameraPosition) {
        lastCamera = camera
        if (!policy.enabled) return
        pendingJob?.cancel()
        pendingJob =
            scope.launch {
                delay(policy.settleDelayMillis)
                evaluate(camera)
            }
    }

    /** 直近のカメラでもう一度判定し直す。[retract] のあとに呼ぶ。 */
    fun requestReapply() {
        lastCamera?.let { onCameraChanged(it) }
    }

    /**
     * 昇格を全部取り下げ、ラスターレイヤを戻す。
     *
     * マーカー集合の作り直し前と、タイリング自体を止めるときに呼ぶ。
     */
    suspend fun retract() {
        // cancel() だけでは足りない。判定コルーチンが delay を抜けて promote の途中まで
        // 進んでいると、こちらが戻し終えた後にあちらが昇格を再開して、レイヤ表示中に
        // ネイティブマーカーが乗る（＝二重描画）。終わるまで待ってから戻す。
        pendingJob?.cancelAndJoin()
        pendingJob = null
        if (mode == Mode.Tile && nativeIds.isEmpty()) return
        semaphore.withPermit {
            demoteAll()
            mode = Mode.Tile
        }
        invalidateTiles()
        setTileLayerVisible(true)
        notifyMode()
    }

    fun destroy() {
        pendingJob?.cancel()
        pendingJob = null
        nativeIds.clear()
        mode = Mode.Tile
    }

    private suspend fun evaluate(camera: MapCameraPosition) {
        val bounds = camera.visibleRegion?.bounds ?: return
        if (bounds.isEmpty) return
        val startedAt = SystemClock.elapsedRealtime()
        val expanded = expandBounds(bounds, policy.expandMargin)
        // 昇格中のものは tiling = false になっているので、対象は
        // 「タイル担当（未昇格）」＋「自分が昇格させたもの」。
        // [nativeIds] はメインスレッド専有なので、別スレッドへ渡す前にここで写しを取る。
        val promotedSnapshot = nativeIds.toSet()
        // 空間検索はメインスレッドから外す。24,000 件規模だと 200ms 超えることがあり、
        // そのままだと切り替えのたびに 1 フレームどころではない停止として見える。
        val near =
            withContext(Dispatchers.Default) {
                markerManager.findMarkersInBounds(expanded).filter {
                    it.tiling || promotedSnapshot.contains(it.state.id)
                }
            }
        val queriedAt = SystemClock.elapsedRealtime()
        val next = decide(current = mode, policy = policy, zoom = camera.zoom, nearCount = near.size)

        if (next == Mode.Native) {
            // 先にレイヤを隠してから昇格する。逆順にするとタイルの絵とネイティブマーカーが
            // 一瞬重なり、bearing が付いていると二重に見える。
            if (mode != Mode.Native) {
                setTileLayerVisible(false)
                mode = Mode.Native
            }
            semaphore.withPermit { promote(near) }
        } else {
            if (mode != Mode.Tile || nativeIds.isNotEmpty()) {
                semaphore.withPermit { demoteAll() }
                mode = Mode.Tile
                invalidateTiles()
                setTileLayerVisible(true)
            }
        }
        trace(
            "evaluate mode=$mode near=${near.size} promoted=${nativeIds.size} " +
                "queryMs=${queriedAt - startedAt} applyMs=${SystemClock.elapsedRealtime() - queriedAt} " +
                "totalMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
        notifyMode()
    }

    private fun trace(message: String) {
        Log.d("MCMarkerTrace", "[CoreSDK][ViewportSwitch] $message")
    }

    private fun notifyMode() {
        val changed = mode != notifiedMode || nativeIds.size != notifiedCount
        if (!changed) return
        notifiedMode = mode
        notifiedCount = nativeIds.size
        policy.onModeChanged?.invoke(mode == Mode.Native, notifiedCount)
    }

    /** [desired] に無いものを外し、まだ出していないものを出す。 */
    private suspend fun promote(desired: List<MarkerEntityInterface<ActualMarker>>) {
        val desiredIds = desired.mapTo(HashSet(desired.size * 2)) { it.state.id }
        val staleIds = nativeIds.filterTo(mutableListOf()) { it !in desiredIds }
        if (staleIds.isNotEmpty()) {
            demote(staleIds)
        }

        val toAdd = desired.filter { it.state.id !in nativeIds }
        if (toAdd.isEmpty()) {
            if (staleIds.isNotEmpty()) renderer.onPostProcess()
            return
        }

        toAdd.chunked(VIEWPORT_RENDER_BATCH_SIZE).forEach { batch ->
            val params =
                batch.map { entity ->
                    object : MarkerOverlayRendererInterface.AddParamsInterface {
                        override val state: MarkerState = entity.state
                        override val bitmapIcon: BitmapIcon =
                            entity.state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                    }
                }
            val actualMarkers = renderer.onAdd(params)
            actualMarkers.forEachIndexed { index, actualMarker ->
                actualMarker ?: return@forEachIndexed
                val entity = batch[index]
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = actualMarker,
                        state = entity.state,
                        visible = entity.visible,
                        isRendered = true,
                        // 昇格中は「タイル担当ではない」。
                        // プロバイダのレンダラは `allEntities().filter { !it.tiling }` を描き、
                        // [MarkerTileRenderer] は `filter { it.tiling }` を焼くので、
                        // このフラグ 1 つで両者の担当が排他になる。立てたままだと
                        // どちらからも描かれず、マーカーが消える。
                        tiling = false,
                    ),
                )
                nativeIds.add(entity.state.id)
            }
            yield()
        }
        renderer.onPostProcess()
    }

    private suspend fun demoteAll() {
        if (nativeIds.isEmpty()) return
        demote(nativeIds.toList())
        renderer.onPostProcess()
    }

    private suspend fun demote(ids: List<String>) {
        val entities =
            ids.mapNotNull { markerManager.getEntity(it) }.filter { it.marker != null }
        if (entities.isNotEmpty()) {
            renderer.onRemove(entities)
            entities.forEach { entity ->
                markerManager.updateEntity(
                    MarkerEntity(
                        marker = null,
                        state = entity.state,
                        visible = entity.visible,
                        isRendered = true,
                        // タイル担当へ戻す。
                        tiling = true,
                    ),
                )
            }
        }
        nativeIds.removeAll(ids.toSet())
    }

    companion object {
        /**
         * 次のモードを決める。副作用が無いので単体テストはここを見る。
         *
         * ヒステリシス: タイル → ネイティブは [MarkerViewportPolicy.nativeMaxCount] 以下、
         * ネイティブ → タイルは [MarkerViewportPolicy.tileMinCount] 以上。その間は現状維持。
         */
        fun decide(
            current: Mode,
            policy: MarkerViewportPolicy,
            zoom: Double,
            nearCount: Int,
        ): Mode {
            if (!policy.enabled) return Mode.Tile
            if (zoom < policy.minZoom) return Mode.Tile
            return when (current) {
                Mode.Tile -> if (nearCount <= policy.nativeMaxCount) Mode.Native else Mode.Tile
                Mode.Native -> if (nearCount >= policy.tileMinCount) Mode.Tile else Mode.Native
            }
        }
    }
}

/**
 * 1 度に renderer へ渡す件数。バッチの合間に [yield] してメインスレッドを明け渡す。
 *
 * 実測（TB520FU / 24,000 件のうち 271 件を昇格）では、この値を 250 から 100 に下げても
 * apply は 42ms のままで変わらなかった。支配的なのはバッチ内の onAdd ではなく、
 * 各プロバイダの `onPostProcess()` が毎回 `markerManager.allEntities()` で全 24,000 件を
 * 走査してレイヤを組み直す部分。減らすならそちらを直す必要がある。
 */
private const val VIEWPORT_RENDER_BATCH_SIZE = 250
