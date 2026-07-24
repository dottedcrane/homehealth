package com.homerenderer.model

import com.homerenderer.util.randomUUIDString

data class HomeModel(
    val id: String = randomUUIDString(),
    val name: String,
    val homeType: HomeType,
    val rooms: List<Room> = emptyList()
) {
    val totalFloorArea: Float
        get() = rooms.sumOf { (it.widthMeters * it.lengthMeters).toDouble() }.toFloat()
}
