package com.mapconductor.core.map

/**
 * A map that can be told to change a loaded style in place.
 *
 * Separate from [VectorStyleSupport], which is about making a style *be* the
 * basemap. This one is about changing the one that is already up, without
 * the reload that would otherwise cost every tile on screen and every
 * overlay the app added. A provider declares it under
 * [VectorStyleMutationSupportKey].
 *
 * Not every provider can take every change: MapLibre on iOS exposes its
 * paint properties as typed `NSExpression`s rather than by name, so a raw
 * style-spec key it has no mapping for cannot be set. That is why [apply]
 * answers with what it could not do instead of returning nothing — a caller
 * that silently assumed success would show a map that is half adjusted and
 * say nothing about it.
 *
 * ios-sdk-core and js-sdk-core carry the same capability.
 */
interface VectorStyleMutationSupport {
    /**
     * Applies [mutations] to the live style, replacing any set applied
     * before: anything the previous set changed and this one does not is put
     * back the way it was.
     *
     * @return the mutations that could not be applied, empty when all were.
     */
    fun apply(mutations: List<StyleMutation>): List<StyleMutation>

    /** Puts back everything [apply] changed. */
    fun clear()
}

/**
 * [VectorStyleMutationSupport] の登録キー。
 *
 * 宣言しないプロバイダは「生きているスタイルは触れない」。スタイル調整は
 * その場合、文書を差し替えるか（地図が読み直す）、ラスター経路で描き直す。
 */
object VectorStyleMutationSupportKey : MapServiceKey<VectorStyleMutationSupport>

/**
 * The bookkeeping every provider's [VectorStyleMutationSupport] needs, so no
 * provider has to get it right twice.
 *
 * Two things are easy to miss and both show up as "the adjustment worked,
 * and then stopped":
 *
 * - **Replacing a set has to undo what it drops.** Moving a slider sends a
 *   whole new set each time. A property the new set no longer mentions must
 *   go back to the value the style gave it — and only the mutation knows
 *   that value, because by now the renderer's own idea of it is the one this
 *   put there.
 * - **A style load wipes everything.** The app switching basemap, the
 *   provider reloading, a design change: whatever the reason, the renderer
 *   comes back with the document's own values and nothing this applied. The
 *   provider calls [onStyleLoaded] and the whole set goes on again.
 *
 * @param applyNow puts mutations on the live style and returns the ones it
 *   could not. Called on whatever thread [apply] was called on; a provider
 *   whose renderer is main-thread-only hops there itself.
 */
class StyleMutationStore(
    private val applyNow: (List<StyleMutation>) -> List<StyleMutation>,
) : VectorStyleMutationSupport {
    private val lock = Any()
    private var current: List<StyleMutation> = emptyList()

    override fun apply(mutations: List<StyleMutation>): List<StyleMutation> {
        val (restores, next) =
            synchronized(lock) {
                val targets = mutations.map { it.target }.toHashSet()
                val restores = current.filter { it.target !in targets }.mapNotNull { it.reversed() }
                current = mutations
                restores to mutations
            }
        // Restores first: a property moving from one rule's value to no rule
        // at all must pass through the style's own value, not stay on the
        // old one because the new set happened to be applied first.
        //
        // Nothing is logged from here. What could not be applied is the
        // caller's to report -- it has the app's diagnostics callback, and
        // this class is plain logic that unit tests can run off a device.
        return applyNow(restores + next)
    }

    override fun clear() {
        val restores =
            synchronized(lock) {
                val restores = current.mapNotNull { it.reversed() }
                current = emptyList()
                restores
            }
        if (restores.isNotEmpty()) applyNow(restores)
    }

    /**
     * Re-applies everything after the map has loaded a style.
     *
     * The provider calls this from whatever it has that fires on a style
     * being ready. Without it an adjustment survives only until the next
     * design change, which is the kind of bug that is found weeks later by
     * someone switching basemap with a slider open.
     */
    fun onStyleLoaded() {
        val again = synchronized(lock) { current }
        if (again.isNotEmpty()) applyNow(again)
    }

    /** What is applied right now. For tests and diagnostics. */
    val applied: List<StyleMutation> get() = synchronized(lock) { current }
}
