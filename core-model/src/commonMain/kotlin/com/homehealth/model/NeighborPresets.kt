package com.homehealth.model

data class NeighborHome(
    val label: String,
    val homeType: HomeType,
    val floors: Int,
    val featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    // Decks support multiple placed instances (their own list), not a featurePlacements slot —
    // see PlacedDeck.
    val placedDecks: List<PlacedDeck> = emptyList(),
    // Single slot, like garage/pool — side + along-wall offset. Unlike garage/pool this isn't
    // in featurePlacements/featureOffsets since nothing else ever reads just HVAC's side.
    val hvacPlacement: Pair<FeatureSide, Float>? = null,
    // Single slot, like HVAC/EV battery — one placed array (onLeftSlope + along-ridge offset).
    val solarArrayPlacement: Pair<Boolean, Float>? = null,
    // Single slot, like HVAC.
    val evBatteryPlacement: Pair<FeatureSide, Float>? = null,
    // Trees/gazebo support multiple placed instances of either kind — see PlacedYardDecor.
    val placedYardDecor: List<PlacedYardDecor> = emptyList(),
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

// [id] is optional but strongly preferred for presets. RoomPlacement.id otherwise defaults to a
// random UUID, and TWO things tie-break on it: which neighbour a room's single entry door lands
// on when several share the same doorTargetRank (see FloorLayout.effectiveWallModes), and which
// placement anchors a merged zone's furniture (placementIdsInZone(...).minOrNull()). Left random,
// a preset's doors and furniture anchors can differ between launches until the first save
// persists the ids. Stable slugs make an authored layout reproducible.
private fun p(type: RoomType, col: Int, row: Int,
              cs: Int = 2, rs: Int = 2, floor: Int = 0, id: String? = null) =
    if (id != null)
        RoomPlacement(id = id, type = type, col = col, row = row,
                      colSpan = cs, rowSpan = rs, floor = floor)
    else
        RoomPlacement(type = type, col = col, row = row,
                      colSpan = cs, rowSpan = rs, floor = floor)

// The Classic — HOUSE, 2 floors. Center-hall colonial with an interior garage bay (cols 5-7),
// same reasoning as TOWNHOUSE's: an attached garage is the common real-world case, and baking
// it into the layout (rather than a free-standing exterior HomeFeature.GARAGE) means it scales
// and tears down with the rest of the home instead of floating beside it. Deck=BACK.
//
// Grid 8 cols × 6 rows → 15m × 12m (col 2 collapses to 1m as the hallway spine).
//
// Circulation is the thing to preserve if you edit this. Two rules do the work:
//   • The 1-wide HALLWAY spine at col 2 is the same column on BOTH floors — colWidths() is not
//     floor-filtered, so a spine on one floor narrows that column on every floor.
//   • Rooms too far from the spine to touch it (the whole cols 5-7 band) are served by a small
//     FOYER "gallery" instead. FOYER is circulation (door rank 1, and it opens wall-lessly into
//     a STAIRCASE) yet, unlike HALLWAY, it does NOT collapse its column — so it adds a real
//     corridor without changing the footprint. Every room below therefore has a strictly
//     best-ranked circulation neighbour, which is also what keeps door routing deterministic
//     (equal-rank candidates would tie-break on placement id).
// The two FOYER placements per floor are deliberately never adjacent: FOYER is an OPEN_FLOW type,
// so touching foyers would merge into one bounding box and swallow the rooms between them.
private fun classicLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,      2, 0, cs=1, rs=6, id="c0-hall"),   // central spine → 1m
        // Open-plan social core, all three OPEN to the spine (no doors needed).
        p(RoomType.DINING_ROOM,  0, 0, id="c0-dining"),             // formal dining, front-left
        p(RoomType.KITCHEN,      0, 2, id="c0-kitchen"),            // 4m depth clears the run
        p(RoomType.LIVING_ROOM,  0, 4, id="c0-living"),             // back row → back slider ✓
        p(RoomType.FOYER,        3, 0, id="c0-foyer-front"),        // entry — front door lands here
        p(RoomType.STAIRCASE,    3, 2, id="c0-stair"),              // OPEN to foyer above + hall
        p(RoomType.POWDER_ROOM,  3, 4, cs=1, rs=2, id="c0-powder"), // guest half-bath off the hall
        p(RoomType.LAUNDRY,      4, 4, cs=1, rs=2, id="c0-laundry"),// off the rear gallery
        // Garage bay — 3x3 cells (6m x 6m), two-car. Front edge at row 0 and right edge at the
        // grid's last column: HouseSceneGeometry.townhouseGarageBox relies on that to cut the
        // door into the real front wall. No fixed car-count minimum — see RoomInventory.minCellArea.
        p(RoomType.GARAGE,       5, 0, cs=3, rs=3, id="c0-garage"),
        p(RoomType.FOYER,        5, 3, cs=1, rs=3, id="c0-foyer-rear"),   // rear gallery
        p(RoomType.HOME_THEATER, 6, 3, cs=2, rs=3, id="c0-theater"),      // media room, 4m x 6m
    )
    // Bedroom floor. Each bedroom has its own closet, and the master is a real suite —
    // bedroom + en-suite bath + walk-in. Both cost cells, and the grid is exactly full, so the
    // left band trades its third secondary bedroom for the two closets: 3 bedrooms that each
    // have somewhere to put clothes beats 5 that don't. See RoomInventory.allowsNarrowFootprint
    // for why a 2-cell STORAGE/BATHROOM is a legal room.
    val f1 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=6, floor=1, id="c1-hall"), // same column ✓
        p(RoomType.BEDROOM,     0, 0, floor=1, id="c1-bed-2"),
        // Closet between the two bedrooms. It touches both, and STORAGE→BEDROOM outranks
        // circulation (FloorLayout.doorTargetRank), so the tie-break falls on placement id —
        // "c1-bed-2" sorts before "c1-bed-3", making this bedroom 2's closet.
        p(RoomType.STORAGE,     0, 2, cs=2, rs=1, floor=1, id="c1-closet-2"),
        p(RoomType.BEDROOM,     0, 3, floor=1, id="c1-bed-3"),
        p(RoomType.STORAGE,     0, 5, cs=2, rs=1, floor=1, id="c1-closet-3"), // bed 3's only neighbour ✓
        p(RoomType.FOYER,       3, 0, floor=1, id="c1-landing"),          // top-of-stairs landing
        p(RoomType.STAIRCASE,   3, 2, floor=1, id="c1-stair"),            // identical to floor 0 ✓
        // Bath + linen closet flanking the stairs, the usual upstairs arrangement.
        p(RoomType.BATHROOM,    3, 4, cs=1, rs=2, floor=1, id="c1-bath-hall"),
        p(RoomType.STORAGE,     4, 4, cs=1, rs=2, floor=1, id="c1-linen"),
        p(RoomType.BATHROOM,    5, 0, cs=1, rs=2, floor=1, id="c1-bath-2"),
        p(RoomType.FOYER,       5, 2, cs=1, rs=4, floor=1, id="c1-gallery"),
        // Master suite — bedroom 4m x 6m, then the bath and walk-in side by side beneath it so
        // both share a wall with the bedroom rather than with the gallery.
        p(RoomType.BEDROOM,     6, 0, cs=2, rs=3, floor=1, id="c1-master"),
        p(RoomType.BATHROOM,    6, 3, cs=1, rs=2, floor=1, id="c1-bath-master"),
        p(RoomType.STORAGE,     7, 3, cs=1, rs=2, floor=1, id="c1-walkin"),  // master is its only bedroom ✓
        p(RoomType.STORAGE,     6, 5, cs=2, rs=1, floor=1, id="c1-closet-hall"),
    )
    return FloorLayout(gridCols = 8, gridRows = 6, rooms = f0 + f1, wallOverrides = mapOf(
        // True en-suite. The walk-in needs no help — the master is its only BEDROOM neighbour,
        // which the STORAGE→BEDROOM rank picks outright. The BATH does: doorTargetRank ranks the
        // adjacent gallery FOYER (1) above any bedroom (5), so left alone it becomes a second
        // hall bath. Cut its door into the master and pin the gallery side SOLID, exactly as
        // penthouseLayout() does. Pinning is what makes this stick: the router still evaluates
        // the gallery pair, but a pair whose first segment is already overridden is skipped.
        WallKey(vertical = false, boundary = 3, index = 6, floor = 1) to WallMode.DOOR,
        WallKey(vertical = true,  boundary = 6, index = 3, floor = 1) to WallMode.SOLID,
        WallKey(vertical = true,  boundary = 6, index = 4, floor = 1) to WallMode.SOLID,
    ))
}

