package com.homerenderer.model

import java.util.UUID

data class HomeModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val homeType: HomeType,
    val rooms: List<Room> = emptyList()
) {
    val totalFloorArea: Float
        get() = rooms.sumOf { (it.widthMeters * it.lengthMeters).toDouble() }.toFloat()
}
