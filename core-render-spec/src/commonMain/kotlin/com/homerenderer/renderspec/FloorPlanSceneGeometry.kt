package com.homerenderer.renderspec

import com.homerenderer.model.FeatureSide
import com.homerenderer.model.FloorLayout
import com.homerenderer.model.HomeFeature
import com.homerenderer.model.PlacedDeck
import com.homerenderer.model.RoomPlacement
import com.homerenderer.model.RoomType
import com.homerenderer.model.RoomZone
import com.homerenderer.model.WallKey
import com.homerenderer.model.WallMode
import com.homerenderer.model.pillarPositions

enum class FloorPlanMaterialSlot {
    SLAB, OUTER_WALL, INNER_WALL, EMPTY_CELL, SELECTED_CELL, ROOM_FLOOR,
    DOOR_MARK, WINDOW, OPEN_WALL, CORRIDOR, DECK, POOL_COPING, POOL_WATER,
    STAIR_TREAD, STAIR_RAIL, STAIR_WELL_FLOOR, STAIR_DOWN_TREAD,
}

data class FloorPlanBoxNode(
    val size: Vec3,
    val position: Vec3,
    val material: FloorPlanMaterialSlot,
    // ROOM_FLOOR only — composable looks up roomFloorMats[roomType].
    val roomType: RoomType? = null,
    // STAIR_DOWN_TREAD depth index (0 until STAIR_DOWN_VARIANTS), mirrors NeighborhoodBoxNode's use.
    val materialVariant: Int = 0,
    // Wall segments: EACH node carries its own tap identity — unlike every other case in this
    // file, one exteriorWalls()/interiorWalls() call emits many nodes that each need a
    // DIFFERENT WallKey (segment col=3 routes differently than col=5). The composable wraps
    // each node individually: key(node.wallKey) { CubeNode(...) }.
    val wallKey: WallKey? = null,
    val wallMode: WallMode? = null,
    // Empty grid cells: same reasoning as walls — one call, many cells, each its own (col,row).
    // The composable wraps each individually: key(currentFloor, col, row) { CubeNode(...) }.
    val emptyCell: Pair<Int, Int>? = null,
    // Room tiles, staircase treads, garage: every tappable node within one call shares ONE
    // closure, exactly like NeighborhoodBoxNode.tappable — callers must invoke one geometry
    // function per placement/target and wire each call's own result, never batch multiple
    // targets' nodes into one list before mapping.
    val tappable: Boolean = false,
)

/** Number of depth-fade materials the composable's stairDownMats list must provide. */
const val STAIR_DOWN_VARIANTS = 4

/**
 * Pure geometry for the floor-plan (top-down) view: ground slab, exterior/interior walls with
 * door/window openings, exterior features (deck/garage/pool), structural pillars, empty-cell
 * tap targets, corridor strips, and per-placement room tiles (including the staircase) —
 * engine-agnostic (no io.github.sceneview/com.google.android.filament imports). Deliberately
 * excludes the inventory/ItemNode section: furniture rendering is a whole shared composable,
 * not pure geometry, already used identically by RoomScene.kt/HouseScene.kt — extracting it is
 * a cross-cutting change out of scope here (same rationale RoomSceneGeometry used to exclude
 * furniture from Phase 2's extraction).
 */
object FloorPlanSceneGeometry {

    fun groundSlab(w: Float, d: Float, wt: Float): List<FloorPlanBoxNode> =
        listOf(FloorPlanBoxNode(Vec3(w, wt, d), Vec3(0f, 0f, 0f), FloorPlanMaterialSlot.SLAB))

    fun doorMarkers(
        currentFloor: Int, doorW: Float, wt: Float, hwCx: Float, topY: Float, d: Float, w: Float,
        sdFace: FeatureSide?, backDoorW: Float, sdCxBack: Float, sdD: Float, sdCzLR: Float,
    ): List<FloorPlanBoxNode> {
        if (currentFloor != 0) return emptyList()
        val nodes = mutableListOf<FloorPlanBoxNode>()
        nodes += FloorPlanBoxNode(Vec3(doorW, 0.04f, wt), Vec3(hwCx, topY, d / 2f), FloorPlanMaterialSlot.DOOR_MARK)
        when (sdFace) {
            FeatureSide.BACK  -> nodes += FloorPlanBoxNode(Vec3(backDoorW, 0.04f, wt), Vec3(sdCxBack, topY, -d / 2f), FloorPlanMaterialSlot.DOOR_MARK)
            FeatureSide.LEFT  -> nodes += FloorPlanBoxNode(Vec3(wt, 0.04f, sdD), Vec3(-w / 2f, topY, sdCzLR), FloorPlanMaterialSlot.DOOR_MARK)
            FeatureSide.RIGHT -> nodes += FloorPlanBoxNode(Vec3(wt, 0.04f, sdD), Vec3(w / 2f, topY, sdCzLR), FloorPlanMaterialSlot.DOOR_MARK)
            else -> {}
        }
        return nodes
    }

