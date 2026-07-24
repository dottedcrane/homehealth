package com.homerenderer.model

data class NeighborHome(
    val label: String,
    val homeType: HomeType,
    val floors: Int,
    val featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    // Decks support multiple placed instances (their own list), not a featurePlacements slot —
    // see PlacedDeck.
    val placedDecks: List<PlacedDeck> = emptyList(),
    val homeSystems: Set<HomeSystem> = emptySet(),
    val totalW: Float = 10.5f,
    val totalD: Float = 9.0f,
    val customLayout: FloorLayout? = null,
)

// ── Model-home floor plans ────────────────────────────────────────────────────
// 2 m per cell. Standard room = colSpan=2,rowSpan=2 (4m×4m). Half room = colSpan=1.
// Circulation is real: the front door opens into a FOYER, which connects to a
// HALLWAY corridor (1 m wide — collapses automatically); every room reaches the
// corridor via its own door. Staircases occupy the SAME col/row on every floor so
// the stack lines up, and each upper floor's corridor is the stair landing.

private fun p(type: RoomType, col: Int, row: Int,
              cs: Int = 2, rs: Int = 2, floor: Int = 0) =
    RoomPlacement(type = type, col = col, row = row,
                  colSpan = cs, rowSpan = rs, floor = floor)

// The Classic — HOUSE, 2 floors. Center-hall colonial. Garage=RIGHT, deck=BACK.
// Grid 5 cols × 6 rows: rooms flank a central hall (col 2). Front door → foyer →
// hall + stairs. Upstairs: 3 bedrooms + 2 baths off the same central landing hall.
private fun classicLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=6),          // central spine
        p(RoomType.LIVING_ROOM, 0, 0),                      // formal living, front-left
        p(RoomType.FOYER,       3, 0),                      // entry — front door lands here
        p(RoomType.DINING_ROOM, 0, 2),                      // kitchen↔dining open ✓
        p(RoomType.STAIRCASE,   3, 2),                      // behind foyer, off the hall
        p(RoomType.KITCHEN,     0, 4),                      // back-left, near deck ✓
        p(RoomType.LAUNDRY,     3, 4),                      // laundry off the hall
    )
    val f1 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=6, floor=1), // landing hall (same spine)
        p(RoomType.BEDROOM,     0, 0, floor=1),             // master
        p(RoomType.BEDROOM,     3, 0, floor=1),             // bedroom 3
        p(RoomType.BATHROOM,    0, 2, floor=1),             // shared hall bath
        p(RoomType.STAIRCASE,   3, 2, floor=1),             // same column ✓
        p(RoomType.BEDROOM,     0, 4, floor=1),             // bedroom 2
        p(RoomType.BATHROOM,    3, 4, floor=1),             // shared hall bath
    )
    return FloorLayout(gridCols = 5, gridRows = 6, rooms = f0 + f1)
}

// The Penthouse — CONDO, 1 floor. Full-floor luxury unit, 3 bed / 2 bath.
// Grid 5 cols × 9 rows (9 m × 18 m floorplate), side hall (col 2) running the full
// depth. Left run: master suite up front with its en-suite bath open to it, then
// kitchen with a small open dining nook, living room at the back. Right run: entry
// foyer, storage, and two slightly-smaller guest bedrooms sandwiching a shared bath.
private fun penthouseLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=9),          // spine, full depth
        p(RoomType.BEDROOM,     0, 0, rs=3),                // master suite (4×6), front-left
        p(RoomType.BATHROOM,    0, 3, rs=1),                // en-suite — open to master ✓
        p(RoomType.KITCHEN,     0, 4),                      // mid-left
        p(RoomType.DINING_ROOM, 0, 6, rs=1),                // dining nook — open to kitchen ✓
        p(RoomType.LIVING_ROOM, 0, 7),                      // back-left
        p(RoomType.FOYER,       3, 0),                      // entry — front door lands here
        p(RoomType.STORAGE,     3, 2, cs=1),                // storage off the hall
        p(RoomType.LAUNDRY,     4, 2, cs=1),                // laundry, tucked behind storage
        p(RoomType.BEDROOM,     3, 4),                      // guest bedroom (4×4)
        p(RoomType.BATHROOM,    3, 6, rs=1),                // shared bath — between both guest rooms ✓
        p(RoomType.BEDROOM,     3, 7),                      // guest bedroom (4×4)
    )
    return FloorLayout(gridCols = 5, gridRows = 9, rooms = f0, wallOverrides = mapOf(
        // True en-suite: the master↔bath wall opens into the suite, and the bath's
        // hallway side is pinned SOLID so the door router (which would otherwise rank
        // the hallway first) doesn't give the en-suite a hall door of its own.
        WallKey(vertical = false, boundary = 3, index = 0, floor = 0) to WallMode.OPEN,
        WallKey(vertical = false, boundary = 3, index = 1, floor = 0) to WallMode.OPEN,
        WallKey(vertical = true,  boundary = 2, index = 3, floor = 0) to WallMode.SOLID,
    ))
}