// The Penthouse — CONDO, 1 floor. Full-floor luxury unit, 3 bed / 2 bath.
// Grid 5 cols × 9 rows (9 m × 18 m floorplate), side hall (col 2) running the full
// depth. Left run: master suite up front with its en-suite bath open to it, then
// kitchen with a small open dining nook, living room at the back. Right run: entry
// foyer, storage, and two slightly-smaller guest bedrooms sandwiching a shared bath.
//
// This is the one model that gets no pantry and no walk-in: the 2-cell-wide left band is fully
// committed to the master suite and the open kitchen/dining/living run, and every alternative
// partition of it forces a 2 m-wide living or dining room. A full-floor condo is the right place
// to stop — see the Classic and Ranch, which hit the same wall.
private fun penthouseLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=9, id="ph-hall"),   // spine, full depth
        p(RoomType.BEDROOM,     0, 0, rs=3, id="ph-master"),       // master suite (4×6), front-left
        p(RoomType.BATHROOM,    0, 3, rs=1, id="ph-bath-master"),  // en-suite — open to master ✓
        p(RoomType.KITCHEN,     0, 4, id="ph-kitchen"),            // mid-left
        p(RoomType.DINING_ROOM, 0, 6, rs=1, id="ph-dining"),       // dining nook — open to kitchen ✓
        p(RoomType.LIVING_ROOM, 0, 7, id="ph-living"),             // back-left
        p(RoomType.FOYER,       3, 0, id="ph-foyer"),              // entry — front door lands here
        // Sits between the foyer and guest bedroom 2, and STORAGE→BEDROOM now outranks
        // circulation, so this reads as that bedroom's closet rather than a hall cupboard —
        // which is the better room for it, and the only closet this floorplate has space for.
        p(RoomType.STORAGE,     3, 2, cs=1, id="ph-closet-2"),
        p(RoomType.LAUNDRY,     4, 2, cs=1, id="ph-laundry"),      // laundry, tucked behind it
        p(RoomType.BEDROOM,     3, 4, id="ph-bed-2"),              // guest bedroom (4×4)
        p(RoomType.BATHROOM,    3, 6, rs=1, id="ph-bath-guest"),   // shared bath — between both ✓
        p(RoomType.BEDROOM,     3, 7, id="ph-bed-3"),              // guest bedroom (4×4)
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

