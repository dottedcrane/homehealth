package com.homerenderer.model

data class Room(
    val id: String,
    val type: RoomType,
    val widthMeters: Float,
    val lengthMeters: Float,
    val heightMeters: Float = 2.7f,
    val floor: Int = 0
)
