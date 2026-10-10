package com.mapconductor.core.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body of every provider's mutation support, against a renderer that
 * only records.
 *
 * Kept in step with `applyStyleMutations.test.mjs` on the web and
 * `ApplyStyleMutationsTests` on iOS.
 */
class ApplyStyleMutationsTest {
    private val calls = mutableListOf<String>()
    private val layers = mutableSetOf("roads", "labels")
    private var refuse: String? = null
    private var ready = true

    private val target =
        object : StyleMutationTarget {
            override val isReady: Boolean get() = ready

            override fun hasLayer(layerId: String) = layerId in layers

            override fun setPaint(
                layerId: String,
                key: String,
                valueJson: String,
            ) {
                if (key == refuse) throw IllegalStateException("nope")
                calls += "paint $layerId $key=$valueJson"
            }

            override fun setLayout(
                layerId: String,
                key: String,
                valueJson: String,
            ) {
                calls += "layout $layerId $key=$valueJson"
            }

            override fun setFilter(
                layerId: String,
                filterJson: String,
            ) {
                calls += "filter $layerId=$filterJson"
            }

            override fun setZoomRange(
                layerId: String,
                minZoom: Float,
                maxZoom: Float,
            ) {
                calls += "zoom $layerId=$minZoom..$maxZoom"
            }

            override fun addLayer(
                layerId: String,
                layerJson: String,
                beforeId: String?,
            ) {
                calls += "add $layerId $layerJson before=$beforeId"
            }

            override fun removeLayer(layerId: String) {
                layers -= layerId
                calls += "remove $layerId"
            }
        }

    @Test
    fun setsPaintAndLayoutProperties() {
        val unapplied =
            applyStyleMutations(
                target,
                listOf(
                    StyleMutation.SetPaint("roads", "line-color", "\"#fff\"", "\"#888\""),
                    StyleMutation.SetLayout("labels", "visibility", "\"none\"", "null"),
                ),
            )

        assertTrue(unapplied.isEmpty())
        assertEquals(
            listOf("paint roads line-color=\"#fff\"", "layout labels visibility=\"none\""),
            calls,
        )
    }

    /**
     * Every renderer takes both ends of a zoom range at once, so a mutation
     * that changes one has to restate the other — and a layer that named
     * neither gets the spec's own defaults.
     */
    @Test
    fun aZoomRangeRestatesBothEnds() {
        applyStyleMutations(
            target,
            listOf(StyleMutation.SetZoomRange("roads", minZoom = 5.0, maxZoom = null, null, null)),
        )
        assertEquals(listOf("zoom roads=5.0..24.0"), calls)
    }

    /**
     * A layer the style does not have is not a crash and not a silent pass:
     * it comes back, and the caller says so.
     */
    @Test
    fun whatTheStyleHasNoLayerForComesBack() {
        val unapplied =
            applyStyleMutations(
                target,
                listOf(
                    StyleMutation.SetPaint("roads", "line-color", "\"#fff\"", "null"),
                    StyleMutation.SetPaint("nope", "line-color", "\"#fff\"", "null"),
                ),
            )
        assertEquals(1, unapplied.size)
        assertEquals("nope", unapplied.single().layerId)
        assertEquals("the good one still went through", 1, calls.size)
    }

    /** One property the renderer rejects must not cost the other three hundred. */
    @Test
    fun aThrowingPropertyIsReportedNotPropagated() {
        refuse = "line-gradient"
        val unapplied =
            applyStyleMutations(
                target,
                listOf(
                    StyleMutation.SetPaint("roads", "line-gradient", "[]", "null"),
                    StyleMutation.SetPaint("roads", "line-color", "\"#fff\"", "null"),
                ),
            )
        assertEquals(1, unapplied.size)
        assertEquals(listOf("paint roads line-color=\"#fff\""), calls)
    }

