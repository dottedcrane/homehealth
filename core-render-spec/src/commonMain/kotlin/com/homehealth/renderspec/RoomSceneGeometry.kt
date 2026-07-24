package com.homehealth.renderspec

import com.homehealth.model.FeatureSide
import com.homehealth.model.RoomZone
import com.homehealth.model.WallMode
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T

data class Vec3(val x: Float, val y: Float, val z: Float)

// Which of RoomScene's pre-built MaterialInstances a node should render with — a portable
// stand-in for passing an engine-specific material handle across the module boundary.
enum class RoomMaterialSlot { WALL, WOOD, GLASS }

data class RoomBoxNode(
    val size: Vec3,
    val position: Vec3,
    val material: RoomMaterialSlot,
)

/**
 * Pure geometry for a room's four walls (with door/window/slider openings) and corner
 * pillars — engine-agnostic (no io.github.sceneview/com.google.android.filament imports)
 * so it can be rendered by any 3D backend. Deliberately excludes the floor slab and
 * furniture: the floor is the drag-gesture collision parent for movable items in the
 * SceneView node tree, so it — and everything nested under it — stays authored directly
 * in RoomScene.kt rather than flattened into this list.
 */
object RoomSceneGeometry {

    private const val DOOR_W = 0.95f
    private const val DOOR_H = 2.1f
    private const val LEAF_T = 0.045f
    private const val WIN_W  = 1.2f
    private const val SILL_H = 0.9f
    private const val WIN_H  = 1.2f
    private const val PILLAR_S = 0.16f

