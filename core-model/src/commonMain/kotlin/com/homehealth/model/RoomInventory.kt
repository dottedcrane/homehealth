package com.homerenderer.model

enum class RoomType {
    // Structural / circulation — typically singular
    LIVING_ROOM, KITCHEN, DINING_ROOM, HALLWAY, FOYER, STAIRCASE,
    // Repeatable
    BEDROOM, BATHROOM, LAUNDRY, OFFICE,
    // Optional / specialty
    PANTRY, STORAGE, GYM, HOME_THEATER, POWDER_ROOM, GARAGE;

    val label: String get() = when (this) {
        LIVING_ROOM  -> "Living"
        KITCHEN      -> "Kitchen"
        DINING_ROOM  -> "Dining"
        HALLWAY      -> "Hallway"
        FOYER        -> "Foyer"
        STAIRCASE    -> "Stairs"
        BEDROOM      -> "Bedroom"
        BATHROOM     -> "Bath"
        LAUNDRY      -> "Laundry"
        OFFICE       -> "Office"
        PANTRY       -> "Pantry"
        STORAGE      -> "Storage"
        GYM          -> "Gym"
        HOME_THEATER -> "Theater"
        POWDER_ROOM  -> "Powder"
        GARAGE       -> "Garage"
    }
}

// Room types whose real-world role is inherently narrow (ensuite bath, closet, laundry nook) —
// exempt from the general minimum footprint below so they can still be created at the 1-cell-wide
// size the default layout already uses for them.
val RoomType.allowsNarrowFootprint: Boolean get() =
    this in setOf(RoomType.BATHROOM, RoomType.POWDER_ROOM, RoomType.STORAGE, RoomType.LAUNDRY)

/**
 * Minimum grid-cell area (colSpan * rowSpan) a room of this type may be created/kept at — blocks
 * slivers like a 1x1 or (for ordinary rooms) 1x2 footprint, while still allowing the 1-cell-wide
 * shapes bathrooms/closets/laundry traditionally use.
 */
fun RoomType.minCellArea(): Int = if (allowsNarrowFootprint) 2 else 3

// GARAGE is the one room type where area alone (minCellArea) isn't a sufficient gate — a 1-wide,
// 9-long sliver satisfies the area check but can't fit even one parked car's width. These are the
// real minimums (in meters) for two vehicles parked side by side with clearance, checked against
// the room's actual span (colSpan/rowSpan * the layout's cellW/cellD), not a fixed cell count,
// since cellW/cellD aren't always 2.0m.
fun RoomType.minWidthM(): Float? = if (this == RoomType.GARAGE) 5.4f else null
fun RoomType.minDepthM(): Float? = if (this == RoomType.GARAGE) 5.4f else null

/** Whether a room of this type can be created/kept at the given cell span on a [cellW]x[cellD] grid. */
fun RoomType.fitsFootprint(colSpan: Int, rowSpan: Int, cellW: Float, cellD: Float): Boolean {
    if (colSpan * rowSpan < minCellArea()) return false
    minWidthM()?.let { if (colSpan * cellW < it) return false }
    minDepthM()?.let { if (rowSpan * cellD < it) return false }
    return true
}

enum class RoomItem {
    // Living room
    SOFA, COFFEE_TABLE, TV_STAND,
    // Bedroom
    BED, DRESSER, NIGHTSTAND,
    // Kitchen
    COUNTER, REFRIGERATOR, STOVE, KITCHEN_SINK, DISHWASHER, MICROWAVE, OVEN, RANGE_HOOD,
    // Bathroom
    TOILET, BATHTUB, VANITY,
    // Dining
    DINING_TABLE, DINING_CHAIRS,
    // Office
    DESK, OFFICE_CHAIR, BOOKSHELF, COMPUTER,
    // Laundry
    WASHER, DRYER, UTILITY_SINK,
    // Gym
    TREADMILL, EXERCISE_BIKE, WEIGHTS,
    // Storage
    WATER_HEATER,
    // Garage
    GARAGE_DOOR,
    // Vehicles — never auto-populated; added by dragging their icon from the exterior
    // pane onto the lot (PlacedItem with CAR_PLACEMENT_ID), tracked per-vehicle in maintenance.
    CAR, BOAT, MOTORCYCLE,
    // Custom keepsake — a user-defined "anything" (heirloom, instrument, artwork…) dragged
    // into any room like furniture, so its lifespan can be tracked in the maintenance hub.
    // Never auto-populated; gets its own "Custom" section in the hub inventory.
    TREASURE;

