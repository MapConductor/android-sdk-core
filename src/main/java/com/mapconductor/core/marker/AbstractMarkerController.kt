package com.mapconductor.core.marker

import com.mapconductor.core.controller.OverlayHit
import com.mapconductor.core.controller.OverlayKind
import com.mapconductor.core.controller.SlottedOverlayController
import com.mapconductor.core.features.GeoPointInterface
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield

abstract class AbstractMarkerController<ActualMarker>(
    val markerManager: MarkerManager<ActualMarker>,
    val renderer: MarkerOverlayRendererInterface<ActualMarker>,
    var clickListener: OnMarkerEventHandler? = null,
) : SlottedOverlayController<
        MarkerState,
        MarkerEntityInterface<ActualMarker>,
    > {
    override val zIndex: Int = 10
    val semaphore = Semaphore(1)
    private val defaultMarkerIcon = DefaultMarkerIcon().toBitmapIcon()

    var dragStartListener: OnMarkerEventHandler? = null
    var dragListener: OnMarkerEventHandler? = null
    var dragEndListener: OnMarkerEventHandler? = null
    var animateStartListener: OnMarkerEventHandler? = null
    var animateEndListener: OnMarkerEventHandler? = null

    /**
     * ドラッグ中のマーカー id。
     *
     * react-sdk が `AbstractMarkerController` に持つ WeakMap のドラッグ状態の移植。
     * react-sdk は state インスタンスをキーにした WeakMap を使うが、Kotlin/Java には
     * 「弱参照かつ同一性キー」のマップが標準に無く、`MarkerState` は `equals` /
     * `hashCode` をフィールド値で上書きしているため `WeakHashMap` だと別インスタンスの
     * 等価な state と衝突する。そのため id の集合で持つ（ios-sdk の `animatingMarkerIds` と同じ方式）。
     *
     * **これは facility であって既定の挙動ではない。**
     * react-sdk では、ネイティブにドラッグ可能なマーカーを持つプロバイダ（Leaflet の
     * Draggable、HERE の H.map behavior など）が `update()` を override して
     * `isDragging` でスキップする。マーカーを動かしているのは SDK 側なので、そこへ
     * `MarkerState` の位置を再適用すると綱引きになるため。
     *
     * 一方 android-sdk のプロバイダは**ドラッグを自前のジェスチャ処理で実装**し、
     * `state.position` を書き換えて、その変更通知から来る [update] の再描画でマーカーを
     * 動かしている（`HereMapViewController` が典型）。つまり [update] こそがドラッグの
     * 駆動経路なので、ここで一律にスキップするとマーカーが指に追従しなくなる。
     *
     * そのため既定では [update] をスキップしない。ネイティブドラッグを持つプロバイダが
     * 必要に応じて [update] を override して [isDragging] で判断すること。
     */
    private val draggingMarkerIds: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        renderer.animateStartListener = { state -> dispatchAnimateStart(state) }
        renderer.animateEndListener = { state -> dispatchAnimateEnd(state) }
    }

    /**
     * クリックを配送する。
     *
     * `clickable=false` のマーカーには配送しない。マーカーのヒットテスト
     * （[find]）はドラッグの開始判定にも使われるため、そちらでは `clickable` を
     * 見られない（`clickable=false` かつ `draggable=true` のマーカーがドラッグ
     * 不能になってしまう）。判定をここに置くことで、ドラッグを保ったまま
     * どのプロバイダでも同じ挙動になる。
     */
    fun dispatchClick(state: MarkerState) {
        if (!state.clickable) return
        state.onClick?.invoke(state)
        clickListener?.invoke(state)
    }

    /**
     * ドラッグ中フラグを立て下げする。[dispatchDragStart] / [dispatchDragEnd] が自動で呼ぶ。
     *
     * フラグを立てるだけで既定の挙動は変わらない（[draggingMarkerIds] の説明を参照）。
     * 参照するかどうかは各プロバイダの判断。
     */
    open fun setDraggingState(
        state: MarkerState,
        dragging: Boolean,
    ) {
        if (dragging) {
            draggingMarkerIds.add(state.id)
        } else {
            draggingMarkerIds.remove(state.id)
        }
    }

    fun isDragging(state: MarkerState): Boolean = draggingMarkerIds.contains(state.id)

    fun dispatchDragStart(state: MarkerState) {
        setDraggingState(state, true)
        state.onDragStart?.invoke(state)
        dragStartListener?.invoke(state)
    }

    fun dispatchDrag(state: MarkerState) {
        state.onDrag?.invoke(state)
        dragListener?.invoke(state)
    }

    fun dispatchDragEnd(state: MarkerState) {
        setDraggingState(state, false)
        state.onDragEnd?.invoke(state)
        dragEndListener?.invoke(state)
    }

    fun dispatchAnimateStart(state: MarkerState) {
        state.onAnimateStart?.invoke(state)
        animateStartListener?.invoke(state)
    }

    fun dispatchAnimateEnd(state: MarkerState) {
        state.onAnimateEnd?.invoke(state)
        animateEndListener?.invoke(state)
    }

    override suspend fun add(data: List<MarkerState>) {
        semaphore.withPermit {
            val modifiedEntities = mutableListOf<MarkerEntityInterface<ActualMarker>>()
            val previous = markerManager.allEntities().map { it.state.id }.toMutableSet()
            val added = mutableListOf<MarkerOverlayRendererInterface.AddParamsInterface>()
            val updated = mutableListOf<MarkerOverlayRendererInterface.ChangeParamsInterface<ActualMarker>>()
            val removed = mutableListOf<MarkerEntityInterface<ActualMarker>>()

            data.forEach { state ->

                if (previous.contains(state.id)) {
                    val prevEntity = markerManager.getEntity(state.id)!!
                    previous.remove(state.id)

                    // 描画結果が変わらないマーカーは renderer を往復させない。
                    // 同じ一覧が再送されただけ（無関係な再コンポーズ等）でも、以前は全件を
                    // onChange に積んでいたため、数千件のマップで毎回フルの往復が走っていた。
                    // react-sdk の MarkerIngestionEngine が持つ同名の最適化の移植。
                    // fingerPrint は animation を含むので、アニメーション要求は素通りしない。
                    if (prevEntity.fingerPrint == state.fingerPrint()) {
                        return@forEach
                    }

                    val markerIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon

                    updated.add(
                        object : MarkerOverlayRendererInterface.ChangeParamsInterface<ActualMarker> {
                            override val current: MarkerEntityInterface<ActualMarker> =
                                MarkerEntity(
                                    state = state,
                                    marker = prevEntity.marker,
                                    isRendered = true,
                                )
                            override val bitmapIcon: BitmapIcon = markerIcon
                            override val prev: MarkerEntityInterface<ActualMarker> = prevEntity
                        },
                    )
                } else {
                    added.add(
                        object : MarkerOverlayRendererInterface.AddParamsInterface {
                            override val state: MarkerState = state
                            override val bitmapIcon: BitmapIcon =
                                state.icon?.toBitmapIcon() ?: defaultMarkerIcon
                        },
                    )
                    previous.remove(state.id)
                }
            }

            // Do NOT hold a ReentrantReadWriteLock write lock across suspend calls
            // (onRemove/onAdd/onChange use withContext and may resume on a different thread,
            // which doesn't own the lock — causing an infinite block in registerEntity's
            // inner write lock). Fine-grained locks inside registerEntity/updateEntity are enough.
            previous.forEach { remainId ->
                markerManager.removeEntity(remainId)?.let { removedEntity ->
                    removed.add(removedEntity)
                }
            }

            // Remove markers
            if (removed.isNotEmpty()) {
                renderer.onRemove(removed)
                // Give the UI thread a chance to breathe when removing many markers.
                if (removed.size >= MARKER_RENDER_BATCH_SIZE) {
                    yield()
                }
            }

            // Add new markers
            if (added.isNotEmpty()) {
                added.chunked(MARKER_RENDER_BATCH_SIZE).forEach { batch ->
                    val actualMarkers: List<ActualMarker?> = renderer.onAdd(batch)
                    actualMarkers.forEachIndexed { index, actualMarker ->
                        actualMarker?.let {
                            val entity =
                                MarkerEntity<ActualMarker>(
                                    marker = actualMarker,
                                    state = batch[index].state,
                                    isRendered = true,
                                )
                            markerManager.registerEntity(entity)
                            modifiedEntities.add(entity)
                        }
                    }
                    yield()
                }
            }

            // Update changed markers
            if (updated.isNotEmpty()) {
                updated.chunked(MARKER_RENDER_BATCH_SIZE).forEach { batch ->
                    val actualMarkers: List<ActualMarker?> = renderer.onChange(batch)
                    actualMarkers.forEachIndexed { index, actualMarker ->
                        actualMarker?.let {
                            val params = batch[index]
                            val entity =
                                MarkerEntity<ActualMarker>(
                                    state = params.current.state,
                                    marker = actualMarker,
                                    isRendered = true,
                                )
                            markerManager.registerEntity(entity)
                        }
                    }
                    yield()
                }
            }

            modifiedEntities.forEach { entity ->
                entity.state.getAnimation()?.let {
                    renderer.onAnimate(entity)
                }
            }
            renderer.onPostProcess()
        }
    }

    override suspend fun update(state: MarkerState) {
        // Fast path: Check entity existence without semaphore to avoid blocking during initial marker addition
        if (!markerManager.hasEntity(state.id)) return

        // Always update the entity in the manager
        val prevEntity = markerManager.getEntity(state.id) ?: return
        val currentFinger = state.fingerPrint()
        val prevFinger = prevEntity.fingerPrint
        if (currentFinger == prevFinger) {
            return
        }

        // Update the entity in manager
        val entity =
            MarkerEntity(
                marker = prevEntity.marker,
                state = state,
                isRendered = prevEntity.isRendered,
            )

        markerManager.updateEntity(entity)

        // Simple fallback: update marker immediately if it's already rendered
        semaphore.withPermit {
            val marker = prevEntity.marker
            val bitmapIcon = state.icon?.toBitmapIcon() ?: defaultMarkerIcon

            val renderEntity =
                MarkerEntity(
                    marker = marker,
                    state = state,
                    isRendered = true,
                )
            val markerParams =
                object : MarkerOverlayRendererInterface.ChangeParamsInterface<ActualMarker> {
                    override val current: MarkerEntityInterface<ActualMarker> = renderEntity
                    override val bitmapIcon: BitmapIcon = bitmapIcon
                    override val prev: MarkerEntityInterface<ActualMarker> = prevEntity
                }
            val markers = renderer.onChange(listOf(markerParams))

            if (markers.size == 1) {
                markers[0]?.let {
                    val finalEntity =
                        MarkerEntity<ActualMarker>(
                            marker = it,
                            state = state,
                            isRendered = true,
                        )
                    markerManager.updateEntity(finalEntity)

                    // Execute the animation property
                    if (prevFinger.animation != currentFinger.animation) {
                        state.getAnimation()?.let {
                            renderer.onAnimate(finalEntity)
                        }
                    }
                }
            }
            renderer.onPostProcess()
        }
    }

    override suspend fun clear() {
        semaphore.withPermit {
            val entities: List<MarkerEntityInterface<ActualMarker>> = markerManager.allEntities()
            renderer.onRemove(entities)
            markerManager.clear()
            draggingMarkerIds.clear()
        }
    }

    /**
     * Properly cleanup native resources when disposing of the controller
     * IMPORTANT: Call this when switching map providers or disposing the map
     */
    override fun destroy() {
        draggingMarkerIds.clear()
        markerManager.destroy()
    }

    override val kind: OverlayKind = OverlayKind.Marker

    /**
     * マーカーはこのカスケードでは解決しない。
     *
     * 判定にアイコン矩形（＝画面投影）が要り、プロバイダごとにスレッドの制約が違うので
     * [com.mapconductor.core.controller.BaseMapViewController.dispatchMarkerTap] を通る。
     */
    override fun resolveTap(position: GeoPointInterface): OverlayHit? = null

    override fun has(id: String): Boolean = markerManager.hasEntity(id)
}

private const val MARKER_RENDER_BATCH_SIZE = 500
