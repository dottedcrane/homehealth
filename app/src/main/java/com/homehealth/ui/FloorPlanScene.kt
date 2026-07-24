package com.homerenderer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.homerenderer.model.*
import com.homerenderer.renderspec.FloorPlanBoxNode
import com.homerenderer.renderspec.FloorPlanMaterialSlot
import com.homerenderer.renderspec.FloorPlanSceneGeometry
import com.homerenderer.renderspec.HouseSceneGeometry
import com.homerenderer.scene.FLOOR_HEIGHT_M
import com.homerenderer.scene.ITEM_SCALE_REFERENCE
import com.homerenderer.scene.WALL_T
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode as CubeNodeImpl

// ── Floor-plan view ───────────────────────────────────────────────────────────
// Camera positioned above, looking straight down — no roof rendered.
// Interior walls are generated dynamically from the FloorLayout grid:
//   • Same placement on both sides of a boundary → no wall (spanning room)
//   • Different placements → interior wall with centred door gap
//   • One side empty or grid edge → exterior wall (no gap)
// HALLWAY placements collapse to a thin divider strip (HALLWAY_SIZE) rather than
// consuming a full-width column or full-depth row.
//
// Walls/pillars/corridor/floor-tile geometry (everything except furniture) is computed
// portably in :core-render-spec by FloorPlanSceneGeometry and mapped to CubeNode calls here.