    val room: RoomType get() = when (this) {
        SOFA, COFFEE_TABLE, TV_STAND                              -> RoomType.LIVING_ROOM
        BED, DRESSER, NIGHTSTAND                                  -> RoomType.BEDROOM
        COUNTER, REFRIGERATOR, STOVE, KITCHEN_SINK,
        DISHWASHER, MICROWAVE, OVEN, RANGE_HOOD                   -> RoomType.KITCHEN
        TOILET, BATHTUB, VANITY                                   -> RoomType.BATHROOM
        DINING_TABLE, DINING_CHAIRS                               -> RoomType.DINING_ROOM
        DESK, OFFICE_CHAIR, BOOKSHELF, COMPUTER                   -> RoomType.OFFICE
        // The water heater lives in the laundry room (homes without one get theirs by
        // dragging it in from the tray — it's portable like every appliance).
        WASHER, DRYER, UTILITY_SINK, WATER_HEATER                 -> RoomType.LAUNDRY
        TREADMILL, EXERCISE_BIKE, WEIGHTS                         -> RoomType.GYM
        TREASURE                                                  -> RoomType.STORAGE
        GARAGE_DOOR, CAR, BOAT, MOTORCYCLE                        -> RoomType.GARAGE
    }

    // Vehicles live on the exterior lot (garage / driveway), enter only by drag-and-drop
    // from the exterior pane's tray, and each instance gets its own maintenance card.
    val isVehicle: Boolean get() = when (this) {
        CAR, BOAT, MOTORCYCLE -> true
        else                  -> false
    }

    // Appliances (and counter segments) that live wherever the user puts them — a combined
    // laundry/storage room, a garage or gym fridge, an island counter. They join the
    // furniture tray so they can be dragged into ANY room, seed rooms as PLACED items
    // (rotatable/flippable, with the actions bar) rather than fixed defaults, and are
    // individually lifespan-tracked like any appliance. The whole kitchen is built this
    // way (counters are half-cell segments the user adds/removes/arranges freely), and
    // the bathroom fixtures too — so every piece in every room shares the same
    // drag/tear/rotate/flip actions; only the garage door stays a fixed default.
    val isPortable: Boolean get() = when (this) {
        REFRIGERATOR, WASHER, DRYER, WATER_HEATER,
        COUNTER, STOVE, KITCHEN_SINK, DISHWASHER,
        MICROWAVE, OVEN, RANGE_HOOD,
        TOILET, BATHTUB, VANITY,
        TREADMILL, EXERCISE_BIKE, WEIGHTS          -> true
        else                                      -> false
    }

    // Width along the wall (the z axis, once quarter-turned into the run) of each piece
    // in the seeded left-wall kitchen run — mirrors the ItemNode footprints in
    // HouseScene. Only floor-standing run pieces have one: the oven, hood, and
    // microwave share another piece's slot (under, above, and on top respectively).
    val runWidth: Float get() = when (this) {
        COUNTER      -> 1.00f
        REFRIGERATOR -> 0.70f
        STOVE        -> 0.60f
        KITCHEN_SINK -> 0.70f
        DISHWASHER   -> 0.60f
        else         -> 0.00f
    }

    // Base items appear automatically; non-base items are user-toggled.
    val isBase: Boolean get() = when (this) {
        SOFA, COFFEE_TABLE, TV_STAND,
        BED, DRESSER,
        COUNTER, STOVE, KITCHEN_SINK, REFRIGERATOR,
        TOILET, BATHTUB,
        DINING_TABLE, DINING_CHAIRS,
        DESK, OFFICE_CHAIR, BOOKSHELF,
        WASHER, DRYER,
        TREADMILL, EXERCISE_BIKE, WEIGHTS,
        WATER_HEATER, GARAGE_DOOR -> true
        else                      -> false
    }

