package com.homehealth.model

import java.util.UUID

// A user-placed tree or gazebo — unlike deck/HVAC (wall-snapped) or garage (wall-snapped,
// single slot), these drop anywhere in the yard, like the pool but with no house-adjacency
// preference at all. [dx]/[dz] are offsets from world origin, clamped into
// HouseSceneGeometry.yardBounds and pushed clear of the house footprint via keepOutsideHouse
// at drop/drag time, same as the pool. Supports any number of instances of either kind.
data class PlacedYardDecor(
    val id: String = UUID.randomUUID().toString(),
    val kind: YardDecorKind,
    val dx: Float = 0f,
    val dz: Float = 0f,
)
