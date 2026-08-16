package com.mapconductor.core.map

import androidx.compose.runtime.compositionLocalOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Typed service key used to register and retrieve map-scoped services (plugins).
 *
 * Keys are typically defined as singleton `object`s.
 *
 * [capability] を指定すると、そのキーを [MutableMapServiceRegistry.put] した時点で
 * 対応状況が自動的に [MapCapabilityStatus.Supported] になる。プロバイダが
 * 「登録する」と「対応していると宣言する」を二重に書かなくて済むようにするため。
 */
interface MapServiceKey<T : Any> {
    val capability: MapCapability?
        get() = null
}

interface MapServiceRegistry {
    fun <T : Any> get(key: MapServiceKey<T>): T?

    /**
     * キーが登録済みか。[get] の null 判定と同じだが、「値が欲しい」のではなく
     * 「対応しているかを知りたい」という意図をコード上で表せる。
     */
    fun <T : Any> has(key: MapServiceKey<T>): Boolean = get(key) != null

    /**
     * [capability] への対応状況。宣言が無ければ [MapCapabilityStatus.Unknown]。
     *
     * **[MapCapabilityStatus.Unknown] を非対応と解釈しないこと。** 初期化途中の
     * マップも Unknown を返す。
     */
    fun capabilityStatus(capability: MapCapability): MapCapabilityStatus = MapCapabilityStatus.Unknown
}

/**
 * 登録の取り消し券。
 *
 * プロバイダも拡張モジュールも、自分が登録したものだけを [dispose] で外せる。
 * キー名をどこかにハードコードした撤収処理（ios-sdk の `removeProviderRegistrations()`
 * が固定 2 キーを列挙していた形）を不要にするためのもの。新しい capability を
 * 増やしても撤収コードを直す必要がない。
 */
fun interface MapServiceRegistration {
    fun dispose()
}

/** 複数の [MapServiceRegistration] をまとめて破棄するための入れ物。 */
class MapServiceRegistrations {
    private val registrations = CopyOnWriteArrayList<MapServiceRegistration>()

    fun add(registration: MapServiceRegistration): MapServiceRegistration {
        registrations.add(registration)
        return registration
    }

    operator fun plusAssign(registration: MapServiceRegistration) {
        add(registration)
    }

    /** 登録した順に関係なくすべて取り消す。二重呼び出しは安全。 */
    fun disposeAll() {
        val snapshot = registrations.toList()
        registrations.clear()
        snapshot.forEach { it.dispose() }
    }
}

class MutableMapServiceRegistry : MapServiceRegistry {
    private val services = ConcurrentHashMap<MapServiceKey<*>, Any>()
    private val capabilities = ConcurrentHashMap<MapCapability, MapCapabilityStatus>()

    fun clear() {
        services.clear()
        capabilities.clear()
    }

    /**
     * サービスを登録する。
     *
     * 取り消し券が欲しい場合は [register] を使う。こちらは戻り値を持たない従来の形で、
     * バイナリ互換のために残してある（戻り値の変更は JVM 上では別メソッドになるため、
     * 旧バージョンのコアに対してコンパイル済みのプロバイダが壊れる）。
     */
    fun <T : Any> put(
        key: MapServiceKey<T>,
        value: T,
    ) {
        register(key, value)
    }

    /**
     * サービスを登録し、取り消し券を返す。
     *
     * [key] が [MapServiceKey.capability] を持つ場合、その capability を
     * [MapCapabilityStatus.Supported] として宣言する。取り消すと宣言も戻る。
     */
    fun <T : Any> register(
        key: MapServiceKey<T>,
        value: T,
    ): MapServiceRegistration {
        services[key] = value
        val capability = key.capability
        val previousStatus = capability?.let { capabilities.put(it, MapCapabilityStatus.Supported) }
        return MapServiceRegistration {
            // 自分が入れた値がまだ残っているときだけ外す（後から別の実装で
            // 上書きされていた場合にそれを消してしまわないように）。
            services.remove(key, value)
            if (capability != null) {
                if (previousStatus == null) {
                    capabilities.remove(capability, MapCapabilityStatus.Supported)
                } else {
                    capabilities[capability] = previousStatus
                }
            }
        }
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
        key.capability?.let { capabilities.remove(it, MapCapabilityStatus.Supported) }
    }

    /**
     * 対応状況を明示的に宣言する。
     *
     * 「まだ登録されていない」と「この SDK では原理的にできない」を区別するために使う。
     * 例: android-for-longdo は WebView ブリッジに同期の座標変換が無いので
     * `declare(MapCapability.ScreenProjectionSync, Unsupported("Longdo JS bridge has no synchronous unproject"))`。
     */
    fun declare(
        capability: MapCapability,
        status: MapCapabilityStatus,
    ): MapServiceRegistration {
        val previous = capabilities.put(capability, status)
        return MapServiceRegistration {
            if (previous == null) {
                capabilities.remove(capability, status)
            } else {
                capabilities[capability] = previous
            }
        }
    }

    /** [declare] の短縮形。 */
    fun declareUnsupported(
        capability: MapCapability,
        reason: String,
    ): MapServiceRegistration = declare(capability, MapCapabilityStatus.Unsupported(reason))

    /** 宣言済みの capability を列挙する（診断・適合テスト用）。 */
    fun declaredCapabilities(): Map<MapCapability, MapCapabilityStatus> = capabilities.toMap()

    override fun capabilityStatus(capability: MapCapability): MapCapabilityStatus =
        capabilities[capability] ?: MapCapabilityStatus.Unknown

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