    @Test
    fun addsAndRemovesALayer() {
        applyStyleMutations(
            target,
            listOf(StyleMutation.AddLayer("bg", """{"id":"bg","type":"background"}""", "roads")),
        )
        assertEquals(listOf("""add bg {"id":"bg","type":"background"} before=roads"""), calls)

        calls.clear()
        applyStyleMutations(target, listOf(StyleMutation.RemoveLayer("roads")))
        assertEquals(listOf("remove roads"), calls)
    }

    /**
     * Adding and removing go through whatever the layer is, every time.
     *
     * The renderer decides what to do about a layer that is already there,
     * or already gone — only it can say, and on the MapTiler bridge it is
     * the only one that keeps a record at all. Putting the whole set back
     * after a style reload depends on this: a layer missing from the
     * reloaded style must still be added, and `hasLayer` cannot be trusted
     * to notice.
     */
    @Test
    fun addAndRemoveAreNotGuardedHere() {
        val unapplied =
            applyStyleMutations(
                target,
                listOf(
                    StyleMutation.AddLayer("roads", """{"id":"roads","type":"background"}""", null),
                    StyleMutation.RemoveLayer("gone"),
                ),
            )
        assertTrue(unapplied.isEmpty())
        assertEquals(
            listOf("""add roads {"id":"roads","type":"background"} before=null""", "remove gone"),
            calls,
        )
    }

    /**
     * スタイルがまだ無いときは「できなかった」ではなく「まだしていない」。
     *
     * これを混同していたのが、読み込み中の地図に対して
     * 「N adjustments were not applied: this map cannot set those properties」
     * と言っていた原因。しかも `UnsupportedPolicy.RELOAD` だと、そのまま
     * 調整済みドキュメントを渡し直してしまう（＝避けたかった再読み込み）。
     * ロードが終われば store が一式を入れ直すので、失われるものは無い。
     */
    @Test
    fun `スタイルが無いあいだは何も報告しない`() {
        ready = false

        val unapplied =
            applyStyleMutations(
                target,
                listOf(
                    StyleMutation.SetPaint("roads", "line-color", "\"#fff\"", "null"),
                    StyleMutation.SetPaint("nope", "line-color", "\"#fff\"", "null"),
                ),
            )

        assertEquals(emptyList<StyleMutation>(), unapplied)
        assertEquals("触ってもいない", emptyList<String>(), calls)
    }

    /** 準備できたら、いつもどおり本当にできないものだけが返る。 */
    @Test
    fun `準備できたあとは判定が戻る`() {
        ready = false
        applyStyleMutations(target, listOf(StyleMutation.SetPaint("nope", "line-color", "\"#fff\"", "null")))
        ready = true

        val unapplied =
            applyStyleMutations(target, listOf(StyleMutation.SetPaint("nope", "line-color", "\"#fff\"", "null")))

        assertEquals(1, unapplied.size)
    }

    /**
     * 既定は「常に準備できている」。外部のドライバが実装していなくても
     * 以前と同じ挙動になる（＝互換のための既定であって、推奨ではない）。
     */
    @Test
    fun `isReady の既定は true`() {
        val plain =
            object : StyleMutationTarget {
                override fun hasLayer(layerId: String) = false

                override fun setPaint(
                    layerId: String,
                    key: String,
                    valueJson: String,
                ) = Unit

                override fun setLayout(
                    layerId: String,
                    key: String,
                    valueJson: String,
                ) = Unit

                override fun setFilter(
                    layerId: String,
                    filterJson: String,
                ) = Unit

                override fun setZoomRange(
                    layerId: String,
                    minZoom: Float,
                    maxZoom: Float,
                ) = Unit

                override fun addLayer(
                    layerId: String,
                    layerJson: String,
                    beforeId: String?,
                ) = Unit

                override fun removeLayer(layerId: String) = Unit
            }

        assertTrue(plain.isReady)
        assertEquals(
            1,
            applyStyleMutations(
                plain,
                listOf(StyleMutation.SetPaint("roads", "line-color", "\"#fff\"", "null")),
            ).size,
        )
    }
}