    fun build(
        zone: RoomZone,
        wallModes: Map<FeatureSide, WallMode>,
        exteriorSlider: Pair<FeatureSide, ClosedFloatingPointRange<Float>>?,
    ): List<RoomBoxNode> {
        val ht  = FLOOR_HEIGHT_M
        val wt  = WALL_T
        val hHt = ht / 2f
        val hw  = zone.w / 2f
        val hd  = zone.d / 2f
        val lintH = (ht - DOOR_H).coerceAtLeast(0f)
        val topOfWin = SILL_H + WIN_H

        fun modeOf(side: FeatureSide) = wallModes[side] ?: WallMode.SOLID

        val nodes = mutableListOf<RoomBoxNode>()
        fun box(sx: Float, sy: Float, sz: Float, px: Float, py: Float, pz: Float, mat: RoomMaterialSlot) {
            nodes += RoomBoxNode(Vec3(sx, sy, sz), Vec3(px, py, pz), mat)
        }

        // Renders a plain exterior slab, unless [exteriorSlider] carves a glazed sliding-door
        // gap into this [side] — mirrors HouseScene's sd1CX/sd2CX/sdD geometry so a kitchen
        // (etc.) that owns part of the exterior slider shows it here too.
        fun exteriorWallOrSlider(side: FeatureSide, alongX: Boolean, full: Float, fixedCoord: Float) {
            val slider = exteriorSlider?.takeIf { it.first == side }?.second
            if (slider == null) {
                if (alongX) box(full, ht, wt, 0f, hHt, fixedCoord, RoomMaterialSlot.WALL)
                else        box(wt, ht, full, fixedCoord, hHt, 0f, RoomMaterialSlot.WALL)
                return
            }
            val half     = full / 2f
            val lo       = slider.start.coerceIn(-half, half)
            val hi       = slider.endInclusive.coerceIn(-half, half)
            val leftLen  = lo - (-half)
            val rightLen = half - hi
            val mid      = (lo + hi) / 2f
            val sdLen    = (hi - lo).coerceAtLeast(0f)
            val sdH      = ht * 0.95f
            val aboveH   = (ht - sdH).coerceAtLeast(0f)
            if (alongX) {
                if (leftLen  > 0.02f) box(leftLen,  ht, wt, -half + leftLen / 2f, hHt, fixedCoord, RoomMaterialSlot.WALL)
                if (rightLen > 0.02f) box(rightLen, ht, wt,  half - rightLen / 2f, hHt, fixedCoord, RoomMaterialSlot.WALL)
                if (sdLen > 0.02f) {
                    box(sdLen, sdH, wt * 0.4f, mid, sdH / 2f, fixedCoord, RoomMaterialSlot.GLASS)
                    if (aboveH > 0f) box(sdLen, aboveH, wt, mid, sdH + aboveH / 2f, fixedCoord, RoomMaterialSlot.WALL)
                }
            } else {
                if (leftLen  > 0.02f) box(wt, ht, leftLen,  fixedCoord, hHt, -half + leftLen / 2f, RoomMaterialSlot.WALL)
                if (rightLen > 0.02f) box(wt, ht, rightLen, fixedCoord, hHt,  half - rightLen / 2f, RoomMaterialSlot.WALL)
                if (sdLen > 0.02f) {
                    box(wt * 0.4f, sdH, sdLen, fixedCoord, sdH / 2f, mid, RoomMaterialSlot.GLASS)
                    if (aboveH > 0f) box(wt, aboveH, sdLen, fixedCoord, sdH + aboveH / 2f, mid, RoomMaterialSlot.WALL)
                }
            }
        }

        // +Z wall (FRONT)
        when (modeOf(FeatureSide.FRONT)) {
            WallMode.DOOR -> {
                val sw = (zone.w - DOOR_W) / 2f
                if (sw > 0f) {
                    box(sw, ht, wt, -zone.w / 2f + sw / 2f, hHt, hd, RoomMaterialSlot.WALL)
                    box(sw, ht, wt,  zone.w / 2f - sw / 2f, hHt, hd, RoomMaterialSlot.WALL)
                }
                if (lintH > 0f) box(DOOR_W, lintH, wt, 0f, DOOR_H + lintH / 2f, hd, RoomMaterialSlot.WALL)
                // Open leaf — hinged at the right jamb, swung into the room (−Z).
                box(LEAF_T, DOOR_H - 0.02f, DOOR_W, DOOR_W / 2f, DOOR_H / 2f, hd - DOOR_W / 2f, RoomMaterialSlot.WOOD)
            }
            WallMode.WINDOW -> {
                val sw = (zone.w - WIN_W) / 2f
                if (sw > 0f) {
                    box(sw, ht, wt, -zone.w / 2f + sw / 2f, hHt, hd, RoomMaterialSlot.WALL)
                    box(sw, ht, wt,  zone.w / 2f - sw / 2f, hHt, hd, RoomMaterialSlot.WALL)
                }
                box(WIN_W, SILL_H, wt, 0f, SILL_H / 2f, hd, RoomMaterialSlot.WALL)
                val aboveH = (ht - topOfWin).coerceAtLeast(0f)
                if (aboveH > 0f) box(WIN_W, aboveH, wt, 0f, topOfWin + aboveH / 2f, hd, RoomMaterialSlot.WALL)
                box(WIN_W, WIN_H, wt * 0.3f, 0f, SILL_H + WIN_H / 2f, hd, RoomMaterialSlot.GLASS)
            }
            WallMode.OPEN -> {}   // true threshold — no wall; corner pillars rendered below
            WallMode.SOLID -> exteriorWallOrSlider(FeatureSide.FRONT, alongX = true, full = zone.w, fixedCoord = hd)
        }

        // -Z wall (BACK)
        when (modeOf(FeatureSide.BACK)) {
            WallMode.DOOR -> {
                val sw = (zone.w - DOOR_W) / 2f
                if (sw > 0f) {
                    box(sw, ht, wt, -zone.w / 2f + sw / 2f, hHt, -hd, RoomMaterialSlot.WALL)
                    box(sw, ht, wt,  zone.w / 2f - sw / 2f, hHt, -hd, RoomMaterialSlot.WALL)
                }
                if (lintH > 0f) box(DOOR_W, lintH, wt, 0f, DOOR_H + lintH / 2f, -hd, RoomMaterialSlot.WALL)
                // Open leaf — hinged at the left jamb, swung into the room (+Z).
                box(LEAF_T, DOOR_H - 0.02f, DOOR_W, -DOOR_W / 2f, DOOR_H / 2f, -hd + DOOR_W / 2f, RoomMaterialSlot.WOOD)
            }
            WallMode.WINDOW -> {
                val sw = (zone.w - WIN_W) / 2f
                if (sw > 0f) {
                    box(sw, ht, wt, -zone.w / 2f + sw / 2f, hHt, -hd, RoomMaterialSlot.WALL)
                    box(sw, ht, wt,  zone.w / 2f - sw / 2f, hHt, -hd, RoomMaterialSlot.WALL)
                }
                box(WIN_W, SILL_H, wt, 0f, SILL_H / 2f, -hd, RoomMaterialSlot.WALL)
                val aboveH = (ht - topOfWin).coerceAtLeast(0f)
                if (aboveH > 0f) box(WIN_W, aboveH, wt, 0f, topOfWin + aboveH / 2f, -hd, RoomMaterialSlot.WALL)
                box(WIN_W, WIN_H, wt * 0.3f, 0f, SILL_H + WIN_H / 2f, -hd, RoomMaterialSlot.GLASS)
            }
            WallMode.OPEN -> {}
            WallMode.SOLID -> exteriorWallOrSlider(FeatureSide.BACK, alongX = true, full = zone.w, fixedCoord = -hd)
        }

        // -X wall (LEFT): door/window opening centred on Z axis
        when (modeOf(FeatureSide.LEFT)) {
            WallMode.DOOR -> {
                val sd = (zone.d - DOOR_W) / 2f
                if (sd > 0f) {
                    box(wt, ht, sd, -hw, hHt,  zone.d / 2f - sd / 2f, RoomMaterialSlot.WALL)
                    box(wt, ht, sd, -hw, hHt, -zone.d / 2f + sd / 2f, RoomMaterialSlot.WALL)
                }
                if (lintH > 0f) box(wt, lintH, DOOR_W, -hw, DOOR_H + lintH / 2f, 0f, RoomMaterialSlot.WALL)
                // Open leaf — hinged at the front jamb, swung into the room (+X).
                box(DOOR_W, DOOR_H - 0.02f, LEAF_T, -hw + DOOR_W / 2f, DOOR_H / 2f, DOOR_W / 2f, RoomMaterialSlot.WOOD)
            }
            WallMode.WINDOW -> {
                val sd = (zone.d - WIN_W) / 2f
                if (sd > 0f) {
                    box(wt, ht, sd, -hw, hHt,  zone.d / 2f - sd / 2f, RoomMaterialSlot.WALL)
                    box(wt, ht, sd, -hw, hHt, -zone.d / 2f + sd / 2f, RoomMaterialSlot.WALL)
                }
                box(wt, SILL_H, WIN_W, -hw, SILL_H / 2f, 0f, RoomMaterialSlot.WALL)
                val aboveH = (ht - topOfWin).coerceAtLeast(0f)
                if (aboveH > 0f) box(wt, aboveH, WIN_W, -hw, topOfWin + aboveH / 2f, 0f, RoomMaterialSlot.WALL)
                box(wt * 0.3f, WIN_H, WIN_W, -hw, SILL_H + WIN_H / 2f, 0f, RoomMaterialSlot.GLASS)
            }
            WallMode.OPEN -> {}
            WallMode.SOLID -> exteriorWallOrSlider(FeatureSide.LEFT, alongX = false, full = zone.d, fixedCoord = -hw)
        }

        // +X wall (RIGHT): door/window opening centred on Z axis
        when (modeOf(FeatureSide.RIGHT)) {
            WallMode.DOOR -> {
                val sd = (zone.d - DOOR_W) / 2f
                if (sd > 0f) {
                    box(wt, ht, sd, hw, hHt,  zone.d / 2f - sd / 2f, RoomMaterialSlot.WALL)
                    box(wt, ht, sd, hw, hHt, -zone.d / 2f + sd / 2f, RoomMaterialSlot.WALL)
                }
                if (lintH > 0f) box(wt, lintH, DOOR_W, hw, DOOR_H + lintH / 2f, 0f, RoomMaterialSlot.WALL)
                // Open leaf — hinged at the back jamb, swung into the room (−X).
                box(DOOR_W, DOOR_H - 0.02f, LEAF_T, hw - DOOR_W / 2f, DOOR_H / 2f, -DOOR_W / 2f, RoomMaterialSlot.WOOD)
            }
            WallMode.WINDOW -> {
                val sd = (zone.d - WIN_W) / 2f
                if (sd > 0f) {
                    box(wt, ht, sd, hw, hHt,  zone.d / 2f - sd / 2f, RoomMaterialSlot.WALL)
                    box(wt, ht, sd, hw, hHt, -zone.d / 2f + sd / 2f, RoomMaterialSlot.WALL)
                }
                box(wt, SILL_H, WIN_W, hw, SILL_H / 2f, 0f, RoomMaterialSlot.WALL)
                val aboveH = (ht - topOfWin).coerceAtLeast(0f)
                if (aboveH > 0f) box(wt, aboveH, WIN_W, hw, topOfWin + aboveH / 2f, 0f, RoomMaterialSlot.WALL)
                box(wt * 0.3f, WIN_H, WIN_W, hw, SILL_H + WIN_H / 2f, 0f, RoomMaterialSlot.GLASS)
            }
            WallMode.OPEN -> {}
            WallMode.SOLID -> exteriorWallOrSlider(FeatureSide.RIGHT, alongX = false, full = zone.d, fixedCoord = hw)
        }

        // ── Structural pillars ─────────────────────────────────────────────────────
        // Model assumption: structure is carried at room corners. A corner touched by any
        // wall is already supported by it, so a pillar appears only where two OPEN sides
        // meet — the minimal set (the floor plan derives the same rule house-wide via
        // FloorLayout.pillarPositions).
        fun sideOpen(side: FeatureSide) = modeOf(side) == WallMode.OPEN
        listOf(
            Triple(FeatureSide.LEFT,  FeatureSide.FRONT, (-hw + PILLAR_S / 2f) to ( hd - PILLAR_S / 2f)),
            Triple(FeatureSide.RIGHT, FeatureSide.FRONT, ( hw - PILLAR_S / 2f) to ( hd - PILLAR_S / 2f)),
            Triple(FeatureSide.LEFT,  FeatureSide.BACK,  (-hw + PILLAR_S / 2f) to (-hd + PILLAR_S / 2f)),
            Triple(FeatureSide.RIGHT, FeatureSide.BACK,  ( hw - PILLAR_S / 2f) to (-hd + PILLAR_S / 2f)),
        ).forEach { (a, b, pos) ->
            if (sideOpen(a) && sideOpen(b)) {
                box(PILLAR_S, ht, PILLAR_S, pos.first, hHt, pos.second, RoomMaterialSlot.WALL)
            }
        }

        return nodes
    }
}
