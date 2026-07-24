package com.homerenderer.ar

data class RoomGeometry(
    val floorY: Float,
    val corners: List<Pair<Float, Float>>,  // (X, Z) world-space wall centres, sorted by angle
    val estimatedWallHeight: Float = 2.7f
) {
    val isValid: Boolean get() = corners.size >= 2
}
