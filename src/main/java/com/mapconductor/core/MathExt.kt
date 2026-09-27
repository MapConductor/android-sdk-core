package com.mapconductor.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fixed-point text, with NaN and the infinities passed through as words.
 *
 * `BigDecimal` throws on them, and a number formatter is the wrong place to
 * end a process: a provider that cannot answer a projection yet produced a
 * NaN coordinate, and printing it took the app down rather than showing
 * something odd. The odd thing on screen is the better failure -- it says
 * which value is wrong.
 */
fun Double.toFixed(decimals: Int = 0): String =
    if (!isFinite()) {
        toString()
    } else {
        BigDecimal(this)
            .setScale(decimals, RoundingMode.DOWN)
            .toPlainString()
    }

fun Float.toFixed(decimals: Int = 0): String =
    if (!isFinite()) {
        toString()
    } else {
        BigDecimal(this.toDouble())
            .setScale(decimals, RoundingMode.DOWN)
            .toPlainString()
    }
