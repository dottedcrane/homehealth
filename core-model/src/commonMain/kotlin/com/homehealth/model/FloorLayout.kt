package com.homehealth.model

import com.homehealth.scene.WALL_T
import java.util.UUID
import kotlin.math.roundToInt

private const val HALLWAY_SIZE = 1.0f

data class RoomPlacement(
    val id: String = UUID.randomUUID().toString(),
    val type: RoomType,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val floor: Int = 0,
) {
    fun occupies(c: Int, r: Int) =
        c in col until col + colSpan && r in row until row + rowSpan
}

data class FloorLayout(
    val gridCols: Int = 8,
    val gridRows: Int = 7,
    val cellW: Float = 2.0f,
    val cellD: Float = 2.0f,
    val rooms: List<RoomPlacement> = defaultRooms(),
    val wallOverrides: Map<WallKey, WallMode> = emptyMap(),
) {
    val totalW: Float get() = colWidths().sum()
    val totalD: Float get() = rowDepths().sum()

    /** Width of each column — HALLWAY columns (rowSpan ≥ colSpan) collapse to HALLWAY_SIZE. */
    fun colWidths(): FloatArray {
        val w = FloatArray(gridCols) { cellW }
        rooms.filter { it.type == RoomType.HALLWAY && it.rowSpan >= it.colSpan }
             .forEach { hw -> for (c in hw.col until hw.col + hw.colSpan) w[c] = HALLWAY_SIZE }
        return w
    }

    /** Depth of each row — HALLWAY rows (colSpan > rowSpan) collapse to HALLWAY_SIZE. */
    fun rowDepths(): FloatArray {
        val d = FloatArray(gridRows) { cellD }
        rooms.filter { it.type == RoomType.HALLWAY && it.colSpan > it.rowSpan }
             .forEach { hw -> for (r in hw.row until hw.row + hw.rowSpan) d[r] = HALLWAY_SIZE }
        return d
    }

    /** xBound[c] = left world-space edge of column c. Size = gridCols + 1. */
    fun colXBounds(): FloatArray {
        val widths = colWidths(); val tw = widths.sum()
        return FloatArray(gridCols + 1).also { arr ->
            arr[0] = -tw / 2f
            for (c in 0 until gridCols) arr[c + 1] = arr[c] + widths[c]
        }
    }

    /** zBound[r] = front world-space edge (high Z) of row r. Size = gridRows + 1. */
    fun rowZBounds(): FloatArray {
        val depths = rowDepths(); val td = depths.sum()
        return FloatArray(gridRows + 1).also { arr ->
            arr[0] = td / 2f
            for (r in 0 until gridRows) arr[r + 1] = arr[r] - depths[r]
        }
    }

    fun placementAt(col: Int, row: Int, floor: Int = 0): RoomPlacement? =
        rooms.firstOrNull { it.floor == floor && it.occupies(col, row) }

    fun toPlacementsAndZones(floor: Int = 0): List<Pair<RoomPlacement, RoomZone>> {
        val xb = colXBounds(); val zb = rowZBounds()
        return rooms
            .filter { it.floor == floor }
            .distinctBy { it.id }
            .map { p ->
                p to RoomZone(
                    p.type,
                    xMin = xb[p.col],
                    xMax = xb[p.col + p.colSpan],
                    zMin = zb[p.row + p.rowSpan],
                    zMax = zb[p.row],
                )
            }
    }

    fun toZones(floor: Int = 0): List<RoomZone> = toPlacementsAndZones(floor).map { it.second }

    /**
     * Zones where adjacent same-type placements are merged into one bounding-box zone.
     * Only OPEN_FLOW types merge (see [RoomType.mergesWithSameType]) — adjacent bedrooms,
     * baths, etc. stay separate zones, each with its own wall, door, and furniture.
     */
    fun toMergedZones(floor: Int = 0): List<RoomZone> {
        val placements = rooms.filter { it.floor == floor }
        val visited    = mutableSetOf<String>()
        val result     = mutableListOf<RoomZone>()
        val xb = colXBounds(); val zb = rowZBounds()
        for (seed in placements) {
            if (seed.id in visited) continue
            val group = mutableListOf<RoomPlacement>()
            val queue = ArrayDeque<RoomPlacement>()
            queue.add(seed); visited.add(seed.id)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                group.add(cur)
                if (!seed.type.mergesWithSameType) continue
                for (c in cur.col until cur.col + cur.colSpan) {
                    placementAt(c, cur.row - 1, floor)
                        ?.takeIf { it.type == seed.type && it.id !in visited }
                        ?.also { visited.add(it.id); queue.add(it) }
                    placementAt(c, cur.row + cur.rowSpan, floor)
                        ?.takeIf { it.type == seed.type && it.id !in visited }
                        ?.also { visited.add(it.id); queue.add(it) }
                }
                for (r in cur.row until cur.row + cur.rowSpan) {
                    placementAt(cur.col - 1, r, floor)
                        ?.takeIf { it.type == seed.type && it.id !in visited }
                        ?.also { visited.add(it.id); queue.add(it) }
                    placementAt(cur.col + cur.colSpan, r, floor)
                        ?.takeIf { it.type == seed.type && it.id !in visited }
                        ?.also { visited.add(it.id); queue.add(it) }
                }
            }
            val minCol = group.minOf { it.col }
            val maxCol = group.maxOf { it.col + it.colSpan }
            val minRow = group.minOf { it.row }
            val maxRow = group.maxOf { it.row + it.rowSpan }
            result.add(RoomZone(
                type = seed.type,
                xMin = xb[minCol],
                xMax = xb[maxCol],
                zMin = zb[maxRow],
                zMax = zb[minRow],
            ))
        }
        return result
    }

    fun maxFloor(): Int = rooms.maxOfOrNull { it.floor } ?: 0

    /** Returns the merged zone that contains the given placement on [floor]. */
    fun mergedZoneFor(p: RoomPlacement, floor: Int = 0): RoomZone? {
        val xb = colXBounds(); val zb = rowZBounds()
        val px = (xb[p.col] + xb[p.col + p.colSpan]) / 2f
        val pz = (zb[p.row] + zb[p.row + p.rowSpan]) / 2f
        return toMergedZones(floor).find { mz ->
            mz.type == p.type && px >= mz.xMin && px <= mz.xMax && pz >= mz.zMin && pz <= mz.zMax
        }
    }

    fun addRoom(p: RoomPlacement): FloorLayout {
        val cleared = rooms.filter { e ->
            e.floor != p.floor || (0 until p.colSpan).none { dc ->
                (0 until p.rowSpan).any { dr -> e.occupies(p.col + dc, p.row + dr) }
            }
        }
        val newRooms = cleared + p
        return copy(
            gridCols = maxOf(gridCols, newRooms.maxOf { it.col + it.colSpan }),
            gridRows = maxOf(gridRows, newRooms.maxOf { it.row + it.rowSpan }),
            rooms    = newRooms,
        )
    }

    fun removeRoom(id: String): FloorLayout {
        val remaining = rooms.filter { it.id != id }
        if (remaining.isEmpty()) return copy(gridCols = 1, gridRows = 1, rooms = remaining)
        return copy(
            gridCols = remaining.maxOf { it.col + it.colSpan },
            gridRows = remaining.maxOf { it.row + it.rowSpan },
            rooms    = remaining,
        )
    }

    fun changeType(id: String, type: RoomType): FloorLayout =
        copy(rooms = rooms.map { if (it.id == id) it.copy(type = type) else it })

    fun withWall(key: WallKey, mode: WallMode): FloorLayout =
        copy(wallOverrides = wallOverrides + (key to mode))

    fun withoutWallOverride(key: WallKey): FloorLayout =
        copy(wallOverrides = wallOverrides - key)

    fun expandCols(): FloorLayout = copy(gridCols = gridCols + 1)
    fun expandRows(): FloorLayout = copy(gridRows = gridRows + 1)

    /** Rooms (any floor) that occupy the last column — these are deleted by [shrinkCols]. */
    fun roomsInLastCol(): List<RoomPlacement> =
        rooms.filter { gridCols - 1 in it.col until it.col + it.colSpan }

    /** Rooms (any floor) that occupy the last row — these are deleted by [shrinkRows]. */
    fun roomsInLastRow(): List<RoomPlacement> =
        rooms.filter { gridRows - 1 in it.row until it.row + it.rowSpan }

    /** Rooms on the topmost floor — these are deleted by [removeTopFloor]. */
    fun roomsOnTopFloor(): List<RoomPlacement> = rooms.filter { it.floor == maxFloor() }

    fun shrinkCols(): FloorLayout {
        if (gridCols <= 1) return this
        val newCols = gridCols - 1
        return copy(
            gridCols = newCols,
            rooms    = rooms - roomsInLastCol().toSet(),
            // Drop overrides on boundaries/columns the shrink just removed — otherwise they
            // sit dormant and silently reactivate on an unrelated wall if the grid is later
            // expanded back to this size (boundary/index values get reused).
            wallOverrides = wallOverrides.filterKeys { k -> if (k.vertical) k.boundary <= newCols else k.index < newCols },
        )
    }

    fun shrinkRows(): FloorLayout {
        if (gridRows <= 1) return this
        val newRows = gridRows - 1
        return copy(
            gridRows = newRows,
            rooms    = rooms - roomsInLastRow().toSet(),
            wallOverrides = wallOverrides.filterKeys { k -> if (k.vertical) k.index < newRows else k.boundary <= newRows },
        )
    }

    /** Removes the topmost floor (added via [addFloor]) and all its rooms. No-op on the ground floor. */
    fun removeTopFloor(): FloorLayout {
        val top = maxFloor()
        if (top == 0) return this
        return copy(rooms = rooms.filter { it.floor != top })
    }

    fun addFloor(): FloorLayout {
        val currentTop = maxFloor()
        val next       = currentTop + 1

        // When adding the first upper floor, the laundry becomes a staircase
        val updatedRooms = if (currentTop == 0) {
            val laundry = rooms.firstOrNull { it.floor == 0 && it.type == RoomType.LAUNDRY }
            if (laundry != null) rooms.map { if (it.id == laundry.id) it.copy(type = RoomType.STAIRCASE) else it }
            else rooms
        } else rooms

        val stair     = updatedRooms.firstOrNull { it.floor == 0 && it.type == RoomType.STAIRCASE }
        val scCol     = stair?.col     ?: 5
        val scRow     = stair?.row     ?: 2
        val scColSpan = stair?.colSpan ?: 1
        val scRowSpan = stair?.rowSpan ?: 2

        val refHallway = updatedRooms.firstOrNull { it.floor == currentTop && it.type == RoomType.HALLWAY }
        val hwCol      = refHallway?.col     ?: 2
        val hwColSpan  = refHallway?.colSpan ?: 1

        // Upper-floor bedroom sizing: each bedroom spans as many rows as possible above the
        // bathroom zone, giving at least rowSpan=4 on a standard 6-row grid (double the old 2).
        val rightStart   = hwCol + hwColSpan     // first col to the right of the hallway
        val rightBedCols = scCol - rightStart    // cols between hallway and staircase
        val bathRow      = gridRows - 2          // last 2 rows reserved for bathroom/storage
        val bedRowSpan   = bathRow               // bedroom fills rows 0..(bathRow-1)

        val newRooms = buildList {
            add(RoomPlacement(type = RoomType.STAIRCASE, col = scCol, row = scRow, colSpan = scColSpan, rowSpan = scRowSpan, floor = next))
            add(RoomPlacement(type = RoomType.HALLWAY,   col = hwCol, row = 0, colSpan = hwColSpan, rowSpan = gridRows, floor = next))
            // Behind-garage bedroom — depth limited by staircase position
            add(RoomPlacement(type = RoomType.BEDROOM,   col = scCol, row = 0, colSpan = scColSpan + 1, rowSpan = scRow, floor = next))
            // Right-side bedroom — double-size minimum (spans full depth above bathroom row)
            if (rightBedCols > 0) {
                add(RoomPlacement(type = RoomType.BEDROOM,  col = rightStart, row = 0, colSpan = rightBedCols, rowSpan = bedRowSpan, floor = next))
                add(RoomPlacement(type = RoomType.BATHROOM, col = rightStart, row = bathRow, colSpan = 1, rowSpan = 2, floor = next))
                if (rightBedCols > 1)
                    add(RoomPlacement(type = RoomType.STORAGE, col = rightStart + 1, row = bathRow, colSpan = rightBedCols - 1, rowSpan = 2, floor = next))
            }
            // Left-side bedroom — previously empty on upper floors; now double-size
            if (hwCol > 0) {
                add(RoomPlacement(type = RoomType.BEDROOM,  col = 0, row = 0, colSpan = hwCol, rowSpan = bedRowSpan, floor = next))
                add(RoomPlacement(type = RoomType.BATHROOM, col = 0, row = bathRow, colSpan = 1, rowSpan = 2, floor = next))
                if (hwCol > 1)
                    add(RoomPlacement(type = RoomType.STORAGE, col = 1, row = bathRow, colSpan = hwCol - 1, rowSpan = 2, floor = next))
            }
            // Bathroom beside staircase (the far corner at bathRow, to the right of the staircase col)
            val farBathStart = scCol + scColSpan
            if (farBathStart < gridCols)
                add(RoomPlacement(type = RoomType.BATHROOM, col = farBathStart, row = bathRow, colSpan = gridCols - farBathStart, rowSpan = 2, floor = next))
        }

        val allRooms = updatedRooms + newRooms
        return copy(
            gridCols = maxOf(gridCols, allRooms.maxOf { it.col + it.colSpan }),
            gridRows = maxOf(gridRows, allRooms.maxOf { it.row + it.rowSpan }),
            rooms    = allRooms,
        )
    }

    /**
     * Ensures a GARAGE room exists on the ground floor — no-op if one's already there. Used to
     * backfill TOWNHOUSE homes (their vehicles used to live on an exterior driveway that no
     * longer exists for that style) both for brand-new claims and for saves from before the
     * interior garage existed. Appends fresh columns at the grid's right edge rather than
     * hunting for existing free space, so it always succeeds regardless of how packed the rest
     * of the layout is.
     */
    fun withGarageRoom(): FloorLayout {
        if (rooms.any { it.type == RoomType.GARAGE }) return this
        val span = 3 // 3x3 cells on the standard 2.0m grid = 6m x 6m — a comfortable default;
                     // the user can shrink it in the editor since GARAGE has no fixed minimum
        val startCol = gridCols
        var layout = this
        repeat(span) { layout = layout.expandCols() }
        return layout.addRoom(RoomPlacement(
            type = RoomType.GARAGE, col = startCol, row = 0,
            colSpan = span, rowSpan = minOf(span, gridRows), floor = 0,
        ))
    }
}

