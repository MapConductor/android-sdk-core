package com.mapconductor.core.map

interface MapPaddingsInterface {
    val top: Double
    val left: Double
    val bottom: Double
    val right: Double
}

/**
 * 地図の端から空ける幅（dp）。
 *
 * 4 辺はそれぞれ省略できる。省略した辺は、カメラを動かすときに
 * [MapViewState.moveCameraTo] が**今のカメラの同じ辺**から引き継ぐ
 * （[MapCameraPosition.resolvedOnto]）。`MapPaddings(top = 300.0)` なら上だけが
 * 300 になり、左・下・右は今のまま。明示された 0 はそのまま 0 として使う。
 *
 * 読むときは省略した辺も 0 を返す。省略したかどうかは [isTopSpecified] などで区別する。
 * `paddings` 自体を渡さなかったとき（[MapCameraPosition] の既定 [Zeros]）は従来どおり
 * 4 辺とも 0 で、余白を外す意味になる。
 */
open class MapPaddings
    @JvmOverloads
    constructor(
        top: Double? = null,
        left: Double? = null,
        bottom: Double? = null,
        right: Double? = null,
    ) : MapPaddingsInterface {
        /**
         * 4 辺すべてを指定するコンストラクタ。
         *
         * 以前の `MapPaddings(double, double, double, double)` と同じ JVM シグネチャを残して、
         * それを呼んでいた Java / コンパイル済みのコードを壊さないために持つ。
         */
        constructor(top: Double, left: Double, bottom: Double, right: Double) :
            this(top as Double?, left as Double?, bottom as Double?, right as Double?)

        // 指定された値。null は省略。
        private val givenTop: Double? = top
        private val givenLeft: Double? = left
        private val givenBottom: Double? = bottom
        private val givenRight: Double? = right

        /** 省略したときは 0。省略したかどうかは [isTopSpecified] で区別する。 */
        override val top: Double = top ?: 0.0

        /** 省略したときは 0。省略したかどうかは [isLeftSpecified] で区別する。 */
        override val left: Double = left ?: 0.0

        /** 省略したときは 0。省略したかどうかは [isBottomSpecified] で区別する。 */
        override val bottom: Double = bottom ?: 0.0

        /** 省略したときは 0。省略したかどうかは [isRightSpecified] で区別する。 */
        override val right: Double = right ?: 0.0

        val isTopSpecified: Boolean get() = givenTop != null
        val isLeftSpecified: Boolean get() = givenLeft != null
        val isBottomSpecified: Boolean get() = givenBottom != null
        val isRightSpecified: Boolean get() = givenRight != null

        /** 4 辺とも指定されているか。 */
        val isFullySpecified: Boolean
            get() = isTopSpecified && isLeftSpecified && isBottomSpecified && isRightSpecified

        /**
         * 省略した辺を [current] の同じ辺で埋めた余白を返す。[current] が null なら 0 で埋める。
         *
         * 4 辺とも指定されていれば自分自身を返す。
         */
        fun resolvedOnto(current: MapPaddingsInterface?): MapPaddings {
            if (isFullySpecified) return this
            return MapPaddings(
                top = givenTop ?: current?.top ?: 0.0,
                left = givenLeft ?: current?.left ?: 0.0,
                bottom = givenBottom ?: current?.bottom ?: 0.0,
                right = givenRight ?: current?.right ?: 0.0,
            )
        }

        companion object {
            val Zeros: MapPaddings = MapPaddings(0.0, 0.0, 0.0, 0.0)

            /** [MapPaddings] はそのまま返す（省略した辺は省略したまま）。それ以外は 4 辺とも指定扱い。 */
            fun from(paddings: MapPaddingsInterface) =
                when (paddings) {
                    is MapPaddings -> paddings
                    else ->
                        MapPaddings(
                            top = paddings.top,
                            left = paddings.left,
                            bottom = paddings.bottom,
                            right = paddings.right,
                        )
                }
        }
    }
