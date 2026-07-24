package com.homehealth.model

enum class WallMode { DOOR, SOLID, OPEN, WINDOW }

data class WallKey(
    val vertical: Boolean,  // true = column boundary, false = row boundary
    val boundary: Int,      // col (vertical) or row (horizontal) of the boundary line
    val index: Int,         // row (vertical) or col (horizontal) within the boundary
    val floor: Int,
)