    fun exteriorFeatures(
        currentFloor: Int, w: Float, d: Float, extY: Float,
        featurePlacements: Map<HomeFeature, FeatureSide>,
        placedDecks: List<PlacedDeck> = emptyList(),
        // TOWNHOUSE only — its garage room already gets a floor tile from the normal room-zone
        // loop (it's a real RoomPlacement), so this only needs to add the door mark on the
        // real exterior wall it touches, matching HouseSceneGeometry.townhouseGarageDoor.
        townhouseGarageBox: FeatureBox? = null,
        topY: Float = extY,
        wt: Float = 0.2f,
    ): List<FloorPlanBoxNode> {
        if (currentFloor != 0) return emptyList()
        val nodes = mutableListOf<FloorPlanBoxNode>()

        townhouseGarageBox?.let { box ->
            val (x0, x1) = HouseSceneGeometry.townhouseGarageDoorXRange(box)
            nodes += FloorPlanBoxNode(Vec3(x1 - x0, 0.04f, wt), Vec3(box.cx, topY, d / 2f), FloorPlanMaterialSlot.DOOR_MARK)
        }

        // Decks can be multiple now, each independently wall-snapped — reuse
        // HouseSceneGeometry.deckBox (the same source of truth the 3D exterior view reads
        // from) rather than a second copy of the position math.
        placedDecks.forEach { deck ->
            val box = HouseSceneGeometry.deckBox(w, d, deck.side, deck.along)
            nodes += FloorPlanBoxNode(Vec3(box.w, 0.05f, box.d), Vec3(box.cx, extY, box.cz), FloorPlanMaterialSlot.DECK)
        }

        featurePlacements[HomeFeature.GARAGE]?.let { fpGarageSide ->
            val gW = (w * 0.55f).coerceIn(2.5f, 4.0f)
            val gD = minOf(d, 5.5f)
            val (gx, gz) = when (fpGarageSide) {
                FeatureSide.LEFT  -> -(w / 2f + gW / 2f) to 0f
                FeatureSide.RIGHT -> (w / 2f + gW / 2f) to 0f
                else              -> (w / 4f) to (d / 2f + gD / 2f)
            }
            // Both nodes share one closure (onGarageTap) — a static pair, not a loop, so this
            // is the Phase 4a "one call, shared closure" pattern, not the per-node wall pattern.
            nodes += FloorPlanBoxNode(Vec3(gW, 0.05f, gD), Vec3(gx, extY, gz),
                FloorPlanMaterialSlot.ROOM_FLOOR, roomType = RoomType.GARAGE, tappable = true)
            nodes += FloorPlanBoxNode(Vec3(gW * 0.7f, 0.05f, 0.28f), Vec3(gx, extY + 0.03f, gz + gD / 2f - 0.18f),
                FloorPlanMaterialSlot.DOOR_MARK, tappable = true)
        }

        featurePlacements[HomeFeature.POOL]?.let { poolSide ->
            val pW = 3.5f; val pD = minOf(d, 5.5f); val cope = 0.35f; val gap = 1.5f
            fun poolAt(pX: Float, pZ: Float, alongX: Boolean) {
                val (copeSx, copeSz) = if (alongX) (pW + cope * 2f) to (pD + cope * 2f) else (pD + cope * 2f) to (pW + cope * 2f)
                val (waterSx, waterSz) = if (alongX) pW to pD else pD to pW
                nodes += FloorPlanBoxNode(Vec3(copeSx, 0.05f, copeSz), Vec3(pX, extY, pZ), FloorPlanMaterialSlot.POOL_COPING)
                nodes += FloorPlanBoxNode(Vec3(waterSx, 0.05f, waterSz), Vec3(pX, extY + 0.03f, pZ), FloorPlanMaterialSlot.POOL_WATER)
            }
            when (poolSide) {
                FeatureSide.RIGHT -> poolAt(w / 2f + gap + cope + pW / 2f, 0f, alongX = true)
                FeatureSide.LEFT  -> poolAt(-(w / 2f + gap + cope + pW / 2f), 0f, alongX = true)
                FeatureSide.FRONT -> poolAt(0f, d / 2f + gap + cope + pD / 2f, alongX = false)
                FeatureSide.BACK  -> poolAt(0f, -(d / 2f + gap + cope + pD / 2f), alongX = false)
            }
        }
        return nodes
    }