/**
 * Whether [p] sits where a TOWNHOUSE's interior garage bay must sit for the exterior garage
 * door/driveway geometry to line up (see HouseSceneGeometry.townhouseGarageBox's doc comment:
 * front edge = the house's front wall, right edge = the house's right wall) — [FloorLayout.
 * withGarageRoom] always seeds it here. Used to keep GARAGE out of the room-type picker for
 * any other room, so a change-type/swap can't silently produce a garage whose exterior door
 * renders in the wrong place.
 */
fun FloorLayout.isValidGaragePosition(p: RoomPlacement): Boolean =
    p.floor == 0 && p.row == 0 && p.col + p.colSpan == gridCols

// The open-plan core of the home: these spaces flow into each other with no wall at all —
// structure is carried by pillars at the open junctions (rendered by the scenes), not walls.
private val OPEN_FLOW = setOf(
    RoomType.LIVING_ROOM, RoomType.DINING_ROOM, RoomType.KITCHEN,
    RoomType.HALLWAY, RoomType.FOYER,
)

// Only the open-plan social spaces merge with a same-type neighbor into one big room.
// Private/repeatable rooms (bedrooms, baths, offices…) placed side by side stay separate
// rooms with their shared wall — as in a real home, where adjacent bedrooms are normal.
private val RoomType.mergesWithSameType get() = this in OPEN_FLOW