// The Townhouse — TOWNHOUSE, 3 floors. Narrow & deep. Garage=FRONT, yard=BACK.
// Grid 4 cols × 7 rows: a side hall (col 2) runs the full depth with the main
// rooms on the left (cols 0-1) and a service strip on the right (col 3, stairs +
// baths + closets). Stairs stay in the same column on all 3 floors. Living on the
// ground floor, bedrooms above.
private fun townhouseLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=7),          // side hall, full depth
        p(RoomType.FOYER,       0, 0),                      // entry — front door lands here
        p(RoomType.STAIRCASE,   3, 0, cs=1),                // stairs — front-right service strip
        p(RoomType.LIVING_ROOM, 0, 2),                      // living off the hall
        p(RoomType.POWDER_ROOM, 3, 2, cs=1),                // half-bath off the hall
        p(RoomType.KITCHEN,     0, 4, rs=3),                // eat-in kitchen, rear
        p(RoomType.LAUNDRY,     3, 4, cs=1, rs=3),          // under-stair laundry
    )
    val f1 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=7, floor=1), // landing hall
        p(RoomType.STAIRCASE,   3, 0, cs=1, floor=1),       // same column ✓
        p(RoomType.BEDROOM,     0, 0, rs=3, floor=1),       // master
        p(RoomType.STORAGE,     3, 2, cs=1, floor=1),       // linen / closet
        p(RoomType.BATHROOM,    0, 3, rs=1, floor=1),       // shared hall bath
        p(RoomType.BEDROOM,     0, 4, rs=3, floor=1),       // bedroom 2
        p(RoomType.BATHROOM,    3, 4, cs=1, rs=3, floor=1), // second bath
    )
    val f2 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=7, floor=2), // landing hall
        p(RoomType.STAIRCASE,   3, 0, cs=1, floor=2),       // same column ✓
        p(RoomType.BEDROOM,     0, 0, rs=3, floor=2),       // top-floor master suite
        p(RoomType.STORAGE,     3, 2, cs=1, floor=2),       // closet
        p(RoomType.BATHROOM,    0, 3, rs=1, floor=2),       // en-suite / hall bath
        p(RoomType.OFFICE,      0, 4, rs=3, floor=2),       // loft office
        p(RoomType.STORAGE,     3, 4, cs=1, rs=3, floor=2), // storage
    )
    return FloorLayout(gridCols = 4, gridRows = 7, rooms = f0 + f1 + f2)
}

// The Ranch — HOUSE, 1 floor. Sprawling single storey. Garage=RIGHT, deck=BACK.
// Grid 8 cols × 6 rows with two hall spines (cols 2 & 5) joined by the foyer at
// the front — an H-shaped corridor. Public wing on the left, bedroom wing on the
// right, service rooms down the centre.
private fun ranchLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=6),  // left spine
        p(RoomType.HALLWAY,     5, 0, cs=1, rs=6),  // right spine
        p(RoomType.FOYER,       3, 0),              // entry hub — joins both spines
        p(RoomType.LIVING_ROOM, 0, 0),              // great room, front-left
        p(RoomType.KITCHEN,     0, 2),              // kitchen↔dining open ✓
        p(RoomType.DINING_ROOM, 0, 4),              // back-left, near deck ✓
        p(RoomType.OFFICE,      3, 2),              // study off the halls
        p(RoomType.POWDER_ROOM, 3, 4, cs=1),        // guest half-bath
        p(RoomType.LAUNDRY,     4, 4, cs=1),        // near garage ✓
        p(RoomType.BEDROOM,     6, 0),              // master, front-right
        p(RoomType.BATHROOM,    6, 2, rs=1),        // shared hall bath
        p(RoomType.BEDROOM,     6, 3),              // bedroom 2
        p(RoomType.BATHROOM,    6, 5, rs=1),        // second bath
    )
    return FloorLayout(gridCols = 8, gridRows = 6, rooms = f0)
}

