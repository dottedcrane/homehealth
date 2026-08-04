package com.homehealth.renderspec

import com.homehealth.model.RoomType

/** Engine-agnostic linear colour, 0..1 per channel — a portable stand-in for `colorOf`. */
data class Rgb(val r: Float, val g: Float, val b: Float)

/**
 * The one floor colour per room type, shared by every scene that draws a room so a kitchen is
 * the same green from above (floor plan), from inside (room scene), and in the backdrop drawn
 * around whichever room you're standing in. Previously spelled out separately in RoomScene and
 * FloorPlanScene, which meant a palette tweak had to be made twice to stay consistent.
 */
object RoomPalette {

    fun floor(type: RoomType): Rgb = when (type) {
        RoomType.LIVING_ROOM  -> Rgb(0.96f, 0.86f, 0.66f)
        RoomType.KITCHEN      -> Rgb(0.76f, 0.93f, 0.70f)
        RoomType.DINING_ROOM  -> Rgb(0.96f, 0.82f, 0.66f)
        RoomType.HALLWAY      -> Rgb(0.83f, 0.83f, 0.83f)
        RoomType.FOYER        -> Rgb(0.94f, 0.91f, 0.84f)
        RoomType.STAIRCASE    -> Rgb(0.70f, 0.70f, 0.72f)
        RoomType.BEDROOM      -> Rgb(0.82f, 0.72f, 0.96f)
        RoomType.BATHROOM     -> Rgb(0.68f, 0.85f, 0.96f)
        RoomType.LAUNDRY      -> Rgb(0.80f, 0.88f, 0.92f)
        RoomType.OFFICE       -> Rgb(0.72f, 0.82f, 0.88f)
        RoomType.PANTRY       -> Rgb(0.95f, 0.93f, 0.75f)
        RoomType.STORAGE      -> Rgb(0.75f, 0.75f, 0.73f)
        RoomType.GYM          -> Rgb(0.78f, 0.90f, 0.76f)
        RoomType.HOME_THEATER -> Rgb(0.25f, 0.25f, 0.28f)
        RoomType.POWDER_ROOM  -> Rgb(0.78f, 0.90f, 0.96f)
        RoomType.GARAGE       -> Rgb(0.62f, 0.60f, 0.56f)
    }

    /**
     * The same hue pulled [amount] of the way toward a neutral grey — how backdrop geometry
     * (the rest of the home seen from inside one room) is tinted. Each room still reads as
     * itself, but never competes with the full-colour room you're actually standing in.
     */
    fun muted(type: RoomType, amount: Float = 0.5f, grey: Float = 0.62f): Rgb {
        val c = floor(type)
        val t = amount.coerceIn(0f, 1f)
        fun mix(v: Float) = v + (grey - v) * t
        return Rgb(mix(c.r), mix(c.g), mix(c.b))
    }
}