// The Townhouse — TOWNHOUSE, 3 floors. Narrow & deep, with an interior garage bay baked
// directly into the layout (cols 4-6) rather than relying on FloorLayout.withGarageRoom()'s
// runtime fallback — that helper only ever adds the GARAGE room itself to floor 0, and since
// gridCols/gridRows are shared by every floor, the SAME 3 extra columns exist on floors 1-2
// too; left unfilled there, they render as bare unbuilt space, which is exactly what looked
// "incomplete" about this model. Baking real rooms into every floor's version of that bay
// fixes it.
//
// Grid 7 cols × 7 rows → 13m × 14m (col 2 collapses to 1m as the hallway spine).
//
// Same circulation scheme as classicLayout() — see its comment for why the spine column must be
// identical on every floor and why the deep cols 4-6 band is served by a small FOYER "gallery"
// rather than a second hallway. Living on the ground floor, bedrooms above, top floor given over
// to a study/gym/media level. Stairs are a 2m × 6m straight run in the same cells on all 3 floors.
private fun townhouseLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,      2, 0, cs=1, rs=7, id="t0-hall"),    // side hall, full depth → 1m
        // Open-plan social core, all OPEN to the spine.
        p(RoomType.DINING_ROOM,  0, 0, id="t0-dining"),              // front-left
        p(RoomType.KITCHEN,      0, 2, id="t0-kitchen"),
        // Walk-in pantry directly behind the kitchen. PANTRY→KITCHEN outranks circulation
        // (FloorLayout.doorTargetRank), so it doors into the kitchen rather than the hall spine
        // it also touches — a pantry with a hallway door isn't a pantry.
        p(RoomType.PANTRY,       0, 4, cs=2, rs=1, id="t0-pantry"),
        p(RoomType.LIVING_ROOM,  0, 5, id="t0-living"),              // back row → back slider ✓
        p(RoomType.FOYER,        3, 0, cs=1, rs=2, id="t0-foyer-front"), // entry — front door here
        p(RoomType.STAIRCASE,    3, 2, cs=1, rs=3, id="t0-stair"),   // OPEN to foyer above + hall
        p(RoomType.POWDER_ROOM,  3, 5, cs=1, rs=2, id="t0-powder"),  // half-bath off the hall
        // Garage bay — 3x3 cells (6m x 6m). Front edge at row 0 and right edge at the grid's
        // last column, matching HouseSceneGeometry.townhouseGarageBox's invariant that the
        // garage's front/right edges ARE the house's actual front/right exterior walls. No
        // fixed car-count minimum any more — see RoomInventory.minCellArea.
        p(RoomType.GARAGE,       4, 0, cs=3, rs=3, id="t0-garage"),
        p(RoomType.FOYER,        4, 3, cs=1, rs=4, id="t0-foyer-rear"),  // rear gallery
        p(RoomType.OFFICE,       5, 3, cs=2, rs=3, id="t0-office"),      // 4m x 6m study
        p(RoomType.LAUNDRY,      5, 6, cs=2, rs=1, id="t0-laundry"),     // off the rear gallery
    )
    // Bedroom floor. Both secondary bedrooms get their own closet, and the master's bath and
    // walk-in run as two full-depth columns beneath it so BOTH share a wall with the bedroom —
    // the old stacked arrangement put the walk-in below the bath, touching no bedroom at all,
    // which is why it doored onto the gallery like any other hall closet.
    val f1 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=7, floor=1, id="t1-hall"),  // same column ✓
        p(RoomType.BEDROOM,     0, 0, rs=3, floor=1, id="t1-bed-2"),       // 4m x 6m
        p(RoomType.STORAGE,     0, 3, cs=2, rs=1, floor=1, id="t1-closet-2"), // ties to bed-2 by id ✓
        p(RoomType.BEDROOM,     0, 4, floor=1, id="t1-bed-3"),             // 4m x 4m
        p(RoomType.STORAGE,     0, 6, cs=2, rs=1, floor=1, id="t1-closet-3"), // bed 3's only neighbour ✓
        p(RoomType.FOYER,       3, 0, cs=1, rs=2, floor=1, id="t1-landing"),
        p(RoomType.STAIRCASE,   3, 2, cs=1, rs=3, floor=1, id="t1-stair"), // identical ✓
        p(RoomType.BATHROOM,    3, 5, cs=1, rs=2, floor=1, id="t1-bath-hall"),
        p(RoomType.BATHROOM,    4, 0, cs=1, rs=2, floor=1, id="t1-bath-2"),
        p(RoomType.FOYER,       4, 2, cs=1, rs=5, floor=1, id="t1-gallery"),
        p(RoomType.BEDROOM,     5, 0, cs=2, rs=4, floor=1, id="t1-master"),   // 4m x 8m suite
        p(RoomType.BATHROOM,    5, 4, cs=1, rs=3, floor=1, id="t1-bath-master"),
        p(RoomType.STORAGE,     6, 4, cs=1, rs=3, floor=1, id="t1-walkin"),   // master is its only bedroom ✓
    )
    val f2 = listOf(
        p(RoomType.HALLWAY,      2, 0, cs=1, rs=7, floor=2, id="t2-hall"), // same column ✓
        p(RoomType.BEDROOM,      0, 0, rs=3, floor=2, id="t2-bed-4"),      // 4m x 6m
        p(RoomType.STORAGE,      0, 3, cs=2, rs=1, floor=2, id="t2-closet-4"), // bed 4's closet ✓
        p(RoomType.OFFICE,       0, 4, rs=3, floor=2, id="t2-office"),     // loft study, 4m x 6m
        p(RoomType.FOYER,        3, 0, cs=1, rs=2, floor=2, id="t2-landing"),
        p(RoomType.STAIRCASE,    3, 2, cs=1, rs=3, floor=2, id="t2-stair"),// identical ✓
        p(RoomType.BATHROOM,     3, 5, cs=1, rs=2, floor=2, id="t2-bath-hall"),
        p(RoomType.STORAGE,      4, 0, cs=1, rs=2, floor=2, id="t2-linen"),
        p(RoomType.FOYER,        4, 2, cs=1, rs=5, floor=2, id="t2-gallery"),
        p(RoomType.GYM,          5, 0, cs=2, rs=3, floor=2, id="t2-gym"),       // 4m x 6m
        p(RoomType.HOME_THEATER, 5, 3, cs=2, rs=4, floor=2, id="t2-theater"),  // 4m x 8m
    )
    return FloorLayout(gridCols = 7, gridRows = 7, rooms = f0 + f1 + f2, wallOverrides = mapOf(
        // True en-suite — same pattern as classicLayout()/penthouseLayout(): door into the
        // master, gallery side pinned SOLID so the router (which ranks FOYER above any bedroom)
        // can't hand the bath a second door onto the landing.
        WallKey(vertical = false, boundary = 4, index = 5, floor = 1) to WallMode.DOOR,
        WallKey(vertical = true,  boundary = 5, index = 4, floor = 1) to WallMode.SOLID,
        WallKey(vertical = true,  boundary = 5, index = 5, floor = 1) to WallMode.SOLID,
        WallKey(vertical = true,  boundary = 5, index = 6, floor = 1) to WallMode.SOLID,
    ))
}