    // Plain furniture — never auto-populated or lifespan-tracked. Furniture enters a room
    // only by drag-and-drop from the room pane's tray (as a PlacedItem), can be rotated and
    // flipped, and is removed by dragging it out through a wall.
    val isFurniture: Boolean get() = when (this) {
        SOFA, COFFEE_TABLE, TV_STAND,
        BED, DRESSER, NIGHTSTAND,
        DINING_TABLE, DINING_CHAIRS,
        DESK, OFFICE_CHAIR, BOOKSHELF, COMPUTER,
        TREASURE                                -> true
        else                                    -> false
    }

    val label: String get() = when (this) {
        SOFA           -> "Sofa"
        COFFEE_TABLE   -> "Table"
        TV_STAND       -> "TV"
        BED            -> "Bed"
        DRESSER        -> "Dresser"
        NIGHTSTAND     -> "Nightstand"
        COUNTER        -> "Counter"
        REFRIGERATOR   -> "Fridge"
        STOVE          -> "Stove"
        KITCHEN_SINK   -> "Sink"
        DISHWASHER     -> "Dishwasher"
        MICROWAVE      -> "Microwave"
        OVEN           -> "Oven"
        RANGE_HOOD     -> "Range Hood"
        TOILET         -> "Toilet"
        BATHTUB        -> "Bathtub"
        VANITY         -> "Vanity"
        DINING_TABLE   -> "Table"
        DINING_CHAIRS  -> "Chairs"
        DESK           -> "Desk"
        OFFICE_CHAIR   -> "Chair"
        BOOKSHELF      -> "Shelf"
        COMPUTER       -> "Computer"
        WASHER         -> "Washer"
        DRYER          -> "Dryer"
        UTILITY_SINK   -> "Sink"
        TREADMILL      -> "Treadmill"
        EXERCISE_BIKE  -> "Bike"
        WEIGHTS        -> "Weights"
        WATER_HEATER   -> "Water Heater"
        GARAGE_DOOR    -> "Garage Door"
        CAR            -> "Car"
        BOAT           -> "Boat"
        MOTORCYCLE     -> "Motorcycle"
        TREASURE       -> "Treasure"
    }

    val expectedLifespanYears: Int? get() = when (this) {
        REFRIGERATOR   -> 13
        STOVE          -> 15
        DISHWASHER     -> 10
        MICROWAVE      ->  9
        OVEN           -> 16
        RANGE_HOOD     -> 14
        WASHER         -> 11
        DRYER          -> 12
        KITCHEN_SINK   -> 20
        UTILITY_SINK   -> 20
        TOILET         -> 25
        BATHTUB        -> 25
        VANITY         -> 20
        SOFA           -> 10
        BED            -> 10
        DRESSER        -> 15
        NIGHTSTAND     -> 15
        COFFEE_TABLE   -> 15
        TV_STAND       ->  8
        COUNTER        -> 25
        DINING_TABLE   -> 20
        DINING_CHAIRS  -> 12
        DESK           -> 20
        OFFICE_CHAIR   ->  8
        BOOKSHELF      -> 25
        COMPUTER       ->  5
        TREADMILL      -> 10
        EXERCISE_BIKE  ->  7
        WATER_HEATER   -> 12
        GARAGE_DOOR    -> 20
        CAR            -> 12
        BOAT           -> 25
        MOTORCYCLE     -> 12
        TREASURE       -> 10
        WEIGHTS        -> null
    }

    // Fractional offsets from room centre — actual offset = frac * roomHalfDim * 0.38.
    // Kitchen items are seeded as one flush run along the kitchen's left (-X) wall by
    // kitchenRunSlots (FloorLayout.kt), which emits metre deltas FROM these bases — the
    // kitchen fracs below are frozen at their pre-run (v4 save) values so drag offsets
    // saved against them, and copies dragged into other rooms, keep their positions.
    val xFrac: Float get() = when (this) {
        SOFA           -> -0.45f
        COFFEE_TABLE   -> -0.20f
        TV_STAND       -> -0.20f
        BED            ->  0.00f
        DRESSER        ->  0.72f
        NIGHTSTAND     ->  0.80f
        COUNTER        ->  0.00f
        STOVE          -> -1.00f
        REFRIGERATOR   ->  1.00f
        KITCHEN_SINK   ->  0.20f
        DISHWASHER     ->  1.00f
        MICROWAVE      -> -0.45f
        OVEN           -> -1.00f
        RANGE_HOOD     -> -1.00f
        TOILET         -> -0.45f
        BATHTUB        ->  0.45f
        VANITY         -> -0.10f
        DINING_TABLE, DINING_CHAIRS ->  0.08f
        DESK           ->  0.00f
        OFFICE_CHAIR   ->  0.00f
        BOOKSHELF      ->  0.80f
        COMPUTER       ->  0.00f
        WASHER         -> -0.35f
        DRYER          -> -0.35f
        UTILITY_SINK   -> -0.35f
        TREADMILL      -> -0.65f
        EXERCISE_BIKE  ->  0.62f
        WEIGHTS        ->  0.62f
        WATER_HEATER   ->  0.72f
        GARAGE_DOOR    ->  0.00f
        CAR, BOAT, MOTORCYCLE -> 0.00f
        TREASURE       ->  0.00f
    }

    val zFrac: Float get() = when (this) {
        SOFA           -> -0.65f
        COFFEE_TABLE   -> -0.05f
        TV_STAND       ->  0.78f
        BED            -> -0.45f
        DRESSER        ->  0.72f
        NIGHTSTAND     -> -0.45f
        COUNTER        -> -1.00f
        STOVE          -> -1.00f
        REFRIGERATOR   -> -0.30f
        KITCHEN_SINK   -> -1.00f
        DISHWASHER     -> -1.00f
        MICROWAVE      -> -1.00f
        OVEN           -> -0.30f
        RANGE_HOOD     -> -1.00f
        TOILET         -> -0.55f
        BATHTUB        -> -0.55f
        VANITY         ->  0.68f
        DINING_TABLE, DINING_CHAIRS -> -0.28f
        DESK           -> -0.60f
        OFFICE_CHAIR   ->  0.20f
        BOOKSHELF      -> -0.30f
        COMPUTER       -> -0.60f
        WASHER         -> -0.45f
        DRYER          ->  0.10f
        UTILITY_SINK   ->  0.62f
        TREADMILL      ->  0.00f
        EXERCISE_BIKE  ->  0.55f
        WEIGHTS        -> -0.58f
        WATER_HEATER   -> -0.72f
        GARAGE_DOOR    ->  0.75f
        CAR, BOAT, MOTORCYCLE -> 0.00f
        TREASURE       ->  0.00f
    }
}

fun RoomType.applianceItems(): List<RoomItem> = RoomItem.entries.filter { it.room == this && !it.isBase }
fun RoomType.items(): List<RoomItem>          = RoomItem.entries.filter { it.room == this }

/**
 * The items shown in a room by DEFAULT — with everything else now a drag/tear/rotate/flip
 * [com.homerenderer.model.PlacedItem], this is just the structural garage door. The user
 * taps a default they don't own and marks it "not in my home" to hide it. (Powder rooms
 * get their toilet + vanity as seeded placed items — see bathroomFixtureSlots.)
 */
fun RoomType.defaultItems(): List<RoomItem> =
    // Vehicles are excluded like furniture: they exist only as dragged-in placed items,
    // parked at the exterior garage/driveway rather than inside a room. The utility sink
    // is retired outright — the portable sink covers laundry rooms; the enum entry stays
    // only so strings in old saves (record keys, hidden-item toggles) still parse.
    items().filterNot { it.isFurniture || it.isVehicle || it.isPortable || it == RoomItem.UTILITY_SINK }

// Body-color choices for parked vehicles (PlacedItem.colorIndex indexes into this list) — the
// single source of truth for both the 3D material (HouseScene's carBodyMats) and the cycle
// swatch in the vehicle actions bar, so the two can never drift out of sync.
val VEHICLE_BODY_COLORS: List<Triple<Float, Float, Float>> = listOf(
    Triple(0.92f, 0.92f, 0.90f), // white
    Triple(0.05f, 0.05f, 0.06f), // black
    Triple(0.80f, 0.80f, 0.82f), // silver
    Triple(0.45f, 0.45f, 0.47f), // gray
    Triple(0.72f, 0.11f, 0.11f), // red
    Triple(0.16f, 0.32f, 0.60f), // blue
    Triple(0.11f, 0.42f, 0.20f), // green
    Triple(0.85f, 0.68f, 0.10f), // yellow
)
