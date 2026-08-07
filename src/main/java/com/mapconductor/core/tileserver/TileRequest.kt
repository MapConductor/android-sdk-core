package com.mapconductor.core.tileserver

data class TileRequest(
    val x: Int,
    val y: Int,
    val z: Int,
    val pixelRatio: Int = 1,
)