// Room pairs that are architecturally open to each other (no wall at all). Same-type
// neighbors only merge within OPEN_FLOW (covered by the clause below); elsewhere the
// shared wall stays.
private val STAIR_LANDINGS = setOf(RoomType.HALLWAY, RoomType.FOYER)
private fun isOpenPair(a: RoomType, b: RoomType) =
    (a in OPEN_FLOW && b in OPEN_FLOW) ||
    // The stairwell opens straight into its landing space — hallway or foyer — so a
    // flight always drops into the entry without a wall, however small the foyer.
    (a == RoomType.STAIRCASE && b in STAIR_LANDINGS) ||
    (b == RoomType.STAIRCASE && a in STAIR_LANDINGS)

// Lower = better door target when a room picks where its ONE entry door goes: circulation
// first, then public living space. Bathrooms and the stairwell rank last so no room routes
// THROUGH them — they're only chosen when they're the sole neighbor (ensuite, attic stair).
//
// Pair-aware because two room types are SERVANTS of a specific neighbor rather than rooms the
// home circulates into: a pantry is part of its kitchen, and a closet/walk-in is part of its
// bedroom. Both outrank circulation, which is the one case where routing to the hallway is
// wrong — a pantry with a hall door isn't a pantry, it's a store room. Every other pair falls
// through to the generic table below, so nothing else changes.
//
// The ENSUITE BATH is deliberately NOT generalised here: a bathroom touching both a hallway and
// a bedroom is usually the hall bath, so making it prefer bedrooms would mis-route far more
// often than it helps. Suites pin theirs with an explicit wallOverride instead — see the
// preset layouts in NeighborPresets.kt.
private fun doorTargetRank(from: RoomType, to: RoomType): Int = when {
    from == RoomType.PANTRY  && to == RoomType.KITCHEN -> -1
    from == RoomType.STORAGE && to == RoomType.BEDROOM -> -1
    else -> when (to) {
        RoomType.HALLWAY                                            -> 0
        RoomType.FOYER                                              -> 1
        RoomType.LIVING_ROOM                                        -> 2
        RoomType.DINING_ROOM, RoomType.KITCHEN                      -> 3
        RoomType.BATHROOM, RoomType.POWDER_ROOM, RoomType.STAIRCASE -> 9
        else                                                        -> 5
    }
}

// Room pairs whose shared wall should always default to solid (no opening).
private fun isSolidPair(a: RoomType, b: RoomType): Boolean {
    val baths        = setOf(RoomType.BATHROOM, RoomType.POWDER_ROOM)
    val publicSpaces = setOf(RoomType.LIVING_ROOM, RoomType.KITCHEN, RoomType.DINING_ROOM)
    if ((a in baths && b in publicSpaces) || (b in baths && a in publicSpaces)) return true
    if ((a == RoomType.BEDROOM && b in setOf(RoomType.KITCHEN, RoomType.LIVING_ROOM)) ||
        (b == RoomType.BEDROOM && a in setOf(RoomType.KITCHEN, RoomType.LIVING_ROOM))) return true
    if ((a == RoomType.STORAGE && b in publicSpaces) || (b == RoomType.STORAGE && a in publicSpaces)) return true
    return false
}

