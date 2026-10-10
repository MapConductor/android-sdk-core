package com.mapconductor.core.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * One change to one layer of a vector style the map has already loaded.
 *
 * This is what lets a style be adjusted without being reloaded. Handing a
 * map a new style document is the obvious way to change how it looks, and on
 * every provider it is the expensive way: the renderer drops its tiles,
 * re-reads the document, re-tessellates, and the overlays the app added have
 * to be put back. A paint property set in place costs none of that.
 *
 * Mutations are produced by the vector style compiler from the same
 * evaluation that produces the adjusted document, so the two can never
 * disagree about what the style should look like. A provider that can take
 * them declares [VectorStyleMutationSupport].
 *
 * ## Why the values are JSON text
 *
 * A style property's value is anything the style spec allows: a string, a
 * number, or an expression tree. Each provider needs it in its own form —
 * MapLibre on Android wants a Gson tree, MapLibre on iOS an `NSExpression`,
 * MapLibre in a browser the plain parsed value — so there is no shared typed
 * representation that saves anyone a conversion. Text is the one form all
 * three can start from, and it is what crossed the FFI boundary anyway.
 *
 * `"null"` means the property had no value, which is also how a renderer is
 * told to go back to the spec default.
 */
sealed class StyleMutation {
    /** The layer this acts on. Every mutation names one. */
    abstract val layerId: String

    /**
     * What this mutation occupies, for working out which of two sets of
     * mutations replace each other. Two mutations with the same target are
     * the same change with different values.
     */
    abstract val target: String

    /**
     * What this mutation changes, named the way the style spec names it.
     *
     * For telling an app *what* a renderer would not do. The layer is the
     * wrong thing to name on its own: when a backend has no property for a
     * spec key, the same key fails on every layer that uses it, so a list
     * of layers is hundreds of names for one cause while the key is the
     * cause.
     */
    open val propertyName: String
        get() = "layer"

    /** The mutation that puts back what was there, or null when there is nothing to put back. */
    abstract fun reversed(): StyleMutation?

    data class SetPaint(
        override val layerId: String,
        val key: String,
        /** JSON text. */
        val value: String,
        /** JSON text of what was there before. */
        val previous: String,
    ) : StyleMutation() {
        override val target get() = "paint|$layerId|$key"

        override val propertyName: String get() = key

        override fun reversed() = SetPaint(layerId, key, previous, value)
    }

    data class SetLayout(
        override val layerId: String,
        val key: String,
        val value: String,
        val previous: String,
    ) : StyleMutation() {
        override val target get() = "layout|$layerId|$key"

        override val propertyName: String get() = key

        override fun reversed() = SetLayout(layerId, key, previous, value)
    }

    data class SetFilter(
        override val layerId: String,
        val value: String,
        val previous: String,
    ) : StyleMutation() {
        override val target get() = "filter|$layerId"

        override val propertyName: String get() = "filter"

        override fun reversed() = SetFilter(layerId, previous, value)
    }

    data class SetZoomRange(
        override val layerId: String,
        val minZoom: Double?,
        val maxZoom: Double?,
        val previousMinZoom: Double?,
        val previousMaxZoom: Double?,
    ) : StyleMutation() {
        override val target get() = "zoom|$layerId"

        override val propertyName: String get() = "zoom range"

        override fun reversed() = SetZoomRange(layerId, previousMinZoom, previousMaxZoom, minZoom, maxZoom)
    }

    /**
     * Adds a layer the style did not have.
     *
     * Used for one thing: a background under a style that has none, so that
     * "everything else muted" looks the same whether the style is drawn by
     * the map or rasterised into tiles over another basemap. Without it the
     * raster path leaves the gaps transparent and the two disagree.
     */
    data class AddLayer(
        /**
         * The new layer's id.
         *
         * Carried rather than read back out of [layer]: the compiler knew
         * it, every reader needs it, and three JSON parses of the same
         * object to recover it are three chances to get it wrong.
         */
        override val layerId: String,
        /** JSON text of the whole layer object. */
        val layer: String,
        /** Insert below this layer; null appends. */
        val beforeId: String?,
    ) : StyleMutation() {
        override val target get() = "add|$layerId"

        override fun reversed() = RemoveLayer(layerId)
    }

    data class RemoveLayer(
        override val layerId: String,
    ) : StyleMutation() {
        override val target get() = "add|$layerId"

        override fun reversed(): StyleMutation? = null
    }

    companion object {
        private const val TAG = "StyleMutation"

        /**
         * Reads the compiler's `mutations` array.
         *
         * An entry this version does not know is skipped rather than fatal:
         * the array is produced by the native library this host shipped with,
         * so an unknown op means the two went out of step, and dropping one
         * change is better than losing every change.
         */
        @JvmStatic
        fun parseList(json: String): List<StyleMutation> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parse)
            }
        }

        private fun parse(obj: JSONObject): StyleMutation? {
            val layerId = obj.optString("layerId").takeIf { it.isNotEmpty() }
            return when (obj.optString("op")) {
                "setPaint" ->
                    layerId?.let {
                        SetPaint(it, obj.optString("key"), jsonTextOf(obj.opt("value")), jsonTextOf(obj.opt("previous")))
                    }
                "setLayout" ->
                    layerId?.let {
                        SetLayout(it, obj.optString("key"), jsonTextOf(obj.opt("value")), jsonTextOf(obj.opt("previous")))
                    }
                "setFilter" ->
                    layerId?.let {
                        SetFilter(it, jsonTextOf(obj.opt("value")), jsonTextOf(obj.opt("previous")))
                    }
                "setZoomRange" ->
                    layerId?.let {
                        SetZoomRange(
                            it,
                            obj.optDoubleOrNull("minZoom"),
                            obj.optDoubleOrNull("maxZoom"),
                            obj.optDoubleOrNull("previousMinZoom"),
                            obj.optDoubleOrNull("previousMaxZoom"),
                        )
                    }
                "addLayer" ->
                    obj.optJSONObject("layer")?.let { layer ->
                        layerId?.let {
                            AddLayer(it, layer.toString(), obj.optString("beforeId").takeIf { id -> id.isNotEmpty() })
                        }
                    }
                "removeLayer" -> layerId?.let(::RemoveLayer)
                else -> null
            }
        }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

        /**
         * A JSON node as the text that would produce it.
         *
         * `org.json` has no "write this value" call -- `toString()` on a
         * String gives the bare characters, which would turn `"none"` into
         * something no parser can read back.
         */
        @JvmStatic
        fun jsonTextOf(value: Any?): String =
            when (value) {
                null, JSONObject.NULL -> "null"
                is String -> JSONObject.quote(value)
                is JSONObject, is JSONArray -> value.toString()
                is Boolean, is Number -> value.toString()
                else -> JSONObject.quote(value.toString())
            }
    }
}
