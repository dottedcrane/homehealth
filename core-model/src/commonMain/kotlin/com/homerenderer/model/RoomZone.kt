package com.homerenderer.model

data class RoomZone(
    val type: RoomType,
    val xMin: Float, val xMax: Float,
    val zMin: Float, val zMax: Float,
) {
    val cx: Float get() = (xMin + xMax) / 2f
    val cz: Float get() = (zMin + zMax) / 2f
    val w:  Float get() = xMax - xMin
    val d:  Float get() = zMax - zMin
}