/**
 * The effective mode of every interior wall segment on [floor] — the SINGLE source of truth
 * consumed both by FloorPlanScene (rendering) and [wallModesFor] (RoomScene). Defaults:
 * open-flow pairs (living/dining/kitchen/hallway/foyer) and hallway/foyer↔stair walls are OPEN,
 * including same-type pairs within that set (a merged room); everything else
 * starts SOLID and each room then gets exactly ONE entry door — routed into a hallway/foyer
 * when adjacent, else its most public neighbor (see [doorTargetRank]) — so a bathroom has one
 * door, bedrooms don't door into each other, and an ensuite bath doors into its bedroom.
 * wallOverrides always win over the default. Keeping one computation is what guarantees a
 * wall edited in the floor plan looks identical from inside the room.
 */
fun FloorLayout.effectiveWallModes(floor: Int): Map<WallKey, WallMode> {
    val doorW  = 0.95f
    val colW   = colWidths()
    val rowD   = rowDepths()
    val result = mutableMapOf<WallKey, WallMode>()
    val byId   = rooms.filter { it.floor == floor }.associateBy { it.id }

    // Door-capable segments per adjacent room pair, in sweep order (vertical walls first) —
    // a chosen pair's door lands on its first wide-enough shared segment.
    val pairSegments = LinkedHashMap<Pair<String, String>, MutableList<WallKey>>()
    // Rooms already flowing into the open plan — they need no entry door of their own.
    val openAdjacent = mutableSetOf<String>()

    fun visit(ap: RoomPlacement?, bp: RoomPlacement?, key: WallKey, wideEnough: Boolean) {
        if (ap == bp) return  // same placement on both sides (or both null)
        if (ap == null || bp == null) {
            result[key] = wallOverrides[key] ?: WallMode.SOLID
            return
        }
        val open = isOpenPair(ap.type, bp.type)
        result[key] = wallOverrides[key] ?: if (open) WallMode.OPEN else WallMode.SOLID
        if (open) {
            openAdjacent += ap.id; openAdjacent += bp.id
        } else if (wideEnough) {
            val pair = if (ap.id < bp.id) ap.id to bp.id else bp.id to ap.id
            pairSegments.getOrPut(pair) { mutableListOf() } += key
        }
    }

    // Vertical walls at column boundaries (walls parallel to Z axis)
    for (col in 1 until gridCols)
        for (row in 0 until gridRows)
            visit(placementAt(col - 1, row, floor), placementAt(col, row, floor),
                WallKey(vertical = true, boundary = col, index = row, floor = floor),
                wideEnough = rowD[row] >= doorW)
    // Horizontal walls at row boundaries (walls parallel to X axis)
    for (row in 1 until gridRows)
        for (col in 0 until gridCols)
            visit(placementAt(col, row - 1, floor), placementAt(col, row, floor),
                WallKey(vertical = false, boundary = row, index = col, floor = floor),
                wideEnough = colW[col] >= doorW)

    // ── One entry door per room ──────────────────────────────────────────────
    // Circulation spaces don't pick their own door (rooms door INTO them); every other
    // room picks its single best neighbor. Two rooms picking each other share one door.
    val circulation = setOf(RoomType.HALLWAY, RoomType.FOYER, RoomType.STAIRCASE)
    val neighborsOf = mutableMapOf<String, MutableSet<String>>()
    pairSegments.keys.forEach { (a, b) ->
        neighborsOf.getOrPut(a) { mutableSetOf() } += b
        neighborsOf.getOrPut(b) { mutableSetOf() } += a
    }
    val chosenPairs = mutableSetOf<Pair<String, String>>()
    for (p in byId.values.sortedBy { it.id }) {
        if (p.type in circulation || p.id in openAdjacent) continue
        val candidates = neighborsOf[p.id].orEmpty().mapNotNull { byId[it] }
        val order = compareBy<RoomPlacement>({ doorTargetRank(p.type, it.type) }, { it.id })
        // Prefer neighbors a door is normally allowed into; if the room is otherwise
        // sealed (e.g. a bathroom boxed in by public rooms), allow one anyway.
        val target = candidates.filterNot { isSolidPair(p.type, it.type) }.minWithOrNull(order)
            ?: candidates.minWithOrNull(order)
            ?: continue
        chosenPairs += if (p.id < target.id) p.id to target.id else target.id to p.id
    }
    chosenPairs.forEach { pair ->
        val key = pairSegments[pair]?.firstOrNull() ?: return@forEach
        if (wallOverrides[key] == null) result[key] = WallMode.DOOR
    }
    return result
}

/**
 * Minimal structural pillar positions (world x/z) for [floor]. Model assumption: the
 * structure is carried at room corners. Every placement contributes its 4 corner points;
 * a corner touched by ANY load-bearing wall — an interior segment whose effective mode
 * isn't OPEN, or the exterior shell — is already supported by that wall and needs no
 * pillar. What remains is exactly the set of corners standing free inside the open plan
 * (e.g. where hallway, living room, and kitchen flow together).
 */
fun FloorLayout.pillarPositions(floor: Int): List<Pair<Float, Float>> {
    val xb = colXBounds()
    val zb = rowZBounds()
    fun pt(x: Float, z: Float) = (x * 1000).roundToInt() to (z * 1000).roundToInt()

    val corners = mutableSetOf<Pair<Int, Int>>()
    rooms.filter { it.floor == floor }.forEach { p ->
        for (c in intArrayOf(p.col, p.col + p.colSpan))
            for (r in intArrayOf(p.row, p.row + p.rowSpan))
                corners += pt(xb[c], zb[r])
    }

    val supported = mutableSetOf<Pair<Int, Int>>()
    effectiveWallModes(floor).forEach { (key, mode) ->
        if (mode == WallMode.OPEN) return@forEach
        if (key.vertical) {
            supported += pt(xb[key.boundary], zb[key.index])
            supported += pt(xb[key.boundary], zb[key.index + 1])
        } else {
            supported += pt(xb[key.index], zb[key.boundary])
            supported += pt(xb[key.index + 1], zb[key.boundary])
        }
    }
    // Exterior shell — always load-bearing.
    for (c in 0..gridCols) { supported += pt(xb[c], zb[0]); supported += pt(xb[c], zb[gridRows]) }
    for (r in 0..gridRows) { supported += pt(xb[0], zb[r]); supported += pt(xb[gridCols], zb[r]) }

    return (corners - supported).map { (xi, zi) -> xi / 1000f to zi / 1000f }
}

