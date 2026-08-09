package com.mapconductor.core

/**
 * 地図SDKドライバー（`android-for-*`）と拡張モジュールのための API であり、
 * アプリ開発者向けの公開 API ではないことを示す。
 *
 * ## なぜ要るか
 *
 * MapConductor は「アプリ開発者向け API は凍結、ドライバー実装点は変えてよい」という
 * 方針で共通化を進めている。ところが Kotlin の `internal` は JVM 上では public に
 * なるため、モジュールをまたぐドライバーへ公開する API は `public` にせざるを得ず、
 * 両者が同じ「公開 API」に見えてしまう。
 *
 * この注釈を付けたものは、
 *  - アプリ側から使うとオプトインの警告が出る
 *  - 公開 API サーフェスのスナップショット（`gradle/api-surface.gradle.kts` の
 *    `apiDump` / `apiCheck`）から除外される
 *
 * ため、ドライバー実装点を触っても凍結ゲートが鳴らなくなる。
 *
 * ## 使いどころ
 *
 * ドライバーだけが実装・呼び出しする型やメンバーに付ける。例:
 * オーバーレイレンダラの契約、コントローラの基底が提供する配線用メンバー、
 * ネイティブイベントの橋渡し。
 *
 * アプリ開発者が触るもの（`MarkerState` などの状態、`MapViewState`、
 * `rememberXxxMapViewState`、各 `*MapView` composable）には**付けないこと**。
 *
 * ## 使い方
 *
 * ```kotlin
 * @InternalMapConductorApi
 * interface SomeDriverContract { … }
 * ```
 *
 * ドライバー側は module 単位でオプトインする（`build.gradle.kts`）:
 *
 * ```kotlin
 * kotlin {
 *     compilerOptions {
 *         optIn.add("com.mapconductor.core.InternalMapConductorApi")
 *     }
 * }
 * ```
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message =
        "これは地図SDKドライバー向けの API です。アプリからは使わないでください。" +
            "予告なく変更されることがあります。",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalMapConductorApi
