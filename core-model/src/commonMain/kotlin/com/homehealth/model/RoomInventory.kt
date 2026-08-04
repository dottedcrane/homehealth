package com.homehealth.model

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

// Room types whose real-world role is inherently narrow (ensuite bath, closet, laundry nook,
// pantry) — exempt from the general minimum footprint below so they can still be created at the
// 1-cell-wide size the default layout already uses for them.
//
// PANTRY belongs here for the same reason a closet does: it's a store room off the kitchen, not a
// room you furnish. At the generic 3-cell tier it was silently missing from the room-type picker
// for every small selection beside a kitchen — the only footprints a pantry is ever drawn at.
val RoomType.allowsNarrowFootprint: Boolean get() =
    this in setOf(RoomType.BATHROOM, RoomType.POWDER_ROOM, RoomType.STORAGE, RoomType.LAUNDRY,
                  RoomType.PANTRY)

/**
 * Minimum grid-cell area (colSpan * rowSpan) a room of this type may be created/kept at — blocks
 * slivers like a 1x1 or (for ordinary rooms) 1x2 footprint, while still allowing the 1-cell-wide
 * shapes bathrooms/closets/laundry traditionally use.
 *
 * FOYER goes as low as a single cell — it's already a wall-less, door-less landing space (see
 * FloorLayout's OPEN_FLOW/STAIR_LANDINGS/isOpenPair, "the stairwell opens straight into its
 * landing space... however small the foyer"), so nothing structural blocks it being tiny.
 * GARAGE dropped its old real-world 5.4m×5.4m ("two vehicles side by side") floor — it's now
 * governed by this same generic area rule as every other room, kept flexible/dynamic so a
 * TOWNHOUSE's interior garage can shrink along with the rest of the home instead of being
 * pinned to a fixed car-count.
 */
fun RoomType.minCellArea(): Int = when {
    this == RoomType.FOYER  -> 1
    allowsNarrowFootprint   -> 2
    else                    -> 3
}

/** Whether a room of this type can be created/kept at the given cell span. */
fun RoomType.fitsFootprint(colSpan: Int, rowSpan: Int): Boolean =
    colSpan * rowSpan >= minCellArea()

enum class RoomItem {
    // Living room
    SOFA, COFFEE_TABLE, TV_STAND,
    // Bedroom
    BED, DRESSER, NIGHTSTAND,
    // Kitchen
    COUNTER, REFRIGERATOR, STOVE, KITCHEN_SINK, DISHWASHER, MICROWAVE, OVEN, RANGE_HOOD,
    // Bathroom
    TOILET, BATHTUB, VANITY,
    // Dining — table and chairs are one placed item, a full "Dining Set" (see label/icon and
    // the merged geometry in HouseScene.kt); there is no separate chairs-only entry.
    DINING_TABLE,
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
        DINING_TABLE                                               -> RoomType.DINING_ROOM
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

    /**
     * Every room type whose drag tray OFFERS this item — a superset of [room], which stays the
     * single nominal room used for seeding ([FloorLayout.defaultFurnitureForZone]), icon tint,
     * and the Maintenance Hub's per-room sections. Relevance is many-to-many because real homes
     * put a fridge in the garage and a washer in the bathroom; [room] can only name one, which
     * is why the tray used to ignore it and show all 29 pieces in every room instead.
     * Invariant: [room] is a member of this set for every non-retired case.
     *
     * This governs the GARAGE ROOM TYPE — both a garage laid out as a room in the floor plan and
     * the standalone garage/driveway scene, whose zone is typed GARAGE too (see
     * CarLotGeometry.drivewayZone). That set is deliberately the SHORTEST of any room: vehicles,
     * a second fridge, the water heater, a keepsake. A garage could hold a washer, a counter, a
     * sink or the gym gear, and it used to offer all of them — thirteen icons, past which the car
     * and the boat the scene exists for sat tenth. Everything cut stays one tap away behind the
     * tray's "All items" expander (see [overflowTrayItems]), and anything a save already parks in
     * a garage keeps its own icon regardless (see RoomFurnitureTray).
     */
    val trayRooms: Set<RoomType> get() = when (this) {
        SOFA          -> setOf(RoomType.LIVING_ROOM, RoomType.HOME_THEATER, RoomType.OFFICE,
                               RoomType.BEDROOM)
        COFFEE_TABLE  -> setOf(RoomType.LIVING_ROOM, RoomType.HOME_THEATER, RoomType.BEDROOM,
                               RoomType.OFFICE, RoomType.FOYER, RoomType.HALLWAY)
        TV_STAND      -> setOf(RoomType.LIVING_ROOM, RoomType.HOME_THEATER, RoomType.BEDROOM,
                               RoomType.GYM, RoomType.OFFICE)
        BED           -> setOf(RoomType.BEDROOM, RoomType.OFFICE)
        DRESSER       -> setOf(RoomType.BEDROOM, RoomType.HALLWAY, RoomType.FOYER,
                               RoomType.DINING_ROOM, RoomType.LIVING_ROOM, RoomType.STORAGE)
        NIGHTSTAND    -> setOf(RoomType.BEDROOM)
        COUNTER       -> setOf(RoomType.KITCHEN, RoomType.PANTRY, RoomType.LAUNDRY,
                               RoomType.STORAGE)
        REFRIGERATOR  -> setOf(RoomType.KITCHEN, RoomType.PANTRY, RoomType.GARAGE)
        STOVE, DISHWASHER, OVEN, RANGE_HOOD
                      -> setOf(RoomType.KITCHEN)
        KITCHEN_SINK  -> setOf(RoomType.KITCHEN, RoomType.LAUNDRY, RoomType.PANTRY)
        MICROWAVE     -> setOf(RoomType.KITCHEN, RoomType.PANTRY, RoomType.OFFICE,
                               RoomType.LAUNDRY)
        TOILET, VANITY -> setOf(RoomType.BATHROOM, RoomType.POWDER_ROOM)
        BATHTUB       -> setOf(RoomType.BATHROOM)
        DINING_TABLE  -> setOf(RoomType.DINING_ROOM, RoomType.KITCHEN)
        DESK          -> setOf(RoomType.OFFICE, RoomType.BEDROOM, RoomType.LIVING_ROOM)
        OFFICE_CHAIR  -> setOf(RoomType.OFFICE, RoomType.BEDROOM, RoomType.DINING_ROOM,
                               RoomType.LIVING_ROOM, RoomType.HOME_THEATER)
        BOOKSHELF     -> setOf(RoomType.OFFICE, RoomType.LIVING_ROOM, RoomType.BEDROOM,
                               RoomType.DINING_ROOM, RoomType.HALLWAY, RoomType.FOYER,
                               RoomType.STORAGE, RoomType.HOME_THEATER)
        COMPUTER      -> setOf(RoomType.OFFICE)
        WASHER, DRYER -> setOf(RoomType.LAUNDRY, RoomType.BATHROOM, RoomType.STORAGE)
        WATER_HEATER  -> setOf(RoomType.LAUNDRY, RoomType.STORAGE, RoomType.GARAGE,
                               RoomType.BATHROOM)
        TREADMILL, EXERCISE_BIKE, WEIGHTS
                      -> setOf(RoomType.GYM, RoomType.STORAGE)
        // Never reaches a tray (not furniture/portable) — it stays a fixed default item.
        GARAGE_DOOR   -> setOf(RoomType.GARAGE)
        CAR, BOAT, MOTORCYCLE -> setOf(RoomType.GARAGE)
        // A keepsake belongs anywhere, so every room's tray offers one.
        TREASURE      -> RoomType.entries.toSet()
        // Retired (see defaultItems) — offered nowhere.
        UTILITY_SINK  -> emptySet()
    }

    /**
     * Floor area in m² one instance of this item claims — mirrors the primary body cube of the
     * matching branch in HouseScene's ItemNode (`Size.x * Size.z`), the same "keep it in step
     * with the 3D geometry" relationship [runWidth] already carries for the kitchen run. Feeds
     * [RoomZone.canFit], so a room's real capacity is what its floor can hold rather than one
     * hardcoded number shared by every room.
     *
     * Zero means the piece claims no floor of its own — it's mounted on, under, or above
     * another piece (microwave, oven, range hood, computer) or is structural (garage door) —
     * so only [maxPerRoom] governs it. Vehicles are zero too: they're never room-anchored,
     * and CarLotGeometry's slot logic decides how many fit.
     */
    val footprintM2: Float get() = when (this) {
        BED            -> 3.20f  // 1.60 × 2.00
        DINING_TABLE   -> 3.00f  // full set, table + four chairs
        SOFA           -> 1.26f  // 1.80 × 0.70
        BATHTUB        -> 1.05f  // 0.70 × 1.50
        TREADMILL      -> 1.02f  // 0.60 × 1.70
        DESK           -> 0.91f  // 1.40 × 0.65
        COUNTER        -> 0.60f  // 1.00 × 0.60
        WEIGHTS        -> 0.54f  // 1.20 × 0.45
        TV_STAND       -> 0.49f  // 1.40 × 0.35
        REFRIGERATOR   -> 0.46f  // 0.70 × 0.66
        VANITY         -> 0.46f  // 0.94 × 0.49
        KITCHEN_SINK   -> 0.42f  // 0.70 × 0.60
        UTILITY_SINK   -> 0.42f  // retired, matched to the kitchen sink
        DRESSER        -> 0.38f  // 0.85 × 0.45
        EXERCISE_BIKE  -> 0.37f  // 0.44 × 0.85
        STOVE, DISHWASHER, WASHER, DRYER
                       -> 0.36f  // 0.60 × 0.60
        COFFEE_TABLE   -> 0.32f  // 0.70 × 0.45
        WATER_HEATER   -> 0.30f  // 0.55 × 0.55
        TREASURE       -> 0.28f  // 0.64 × 0.44
        TOILET         -> 0.25f  // 0.42 × 0.60
        OFFICE_CHAIR   -> 0.25f  // 0.50 × 0.50
        NIGHTSTAND     -> 0.18f  // 0.45 × 0.40
        BOOKSHELF      -> 0.18f  // 0.70 × 0.25
        MICROWAVE, OVEN, RANGE_HOOD, COMPUTER, GARAGE_DOOR,
        CAR, BOAT, MOTORCYCLE
                       -> 0.00f
    }

    /**
     * Hard ceiling on how many of this item one room may hold, whatever its size — a backstop
     * so a ballroom can't take twenty toilets. For anything with a real [footprintM2] the
     * floor-area budget in [RoomZone.canFit] normally binds first; this only decides the case
     * of a very large room, and of the zero-footprint pieces the budget can't measure.
     */
    val maxPerRoom: Int get() = when (this) {
        // Keepsakes are user-defined and tiny; vehicles aren't room-anchored at all.
        TREASURE, CAR, BOAT, MOTORCYCLE -> Int.MAX_VALUE
        // Counter segments are half-cell pieces the user arranges freely into runs and
        // islands. Never drop this below 2 — kitchenRunSlots already seeds up to two.
        COUNTER -> 8
        BOOKSHELF, COFFEE_TABLE, OFFICE_CHAIR, SOFA, NIGHTSTAND, DRESSER,
        TV_STAND, COMPUTER, BED, WEIGHTS, DESK, DINING_TABLE -> 6
        VANITY, TREADMILL, EXERCISE_BIKE, OVEN, MICROWAVE -> 2
        // Plumbed, vented, or structural — one per room.
        REFRIGERATOR, STOVE, KITCHEN_SINK, DISHWASHER, RANGE_HOOD, TOILET,
        BATHTUB, WASHER, DRYER, WATER_HEATER, GARAGE_DOOR, UTILITY_SINK -> 1
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
        DINING_TABLE,
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
        DINING_TABLE,
        DESK, OFFICE_CHAIR, BOOKSHELF, COMPUTER,
        TREASURE                                -> true
        else                                    -> false
    }

    val label: String get() = when (this) {
        SOFA           -> "Sofa"
        COFFEE_TABLE   -> "Accent Table"
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
        DINING_TABLE   -> "Dining Set"
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
        DINING_TABLE   ->  0.08f
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
        DINING_TABLE   -> -0.28f
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

fun RoomType.items(): List<RoomItem>          = RoomItem.entries.filter { it.room == this }

/**
 * The items shown in a room by DEFAULT — with everything else now a drag/tear/rotate/flip
 * [com.homehealth.model.PlacedItem], this is just the structural garage door. The user
 * taps a default they don't own and marks it "not in my home" to hide it. (Powder rooms
 * get their toilet + vanity as seeded placed items — see bathroomFixtureSlots.)
 */
fun RoomType.defaultItems(): List<RoomItem> =
    // Vehicles are excluded like furniture: they exist only as dragged-in placed items,
    // parked at the exterior garage/driveway rather than inside a room. The utility sink
    // is retired outright — the portable sink covers laundry rooms; the enum entry stays
    // only so strings in old saves (record keys, hidden-item toggles) still parse.
    items().filterNot { it.isFurniture || it.isVehicle || it.isPortable || it == RoomItem.UTILITY_SINK }

/**
 * The drag-tray icons a room of this type offers up front, in enum order — everything whose
 * [RoomItem.trayRooms] names this type. A GARAGE room's tray includes vehicles, which drop via
 * dropRoomVehicleAt onto its roomGarageLot rather than dropFurnitureAt; folding them in here
 * (rather than at one pane's call site, as before) keeps the bottom and right panes identical.
 */
fun RoomType.trayItems(): List<RoomItem> =
    RoomItem.entries.filter {
        (it.isFurniture || it.isPortable || it.isVehicle) && this in it.trayRooms
    }

/**
 * [items] reordered so what this room is FOR leads: everything whose nominal [RoomItem.room] is
 * this type, then everything it merely borrows, each keeping enum order. A garage's tray opens
 * with the car, boat and motorcycle rather than burying them behind the fridge and the water
 * heater; a bedroom's opens with the bed rather than the sofa. Enum order alone can't do this —
 * it's one global sequence, and every room reads it from a different place.
 *
 * Ordering by [RoomItem.room] rather than by a hand-kept per-room list means a new item lands in
 * the right band for free. [List.partition] preserves order within each band, and the bands don't
 * depend on what the room currently holds — tray icons must not shuffle under a user's finger as
 * they place things.
 */
fun RoomType.inTrayOrder(items: List<RoomItem>): List<RoomItem> =
    items.partition { it.room == this }.let { (native, borrowed) -> native + borrowed }

/**
 * Everything else the tray can offer behind its "All items" expander — the unusual placement
 * (a bathtub in the living room) [trayItems] deliberately omits, kept reachable so filtering
 * the tray never removes a capability. Vehicles are excluded: dropRoomVehicleAt bails on any
 * zone that isn't a GARAGE, so offering them elsewhere would be a dead icon.
 */
fun RoomType.overflowTrayItems(): List<RoomItem> =
    RoomItem.entries.filter { (it.isFurniture || it.isPortable) && this !in it.trayRooms }

/**
 * Fraction of a room's floor that placed items may cover before it reads as crammed. Real
 * furnished rooms sit near 30-45%; 55% leaves generous headroom while still bounding a room.
 */
const val ROOM_FILL_LIMIT = 0.55f

/**
 * Whether this room can take one more [item] given what it already holds ([counts], from
 * [roomItemCounts]). Two gates: [RoomItem.maxPerRoom]'s per-type ceiling, then a floor-area
 * budget shared by everything in the room — so the real limit is what THIS room can physically
 * fit, not one number every room shares.
 *
 * The first of any item always fits: a room must be able to hold one of each thing its tray
 * offers however small it is (a 2-cell powder room and its toilet), so the budget only ever
 * limits duplicates. Existing saves can already sit over budget — nothing is ever pruned to
 * satisfy this, it gates additions only.
 */
fun RoomZone.canFit(item: RoomItem, counts: Map<RoomItem, Int>): Boolean {
    val n = counts[item] ?: 0
    if (n >= item.maxPerRoom) return false
    if (n == 0 || item.footprintM2 <= 0f) return true
    val used = counts.entries.fold(0f) { acc, (ri, c) -> acc + ri.footprintM2 * c }
    return used + item.footprintM2 <= w * d * ROOM_FILL_LIMIT
}

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