// Which mode (SOLID/OPEN/DOOR/WINDOW) each of a room placement's 4 sides renders as.
// Reads [effectiveWallModes] — the exact map FloorPlanScene renders from — so the room
// interior always shows the same walls/doors the floor plan does, including overrides.
fun FloorLayout.wallModesFor(p: RoomPlacement): Map<FeatureSide, WallMode> {
    val modes = effectiveWallModes(p.floor)

    // Surfaces the first "interesting" (non-solid) segment mode along a side — any door/arch/
    // window anywhere along the side renders for the whole side in RoomScene. Segments absent
    // from the map (exterior walls, interior of a merged room) fall back to override-or-solid.
    fun sideMode(keys: List<WallKey>): WallMode {
        for (key in keys) {
            val mode = modes[key] ?: wallOverrides[key] ?: WallMode.SOLID
            if (mode != WallMode.SOLID) return mode
        }
        return WallMode.SOLID
    }

    val cols = p.col until p.col + p.colSpan
    val rows = p.row until p.row + p.rowSpan
    return mapOf(
        FeatureSide.FRONT to sideMode(cols.map { c ->
            WallKey(vertical = false, boundary = p.row, index = c, floor = p.floor) }),
        FeatureSide.BACK to sideMode(cols.map { c ->
            WallKey(vertical = false, boundary = p.row + p.rowSpan, index = c, floor = p.floor) }),
        FeatureSide.LEFT to sideMode(rows.map { r ->
            WallKey(vertical = true, boundary = p.col, index = r, floor = p.floor) }),
        FeatureSide.RIGHT to sideMode(rows.map { r ->
            WallKey(vertical = true, boundary = p.col + p.colSpan, index = r, floor = p.floor) }),
    )
}

/**
 * Same as [wallModesFor], but for a (possibly merged) [RoomZone] rather than a single
 * [RoomPlacement] — looks up a representative placement whose merged zone matches, so callers
 * that only have the zone (e.g. HouseScene/FloorPlanScene's furniture pass) can still find out
 * which side carries an interior door.
 */
fun FloorLayout.wallModesForZone(zone: RoomZone, floor: Int): Map<FeatureSide, WallMode> {
    val p = rooms.firstOrNull { it.floor == floor && it.type == zone.type && mergedZoneFor(it, floor) == zone }
        ?: return emptyMap()
    return wallModesFor(p)
}

/**
 * An extra, user-added copy of an existing [RoomItem] in one specific room instance — e.g. a
 * second sofa in a living room made bigger than the default footprint. [placementId] ties it to
 * the [RoomPlacement] the user was standing in when they added it (any placement in a merged
 * room's group is a valid tag — see [placementIdsInZone]). [dx]/[dz] are room-relative drag
 * offsets, same convention as the flat itemOffsets map used for the original per-type instances.
 */
// Sentinel [PlacedItem.placementId] for cars: they aren't anchored to a room placement but
// to the exterior garage/driveway. Never matches a real placement id, so every per-room
// rendering loop (room view, floor plan, house interiors) skips cars automatically.
const val CAR_PLACEMENT_ID = "vehicles"

// Sentinel [PlacedItem.placementId] for things stashed in the attic — same reasoning as cars:
// the attic is a real place you walk into, but it is never a [RoomPlacement] (it doesn't appear
// on the floor plan at all), so items up there have no room id to hang off. Only keepsakes get
// there today, dragged in from the attic pane's tray.
const val ATTIC_PLACEMENT_ID = "attic"

// Sentinel [PlacedItem.placementId] for things kept in an ATTACHED garage — the wing an Estate or
// Ranch carries, which is geometry outside the footprint rather than a room. Homes whose garage IS
// a room (Townhouse, Classic) never use this: their garage contents hang off that room's own id, so
// the one physical space always has exactly one bucket. See RoomSurroundings.garagePlacementId.
const val GARAGE_PLACEMENT_ID = "garage"

data class PlacedItem(
    val id: String = UUID.randomUUID().toString(),
    val placementId: String,
    val item: RoomItem,
    val dx: Float = 0f,
    val dz: Float = 0f,
    // Orientation — quarter-turn rotation about the item's centre plus mirrors across
    // each floor axis. Only meaningful for furniture (appliance geometry is wall-anchored).
    val rotDeg: Int = 0,
    val flipX: Boolean = false,
    val flipZ: Boolean = false,
    // Vehicles only — body-paint choice (index into VEHICLE_BODY_COLORS) and fuel type, set
    // from the vehicle actions bar. Fuel type also gates the oil-change task for that vehicle
    // type in HomeTaskList once every vehicle of that type is electric.
    val colorIndex: Int = 0,
    val electric: Boolean = false,
)

/**
 * Whether a room [depthM] metres deep has room for at least the mandatory kitchen run —
 * fridge, stove, sink, dishwasher, no counters — that [kitchenRunSlots] lays out. Uses the
 * exact same usable-depth formula [kitchenRunSlots] computes internally, kept in one place so
 * the two can never drift apart. [RoomType.minCellArea]'s generic area tier isn't a strong
 * enough gate on its own — a room can clear it (e.g. a 2x2) while still being far too small for
 * a real appliance run, which [kitchenRunSlots] would otherwise cram in regardless (it only
 * ever drops counters, down to zero, never the mandatory appliances themselves). Takes a plain
 * depth rather than a [RoomZone] so it can gate a candidate room that doesn't exist yet (the
 * room-type picker, working from selected-tile colSpan/rowSpan) as well as a real one.
 */
fun canFitKitchenRun(depthM: Float): Boolean {
    val usable = depthM - WALL_T - 0.1f
    val mandatory = RoomItem.REFRIGERATOR.runWidth + RoomItem.STOVE.runWidth +
        RoomItem.KITCHEN_SINK.runWidth + RoomItem.DISHWASHER.runWidth
    return usable >= mandatory
}