    fun exteriorWalls(
        layout: FloorLayout, currentFloor: Int, w: Float, d: Float, ht: Float, wt: Float, hHt: Float, topY: Float,
        colW: FloatArray, rowD: FloatArray, xBound: FloatArray, zBound: FloatArray,
        outerOverrides: Map<WallKey, WallMode>,
        frontGapL: Float, frontGapR: Float, backGapL: Float, backGapR: Float,
        lrGapBtm: Float, lrGapTop: Float, sdFace: FeatureSide?,
    ): List<FloorPlanBoxNode> {
        val nodes = mutableListOf<FloorPlanBoxNode>()
        fun wallBox(size: Vec3, position: Vec3, key: WallKey, mode: WallMode, mat: FloorPlanMaterialSlot = FloorPlanMaterialSlot.OUTER_WALL) {
            nodes += FloorPlanBoxNode(size, position, mat, wallKey = key, wallMode = mode)
        }

        // Front wall (+Z)
        for (col in 0 until layout.gridCols) {
            val segL = xBound[col]; val segR = xBound[col + 1]; val segCx = (segL + segR) / 2f
            val segW = colW[col]
            val key = WallKey(vertical = false, boundary = 0, index = col, floor = currentFloor)
            val mode = outerOverrides[key] ?: WallMode.SOLID
            val oL = maxOf(segL, frontGapL); val oR = minOf(segR, frontGapR); val hasGap = oL < oR
            if (hasGap) {
                val lw = oL - segL; val rw = segR - oR
                if (lw > 0f) wallBox(Vec3(lw, ht, wt), Vec3(segL + lw / 2f, hHt, d / 2f), key, mode)
                if (rw > 0f) wallBox(Vec3(rw, ht, wt), Vec3(oR + rw / 2f, hHt, d / 2f), key, mode)
            } else {
                wallBox(Vec3(segW, ht, wt), Vec3(segCx, hHt, d / 2f), key, mode)
            }
            if (mode == WallMode.WINDOW && !hasGap)
                wallBox(Vec3(segW * 0.45f, 0.04f, wt * 1.4f), Vec3(segCx, topY, d / 2f), key, mode, FloorPlanMaterialSlot.WINDOW)
        }
        // Back wall (-Z)
        for (col in 0 until layout.gridCols) {
            val segL = xBound[col]; val segR = xBound[col + 1]; val segCx = (segL + segR) / 2f
            val segW = colW[col]
            val key = WallKey(vertical = false, boundary = layout.gridRows, index = col, floor = currentFloor)
            val mode = outerOverrides[key] ?: WallMode.SOLID
            val oL = maxOf(segL, backGapL); val oR = minOf(segR, backGapR); val hasGap = oL < oR
            if (hasGap) {
                val lw = oL - segL; val rw = segR - oR
                if (lw > 0f) wallBox(Vec3(lw, ht, wt), Vec3(segL + lw / 2f, hHt, -d / 2f), key, mode)
                if (rw > 0f) wallBox(Vec3(rw, ht, wt), Vec3(oR + rw / 2f, hHt, -d / 2f), key, mode)
            } else {
                wallBox(Vec3(segW, ht, wt), Vec3(segCx, hHt, -d / 2f), key, mode)
            }
            if (mode == WallMode.WINDOW && !hasGap)
                wallBox(Vec3(segW * 0.45f, 0.04f, wt * 1.4f), Vec3(segCx, topY, -d / 2f), key, mode, FloorPlanMaterialSlot.WINDOW)
        }
        // Left wall (-X)
        for (row in 0 until layout.gridRows) {
            val segTop = zBound[row]; val segBtm = zBound[row + 1]; val segCz = (segTop + segBtm) / 2f
            val segD = rowD[row]
            val key = WallKey(vertical = true, boundary = 0, index = row, floor = currentFloor)
            val mode = outerOverrides[key] ?: WallMode.SOLID
            val oTop = minOf(segTop, lrGapTop); val oBtm = maxOf(segBtm, lrGapBtm)
            val hasGap = if (sdFace == FeatureSide.LEFT) oBtm < oTop else false
            if (hasGap) {
                val tw = segTop - oTop; val bw = oBtm - segBtm
                if (tw > 0f) wallBox(Vec3(wt, ht, tw), Vec3(-w / 2f, hHt, oTop + tw / 2f), key, mode)
                if (bw > 0f) wallBox(Vec3(wt, ht, bw), Vec3(-w / 2f, hHt, segBtm + bw / 2f), key, mode)
            } else {
                wallBox(Vec3(wt, ht, segD), Vec3(-w / 2f, hHt, segCz), key, mode)
            }
            if (mode == WallMode.WINDOW && !hasGap)
                wallBox(Vec3(wt * 1.4f, 0.04f, segD * 0.45f), Vec3(-w / 2f, topY, segCz), key, mode, FloorPlanMaterialSlot.WINDOW)
        }
        // Right wall (+X)
        for (row in 0 until layout.gridRows) {
            val segTop = zBound[row]; val segBtm = zBound[row + 1]; val segCz = (segTop + segBtm) / 2f
            val segD = rowD[row]
            val key = WallKey(vertical = true, boundary = layout.gridCols, index = row, floor = currentFloor)
            val mode = outerOverrides[key] ?: WallMode.SOLID
            val oTop = minOf(segTop, lrGapTop); val oBtm = maxOf(segBtm, lrGapBtm)
            val hasGap = if (sdFace == FeatureSide.RIGHT) oBtm < oTop else false
            if (hasGap) {
                val tw = segTop - oTop; val bw = oBtm - segBtm
                if (tw > 0f) wallBox(Vec3(wt, ht, tw), Vec3(w / 2f, hHt, oTop + tw / 2f), key, mode)
                if (bw > 0f) wallBox(Vec3(wt, ht, bw), Vec3(w / 2f, hHt, segBtm + bw / 2f), key, mode)
            } else {
                wallBox(Vec3(wt, ht, segD), Vec3(w / 2f, hHt, segCz), key, mode)
            }
            if (mode == WallMode.WINDOW && !hasGap)
                wallBox(Vec3(wt * 1.4f, 0.04f, segD * 0.45f), Vec3(w / 2f, topY, segCz), key, mode, FloorPlanMaterialSlot.WINDOW)
        }
        return nodes
    }

