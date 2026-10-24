package com.mapconductor.core.map

/**
 * The seven things a renderer has to be able to do for a style to be
 * adjusted in place.
 *
 * A provider implements these against its own SDK and gets
 * [applyStyleMutations] for everything above them — what each mutation
 * means, which ones can be skipped, and keeping one bad property from
 * costing the rest. MapLibre and Mapbox name their style APIs differently,
 * so the adapter has to be per-provider; the decisions do not.
 *
 * Values arrive as JSON text, which is what crossed the FFI boundary: the
 * provider turns it into whatever its SDK wants — a Gson tree for
 * MapLibre's `Expression.Converter`, a `Value` for Mapbox v11.
 *
 * js-sdk-core does the same work structurally, because MapLibre GL JS,
 * Mapbox GL JS and the MapTiler SDK happen to share their method names.
 */
interface StyleMutationTarget {
    /**
     * Whether the renderer has a style up to be changed.
     *
     * **False is not failure, and must not be reported as one.** A renderer
     * with no style loaded yet cannot be asked about layers, so without this
     * every mutation came back as one the map "cannot" make -- which is how
     * an app got told "N adjustments were not applied" about a map that was
     * merely still loading, and, under [VectorStyleMutationSupport]'s
     * reloading policy, had the adjusted document handed to it for no
     * reason. Nothing is lost by waiting: the store puts the whole set back
     * when the style loads.
     *
     * Override it. The default is for a target that is always ready; a
     * provider whose style arrives asynchronously -- which is all three of
     * them -- answers whether it has one.
     */
    val isReady: Boolean
        get() = true

    /**
     * False for a layer the loaded style does not have.
     *
     * Asked only when [isReady], so it is about the style's contents and
     * never about whether there is a style: answering false for "not loaded
     * yet" is what [isReady] exists to stop. A bridge that cannot be asked
     * about a document's layers -- MapTiler's can only be asked about the
     * ones it was told to add -- answers true and lets the attempt decide.
     *
     * Only the four property changes ask. [addLayer] and [removeLayer] guard
     * themselves, because "is it already there" is a question only the
     * renderer can answer about a layer it owns.
     */
    fun hasLayer(layerId: String): Boolean

    fun setPaint(
        layerId: String,
        key: String,
        valueJson: String,
    )

    fun setLayout(
        layerId: String,
        key: String,
        valueJson: String,
    )

    /** `"null"` removes the filter. */
    fun setFilter(
        layerId: String,
        filterJson: String,
    )

    fun setZoomRange(
        layerId: String,
        minZoom: Float,
        maxZoom: Float,
    )

    /**
     * Adds a layer, or does nothing if the renderer already has one with
     * this id -- which is the usual case, because a style reload puts the
     * whole set back.
     */
    fun addLayer(
        layerId: String,
        layerJson: String,
        beforeId: String?,
    )

    /** A layer the renderer does not have is not an error. */
    fun removeLayer(layerId: String)
}

/**
 * Applies style mutations to a live renderer through [target].
 *
 * The body of a provider's [VectorStyleMutationSupport]: a provider wires
 * this into a [StyleMutationStore] and registers that, and every provider
 * then behaves identically by construction.
 *
 * @return the mutations it could not apply. A layer the loaded style does
 *   not have, or a property the renderer rejected: the caller is told
 *   rather than left to assume the map looks the way the rules asked.
 *   Nothing is thrown — one bad property must not cost the other three
 *   hundred. Empty while the renderer has no style up, because then there
 *   is nothing it could not do — only something it has not done yet.
 */
fun applyStyleMutations(
    target: StyleMutationTarget,
    mutations: List<StyleMutation>,
): List<StyleMutation> {
    // Not ready is not a refusal. See [StyleMutationTarget.isReady].
    if (!target.isReady) return emptyList()
    return mutations.filter { mutation ->
        runCatching { !applyOne(target, mutation) }.getOrDefault(true)
    }
}

private fun applyOne(
    target: StyleMutationTarget,
    mutation: StyleMutation,
): Boolean =
    when (mutation) {
        is StyleMutation.SetPaint -> {
            if (!target.hasLayer(mutation.layerId)) {
                false
            } else {
                target.setPaint(mutation.layerId, mutation.key, mutation.value)
                true
            }
        }
        is StyleMutation.SetLayout -> {
            if (!target.hasLayer(mutation.layerId)) {
                false
            } else {
                target.setLayout(mutation.layerId, mutation.key, mutation.value)
                true
            }
        }
        is StyleMutation.SetFilter -> {
            if (!target.hasLayer(mutation.layerId)) {
                false
            } else {
                target.setFilter(mutation.layerId, mutation.value)
                true
            }
        }
        is StyleMutation.SetZoomRange -> {
            if (!target.hasLayer(mutation.layerId)) {
                false
            } else {
                // The spec's own defaults, for a layer that named neither.
                // Every renderer takes both ends at once, so a mutation that
                // changes one has to restate the other.
                target.setZoomRange(
                    mutation.layerId,
                    mutation.minZoom?.toFloat() ?: 0f,
                    mutation.maxZoom?.toFloat() ?: 24f,
                )
                true
            }
        }
        is StyleMutation.AddLayer -> {
            target.addLayer(mutation.layerId, mutation.layer, mutation.beforeId)
            true
        }
        is StyleMutation.RemoveLayer -> {
            target.removeLayer(mutation.layerId)
            true
        }
    }