// The Ranch — HOUSE, 1 floor. Sprawling single storey. Garage=RIGHT, deck=BACK.
// Grid 8 cols × 6 rows with two hall spines (cols 2 & 5) joined by the foyer at
// the front — an H-shaped corridor. Public wing on the left, bedroom wing on the
// right, service rooms down the centre.
//
// The bedroom wing's second full bath became the master's en-suite and bedroom 2's closet: the
// wing is exactly 12 cells, and a suite plus a closet doesn't fit alongside two hall baths. The
// centre-band powder room still covers guests, so the home keeps 1.5 baths either way.
private fun ranchLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,     2, 0, cs=1, rs=6, id="r0-hall-left"),   // left spine
        p(RoomType.HALLWAY,     5, 0, cs=1, rs=6, id="r0-hall-right"),  // right spine
        p(RoomType.FOYER,       3, 0, id="r0-foyer"),        // entry hub — joins both spines
        p(RoomType.LIVING_ROOM, 0, 0, id="r0-living"),       // great room, front-left
        p(RoomType.KITCHEN,     0, 2, id="r0-kitchen"),      // kitchen↔dining open ✓
        p(RoomType.DINING_ROOM, 0, 4, id="r0-dining"),       // back-left, near deck ✓
        p(RoomType.OFFICE,      3, 2, id="r0-office"),       // study off the halls
        p(RoomType.POWDER_ROOM, 3, 4, cs=1, id="r0-powder"), // guest half-bath
        p(RoomType.LAUNDRY,     4, 4, cs=1, id="r0-laundry"),// near garage ✓
        p(RoomType.BEDROOM,     6, 0, id="r0-bed-master"),   // master, front-right
        p(RoomType.BATHROOM,    6, 2, rs=1, id="r0-bath-master"), // en-suite, see wallOverrides
        p(RoomType.BEDROOM,     6, 3, id="r0-bed-2"),        // bedroom 2
        p(RoomType.STORAGE,     6, 5, rs=1, id="r0-closet-2"),   // bed 2's only neighbour ✓
    )
    return FloorLayout(gridCols = 8, gridRows = 6, rooms = f0, wallOverrides = mapOf(
        // True en-suite. The bath spans both wing columns, so its master-side boundary has two
        // segments — one carries the door, the other is pinned so the suite gets ONE opening.
        // The right hall spine (rank 0) and bedroom 2 below are both pinned off, leaving the
        // master as the only way in.
        WallKey(vertical = false, boundary = 2, index = 6, floor = 0) to WallMode.DOOR,
        WallKey(vertical = false, boundary = 2, index = 7, floor = 0) to WallMode.SOLID,
        WallKey(vertical = true,  boundary = 6, index = 2, floor = 0) to WallMode.SOLID,
        WallKey(vertical = false, boundary = 3, index = 6, floor = 0) to WallMode.SOLID,
        WallKey(vertical = false, boundary = 3, index = 7, floor = 0) to WallMode.SOLID,
    ))
}