    fun interiorWalls(
        layout: FloorLayout, currentFloor: Int, ht: Float, wt: Float, hHt: Float, topY: Float, doorW: Float,
        colW: FloatArray, rowD: FloatArray, xBound: FloatArray, zBound: FloatArray,
        effModes: Map<WallKey, WallMode>,
        frontGapL: Float, frontGapR: Float, backGapL: Float, backGapR: Float,
        lrGapBtm: Float, lrGapTop: Float, sdFace: FeatureSide?,
    ): List<FloorPlanBoxNode> {
        val nodes = mutableListOf<FloorPlanBoxNode>()
        fun wallBox(size: Vec3, position: Vec3, key: WallKey, mode: WallMode, mat: FloorPlanMaterialSlot) {
            nodes += FloorPlanBoxNode(size, position, mat, wallKey = key, wallMode = mode)
        }

        // Vertical walls at column boundaries (walls parallel to Z axis)
        for (col in 1 until layout.gridCols) {
            val wallX = xBound[col]
            for (row in 0 until layout.gridRows) {
                val lp = layout.placementAt(col - 1, row, currentFloor)
                val rp = layout.placementAt(col, row, currentFloor)
                if (lp == rp) continue
                val key = WallKey(vertical = true, boundary = col, index = row, floor = currentFloor)
                val segD = rowD[row]
                val wallCz = (zBound[row] + zBound[row + 1]) / 2f
                if ((row == 0 && wallX > frontGapL && wallX < frontGapR) ||
                    (row == layout.gridRows - 1 && wallX > backGapL && wallX < backGapR)) continue
                if (lp == null || rp == null) {
                    val occ = (lp ?: rp)!!.type
                    if (occ != RoomType.HALLWAY && occ != RoomType.FOYER && occ != RoomType.STAIRCASE) {
                        val mode = effModes[key] ?: WallMode.SOLID
                        wallBox(Vec3(wt, ht, segD), Vec3(wallX, hHt, wallCz), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                    }
                } else {
                    val mode = effModes[key] ?: WallMode.SOLID
                    if (mode == WallMode.OPEN) {
                        wallBox(Vec3(wt, 0.03f, segD), Vec3(wallX, wt / 2f + 0.03f, wallCz), key, mode, FloorPlanMaterialSlot.OPEN_WALL)
                    } else {
                        when (mode) {
                            WallMode.SOLID -> wallBox(Vec3(wt, ht, segD), Vec3(wallX, hHt, wallCz), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                            WallMode.WINDOW -> {
                                wallBox(Vec3(wt, ht, segD), Vec3(wallX, hHt, wallCz), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                wallBox(Vec3(wt * 1.4f, 0.04f, segD * 0.45f), Vec3(wallX, topY, wallCz), key, mode, FloorPlanMaterialSlot.WINDOW)
                            }
                            else -> { // DOOR
                                if (segD < doorW) {
                                    wallBox(Vec3(wt, ht, segD), Vec3(wallX, hHt, wallCz), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                } else {
                                    val seg = (segD - doorW) / 2f
                                    wallBox(Vec3(wt, ht, seg), Vec3(wallX, hHt, wallCz - (doorW + seg) / 2f), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                    wallBox(Vec3(wt, ht, seg), Vec3(wallX, hHt, wallCz + (doorW + seg) / 2f), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                    wallBox(Vec3(wt, 0.04f, doorW), Vec3(wallX, topY, wallCz), key, mode, FloorPlanMaterialSlot.DOOR_MARK)
                                }
                            }
                        }
                    }
                }
            }
        }
        // Horizontal walls at row boundaries (walls parallel to X axis)
        for (row in 1 until layout.gridRows) {
            val wallZ = zBound[row]
            for (col in 0 until layout.gridCols) {
                val fp = layout.placementAt(col, row - 1, currentFloor)
                val bp = layout.placementAt(col, row, currentFloor)
                if (fp == bp) continue
                val key = WallKey(vertical = false, boundary = row, index = col, floor = currentFloor)
                val segW = colW[col]
                val wallCx = (xBound[col] + xBound[col + 1]) / 2f
                if ((col == 0 && sdFace == FeatureSide.LEFT && wallZ > lrGapBtm && wallZ < lrGapTop) ||
                    (col == layout.gridCols - 1 && sdFace == FeatureSide.RIGHT && wallZ > lrGapBtm && wallZ < lrGapTop)) continue
                if (fp == null || bp == null) {
                    val occ = (fp ?: bp)!!.type
                    if (occ != RoomType.HALLWAY && occ != RoomType.FOYER && occ != RoomType.STAIRCASE) {
                        val mode = effModes[key] ?: WallMode.SOLID
                        wallBox(Vec3(segW, ht, wt), Vec3(wallCx, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                    }
                } else {
                    val mode = effModes[key] ?: WallMode.SOLID
                    if (mode == WallMode.OPEN) {
                        wallBox(Vec3(segW, 0.03f, wt), Vec3(wallCx, wt / 2f + 0.03f, wallZ), key, mode, FloorPlanMaterialSlot.OPEN_WALL)
                    } else {
                        when (mode) {
                            WallMode.SOLID -> wallBox(Vec3(segW, ht, wt), Vec3(wallCx, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                            WallMode.WINDOW -> {
                                wallBox(Vec3(segW, ht, wt), Vec3(wallCx, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                wallBox(Vec3(segW * 0.45f, 0.04f, wt * 1.4f), Vec3(wallCx, topY, wallZ), key, mode, FloorPlanMaterialSlot.WINDOW)
                            }
                            else -> { // DOOR
                                if (segW < doorW) {
                                    wallBox(Vec3(segW, ht, wt), Vec3(wallCx, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                } else {
                                    val seg = (segW - doorW) / 2f
                                    wallBox(Vec3(seg, ht, wt), Vec3(wallCx - (doorW + seg) / 2f, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                    wallBox(Vec3(seg, ht, wt), Vec3(wallCx + (doorW + seg) / 2f, hHt, wallZ), key, mode, FloorPlanMaterialSlot.INNER_WALL)
                                    wallBox(Vec3(doorW, 0.04f, wt), Vec3(wallCx, topY, wallZ), key, mode, FloorPlanMaterialSlot.DOOR_MARK)
                                }
                            }
                        }
                    }
                }
            }
        }
        return nodes
    }

    fun pillars(layout: FloorLayout, currentFloor: Int, ht: Float): List<FloorPlanBoxNode> {
        val pillarS = 0.20f
        return layout.pillarPositions(currentFloor).map { (px, pz) ->
            FloorPlanBoxNode(Vec3(pillarS, ht, pillarS), Vec3(px, ht / 2f, pz), FloorPlanMaterialSlot.INNER_WALL)
        }
    }

    fun emptyCells(
        layout: FloorLayout, currentFloor: Int, colW: FloatArray, rowD: FloatArray,
        xBound: FloatArray, zBound: FloatArray, selectedCells: Set<Pair<Int, Int>>, wt: Float,
    ): List<FloorPlanBoxNode> {
        val nodes = mutableListOf<FloorPlanBoxNode>()
        for (col in 0 until layout.gridCols) {
            for (row in 0 until layout.gridRows) {
                if (layout.placementAt(col, row, currentFloor) == null) {
                    val cellCx = (xBound[col] + xBound[col + 1]) / 2f
                    val cellCz = (zBound[row] + zBound[row + 1]) / 2f
                    val selected = (col to row) in selectedCells
                    nodes += FloorPlanBoxNode(
                        size = Vec3(colW[col] - 0.1f, 0.03f, rowD[row] - 0.1f),
                        position = Vec3(cellCx, wt / 2f + (if (selected) 0.02f else 0.015f), cellCz),
                        material = if (selected) FloorPlanMaterialSlot.SELECTED_CELL else FloorPlanMaterialSlot.EMPTY_CELL,
                        emptyCell = col to row,
                    )
                }
            }
        }
        return nodes
    }

    fun corridorStrips(
        corridorCells: Set<Pair<Int, Int>>, layout: FloorLayout, currentFloor: Int,
        colW: FloatArray, rowD: FloatArray, xBound: FloatArray, zBound: FloatArray, wt: Float,
    ): List<FloorPlanBoxNode> {
        val nodes = mutableListOf<FloorPlanBoxNode>()
        val corridorY = wt / 2f + 0.020f
        val armW = layout.cellW * 0.18f
        val armD = layout.cellD * 0.18f
        val slop = wt
        corridorCells.forEach { (col, row) ->
            val cellCx = (xBound[col] + xBound[col + 1]) / 2f
            val cellCz = (zBound[row] + zBound[row + 1]) / 2f
            val goH = (col - 1 to row) in corridorCells || layout.placementAt(col - 1, row, currentFloor) != null ||
                      (col + 1 to row) in corridorCells || layout.placementAt(col + 1, row, currentFloor) != null
            val goV = (col to row - 1) in corridorCells || layout.placementAt(col, row - 1, currentFloor) != null ||
                      (col to row + 1) in corridorCells || layout.placementAt(col, row + 1, currentFloor) != null
            if (goH) nodes += FloorPlanBoxNode(Vec3(colW[col] + slop * 2, 0.04f, armD), Vec3(cellCx, corridorY, cellCz), FloorPlanMaterialSlot.CORRIDOR)
            if (goV) nodes += FloorPlanBoxNode(Vec3(armW, 0.04f, rowD[row] + slop * 2), Vec3(cellCx, corridorY, cellCz), FloorPlanMaterialSlot.CORRIDOR)
        }
        return nodes
    }

    /**
     * ONE call per placement (mirrors NeighborhoodSceneGeometry.blockHome's "one call per
     * lot"): every tappable node returned — including every staircase tread/landing — shares
     * a single closure, bound at the composable's call site (never inside this function).
     */
    fun roomTile(
        placement: RoomPlacement, zone: RoomZone, layout: FloorLayout, currentFloor: Int,
        insX: Float, insZ: Float, corridorCells: Set<Pair<Int, Int>>,
        xBound: FloatArray, zBound: FloatArray, rowD: FloatArray, colW: FloatArray,
        wt: Float, ht: Float,
    ): List<FloorPlanBoxNode> {
        if (zone.type == RoomType.FOYER) {
            return listOf(FloorPlanBoxNode(
                Vec3(zone.w, 0.05f, zone.d), Vec3(zone.cx, wt / 2f + 0.025f, zone.cz),
                FloorPlanMaterialSlot.ROOM_FLOOR, roomType = zone.type, tappable = true))
        }
        if (zone.type == RoomType.HALLWAY) {
            val nodes = mutableListOf<FloorPlanBoxNode>()
            val doorExtra = 0.20f
            fun hasDoorInto(t: RoomType?) = t != null && t != RoomType.HALLWAY
            for (row in placement.row until placement.row + placement.rowSpan) {
                val tileCz = (zBound[row] + zBound[row + 1]) / 2f
                val tileD = rowD[row]
                val leftT = layout.placementAt(placement.col - 1, row, currentFloor)?.type
                val rightT = layout.placementAt(placement.col + placement.colSpan, row, currentFloor)?.type
                val extraL = if (hasDoorInto(leftT)) doorExtra else 0f
                val extraR = if (hasDoorInto(rightT)) doorExtra else 0f
                nodes += FloorPlanBoxNode(
                    Vec3(zone.w + extraL + extraR, 0.05f, tileD),
                    Vec3(zone.cx - extraL / 2f + extraR / 2f, wt / 2f + 0.025f, tileCz),
                    FloorPlanMaterialSlot.ROOM_FLOOR, roomType = zone.type, tappable = true)
            }
            return nodes
        }
        if (zone.type == RoomType.STAIRCASE) {
            return staircase(placement, zone, layout, currentFloor, wt, ht)
        }
        val leftOk  = (placement.row until placement.row + placement.rowSpan).any { r ->
            layout.placementAt(placement.col - 1, r, currentFloor)?.type == placement.type ||
            (placement.col - 1 to r) in corridorCells }
        val rightOk = (placement.row until placement.row + placement.rowSpan).any { r ->
            layout.placementAt(placement.col + placement.colSpan, r, currentFloor)?.type == placement.type ||
            (placement.col + placement.colSpan to r) in corridorCells }
        val frontOk = (placement.col until placement.col + placement.colSpan).any { c ->
            layout.placementAt(c, placement.row - 1, currentFloor)?.type == placement.type ||
            (c to placement.row - 1) in corridorCells }
        val backOk  = (placement.col until placement.col + placement.colSpan).any { c ->
            layout.placementAt(c, placement.row + placement.rowSpan, currentFloor)?.type == placement.type ||
            (c to placement.row + placement.rowSpan) in corridorCells }
        val lIns = if (leftOk) 0f else insX;  val rIns = if (rightOk) 0f else insX
        val fIns = if (frontOk) 0f else insZ; val bIns = if (backOk) 0f else insZ
        return listOf(FloorPlanBoxNode(
            Vec3(zone.w - lIns - rIns, 0.05f, zone.d - fIns - bIns),
            Vec3(zone.cx + (lIns - rIns) / 2f, wt / 2f + 0.025f, zone.cz + (bIns - fIns) / 2f),
            FloorPlanMaterialSlot.ROOM_FLOOR, roomType = zone.type, tappable = true))
    }

    /**
     * Direction-aware stairs: a flight that CLIMBS renders as rising wood treads with stepped
     * handrails; the flight ARRIVING from the floor below renders as an open stairwell — a dark
     * pit whose treads fade as they descend, guarded by panels. See the original FloorPlanScene.kt
     * comment (preserved logic, not preserved prose) for the full switchback rationale.
     */
    private fun staircase(
        placement: RoomPlacement, zone: RoomZone, layout: FloorLayout, currentFloor: Int,
        wt: Float, ht: Float,
    ): List<FloorPlanBoxNode> {
        val nodes = mutableListOf<FloorPlanBoxNode>()
        val hasUp = currentFloor < layout.maxFloor()
        val hasDown = currentFloor > 0
        val nSteps = 8
        val treadD = zone.d / nSteps
        val riseH = ht / 7f

        fun zNbrType(row: Int): RoomType? = (placement.col until placement.col + placement.colSpan)
            .firstNotNullOfOrNull { c -> layout.placementAt(c, row, 0)?.type }
        val maxNbr = zNbrType(placement.row - 1)
        val minNbr = zNbrType(placement.row + placement.rowSpan)
        val groundBottomAtMax = when {
            maxNbr == RoomType.FOYER   -> true
            minNbr == RoomType.FOYER   -> false
            maxNbr == RoomType.HALLWAY -> true
            minNbr == RoomType.HALLWAY -> false
            else                       -> true
        }
        val bottomAtMax = groundBottomAtMax xor (currentFloor % 2 == 1)
        fun stepZ(i: Float) = if (bottomAtMax) zone.zMax - i * treadD else zone.zMin + i * treadD

        fun upFlight(fx: Float, fw: Float) {
            for (i in 0 until nSteps - 2) {
                val ty = wt / 2f + i * riseH
                val tz = stepZ(i + 0.5f)
                nodes += FloorPlanBoxNode(Vec3(fw, 0.05f, treadD), Vec3(fx, ty, tz), FloorPlanMaterialSlot.STAIR_TREAD, tappable = true)
                if (i > 0) nodes += FloorPlanBoxNode(Vec3(fw, riseH, wt * 0.35f), Vec3(fx, ty - riseH / 2f, stepZ(i.toFloat())), FloorPlanMaterialSlot.INNER_WALL)
                listOf(fx - fw / 2f + 0.04f, fx + fw / 2f - 0.04f).forEach { rx ->
                    nodes += FloorPlanBoxNode(Vec3(0.06f, 0.05f, treadD), Vec3(rx, ty + 0.90f, tz), FloorPlanMaterialSlot.STAIR_RAIL)
                }
            }
            val landY = wt / 2f + (nSteps - 2) * riseH
            nodes += FloorPlanBoxNode(Vec3(fw, 0.05f, treadD * 2f), Vec3(fx, landY, stepZ(7f)), FloorPlanMaterialSlot.STAIR_TREAD, tappable = true)
            nodes += FloorPlanBoxNode(Vec3(fw, riseH, wt * 0.35f), Vec3(fx, landY - riseH / 2f, stepZ(6f)), FloorPlanMaterialSlot.INNER_WALL)
            listOf(fx - fw / 2f + 0.04f, fx + fw / 2f - 0.04f).forEach { rx ->
                nodes += FloorPlanBoxNode(Vec3(0.06f, 0.05f, treadD * 2f), Vec3(rx, landY + 0.90f, stepZ(7f)), FloorPlanMaterialSlot.STAIR_RAIL)
            }
        }

        fun downWell(fx: Float, fw: Float) {
            nodes += FloorPlanBoxNode(Vec3(fw, 0.03f, zone.d), Vec3(fx, wt / 2f + 0.005f, zone.cz), FloorPlanMaterialSlot.STAIR_WELL_FLOOR, tappable = true)
            for (i in 0 until nSteps) {
                val tz = stepZ(i + 0.5f)
                nodes += FloorPlanBoxNode(Vec3(fw * 0.92f, 0.03f, treadD * 0.82f), Vec3(fx, wt / 2f + 0.025f - i * 0.002f, tz),
                    FloorPlanMaterialSlot.STAIR_DOWN_TREAD, materialVariant = (i * STAIR_DOWN_VARIANTS) / nSteps, tappable = true)
            }
            nodes += FloorPlanBoxNode(Vec3(fw + 0.12f, 0.90f, 0.06f),
                Vec3(fx, wt / 2f + 0.45f, if (bottomAtMax) zone.zMin + 0.03f else zone.zMax - 0.03f), FloorPlanMaterialSlot.INNER_WALL)
        }

        when {
            hasUp && hasDown -> {
                val halfW = zone.w * 0.42f
                upFlight(zone.cx + zone.w * 0.25f, halfW)
                downWell(zone.cx - zone.w * 0.25f, halfW)
                val divCz = zone.cz + (if (bottomAtMax) -treadD else treadD)
                nodes += FloorPlanBoxNode(Vec3(0.06f, 0.90f, zone.d - 2f * treadD), Vec3(zone.cx, wt / 2f + 0.45f, divCz), FloorPlanMaterialSlot.INNER_WALL)
                nodes += FloorPlanBoxNode(Vec3(0.06f, 0.90f, zone.d), Vec3(zone.cx - zone.w * 0.25f - halfW / 2f - 0.03f, wt / 2f + 0.45f, zone.cz), FloorPlanMaterialSlot.INNER_WALL)
            }
            hasDown -> {
                downWell(zone.cx, zone.w * 0.84f)
                listOf(-1, 1).forEach { sgn ->
                    nodes += FloorPlanBoxNode(Vec3(0.06f, 0.90f, zone.d), Vec3(zone.cx + sgn * (zone.w * 0.42f + 0.03f), wt / 2f + 0.45f, zone.cz), FloorPlanMaterialSlot.INNER_WALL)
                }
            }
            else -> upFlight(zone.cx, zone.w * 0.84f)
        }
        return nodes
    }
}
