package com.mapconductor.core.map

interface MapDesignTypeInterface<T> {
    val id: T
    val attributionRules: List<AttributionRule>
        get() = emptyList()

    fun getValue(): T
}