// The Estate — HOUSE, 1 floor. Large luxury single storey. Pool=BACK, garage=RIGHT,
// HVAC+SOLAR. Grid 8 cols × 7 rows, H-shaped corridor (spines cols 2 & 5, joined by
// the grand foyer). Great room + eat-in kitchen left, media/service centre, master
// suite with walk-in and a guest bedroom on the right.
// The great room gave up one of its three rows for a butler's pantry between it and the kitchen —
// the only free cells anywhere adjacent to the kitchen, since the left band is exactly full.
private fun estateLayout(): FloorLayout {
    val f0 = listOf(
        p(RoomType.HALLWAY,      2, 0, cs=1, rs=7, id="e0-hall-left"),  // left spine
        p(RoomType.HALLWAY,      5, 0, cs=1, rs=7, id="e0-hall-right"), // right spine
        p(RoomType.FOYER,        3, 0, id="e0-foyer"),      // grand entry — joins both spines
        p(RoomType.LIVING_ROOM,  0, 0, id="e0-living"),     // great room, front-left
        p(RoomType.PANTRY,       0, 2, cs=2, rs=1, id="e0-pantry"),  // doors into the kitchen ✓
        p(RoomType.KITCHEN,      0, 3, id="e0-kitchen"),    // kitchen↔dining open ✓
        p(RoomType.DINING_ROOM,  0, 5, id="e0-dining"),     // back-left, pool view ✓
        p(RoomType.POWDER_ROOM,  3, 2, cs=1, id="e0-powder"),   // guest half-bath
        p(RoomType.LAUNDRY,      4, 2, cs=1, id="e0-laundry"),  // near garage ✓
        p(RoomType.HOME_THEATER, 3, 4, rs=3, id="e0-theater"),  // media room, centre
        p(RoomType.BEDROOM,      6, 0, rs=3, id="e0-bed-master"),          // suite, front-right
        p(RoomType.BATHROOM,     6, 3, cs=1, rs=2, id="e0-bath-master"),   // en-suite
        p(RoomType.STORAGE,      7, 3, cs=1, rs=2, id="e0-walkin"),        // walk-in closet
        p(RoomType.BEDROOM,      6, 5, id="e0-bed-guest"),                 // guest, back-right
    )
    return FloorLayout(gridCols = 8, gridRows = 7, rooms = f0, wallOverrides = mapOf(
        // Both suite rooms sit BETWEEN the master and the guest bedroom, so neither resolves on
        // its own: the bath ranks the right hall spine (0) above any bedroom, and the walk-in
        // ties at STORAGE→BEDROOM rank between the two bedrooms — a tie that would fall to
        // whichever id sorts first. Cut each door into the master and pin every other side.
        // Note a SOLID pin alone would not be enough for the walk-in: the router would still
        // pick the guest room and then skip it as already-overridden, leaving no door at all.
        WallKey(vertical = false, boundary = 3, index = 7, floor = 0) to WallMode.DOOR,  // master↔walk-in
        WallKey(vertical = false, boundary = 5, index = 7, floor = 0) to WallMode.SOLID, // walk-in↔guest
        WallKey(vertical = false, boundary = 3, index = 6, floor = 0) to WallMode.DOOR,  // master↔bath
        WallKey(vertical = false, boundary = 5, index = 6, floor = 0) to WallMode.SOLID, // bath↔guest
        WallKey(vertical = true,  boundary = 6, index = 3, floor = 0) to WallMode.SOLID, // hall↔bath
        WallKey(vertical = true,  boundary = 6, index = 4, floor = 0) to WallMode.SOLID,
    ))
}

