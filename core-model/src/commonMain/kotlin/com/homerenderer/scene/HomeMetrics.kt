package com.homerenderer.scene

import com.homerenderer.model.HomeSize

const val FLOOR_HEIGHT_M = 2.7f
const val WALL_T         = 0.2f

fun HomeSize.toFootprintMeters(): Pair<Float, Float> = when (this) {
    HomeSize.SMALL  ->  5f to  4f
    HomeSize.MEDIUM ->  7f to  5f
    HomeSize.LARGE  -> 10f to  7f
    HomeSize.XLARGE -> 13f to  9f
}

// Smallest room dimension at LARGE house (column width = w/3, row depth = d/2).
// Items sized for real-world scale; render at scale 1.0 in a LARGE house room.
val ITEM_SCALE_REFERENCE: Float = HomeSize.LARGE.toFootprintMeters().let { (w, d) ->
    minOf(w / 3f, d / 2f)   // = minOf(3.33, 3.5) = 3.33 m
}

fun HomeSize.windowWidth() = when (this) {
    HomeSize.SMALL  -> 0.80f
    HomeSize.MEDIUM -> 0.90f
    HomeSize.LARGE  -> 1.00f
    HomeSize.XLARGE -> 1.20f
}

fun HomeSize.windowHeight() = when (this) {
    HomeSize.SMALL  -> 1.00f
    HomeSize.MEDIUM -> 1.10f
    HomeSize.LARGE  -> 1.20f
    HomeSize.XLARGE -> 1.40f
}
