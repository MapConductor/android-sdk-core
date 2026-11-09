package com.mapconductor.core.controller

import kotlin.math.abs

/**
 * オーバーレイの重なり順（すべてのプロバイダ・3 プラットフォーム共通の決まり）。
 *
 * 下から順に:
 * 1. ラスターレイヤー（RasterLayerState、アプリが足すタイル）
 * 2. GroundImage
 * 3. ポリゴン（ネイティブのポリゴンと、SDK のポリゴンのタイルレイヤーが同じ帯）
 * 4. サークル
 * 5. ポリライン
 * 6. マーカー（ネイティブのマーカーと、SDK のマーカーのタイルレイヤーが同じ帯）
 *
 * 帯の中は zIndex の小さい順、同じ zIndex なら後から渡したものが上。
 * 追加した順・送り直した順（clearOverlays の後など）・デザイン変更の後に左右されない。
 *
 * ネイティブ SDK の重なりの持ち方は 2 通りあり、それぞれに 1 つずつ道具を用意する:
 * - 種類をまたいで比べられる 1 本の数値（Google Maps の zIndex など）→ [flatZIndex]
 * - スタイルの層の並び（MapLibre / Mapbox などの GL 系）→ [insertBefore]
 */
object OverlayStacking {
    /** 下から上への帯の並び。 */
    val BOTTOM_TO_TOP: List<OverlayKind> =
        listOf(
            OverlayKind.RasterLayer,
            OverlayKind.GroundImage,
            OverlayKind.Polygon,
            OverlayKind.Circle,
            OverlayKind.Polyline,
            OverlayKind.Marker,
        )

    /** 帯の番号（0 が一番下）。 */
    fun band(kind: OverlayKind): Int = BOTTOM_TO_TOP.indexOf(kind)

    /**
     * 帯の幅。[flatZIndex] は `band * BAND_WIDTH` を中心に、zIndex を
     * `±BAND_WIDTH / 2` 未満へ押し込める。
     */
    const val BAND_WIDTH: Float = 1000f

    // zIndex が小さいうちはほぼ 1 刻みで並び、大きくなっても帯の外へ出ない。
    private const val SQUASH_SCALE = 100_000.0

    /**
     * 種類をまたいで 1 本の数値で重なりを比べる SDK 向けの値。
     *
     * 帯ごとに [BAND_WIDTH] の区間を割り当て、zIndex（Int 全域）を単調に
     * その区間の内側へ押し込める。どんな zIndex を渡しても下の帯の上へは出ない。
     * float の精度上、zIndex の差が非常に大きい（数十万以上）ところでは
     * 隣り合う値が同じになることがある（その場合の順はネイティブ SDK 次第）。
     */
    fun flatZIndex(
        kind: OverlayKind,
        zIndex: Int,
    ): Float {
        val z = zIndex.toDouble()
        val squashed = z / (abs(z) + SQUASH_SCALE) * (BAND_WIDTH / 2.0) * 0.999
        return (band(kind) * BAND_WIDTH + squashed).toFloat()
    }

    /**
     * 重なりの並びを決める鍵。帯 → zIndex の順で比べる。
     * 同じ鍵どうしは、後から入れたものが上（[insertBefore] がそう選ぶ）。
     */
    data class Key(
        val kind: OverlayKind,
        val zIndex: Int = 0,
    ) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val b = band(kind).compareTo(band(other.kind))
            if (b != 0) return b
            return zIndex.compareTo(other.zIndex)
        }
    }

    /**
     * 層の並び（GL 系のスタイルなど）に新しい層を入れるとき、どの層の**下**に入れるか。
     *
     * [layersBottomToTop] は今の並び（下から上）。[keyOf] は SDK が足した層なら鍵を、
     * ベースマップなど SDK のものでない層なら null を返す。
     *
     * 鍵が [key] より大きい最初の（一番下の）SDK の層を返す。無ければ null
     * （呼び出し側は一番上へ足す）。同じ鍵の層の上に入るので、同じ zIndex なら
     * 後から渡したものが上になる。
     *
     * 並びを持たず毎回スタイルから読むので、スタイルの読み直しや送り直しの後でも
     * 結果が変わらない。
     */
    fun <L> insertBefore(
        layersBottomToTop: List<L>,
        key: Key,
        keyOf: (L) -> Key?,
    ): L? =
        layersBottomToTop.firstOrNull { layer ->
            val k = keyOf(layer) ?: return@firstOrNull false
            k > key
        }
}

/**
 * 層の id で重なりを持つ SDK（GL 系のスタイル）向けの台帳。
 *
 * SDK が足した層の id と [OverlayStacking.Key] の対応を持ち、新しい層を入れる位置
 * （どの層の下に入れるか）を [beforeId] で返す。台帳に無い id はベースマップの層として
 * 無視する。スタイルを読み直して層が消えても台帳は残るので、足し直すときも同じ位置になる。
 */
class OverlayLayerStack {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, OverlayStacking.Key>()

    fun put(
        layerId: String,
        key: OverlayStacking.Key,
    ) {
        keys[layerId] = key
    }

    fun remove(layerId: String) {
        keys.remove(layerId)
    }

    fun keyOf(layerId: String): OverlayStacking.Key? = keys[layerId]

    /**
     * [key] の層を、今の並び [layerIdsBottomToTop]（下から上）のどの層の下に入れるか。
     * null なら一番上へ足す。
     */
    fun beforeId(
        layerIdsBottomToTop: List<String>,
        key: OverlayStacking.Key,
    ): String? = OverlayStacking.insertBefore(layerIdsBottomToTop, key) { keys[it] }
}
