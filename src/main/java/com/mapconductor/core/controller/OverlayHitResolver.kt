package com.mapconductor.core.controller

import com.mapconductor.core.InternalMapConductorApi
import com.mapconductor.core.features.GeoPointInterface

/**
 * タップ座標から「どのオーバーレイに当たったか」を、正準順で 1 つだけ決める。
 *
 * 8 プロバイダが同じ 40〜50 行のカスケードを各自持っていたものの集約
 * （android-for-maplibre / mapbox / here / googlemaps / arcgis / arcgis2d /
 * tomtom / maptiler / longdo）。
 *
 * ## 決めているのは「順序」と「先勝ち」だけ
 *
 * 当たり判定そのものは各 Manager（[com.mapconductor.core.polygon.PolygonManager] 等）が
 * 既にコアで持っている。重複していたのは常に**それを呼ぶ配線**の側なので、ここで畳む。
 *
 * ## 探索は必ずこの順（[CANONICAL_ORDER]）
 *
 * `circle → groundImage → polyline → polygon`。面積の小さい・上に載るものから見る。
 * マーカーはこの順の**さらに手前**だが、ヒットテストに画面投影が要り SDK と
 * スレッドの制約を受けるため、[BaseMapViewController.dispatchMarkerTap] という
 * 別の口に分けてある。
 *
 * ## ポリラインだけ配送座標がタップ点ではない
 *
 * ポリラインは「線上の最近傍点」を [OverlayHit.clicked] にする。線の上をきっかり
 * タップすることはないので、タップ点をそのまま返すと線から外れた座標が
 * アプリへ渡る。3 プラットフォーム共通の既存契約。
 *
 * ## 当たらなかったコントローラには何もしない
 *
 * `find` は副作用を持たない。当たった 1 つだけを [OverlayHit] にして返し、配送は
 * 呼び出し側が [OverlayHit.dispatch] で行う。解決と配送を分けてあるのは、
 * 配送先のスレッドがプロバイダによって違うため（Google Maps は投影がメイン
 * スレッド専用なのでカスケード全体をメインで回す）。
 */
@InternalMapConductorApi
object OverlayHitResolver {
    /**
     * マーカーを除いた正準順。
     *
     * マーカーがここに無いのは「順序の外」という意味ではなく、
     * **判定手段が違う**（地理座標ではなく画面座標での矩形判定）ため。
     * 実際の全体順序は marker → circle → groundImage → polyline → polygon → map。
     */
    val CANONICAL_ORDER: List<OverlayKind> =
        listOf(
            OverlayKind.Circle,
            OverlayKind.GroundImage,
            OverlayKind.Polyline,
            OverlayKind.Polygon,
        )

    /**
     * [order] の種別順に登録済みコントローラを試し、最初に [probe] が非 null を返したものを返す。
     *
     * カスケードの**順序規則そのもの**。同じ種別に複数のコントローラが登録されている
     * ことがある（マーカークラスタリングは Marker 種別で追加登録する）ので、
     * 種別の中では登録順に見る。
     *
     * [SlottedOverlayController] でないコントローラ（カメラ購読のためだけに登録する
     * 拡張モジュール等）は種別を持たないので対象外。
     */
    fun <T : Any> firstHit(
        controllers: List<OverlayControllerInterface<*, *>>,
        order: List<OverlayKind> = CANONICAL_ORDER,
        probe: (SlottedOverlayController<*, *>) -> T?,
    ): T? {
        val slotted = controllers.filterIsInstance<SlottedOverlayController<*, *>>()
        order.forEach { kind ->
            slotted.forEach { controller ->
                if (controller.kind != kind) return@forEach
                probe(controller)?.let { return it }
            }
        }
        return null
    }

    /**
     * [position] のタップが当たったオーバーレイを 1 つ返す。当たらなければ null。
     *
     * 種別ごとの当たり判定と配送方法は各コントローラの
     * [SlottedOverlayController.resolveTap] が持つ。ここは順序だけを決める。
     *
     * @param order 探索順。既定は [CANONICAL_ORDER]。
     */
    fun resolve(
        controllers: List<OverlayControllerInterface<*, *>>,
        position: GeoPointInterface,
        order: List<OverlayKind> = CANONICAL_ORDER,
    ): OverlayHit? = firstHit(controllers, order) { it.resolveTap(position) }
}

/**
 * [OverlayHitResolver.resolve] が返す「当たり」。
 *
 * 配送は [dispatch] を呼ぶまで起きない。解決した時点では何の副作用も無い。
 */
@InternalMapConductorApi
class OverlayHit(
    /** 当たったオーバーレイの種別。 */
    val kind: OverlayKind,
    /**
     * アプリへ渡すクリック座標。
     *
     * ポリラインだけ「線上の最近傍点」で、他はタップ点そのもの。
     * `[-180,180]` への正規化は各イベント型（`PolygonEvent` 等）の生成時に行われる。
     */
    val clicked: GeoPointInterface,
    private val deliver: () -> Unit,
) {
    /** `state.onClick` と非推奨のコントローラリスナーへ配送する。 */
    fun dispatch() = deliver()
}