/**
 * The seeded kitchen arrangement: one flush run along the room's left (-X) wall — fridge,
 * counter, stove (the oven's door tucked under its cooktop, hood above), counter, sink,
 * then the dishwasher under its own counter-height top — packed edge-to-edge with no gaps,
 * centred along the wall, every front quarter-turned toward the room. The microwave sits
 * on the first counter. Counters join the run only while it still fits the wall: both in
 * the 6 m-deep model kitchens (Townhouse/Penthouse), just the fridge-side one in the 4 m
 * kitchens (starter/Classic/Ranch/Estate — 3.6 m of pieces on ~3.7 m of usable wall, with
 * the microwave still on it), none in user-shaped kitchens narrower than that (there the
 * microwave perches on the dishwasher's top). Each entry is the item plus its metre delta
 * from the item's frac base — the kitchen fracs are frozen (see [RoomItem.xFrac]) so
 * offsets in existing saves stay valid; COUNTER appears once per run segment.
 */
fun kitchenRunSlots(zone: RoomZone): List<Pair<RoomItem, Pair<Float, Float>>> {
    val usable = zone.d - WALL_T - 0.1f
    val appliancesOnly = RoomItem.entries.filterNot { it == RoomItem.COUNTER }.map { it.runWidth }.sum()
    val counters = ((usable - appliancesOnly) / RoomItem.COUNTER.runWidth).toInt().coerceIn(0, 2)
    val run = buildList {
        add(RoomItem.REFRIGERATOR)
        if (counters >= 1) add(RoomItem.COUNTER)
        add(RoomItem.STOVE)
        if (counters >= 2) add(RoomItem.COUNTER)
        add(RoomItem.KITCHEN_SINK)
        add(RoomItem.DISHWASHER)
    }
    // Back edges all hug the wall face behind a 5 cm reveal; the fridge is 6 cm deeper
    // than the rest, so x is per-item to keep every back edge on the same line.
    fun wallX(depth: Float) = -zone.w / 2f + WALL_T / 2f + 0.05f + depth / 2f
    fun target(ri: RoomItem, x: Float, z: Float) =
        ri to ((x - ri.xFrac * zone.w * 0.38f) to (z - ri.zFrac * zone.d * 0.38f))
    val slots = mutableListOf<Pair<RoomItem, Pair<Float, Float>>>()
    var edge = -run.map { it.runWidth }.sum() / 2f
    var microHost: Pair<Float, Float>? = null
    for (ri in run) {
        val x = wallX(if (ri == RoomItem.REFRIGERATOR) 0.66f else 0.60f)
        val z = edge + ri.runWidth / 2f
        edge += ri.runWidth
        slots += target(ri, x, z)
        when {
            ri == RoomItem.STOVE -> {
                slots += target(RoomItem.OVEN, x, z)        // oven door under the cooktop
                slots += target(RoomItem.RANGE_HOOD, x, z)  // canopy overhead
            }
            ri == RoomItem.COUNTER && microHost == null    -> microHost = x to z
            ri == RoomItem.DISHWASHER && microHost == null -> microHost = x to z
        }
    }
    microHost?.let { (x, z) -> slots += target(RoomItem.MICROWAVE, x, z) }
    return slots
}

private data class FixtureSlot(val item: RoomItem, val dx: Float, val dz: Float, val rotDeg: Int)

/**
 * The seeded bathroom fixtures — toilet backed flush onto a door-free z wall, bathtub
 * tucked into a door-free corner with its spout/shower column at the wall end, vanity at
 * its frac anchor — computed from [wallModesForZone] (the same map the walls render from)
 * so nothing seeds in front of the room's doorway or an open archway (an en-suite's).
 * Emitted as metre deltas from each item's frac base plus a quarter-turn count: the same
 * flush arrangement the old wall-anchored defaults produced, now as ordinary placed items
 * with the full drag/tear/rotate/flip actions. Powder rooms take toilet + vanity only.
 */
private fun FloorLayout.bathroomFixtureSlots(zone: RoomZone, floor: Int): List<FixtureSlot> {
    val doorSides = wallModesForZone(zone, floor)
        .filterValues { it == WallMode.DOOR || it == WallMode.OPEN }.keys
    val w = zone.w
    val d = zone.d
    val backFace  = -d / 2f + WALL_T / 2f
    val rightFace =  w / 2f - WALL_T / 2f
    val slots = mutableListOf<FixtureSlot>()
    fun add(ri: RoomItem, x: Float, z: Float, rot: Int) =
        slots.add(FixtureSlot(ri, x - ri.xFrac * w * 0.38f, z - ri.zFrac * d * 0.38f, rot))

    // Toilet — backs onto the back wall unless that wall carries the opening; the
    // half-turn to the front wall also mirrors it across the room, clearing the vanity.
    val tBack = FeatureSide.BACK !in doorSides || FeatureSide.FRONT in doorSides
    val tx = RoomItem.TOILET.xFrac * w * 0.38f * (if (tBack) 1f else -1f)
    if (tBack) add(RoomItem.TOILET, tx, backFace + 0.36f, 0)
    else       add(RoomItem.TOILET, tx, -backFace - 0.36f, 180)

    // Bathtub — hugs a door-free corner; the tub is x-symmetric, so a half-turn is all
    // it takes to park the spout and shower column against the opposite z wall.
    if (zone.type == RoomType.BATHROOM) {
        val zBack  = FeatureSide.BACK !in doorSides || FeatureSide.FRONT in doorSides
        val xRight = FeatureSide.RIGHT !in doorSides || FeatureSide.LEFT in doorSides
        val bx = if (xRight) rightFace - 0.40f else -rightFace + 0.40f
        val bz = if (zBack) backFace + 0.80f else -backFace - 0.80f
        add(RoomItem.BATHTUB, bx, bz, if (zBack) 0 else 180)
    }

    // Vanity — the bathroom sink, mid-room at its frac anchor, faucet toward the front.
    add(RoomItem.VANITY, RoomItem.VANITY.xFrac * w * 0.38f, RoomItem.VANITY.zFrac * d * 0.38f, 0)
    return slots
}

/**
 * The seeded theater arrangement — a screen against the back (-Z) wall and two rows of seating
 * facing it, computed like [bathroomFixtureSlots] rather than left to the generic pass: no
 * [RoomItem] names [RoomType.HOME_THEATER] as its nominal room (the whole point of [trayRooms]
 * is that a theater borrows sofa/chair/TV-stand pieces whose actual home is elsewhere), so
 * without an explicit slot table a new theater seeds as a bare floor. No door query is needed
 * the way a bathroom's fixtures need one — a theater's screen wall is simply "the back wall",
 * with nothing to dodge.
 *
 * TV_STAND's stand/screen cubes sit at zero local z-offset (see HouseScene's ItemNode), so it
 * reads the same from either face — no rotation needed to make it "face" the seating. SOFA's
 * back cushion, by contrast, sits behind the seat at its default rotDeg=0 (see RoomItem.SOFA's
 * geometry); rotDeg=180 negates both local offsets, swinging the back cushion to the +Z side so
 * the seat faces -Z, toward the screen — the same convention every row below reuses.
 */
