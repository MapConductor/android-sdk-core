package com.mapconductor.core.map

import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed service key used to register and retrieve map-scoped services (plugins).
 *
 * Keys are typically defined as singleton `object`s.
 */
interface MapServiceKey<T : Any>

interface MapServiceRegistry {
    fun <T : Any> get(key: MapServiceKey<T>): T?
}

class MutableMapServiceRegistry : MapServiceRegistry {
    private val services = ConcurrentHashMap<MapServiceKey<*>, Any>()

    fun clear() {
        services.clear()
    }

    fun <T : Any> put(
        key: MapServiceKey<T>,
        value: T,
    ) {
        services[key] = value
    }

    /**
     * 登録済みのサービスを1件だけ取り消す。未登録のキーを渡しても何も起きない。
     *
     * [clear] がレジストリ全体を空にするのに対し、こちらは他の capability を残したまま
     * 1つだけ取り下げたいプラグイン向け。ios-sdk の
     * `MutableMapServiceRegistry.remove(_:)` と同じ意味論。
     */
    fun <T : Any> remove(key: MapServiceKey<T>) {
        services.remove(key)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(key: MapServiceKey<T>): T? = services[key] as? T
}

object EmptyMapServiceRegistry : MapServiceRegistry {
    override fun <T : Any> get(key: MapServiceKey<T>): T? = null
}

val LocalMapServiceRegistry =
    compositionLocalOf<MapServiceRegistry> {
        EmptyMapServiceRegistry
    }