// ─────────────────────────────────────────────────────────────────────────────

data class PlacedNeighbor(
    val home: NeighborHome,
    val wx: Float,
    val wz: Float,
    val yRotDeg: Float,
)

/**
 * Where each model home stands. The five ring the park in the middle of the neighbourhood, all
 * FACING it (yRotDeg turns a home's own +Z front toward the green), with the loop road passing
 * behind them — so walking the green shows you five front elevations, which is where blockHome
 * puts every piece of facade detail. The user's own claimed home is the sixth on the ring, fixed
 * at the origin on the north side, backing onto the park with its driveway out to the loop.
 *
 * Coordinates are the same world frame everything else uses, and the plan they're measured
 * against — the park's centre, the ring's four sides — lives in NeighborhoodSceneGeometry.
 */
val PLACED_NEIGHBORS = listOf(
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Classic",
            homeType = HomeType(HomeStyle.HOUSE, HomeSize.MEDIUM, FloorCount.TWO, LayoutType.MIXED),
            floors = 2,
            // No exterior GARAGE feature — like TOWNHOUSE, this model parks vehicles in an
            // interior GARAGE room instead, baked directly into classicLayout() (claimNeighbor's
            // withGarageRoom() call is a no-op here — it only exists as a migration fallback for
            // saves from before the interior garage existed).
            featurePlacements = emptyMap(),
            placedDecks = listOf(PlacedDeck(side = FeatureSide.BACK)),
            // No pool/garage to sit opposite — the default side wall.
            hvacPlacement = FeatureSide.RIGHT to 0f,
            // Roof-mounted, so it doesn't compete with HVAC/deck for a wall side — same slot
            // Estate already showcases.
            solarArrayPlacement = true to 0f,
            // Matches classicLayout()'s actual 8x6-cell footprint (15m x 12m) now that the
            // garage bay is part of the layout — see the Townhouse entry below for the same
            // reasoning.
            totalW = 15.0f,
            totalD = 12.0f,
            customLayout = classicLayout(),
        ),
        wx = -25f, wz = -26f, yRotDeg = 90f,
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
            // Pool is on BACK and the garage on RIGHT, so the free side wall is LEFT. (This
            // used to be FRONT — literally opposite the pool — but a condenser across the front
            // elevation looks wrong and the actions bar's rotate can't even cycle back to it;
            // see HouseSceneGeometry.UTILITY_SIDES.)
            hvacPlacement = FeatureSide.LEFT to 0f,
            solarArrayPlacement = true to 0f,
            // estateLayout()'s real footprint. Previously left at the 10.5 x 9.0 default, which
            // made the neighborhood massing block noticeably smaller than the home you actually
            // get on claiming — the two spine columns (2 and 5) each collapse to 1m.
            totalW = 14.0f,
            totalD = 14.0f,
            customLayout = estateLayout(),
        ),
        wx = 26f, wz = -26f, yRotDeg = -90f,
    ),
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Townhouse",
            homeType = HomeType(HomeStyle.TOWNHOUSE, HomeSize.MEDIUM, FloorCount.THREE_PLUS, LayoutType.COMPARTMENTALIZED),
            floors = 3,
            // No exterior GARAGE feature — TOWNHOUSE parks vehicles in an interior GARAGE
            // room instead, baked directly into townhouseLayout() (claimNeighbor's
            // withGarageRoom() call becomes a no-op here — it only exists now as a migration
            // fallback for saves from before the interior garage existed).
            // No pool/garage to sit opposite — the default side wall.
            hvacPlacement = FeatureSide.RIGHT to 0f,
            // townhouseLayout()'s real footprint. This is 13m, not the 14m you get from
            // 7 cols x 2m: the hallway spine at col 2 collapses that column to 1m (colWidths()),
            // which the old declared value overlooked.
            totalW = 13.0f,
            totalD = 14.0f,
            customLayout = townhouseLayout(),
        ),
        wx = -22f, wz = -52f, yRotDeg = 0f,
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
            // Opposite the garage (RIGHT).
            hvacPlacement = FeatureSide.LEFT to 0f,
            // Roof-mounted, so it doesn't compete with garage/HVAC/deck for a wall side.
            solarArrayPlacement = true to 0f,
            // ranchLayout()'s real footprint — same fix as the Estate above (was left at the
            // 10.5 x 9.0 default); both spine columns (2 and 5) collapse to 1m.
            totalW = 14.0f,
            totalD = 12.0f,
            customLayout = ranchLayout(),
        ),
        wx = 22f, wz = -52f, yRotDeg = 0f,
    ),
    // Penthouse at end of cul-de-sac — rendered as the top floor of a building
    PlacedNeighbor(
        home = NeighborHome(
            label = "The Penthouse",
            homeType = HomeType(HomeStyle.CONDO, HomeSize.LARGE, FloorCount.ONE, LayoutType.OPEN_PLAN),
            floors = 1,
            featurePlacements = emptyMap(),
            // No pool/garage to sit opposite — the default side wall.
            hvacPlacement = FeatureSide.RIGHT to 0f,
            // Tower massing matches the full-floor layout's 9 m × 18 m floorplate.
            totalW = 9.0f,
            totalD = 18.0f,
            customLayout = penthouseLayout(),
        ),
        wx = 0f, wz = -54f, yRotDeg = 0f,
    ),
)