@Composable
fun SceneScope.FloorPlanScene(
    layout: FloorLayout,
    currentFloor: Int = 0,
    homeStyle: HomeStyle = HomeStyle.HOUSE,
    featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    placedDecks: List<PlacedDeck> = emptyList(),
    removedInstances: Set<String> = emptySet(),
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    placedItems: List<PlacedItem> = emptyList(),
    selectedCells: Set<Pair<Int, Int>> = emptySet(),
    onTapPlacement: (RoomPlacement) -> Unit = {},
    onLongPressPlacement: (RoomPlacement) -> Unit = {},
    onTapEmptyCell: (col: Int, row: Int) -> Unit = { _, _ -> },
    onWallTap: (WallKey, WallMode) -> Unit = { _, _ -> },
    // Tapping the attached garage slab steps into the garage scene, like tapping a room.
    onGarageTap: () -> Unit = {},
) {
    val w = layout.totalW
    val d = layout.totalD
    val pwz = layout.toPlacementsAndZones(currentFloor)
    // TOWNHOUSE's interior garage room, real-world positioned — same source of truth the 3D
    // exterior view reads (HouseSceneGeometry.townhouseGarageBox) so the door mark drawn on
    // the wall below lines up with the actual 3D door exactly.
    val townhouseGarageBox = if (homeStyle == HomeStyle.TOWNHOUSE)
        HouseSceneGeometry.townhouseGarageBox(layout) else null

    val slabMat      = remember { materialLoader.createColorInstance(colorOf(0.72f, 0.70f, 0.67f)) }
    val outerWallMat = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.92f, 0.82f)) }
    val innerWallMat = remember { materialLoader.createColorInstance(colorOf(0.80f, 0.78f, 0.72f)) }
    val emptyCellMat = remember { materialLoader.createColorInstance(colorOf(0.86f, 0.84f, 0.82f)) }
    val selectedCellMat = remember { materialLoader.createColorInstance(colorOf(0.45f, 0.75f, 0.55f)) }
    val roomFloorMats = remember {
        mapOf(
            RoomType.LIVING_ROOM  to materialLoader.createColorInstance(colorOf(0.96f, 0.86f, 0.66f)),
            RoomType.KITCHEN      to materialLoader.createColorInstance(colorOf(0.76f, 0.93f, 0.70f)),
            RoomType.DINING_ROOM  to materialLoader.createColorInstance(colorOf(0.96f, 0.82f, 0.66f)),
            RoomType.HALLWAY      to materialLoader.createColorInstance(colorOf(0.83f, 0.83f, 0.83f)),
            RoomType.FOYER        to materialLoader.createColorInstance(colorOf(0.94f, 0.91f, 0.84f)),
            RoomType.STAIRCASE    to materialLoader.createColorInstance(colorOf(0.70f, 0.70f, 0.72f)),
            RoomType.BEDROOM      to materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.96f)),
            RoomType.BATHROOM     to materialLoader.createColorInstance(colorOf(0.68f, 0.85f, 0.96f)),
            RoomType.LAUNDRY      to materialLoader.createColorInstance(colorOf(0.80f, 0.88f, 0.92f)),
            RoomType.OFFICE       to materialLoader.createColorInstance(colorOf(0.72f, 0.82f, 0.88f)),
            RoomType.PANTRY       to materialLoader.createColorInstance(colorOf(0.95f, 0.93f, 0.75f)),
            RoomType.STORAGE      to materialLoader.createColorInstance(colorOf(0.75f, 0.75f, 0.73f)),
            RoomType.GYM          to materialLoader.createColorInstance(colorOf(0.78f, 0.90f, 0.76f)),
            RoomType.HOME_THEATER to materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)),
            RoomType.POWDER_ROOM  to materialLoader.createColorInstance(colorOf(0.78f, 0.90f, 0.96f)),
            RoomType.GARAGE       to materialLoader.createColorInstance(colorOf(0.62f, 0.60f, 0.56f)),
        )
    }
    val sofaMat         = remember { materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.58f)) }
    val woodItemMat     = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.35f, 0.18f)) }
    val applianceMat    = remember { materialLoader.createColorInstance(colorOf(0.85f, 0.85f, 0.88f)) }
    val steelFpMat      = remember { materialLoader.createColorInstance(colorOf(0.45f, 0.47f, 0.50f)) }
    val fixtureMat      = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.95f, 0.95f)) }
    val fabricMat       = remember { materialLoader.createColorInstance(colorOf(0.65f, 0.80f, 0.92f)) }
    val screenMat       = remember { materialLoader.createColorInstance(colorOf(0.08f, 0.08f, 0.10f)) }
    val sinkFpMat       = remember { materialLoader.createColorInstance(colorOf(0.20f, 0.50f, 0.85f)) }
    val dishwasherFpMat = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.65f, 0.60f)) }
    val bathtubRimFpMat = remember { materialLoader.createColorInstance(colorOf(0.96f, 0.96f, 0.96f)) }
    val bathtubBsnFpMat = remember { materialLoader.createColorInstance(colorOf(0.35f, 0.65f, 0.90f)) }
    val deckFpMat     = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.35f, 0.18f)) }
    val poolCopeFpMat = remember { materialLoader.createColorInstance(colorOf(0.82f, 0.80f, 0.76f)) }
    val poolWaterFpMat= remember { materialLoader.createColorInstance(colorOf(0.05f, 0.50f, 0.88f)) }
    val doorMarkMat   = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.28f, 0.08f)) }
    val windowMat     = remember { materialLoader.createColorInstance(colorOf(0.40f, 0.68f, 0.92f)) }
    val openWallMat   = remember { materialLoader.createColorInstance(colorOf(0.74f, 0.72f, 0.68f)) }
    val corridorMat   = remember { materialLoader.createColorInstance(colorOf(0.78f, 0.76f, 0.72f)) }
    // Depth cue for the open stairwell of a flight arriving from the floor below —
    // treads fade darker the further they descend.
    val stairDownMats = remember { List(4) { i ->
        materialLoader.createColorInstance(colorOf(0.52f - i * 0.12f, 0.52f - i * 0.12f, 0.54f - i * 0.12f)) } }
    DisposableEffect(Unit) {
        onDispose {
            engine.destroyMaterialInstance(slabMat)
            engine.destroyMaterialInstance(outerWallMat)
            engine.destroyMaterialInstance(innerWallMat)
            engine.destroyMaterialInstance(emptyCellMat)
            roomFloorMats.values.forEach { engine.destroyMaterialInstance(it) }
            listOf(sofaMat, woodItemMat, applianceMat, steelFpMat, fixtureMat, fabricMat, screenMat,
                   sinkFpMat, dishwasherFpMat, bathtubRimFpMat, bathtubBsnFpMat,
                   deckFpMat, poolCopeFpMat, poolWaterFpMat,
                   doorMarkMat, windowMat, openWallMat, corridorMat)
                .forEach { engine.destroyMaterialInstance(it) }
            stairDownMats.forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Maps a portable FloorPlanSceneGeometry node list to actual CubeNode calls. Wall/empty-cell
    // nodes carry their OWN tap identity (see FloorPlanBoxNode's kdoc) and are individually
    // key()-wrapped here; [onTap]/[onTapLongPress] apply only to the shared-closure "tappable"
    // nodes (room tiles, staircase treads, garage) — see the "one call per target" invariant
    // this relies on, same as NeighborhoodScene's emitNeighborhoodNodes.
    @Composable
    fun SceneScope.emitFloorPlanNodes(
        nodes: List<FloorPlanBoxNode>,
        onTap: () -> Unit = {},
        onTapLongPress: (() -> Unit)? = null,
    ) {
        nodes.forEach { n ->
            val mat = when (n.material) {
                FloorPlanMaterialSlot.SLAB            -> slabMat
                FloorPlanMaterialSlot.OUTER_WALL      -> outerWallMat
                FloorPlanMaterialSlot.INNER_WALL      -> innerWallMat
                FloorPlanMaterialSlot.EMPTY_CELL      -> emptyCellMat
                FloorPlanMaterialSlot.SELECTED_CELL   -> selectedCellMat
                FloorPlanMaterialSlot.ROOM_FLOOR      -> roomFloorMats[n.roomType] ?: slabMat
                FloorPlanMaterialSlot.DOOR_MARK       -> doorMarkMat
                FloorPlanMaterialSlot.WINDOW          -> windowMat
                FloorPlanMaterialSlot.OPEN_WALL       -> openWallMat
                FloorPlanMaterialSlot.CORRIDOR        -> corridorMat
                FloorPlanMaterialSlot.DECK            -> deckFpMat
                FloorPlanMaterialSlot.POOL_COPING     -> poolCopeFpMat
                FloorPlanMaterialSlot.POOL_WATER      -> poolWaterFpMat
                FloorPlanMaterialSlot.STAIR_TREAD     -> woodItemMat
                FloorPlanMaterialSlot.STAIR_RAIL      -> steelFpMat
                FloorPlanMaterialSlot.STAIR_WELL_FLOOR-> screenMat
                FloorPlanMaterialSlot.STAIR_DOWN_TREAD-> stairDownMats[n.materialVariant]
            }
            val wk = n.wallKey
            val ec = n.emptyCell
            when {
                wk != null -> key(wk) {
                    CubeNode(size = Size(n.size.x, n.size.y, n.size.z), materialInstance = mat,
                        position = Position(n.position.x, n.position.y, n.position.z),
                        apply = { onSingleTapConfirmed = { onWallTap(wk, n.wallMode!!); true } }) {}
                }
                ec != null -> key(currentFloor, ec.first, ec.second) {
                    CubeNode(size = Size(n.size.x, n.size.y, n.size.z), materialInstance = mat,
                        position = Position(n.position.x, n.position.y, n.position.z),
                        apply = { onSingleTapConfirmed = { onTapEmptyCell(ec.first, ec.second); true } }) {}
                }
                n.tappable -> CubeNode(size = Size(n.size.x, n.size.y, n.size.z), materialInstance = mat,
                    position = Position(n.position.x, n.position.y, n.position.z),
                    apply = {
                        onSingleTapConfirmed = { onTap(); true }
                        if (onTapLongPress != null) onLongPress = { onTapLongPress() }
                    }) {}
                else -> CubeNode(size = Size(n.size.x, n.size.y, n.size.z), materialInstance = mat,
                    position = Position(n.position.x, n.position.y, n.position.z)) {}
            }
        }
    }

    val ht    = FLOOR_HEIGHT_M
    val wt    = WALL_T
    val hHt   = ht / 2f
    val doorW = 0.95f
    val sdW   = (w * 0.36f).coerceIn(1.2f, 2.2f)
    val sdD   = (d * 0.36f).coerceIn(1.2f, 2.2f)
    val sdFace: FeatureSide? = if (currentFloor == 0) placedDecks.firstOrNull()?.side ?: FeatureSide.BACK else null
    // Front door lands in the foyer if there is one, otherwise the hallway.
    val hwZone = layout.toZones(currentFloor).let { zs ->
        zs.find { it.type == RoomType.FOYER } ?: zs.find { it.type == RoomType.HALLWAY }
    }
    val hwCx   = hwZone?.cx ?: 0f
    val hwCz   = hwZone?.cz ?: 0f
    val topY  = ht + 0.02f
    val extY  = wt / 2f + 0.02f
    val ox    = -w / 2f
    val oz    =  d / 2f

    // Per-column widths and per-row depths; HALLWAY placements collapse to HALLWAY_SIZE.
    val colW   = layout.colWidths()
    val rowD   = layout.rowDepths()
    // Cumulative boundary arrays in world space.
    val xBound = FloatArray(layout.gridCols + 1).also { arr ->
        arr[0] = ox
        for (c in 0 until layout.gridCols) arr[c + 1] = arr[c] + colW[c]
    }
    val zBound = FloatArray(layout.gridRows + 1).also { arr ->
        arr[0] = oz
        for (r in 0 until layout.gridRows) arr[r + 1] = arr[r] - rowD[r]
    }

    // Sliding door: pick the best room at the target wall face rather than centering on the hallway.
    // Priority: living room / dining room > bedroom > office > any non-utility room.
    val sdExclude = setOf(RoomType.HALLWAY, RoomType.STAIRCASE, RoomType.BATHROOM, RoomType.POWDER_ROOM, RoomType.STORAGE)
    val sdOrder   = listOf(RoomType.LIVING_ROOM, RoomType.DINING_ROOM, RoomType.BEDROOM, RoomType.OFFICE)
    fun bestWallRoom(candidates: List<RoomPlacement>): RoomPlacement? {
        val ok = candidates.filter { it.type !in sdExclude }
        for (t in sdOrder) ok.firstOrNull { it.type == t }?.let { return it }
        return ok.firstOrNull()
    }
    val sdCxBack: Float = bestWallRoom(
        layout.rooms.filter { it.floor == currentFloor && it.row + it.rowSpan == layout.gridRows }
    )?.let { (xBound[it.col] + xBound[it.col + it.colSpan]) / 2f } ?: hwCx
    val sdCzLR: Float = when (sdFace) {
        FeatureSide.LEFT  -> bestWallRoom(layout.rooms.filter { it.floor == currentFloor && it.col == 0 })
            ?.let { (zBound[it.row] + zBound[it.row + it.rowSpan]) / 2f } ?: hwCz
        FeatureSide.RIGHT -> bestWallRoom(layout.rooms.filter { it.floor == currentFloor && it.col + it.colSpan == layout.gridCols })
            ?.let { (zBound[it.row] + zBound[it.row + it.rowSpan]) / 2f } ?: hwCz
        else -> hwCz
    }

    // ── Ground slab ───────────────────────────────────────────────────────────
    emitFloorPlanNodes(FloorPlanSceneGeometry.groundSlab(w, d, wt))

    // ── Exterior walls — per-cell segments, tappable for window placement ─────
    val outerOverrides = layout.wallOverrides
    // If the sliding door gap would cut into a back-wall bedroom or bathroom, fall back to regular door width.
    val enclosedTypes = setOf(RoomType.BEDROOM, RoomType.BATHROOM, RoomType.POWDER_ROOM)
    val backDoorW: Float = if (sdFace == FeatureSide.BACK &&
        layout.rooms.any { r ->
            r.floor == currentFloor && r.row + r.rowSpan == layout.gridRows &&
            r.type in enclosedTypes &&
            sdCxBack - sdW / 2f < xBound[r.col + r.colSpan] &&
            sdCxBack + sdW / 2f > xBound[r.col]
        }) doorW else sdW

    // Gap bounds: Float sentinels produce no overlap when no gap on that face.
    // Upper floors have no exterior entry points — front and sliding-door gaps are inactive.
    val frontGapL = if (currentFloor == 0) hwCx - doorW / 2f else  Float.MAX_VALUE
    val frontGapR = if (currentFloor == 0) hwCx + doorW / 2f else -Float.MAX_VALUE
    val backGapL  = if (sdFace == FeatureSide.BACK)  sdCxBack - backDoorW / 2f else  Float.MAX_VALUE
    val backGapR  = if (sdFace == FeatureSide.BACK)  sdCxBack + backDoorW / 2f else -Float.MAX_VALUE
    val lrGapBtm  = if (sdFace == FeatureSide.LEFT || sdFace == FeatureSide.RIGHT) sdCzLR - sdD / 2f else  Float.MAX_VALUE
    val lrGapTop  = if (sdFace == FeatureSide.LEFT || sdFace == FeatureSide.RIGHT) sdCzLR + sdD / 2f else -Float.MAX_VALUE

    emitFloorPlanNodes(FloorPlanSceneGeometry.exteriorWalls(
        layout, currentFloor, w, d, ht, wt, hHt, topY, colW, rowD, xBound, zBound,
        outerOverrides, frontGapL, frontGapR, backGapL, backGapR, lrGapBtm, lrGapTop, sdFace,
    ))

    // Door markers — ground floor only (upper floors have no exterior entry points)
    emitFloorPlanNodes(FloorPlanSceneGeometry.doorMarkers(
        currentFloor, doorW, wt, hwCx, topY, d, w, sdFace, backDoorW, sdCxBack, sdD, sdCzLR,
    ))

    // ── Exterior features — ground floor only (no deck/garage/pool on upper floors) ──────────
    emitFloorPlanNodes(
        FloorPlanSceneGeometry.exteriorFeatures(currentFloor, w, d, extY, featurePlacements, placedDecks, townhouseGarageBox, topY, wt),
        onTap = onGarageTap,
    )

    // ── Dynamic interior walls ────────────────────────────────────────────────
    // Wall modes come from the shared effectiveWallModes() map — the same source RoomScene
    // reads via wallModesFor — so what you see from above is exactly what you see standing
    // inside the room, including any wall/door overrides.
    val effModes = layout.effectiveWallModes(currentFloor)
    emitFloorPlanNodes(FloorPlanSceneGeometry.interiorWalls(
        layout, currentFloor, ht, wt, hHt, topY, doorW, colW, rowD, xBound, zBound,
        effModes, frontGapL, frontGapR, backGapL, backGapR, lrGapBtm, lrGapTop, sdFace,
    ))

    // ── Structural pillars — minimal set: room corners not carried by any wall ──
    emitFloorPlanNodes(FloorPlanSceneGeometry.pillars(layout, currentFloor, ht))

    // ── Empty grid cells — tappable to add a room ─────────────────────────────
    emitFloorPlanNodes(FloorPlanSceneGeometry.emptyCells(
        layout, currentFloor, colW, rowD, xBound, zBound, selectedCells, wt,
    ))

    // ── Room zone floors — coloured and tappable ──────────────────────────────
    val structural = setOf(RoomType.HALLWAY, RoomType.STAIRCASE, RoomType.FOYER)

    // L-shaped corridor cells: horizontal branch from each isolated room to the hallway column,
    // plus vertical extension in the hallway column when the room falls outside the hallway row range.
    val corridorCells = mutableSetOf<Pair<Int, Int>>()
    val hwRoom = layout.rooms.firstOrNull { it.floor == currentFloor && it.type == RoomType.HALLWAY }
    if (hwRoom != null) {
        val hwColMin = hwRoom.col
        val hwColMax = hwRoom.col + hwRoom.colSpan
        val hwRowMin = hwRoom.row
        val hwRowMax = hwRoom.row + hwRoom.rowSpan
        val dirs4 = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)
        layout.rooms.filter { it.floor == currentFloor && it.type !in structural }.forEach { p ->
            val touchesHallway = (p.col until p.col + p.colSpan).any { c ->
                (p.row until p.row + p.rowSpan).any { r ->
                    dirs4.any { (dc, dr) ->
                        layout.placementAt(c + dc, r + dr, currentFloor)?.type == RoomType.HALLWAY
                    }
                }
            }
            if (touchesHallway) return@forEach
            val connectRow = (p.row until p.row + p.rowSpan).minByOrNull { r ->
                if (r in hwRowMin until hwRowMax) 0
                else minOf(kotlin.math.abs(r - hwRowMin), kotlin.math.abs(r - (hwRowMax - 1)))
            } ?: p.row
            when {
                p.col + p.colSpan <= hwColMin ->
                    for (c in p.col + p.colSpan until hwColMin)
                        if (layout.placementAt(c, connectRow, currentFloor) == null) corridorCells += c to connectRow
                p.col >= hwColMax ->
                    for (c in hwColMax until p.col)
                        if (layout.placementAt(c, connectRow, currentFloor) == null) corridorCells += c to connectRow
            }
            val vCol = if (p.col >= hwColMax) hwColMax - 1 else hwColMin
            when {
                connectRow < hwRowMin ->
                    for (r in connectRow until hwRowMin)
                        if (layout.placementAt(vCol, r, currentFloor) == null) corridorCells += vCol to r
                connectRow >= hwRowMax ->
                    for (r in hwRowMax..connectRow)
                        if (layout.placementAt(vCol, r, currentFloor) == null) corridorCells += vCol to r
            }
        }
    }

    val insX = layout.cellW * 0.05f
    val insZ = layout.cellD * 0.05f
    // Each placement's render block is keyed on its stable `id` — CubeNode's `apply`
    // lambda (which wires onSingleTapConfirmed) only runs once, at node creation.
    // Without a key, Compose's slot table reuses nodes by call order, so adding/
    // removing a room (which shifts how many placements precede this one) or
    // switching floors would leave a node's tap handler bound to whichever
    // placement happened to occupy this slot when the node was first created —
    // e.g. tapping a floor-3 room could fire the "remove" callback for a stale
    // floor-2 placement. Keying by id forces a fresh node whenever the room
    // actually rendered at this position changes.
    pwz.forEach { (placement, zone) -> key(placement.id) {
        emitFloorPlanNodes(
            FloorPlanSceneGeometry.roomTile(
                placement, zone, layout, currentFloor, insX, insZ, corridorCells,
                xBound, zBound, rowD, colW, wt, ht,
            ),
            onTap = { onTapPlacement(placement) },
            onTapLongPress = { onLongPressPlacement(placement) },
        )
    } }

    // Corridor strip rendering — direction-aware cross arms in each corridor cell
    emitFloorPlanNodes(FloorPlanSceneGeometry.corridorStrips(
        corridorCells, layout, currentFloor, colW, rowD, xBound, zBound, wt,
    ))

    // ── Inventory items — use merged zones so same-type adjacent rooms share one item set ──
    // Does this zone's -X wall or -Z wall carry a sliding glass door? Used to keep the
    // kitchen counter/appliances from being rendered on top of the door.
    fun overlaps1D(aMin: Float, aMax: Float, bMin: Float, bMax: Float) = aMin < bMax && aMax > bMin
    fun kitchenWallBlocks(zone: RoomZone): Pair<Boolean, Boolean> {
        val leftBlocked = sdFace == FeatureSide.LEFT &&
            zone.cx - zone.w / 2f <= ox + 0.05f &&
            overlaps1D(zone.cz - zone.d / 2f, zone.cz + zone.d / 2f, lrGapBtm, lrGapTop)
        val backBlocked = sdFace == FeatureSide.BACK &&
            zone.cz - zone.d / 2f <= -d / 2f + 0.05f &&
            overlaps1D(zone.cx - zone.w / 2f, zone.cx + zone.w / 2f, backGapL, backGapR)
        return leftBlocked to backBlocked
    }

    val mergedZones = layout.toMergedZones(currentFloor)
    mergedZones.forEach { zone ->
        if (zone.type == RoomType.HALLWAY || zone.type == RoomType.FOYER) return@forEach
        if (zone.type == RoomType.STAIRCASE) return@forEach
        val scale = (minOf(zone.w, zone.d) / ITEM_SCALE_REFERENCE).coerceAtMost(1.0f)
        val (leftBlocked, backBlocked) = if (zone.type == RoomType.KITCHEN)
            kitchenWallBlocks(zone) else false to false
        val wallModes = if (zone.type == RoomType.KITCHEN) layout.wallModesForZone(zone, currentFloor) else emptyMap()
        val leftDoor  = (if (leftBlocked) wallModes[FeatureSide.RIGHT] else wallModes[FeatureSide.LEFT]) == WallMode.DOOR
        val backDoor  = (if (backBlocked) wallModes[FeatureSide.FRONT] else wallModes[FeatureSide.BACK]) == WallMode.DOOR
        @Composable
        fun itemNode(item: RoomItem) {
            // Same drag offsets the room view applies, so a moved sofa reads consistently here.
            val (offX, offZ) = itemOffsets[item] ?: (0f to 0f)
            ItemNode(
                item            = item,
                cx              = zone.cx + item.xFrac * zone.w * 0.38f + offX,
                cz              = zone.cz + item.zFrac * zone.d * 0.38f + offZ,
                sofaMat         = sofaMat,
                woodMat         = woodItemMat,
                applMat         = applianceMat,
                steelMat        = steelFpMat,
                fixMat          = fixtureMat,
                fabMat          = fabricMat,
                screenMat       = screenMat,
                sinkMat         = sinkFpMat,
                dishwasherMat   = dishwasherFpMat,
                bathtubRimMat   = bathtubRimFpMat,
                bathtubBasinMat = bathtubBsnFpMat,
                scale           = scale,
                zoneW           = zone.w,
                zoneD           = zone.d,
                zoneCx          = zone.cx,
                zoneCz          = zone.cz,
                interactive     = false,
                leftWallBlocked = leftBlocked,
                backWallBlocked = backBlocked,
                leftWallDoor    = leftDoor,
                backWallDoor    = backDoor,
            )
        }
        val placementIds = layout.placementIdsInZone(zone, currentFloor)
        val repId = placementIds.minOrNull() ?: ""
        // TOWNHOUSE's canonical garage room already has a real door mark on its exterior wall
        // (above) — its decorative GARAGE_DOOR default item would float redundantly inside the
        // tile, same reasoning as HouseScene's exterior "read-through-glass" render.
        val skipGarageDoorItem = zone.type == RoomType.GARAGE && townhouseGarageBox != null
        zone.type.defaultItems().filter { it != RoomItem.GARAGE_DOOR || !skipGarageDoorItem }
            .filter { "$repId:${it.name}" !in removedInstances }.forEach { itemNode(it) }

        // Placed furniture (and legacy extra copies) belonging to this (possibly merged)
        // room. Keyed on identity + orientation so a rotate/flip rebuilds the nodes
        // instead of morphing geometry in place.
        placedItems.filter { it.placementId in placementIds }.forEach { pi ->
            key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
            ItemNode(
                item            = pi.item,
                cx              = zone.cx + pi.item.xFrac * zone.w * 0.38f + pi.dx,
                cz              = zone.cz + pi.item.zFrac * zone.d * 0.38f + pi.dz,
                sofaMat         = sofaMat,
                woodMat         = woodItemMat,
                applMat         = applianceMat,
                steelMat        = steelFpMat,
                fixMat          = fixtureMat,
                fabMat          = fabricMat,
                screenMat       = screenMat,
                sinkMat         = sinkFpMat,
                dishwasherMat   = dishwasherFpMat,
                bathtubRimMat   = bathtubRimFpMat,
                bathtubBasinMat = bathtubBsnFpMat,
                scale           = scale,
                // Freestanding for furniture/portables — see RoomScene's placed-item note.
                zoneW           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.w,
                zoneD           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.d,
                zoneCx          = zone.cx,
                zoneCz          = zone.cz,
                interactive     = false,
                rotDeg          = pi.rotDeg,
                flipX           = pi.flipX,
                flipZ           = pi.flipZ,
            )
            }
        }
    }
}