private fun theaterSlots(zone: RoomZone): List<FixtureSlot> {
    val w = zone.w
    val d = zone.d
    val backFace = -d / 2f + WALL_T / 2f
    val slots = mutableListOf<FixtureSlot>()
    fun add(ri: RoomItem, x: Float, z: Float, rot: Int) =
        slots.add(FixtureSlot(ri, x - ri.xFrac * w * 0.38f, z - ri.zFrac * d * 0.38f, rot))

    add(RoomItem.TV_STAND, 0f, backFace + 0.30f, 0)

    // Front and back rows, both facing the screen (-Z) — spacing scaled off the room's own
    // depth so a small theater doesn't crowd its rows into each other or the screen.
    val frontZ = backFace + d * 0.42f
    val backZ  = backFace + d * 0.68f
    add(RoomItem.SOFA,         0f,         frontZ, 180)
    add(RoomItem.OFFICE_CHAIR, -w * 0.28f, backZ,  180)
    add(RoomItem.OFFICE_CHAIR,  w * 0.28f, backZ,  180)
    add(RoomItem.COFFEE_TABLE, w * 0.38f,  frontZ, 0)
    return slots
}

/**
 * The furnished starting state for one room — one of each furniture piece and portable
 * appliance (the whole kitchen included, seeded as the left-wall run built by
 * [kitchenRunSlots]) matching [zone]'s type, as drag-and-droppable [PlacedItem]s anchored to
 * the room's representative placement id. Scoped to a single zone so it can reseed one room
 * after a type change/swap, not just the whole floor; [defaultFurniturePlacedItems] below is a
 * thin loop over every zone built on top of this. Returns an empty list if [zone] has no
 * surviving placement to anchor to (shouldn't happen for a zone read from the same layout).
 * [removedInstances] filtering is deliberately NOT done here — callers filter the returned
 * items by `"${it.placementId}:${it.item.name}"`, since a fresh reseed (type change/swap) has
 * no [removedInstances] history to honor for the room's new contents.
 */
fun FloorLayout.defaultFurnitureForZone(
    zone: RoomZone,
    floor: Int,
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    includeItem: (RoomItem) -> Boolean = { it.isFurniture || it.isPortable },
): List<PlacedItem> {
    val repId = placementIdsInZone(zone, floor).minOrNull() ?: return emptyList()
    val runSlots = if (zone.type == RoomType.KITCHEN) kitchenRunSlots(zone) else emptyList()
    // Bathroom fixtures get computed wall-aware slots like the kitchen run does —
    // handled apart from the generic loop because powder rooms borrow the toilet
    // and vanity, whose nominal room never equals the powder zone's type. Theaters are the
    // same story: no RoomItem names HOME_THEATER as its room (see theaterSlots' kdoc), so
    // without this the generic pass below matches nothing and a new theater seeds empty.
    val fixtureSlots = when (zone.type) {
        RoomType.BATHROOM, RoomType.POWDER_ROOM -> bathroomFixtureSlots(zone, floor)
        RoomType.HOME_THEATER                   -> theaterSlots(zone)
        else                                    -> emptyList()
    }
    val generic = RoomItem.entries
        .filter { includeItem(it) && it.room == zone.type }
        // Treasures are user-defined keepsakes — never part of a room's default kit.
        .filterNot { it == RoomItem.TREASURE }
        .filterNot { ri -> fixtureSlots.any { it.item == ri } }
        .flatMap { ri ->
            val (dx, dz) = itemOffsets[ri] ?: (0f to 0f)
            // Kitchens seed every piece onto its run slot (counters once per
            // segment); everything else seeds singly at its frac anchor, unrotated.
            if (zone.type == RoomType.KITCHEN)
                runSlots.filter { it.first == ri }.map { (_, slot) ->
                    PlacedItem(placementId = repId, item = ri,
                        dx = dx + slot.first, dz = dz + slot.second, rotDeg = 90)
                }
            else listOf(PlacedItem(placementId = repId, item = ri, dx = dx, dz = dz))
        }
    val fixtures = fixtureSlots
        .filter { includeItem(it.item) }
        .map { slot ->
            val (dx, dz) = itemOffsets[slot.item] ?: (0f to 0f)
            PlacedItem(placementId = repId, item = slot.item,
                dx = dx + slot.dx, dz = dz + slot.dz, rotDeg = slot.rotDeg)
        }
    return generic + fixtures
}

/**
 * The furnished starting state for the whole home — [defaultFurnitureForZone] applied to every
 * room, floor by floor. Used to seed a newly claimed home, and — via [includeItem] — to
 * migrate saves one item category at a time (v3 converted furniture defaults, v4 the
 * portables): [removedInstances]/[itemOffsets] carry over what the user had hidden or moved
 * under the old default-item scheme.
 */
fun FloorLayout.defaultFurniturePlacedItems(
    removedInstances: Set<String> = emptySet(),
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    includeItem: (RoomItem) -> Boolean = { it.isFurniture || it.isPortable },
): List<PlacedItem> =
    (0..maxFloor()).flatMap { floor ->
        toMergedZones(floor).flatMap { zone ->
            defaultFurnitureForZone(zone, floor, itemOffsets, includeItem)
        }
    }.filterNot { "${it.placementId}:${it.item.name}" in removedInstances }

/**
 * All placement ids that resolve to the same (possibly merged) visual room as [zone] — used to
 * READ every placed item belonging to that room regardless of which constituent placement the
 * user is currently standing in. Writing a new [PlacedItem] only needs one such id (whichever is
 * currently active), since any id in the group maps back to this same zone.
 */
fun FloorLayout.placementIdsInZone(zone: RoomZone, floor: Int): Set<String> =
    rooms.filter { it.floor == floor && mergedZoneFor(it, floor) == zone }.map { it.id }.toSet()

/**
 * How many of each [RoomItem] the room covered by [placementIds] holds — the count the furniture
 * tray badges each icon with, and the one [RoomZone.canFit] measures its floor-area budget
 * against. Takes the id set rather than a [RoomZone] so callers can reuse the
 * [placementIdsInZone] result they already computed.
 *
 * Vehicles are excluded: they hang off [CAR_PLACEMENT_ID] rather than any room placement, and
 * CarLotGeometry's anchor/driveway slot logic already governs how many of them fit.
 */