// The Estate — HOUSE, 1 floor. Large luxury single storey. Pool=BACK, garage=RIGHT,
// HVAC+SOLAR. Grid 8 cols × 7 rows, H-shaped corridor (spines cols 2 & 5, joined by
// the grand foyer). Great room + eat-in kitchen left, media/service centre, master
// suite with walk-in and a guest bedroom on the right.
private fun estateLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,      2, 0, cs=1, rs=7),         // left spine
        p(RoomType.HALLWAY,      5, 0, cs=1, rs=7),         // right spine
        p(RoomType.FOYER,        3, 0),                     // grand entry — joins both spines
        p(RoomType.LIVING_ROOM,  0, 0, rs=3),              // great room, front-left
        p(RoomType.KITCHEN,      0, 3),                    // kitchen↔dining open ✓
        p(RoomType.DINING_ROOM,  0, 5),                    // back-left, pool view ✓
        p(RoomType.POWDER_ROOM,  3, 2, cs=1),              // guest half-bath
        p(RoomType.LAUNDRY,      4, 2, cs=1),              // near garage ✓
        p(RoomType.HOME_THEATER, 3, 4, rs=3),              // media room, centre
        p(RoomType.BEDROOM,      6, 0, rs=3),              // master suite, front-right
        p(RoomType.BATHROOM,     6, 3, cs=1, rs=2),        // master bath
        p(RoomType.STORAGE,      7, 3, cs=1, rs=2),        // walk-in closet
        p(RoomType.BEDROOM,      6, 5),                    // guest bedroom, back-right
    )
    return FloorLayout(gridCols = 8, gridRows = 7, rooms = f0)
}

// ─────────────────────────────────────────────────────────────────────────────

data class PlacedNeighbor(
    val home: NeighborHome,
    val wx: Float,
    val wz: Float,
    val yRotDeg: Float,
)

val PLACED_NEIGHBORS = listOf(
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Classic",
            homeType = HomeType(HomeStyle.HOUSE, HomeSize.MEDIUM, FloorCount.TWO, LayoutType.MIXED),
            floors = 2,
            featurePlacements = mapOf(
                HomeFeature.GARAGE to FeatureSide.RIGHT,
            ),
            placedDecks = listOf(PlacedDeck(side = FeatureSide.BACK)),
            homeSystems = setOf(HomeSystem.HVAC),
            customLayout = classicLayout(),
        ),
        wx = -22f, wz = -18f, yRotDeg = -90f,
    ),
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Estate",
            homeType = HomeType(HomeStyle.HOUSE, HomeSize.LARGE, FloorCount.ONE, LayoutType.OPEN_PLAN),
            floors = 1,
            featurePlacements = mapOf(
                HomeFeature.GARAGE to FeatureSide.RIGHT,
                HomeFeature.POOL   to FeatureSide.BACK,
            ),
            homeSystems = setOf(HomeSystem.HVAC, HomeSystem.SOLAR),
            customLayout = estateLayout(),
        ),
        wx = 22f, wz = -18f, yRotDeg = 90f,
    ),
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Townhouse",
            homeType = HomeType(HomeStyle.TOWNHOUSE, HomeSize.MEDIUM, FloorCount.THREE_PLUS, LayoutType.COMPARTMENTALIZED),
            floors = 3,
            // No exterior GARAGE feature — TOWNHOUSE parks vehicles in an interior GARAGE
            // room instead (auto-added to townhouseLayout() by claimNeighbor's withGarageRoom()).
            homeSystems = setOf(HomeSystem.HVAC),
            customLayout = townhouseLayout(),
        ),
        wx = -22f, wz = -40f, yRotDeg = -90f,
    ),
    PlacedNeighbor(
        home = NeighborHome(
            label    = "The Ranch",
            homeType = HomeType(HomeStyle.HOUSE, HomeSize.MEDIUM, FloorCount.ONE, LayoutType.OPEN_PLAN),
            floors   = 1,
            featurePlacements = mapOf(
                HomeFeature.GARAGE to FeatureSide.RIGHT,
            ),
            placedDecks = listOf(PlacedDeck(side = FeatureSide.BACK)),
            homeSystems  = setOf(HomeSystem.HVAC),
            customLayout = ranchLayout(),
        ),
        wx = 22f, wz = -40f, yRotDeg = 90f,
    ),
    // Penthouse at end of cul-de-sac — rendered as the top floor of a building
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Penthouse",
            homeType = HomeType(HomeStyle.CONDO, HomeSize.LARGE, FloorCount.ONE, LayoutType.OPEN_PLAN),
            floors = 1,
            featurePlacements = emptyMap(),
            homeSystems = setOf(HomeSystem.HVAC),
            // Tower massing matches the full-floor layout's 9 m × 18 m floorplate.
            totalW = 9.0f,
            totalD = 18.0f,
            customLayout = penthouseLayout(),
        ),
        wx = 0f, wz = -58f, yRotDeg = 0f,
    ),
)