fun roomItemCounts(placedItems: List<PlacedItem>, placementIds: Set<String>): Map<RoomItem, Int> =
    placedItems.filter { it.placementId in placementIds && !it.item.isVehicle }
        .groupingBy { it.item }.eachCount()

/**
 * Drops any [items] whose [PlacedItem.placementId] no longer matches a surviving
 * [RoomPlacement] — orphaned by [removeRoom]/[shrinkCols]/[shrinkRows]/[removeTopFloor], all of
 * which can delete a placement without knowing about placedItems (separate state, held by the
 * caller). Call after any mutation that can delete a room. Vehicles ([CAR_PLACEMENT_ID]), anything
 * in the attic ([ATTIC_PLACEMENT_ID]) and anything in an attached garage ([GARAGE_PLACEMENT_ID])
 * are never room-anchored, so they always survive — without that, deleting any room would silently
 * take every keepsake in the attic and every tool in the garage with it, since no surviving
 * placement claims them.
 */
private val NON_ROOM_PLACEMENT_IDS =
    setOf(CAR_PLACEMENT_ID, ATTIC_PLACEMENT_ID, GARAGE_PLACEMENT_ID)

/**
 * The same split, but handing back what was about to be lost as well as what survives — because
 * deleting a room should not be able to delete the user's own belongings.
 *
 * Deciding WHICH orphans are worth keeping needs the maintenance DB (does this one carry an install
 * year or a document?), which core-model has no access to, so the policy lives at the call site and
 * this only reports the facts. See MainActivity's `rescueOrphans`: keepsakes and anything with
 * tracked history are re-homed into the attic, and only genuinely anonymous furniture is dropped.
 */
data class OrphanSweep(val kept: List<PlacedItem>, val orphaned: List<PlacedItem>)

fun FloorLayout.sweepOrphanedPlacedItems(items: List<PlacedItem>): OrphanSweep {
    val validIds = rooms.map { it.id }.toSet()
    val (kept, orphaned) = items.partition {
        it.placementId in NON_ROOM_PLACEMENT_IDS || it.placementId in validIds
    }
    return OrphanSweep(kept, orphaned)
}

/** True for a placement id that names a space outside the floor plan — the lot, the attic, an
 *  attached garage. Those belong to the OWNER rather than to the claimed model's room layout, so
 *  they survive a floor-plan edit (above) and a model claim alike (see claimNeighbor). */
fun isNonRoomPlacement(placementId: String): Boolean = placementId in NON_ROOM_PLACEMENT_IDS

/**
 * One stable key per (possibly merged) zone of [type], across every floor — used to give each
 * auto-populated "default" item instance a per-room identity, since [RoomZone] itself has no id.
 * Zones are recomputed from the layout every render, so the key anchors on the lexicographically
 * smallest constituent [RoomPlacement.id] (stable across renders, and across merges/un-merges as
 * long as at least one original placement survives).
 */
fun FloorLayout.defaultInstanceKeys(type: RoomType): List<String> =
    (0..maxFloor()).flatMap { floor ->
        toMergedZones(floor).filter { it.type == type }
            .mapNotNull { zone -> placementIdsInZone(zone, floor).minOrNull() }
    }

fun defaultRooms(): List<RoomPlacement> = listOf(
    // Grid: 8 cols × 7 rows, cellW=2.0m, cellD=2.0m.
    // H-shaped corridor network: left spine (col 2), right spine (col 5), horizontal bridge (row 2, cols 3-4).
    // colWidths() collapses cols 2 and 5 to 1m (vertical spines).
    // rowDepths() collapses row 2 to 1m (horizontal bridge).
    // LAUNDRY at row=5 so addFloor() converts it to STAIRCASE at scRow=5.

    // ── Corridor infrastructure — non-enterable ──────────────────────────────
    RoomPlacement(type = RoomType.HALLWAY, col = 2, row = 0, colSpan = 1, rowSpan = 7),  // left vertical spine
    RoomPlacement(type = RoomType.HALLWAY, col = 5, row = 0, colSpan = 1, rowSpan = 7),  // right vertical spine
    RoomPlacement(type = RoomType.HALLWAY, col = 3, row = 2, colSpan = 2, rowSpan = 1),  // horizontal bridge (collapses row 2 to 1m)

    // ── Left zone (cols 0-1) ─────────────────────────────────────────────────
    RoomPlacement(type = RoomType.FOYER,       col = 0, row = 0, colSpan = 2, rowSpan = 3),  // 4m × 5m, entry — spans through corridor row
    RoomPlacement(type = RoomType.KITCHEN,     col = 0, row = 3, colSpan = 2, rowSpan = 2),  // 4m × 4m
    RoomPlacement(type = RoomType.DINING_ROOM, col = 0, row = 5, colSpan = 2, rowSpan = 2),  // 4m × 4m, open to kitchen above

    // ── Mid zone (cols 3-4) ──────────────────────────────────────────────────
    RoomPlacement(type = RoomType.OFFICE,      col = 3, row = 0, colSpan = 2, rowSpan = 2),  // 4m × 4m, study/office
    RoomPlacement(type = RoomType.LIVING_ROOM, col = 3, row = 3, colSpan = 2, rowSpan = 2),  // 4m × 4m
    RoomPlacement(type = RoomType.BEDROOM,     col = 3, row = 5, colSpan = 2, rowSpan = 2),  // 4m × 4m, master bedroom

    // ── Right zone (cols 6-7) ────────────────────────────────────────────────
    RoomPlacement(type = RoomType.BATHROOM,     col = 6, row = 0, colSpan = 1, rowSpan = 2),  // 2m × 4m, guest bath
    RoomPlacement(type = RoomType.STORAGE,      col = 7, row = 0, colSpan = 1, rowSpan = 2),  // 2m × 4m, coat closet
    RoomPlacement(type = RoomType.HOME_THEATER, col = 6, row = 3, colSpan = 2, rowSpan = 2),  // 4m × 4m
    RoomPlacement(type = RoomType.LAUNDRY,      col = 6, row = 5, colSpan = 1, rowSpan = 2),  // row=5 → staircase on addFloor()
    RoomPlacement(type = RoomType.POWDER_ROOM,  col = 7, row = 5, colSpan = 1, rowSpan = 2),
)
