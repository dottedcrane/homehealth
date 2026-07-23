package com.homerenderer.renderspec

import com.homerenderer.model.FeatureSide
import com.homerenderer.model.FloorLayout
import com.homerenderer.model.HomeStyle
import com.homerenderer.model.RoomType
import com.homerenderer.scene.FLOOR_HEIGHT_M
import com.homerenderer.scene.WALL_T
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class HouseSceneMaterialSlot {
    SLAB, WALL, GLASS, FRONT_DOOR, GARAGE_INTERIOR_DOOR, ROOM_FLOOR,
    ROOF_PITCH, ROOF_FLAT, YARD,
    GARAGE_SHELL, GARAGE_DOOR,
    DECK, POOL_COPING, POOL_WATER,
    SOLAR, HVAC_BODY, HVAC_GRILLE,
}

data class HouseSceneBoxNode(
    val size: Vec3,
    val position: Vec3,
    // Precedent: NeighborhoodBoxNode.rotationDeg — reuses Vec3 for io.github.sceneview.math.Rotation(x,y,z).
    // Needed for the garage door's pivot fold and the pitched roof/solar panels' tilt.
    val rotationDeg: Vec3 = Vec3(0f, 0f, 0f),
    val material: HouseSceneMaterialSlot,
    // ROOM_FLOOR only — composable looks up roomFloorMats[roomType].
    val roomType: RoomType? = null,
    // Sliding-glass-pane identity: 1 = back/living-room, 2 = back/master-bedroom, 3 = single
    // left-or-right side pane. Each is returned from a fixed 0-or-1-node function call (never
    // a forEach), so the composable dispatches by doorId with plain if/else — no key() needed.
    val doorId: Int? = null,
    // Shared-closure dispatch, same contract as every prior XxxBoxNode.tappable: every
    // tappable node from one function call routes to the same closure at the call site.
    // material doubles as a secondary dispatch key where behavior differs (FRONT_DOOR and
    // GARAGE_INTERIOR_DOOR also play a creak sound; GARAGE_DOOR plays the garage rumble;
    // SOLAR/HVAC_BODY route to effTapSystem instead of effEnterHome).
    val tappable: Boolean = false,
)

/** The usable-yard drag-clamp region for freely-placed garage/deck/pool — a style-scaled
 *  rectangle around the house footprint, distinct from [HouseSceneGeometry.yardPlane]'s larger
 *  purely-visual grass extent. Null means the style has no yard at all (CONDO). */
data class YardBounds(val xMin: Float, val xMax: Float, val zMin: Float, val zMax: Float)

/** A freely-placeable exterior feature's resolved footprint: world-space center + size,
 *  the single source of truth both its renderer and its driveway/overlap math read from. */
data class FeatureBox(val cx: Float, val cz: Float, val w: Float, val d: Float)

private const val PITCH_DEG = 25f
private val PITCH_RAD = kotlin.math.PI / 180.0 * PITCH_DEG

private data class WallOpening(val aMin: Float, val aMax: Float, val yLo: Float, val yHi: Float)
private data class WallSegment(val cAlong: Float, val len: Float, val cY: Float, val hY: Float)

/**
 * Sweeps a wall's length/height into solid segments around a list of rectangular openings.
 * Pure port of HouseScene.kt's original `PunchedWall` — that function was `@Composable` only
 * because its `place` callback was `@Composable`; the sweep math itself was always pure. Height
 * is split into horizontal bands at every opening edge; within each band the wall is split
 * around the active holes.
 */
private fun sweepWallSegments(length: Float, height: Float, openings: List<WallOpening>): List<WallSegment> {
    val half = length / 2f
    val segments = mutableListOf<WallSegment>()
    val yEdges = (listOf(0f, height) +
        openings.flatMap { listOf(it.yLo.coerceIn(0f, height), it.yHi.coerceIn(0f, height)) })
        .distinct().sorted()
    for (i in 0 until yEdges.size - 1) {
        val yb0 = yEdges[i]; val yb1 = yEdges[i + 1]
        val hY = yb1 - yb0
        if (hY < 0.01f) continue
        val cY = (yb0 + yb1) / 2f
        val holes = openings
            .filter { it.yLo <= yb0 + 0.001f && it.yHi >= yb1 - 0.001f }
            .map { it.aMin.coerceIn(-half, half) to it.aMax.coerceIn(-half, half) }
            .sortedBy { it.first }
        var cursor = -half
        for ((hMin, hMax) in holes) {
            if (hMin > cursor + 0.01f) segments += WallSegment(cursor + (hMin - cursor) / 2f, hMin - cursor, cY, hY)
            cursor = maxOf(cursor, hMax)
        }
        if (half > cursor + 0.01f) segments += WallSegment(cursor + (half - cursor) / 2f, half - cursor, cY, hY)
    }
    return segments
}

/**
 * Pure geometry for the exterior "house shell" view: ground slab, punched exterior walls,
 * front door/window glass, sliding-door panes, roof, solar panels, yard plane, garage shell +
 * overhead door, garage/house interior door, deck, pool, HVAC unit, and interior room-floor
 * tiles visible through the glass — engine-agnostic (no io.github.sceneview/
 * com.google.android.filament imports). Deliberately excludes vehicles/driveway (VehiclesOnLot
 * nests vehicle nodes as children of the driveway node in local space — a parent/child grouping
 * concept no render-spec node type has yet) and the inventory/ItemNode section (a whole shared
 * composable, not pure geometry, already used identically by RoomScene.kt/FloorPlanScene.kt) —
 * both out of scope for this phase, same rationale prior extractions used for furniture.
 */
object HouseSceneGeometry {

    fun groundSlab(w: Float, d: Float, yBase: Float, tappable: Boolean): List<HouseSceneBoxNode> =
        listOf(HouseSceneBoxNode(Vec3(w, WALL_T, d), Vec3(0f, yBase, 0f), material = HouseSceneMaterialSlot.SLAB, tappable = tappable))

    fun frontWall(
        floor: Int, w: Float, d: Float, yBase: Float,
        doorWx: Float, groundWinX: Float, winW: Float, winLo: Float, winHi: Float,
        // TOWNHOUSE only — the interior garage room's door, cut directly into this real wall
        // (see townhouseGarageDoor) rather than a separate free-standing shell.
        garageDoorXRange: Pair<Float, Float>? = null,
    ): List<HouseSceneBoxNode> {
        val openings = if (floor == 0) {
            buildList {
                add(WallOpening(doorWx - 0.95f / 2f, doorWx + 0.95f / 2f, 0f, 2.1f))
                add(WallOpening(groundWinX - winW / 2f, groundWinX + winW / 2f, winLo, winHi))
                garageDoorXRange?.let { (x0, x1) -> add(WallOpening(x0, x1, 0f, TOWNHOUSE_GARAGE_DOOR_H)) }
            }
        } else {
            listOf(WallOpening(-w / 4f - winW / 2f, -w / 4f + winW / 2f, winLo, winHi),
                   WallOpening( w / 4f - winW / 2f,  w / 4f + winW / 2f, winLo, winHi))
        }
        return sweepWallSegments(w, FLOOR_HEIGHT_M, openings).map { seg ->
            HouseSceneBoxNode(Vec3(seg.len, seg.hY, WALL_T), Vec3(seg.cAlong, yBase + seg.cY, d / 2f),
                material = HouseSceneMaterialSlot.WALL, tappable = true)
        }
    }

    fun backWall(
        floor: Int, w: Float, d: Float, yBase: Float,
        sdFace: FeatureSide?, sd1CX: Float, sd1W: Float, sd2CX: Float, sd2W: Float, sdH: Float,
        winW: Float, winLo: Float, winHi: Float,
    ): List<HouseSceneBoxNode> {
        val openings = when {
            floor == 0 && sdFace == FeatureSide.BACK ->
                listOf(WallOpening(sd1CX - sd1W / 2f, sd1CX + sd1W / 2f, 0f, sdH),
                       WallOpening(sd2CX - sd2W / 2f, sd2CX + sd2W / 2f, 0f, sdH))
            floor > 0 ->
                listOf(WallOpening(-w / 4f - winW / 2f, -w / 4f + winW / 2f, winLo, winHi),
                       WallOpening( w / 4f - winW / 2f,  w / 4f + winW / 2f, winLo, winHi))
            else -> emptyList()
        }
        return sweepWallSegments(w, FLOOR_HEIGHT_M, openings).map { seg ->
            HouseSceneBoxNode(Vec3(seg.len, seg.hY, WALL_T), Vec3(seg.cAlong, yBase + seg.cY, -d / 2f),
                material = HouseSceneMaterialSlot.WALL, tappable = true)
        }
    }

    fun leftWall(
        floor: Int, w: Float, d: Float, yBase: Float,
        sdFace: FeatureSide?, hwCz: Float, sdD: Float, sdH: Float,
        winW: Float, winLo: Float, winHi: Float,
    ): List<HouseSceneBoxNode> {
        val openings = if (floor == 0 && sdFace == FeatureSide.LEFT)
            listOf(WallOpening(hwCz - sdD / 2f, hwCz + sdD / 2f, 0f, sdH))
        else
            listOf(WallOpening(-d / 4f - winW / 2f, -d / 4f + winW / 2f, winLo, winHi),
                   WallOpening( d / 4f - winW / 2f,  d / 4f + winW / 2f, winLo, winHi))
        return sweepWallSegments(d, FLOOR_HEIGHT_M, openings).map { seg ->
            HouseSceneBoxNode(Vec3(WALL_T, seg.hY, seg.len), Vec3(-w / 2f, yBase + seg.cY, seg.cAlong),
                material = HouseSceneMaterialSlot.WALL, tappable = true)
        }
    }

    fun rightWall(
        floor: Int, w: Float, d: Float, yBase: Float,
        sdFace: FeatureSide?, hwCz: Float, sdD: Float, sdH: Float,
        winW: Float, winLo: Float, winHi: Float,
    ): List<HouseSceneBoxNode> {
        val openings = if (floor == 0 && sdFace == FeatureSide.RIGHT)
            listOf(WallOpening(hwCz - sdD / 2f, hwCz + sdD / 2f, 0f, sdH))
        else
            listOf(WallOpening(-d / 4f - winW / 2f, -d / 4f + winW / 2f, winLo, winHi),
                   WallOpening( d / 4f - winW / 2f,  d / 4f + winW / 2f, winLo, winHi))
        return sweepWallSegments(d, FLOOR_HEIGHT_M, openings).map { seg ->
            HouseSceneBoxNode(Vec3(WALL_T, seg.hY, seg.len), Vec3(w / 2f, yBase + seg.cY, seg.cAlong),
                material = HouseSceneMaterialSlot.WALL, tappable = true)
        }
    }

    fun frontDoorAndGlass(
        floor: Int, doorWx: Float, groundWinX: Float, winW: Float, winH: Float, winY: Float,
        w: Float, d: Float, p: Float, yBase: Float,
    ): List<HouseSceneBoxNode> = if (floor == 0) {
        listOf(
            HouseSceneBoxNode(Vec3(0.95f, 2.1f, p), Vec3(doorWx, yBase + 2.1f / 2f, d / 2f + p / 2f),
                material = HouseSceneMaterialSlot.FRONT_DOOR, tappable = true),
            HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(groundWinX, winY, d / 2f + p / 2f),
                material = HouseSceneMaterialSlot.GLASS, tappable = true),
        )
    } else {
        listOf(
            HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(-w / 4f, winY, d / 2f + p / 2f),
                material = HouseSceneMaterialSlot.GLASS, tappable = true),
            HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(w / 4f, winY, d / 2f + p / 2f),
                material = HouseSceneMaterialSlot.GLASS, tappable = true),
        )
    }

    fun backGlass(
        floor: Int, sdFace: FeatureSide?, sd1CX: Float, sd1W: Float, sd2CX: Float, sd2W: Float, sdH: Float,
        winW: Float, winH: Float, winY: Float, w: Float, d: Float, p: Float, yBase: Float,
        door1Fraction: Float, door2Fraction: Float,
    ): List<HouseSceneBoxNode> = if (floor == 0 && sdFace == FeatureSide.BACK) {
        listOf(
            HouseSceneBoxNode(Vec3(sd1W, sdH, p), Vec3(sd1CX + sd1W * door1Fraction, yBase + sdH / 2f, -d / 2f - p / 2f),
                material = HouseSceneMaterialSlot.GLASS, doorId = 1, tappable = true),
            HouseSceneBoxNode(Vec3(sd2W, sdH, p), Vec3(sd2CX + sd2W * door2Fraction, yBase + sdH / 2f, -d / 2f - p / 2f),
                material = HouseSceneMaterialSlot.GLASS, doorId = 2, tappable = true),
        )
    } else if (floor > 0) {
        listOf(
            HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(-w / 4f, winY, -d / 2f - p / 2f),
                material = HouseSceneMaterialSlot.GLASS, tappable = true),
            HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(w / 4f, winY, -d / 2f - p / 2f),
                material = HouseSceneMaterialSlot.GLASS, tappable = true),
        )
    } else emptyList()

    /** Called once each for LEFT and RIGHT — whichever matches [sdFace] gets the slider, the other gets 2 windows. */
    fun sideGlass(
        side: FeatureSide, floor: Int, sdFace: FeatureSide?, hwCz: Float, sdD: Float, sdH: Float,
        winW: Float, winH: Float, winY: Float, w: Float, d: Float, p: Float, yBase: Float, doorFraction: Float,
    ): List<HouseSceneBoxNode> {
        val sign = if (side == FeatureSide.LEFT) -1f else 1f
        return if (floor == 0 && sdFace == side) {
            val slideSign = if (side == FeatureSide.LEFT) 1f else -1f
            listOf(HouseSceneBoxNode(
                Vec3(p, sdH, sdD), Vec3(sign * (w / 2f + p / 2f), yBase + sdH / 2f, hwCz + slideSign * sdD * doorFraction),
                material = HouseSceneMaterialSlot.GLASS, doorId = 3, tappable = true))
        } else {
            listOf(
                HouseSceneBoxNode(Vec3(p, winH, winW), Vec3(sign * (w / 2f + p / 2f), winY, -d / 4f),
                    material = HouseSceneMaterialSlot.GLASS, tappable = true),
                HouseSceneBoxNode(Vec3(p, winH, winW), Vec3(sign * (w / 2f + p / 2f), winY, d / 4f),
                    material = HouseSceneMaterialSlot.GLASS, tappable = true),
            )
        }
    }

    fun roof(w: Float, d: Float, wallTopY: Float, style: HomeStyle): List<HouseSceneBoxNode> {
        val isPitched = style == HomeStyle.HOUSE || style == HomeStyle.TOWNHOUSE
        return if (isPitched) {
            val overhang = 0.6f
            val halfSpan = w / 2f + overhang
            val slantLen = (halfSpan / cos(PITCH_RAD)).toFloat()
            val ridgeH = (halfSpan * tan(PITCH_RAD)).toFloat()
            val cx = halfSpan / 2f
            val cy = wallTopY + ridgeH / 2f
            val depth = d + overhang * 2f
            listOf(
                HouseSceneBoxNode(Vec3(slantLen, 0.15f, depth), Vec3(cx, cy, 0f), rotationDeg = Vec3(0f, 0f, -PITCH_DEG),
                    material = HouseSceneMaterialSlot.ROOF_PITCH, tappable = true),
                HouseSceneBoxNode(Vec3(slantLen, 0.15f, depth), Vec3(-cx, cy, 0f), rotationDeg = Vec3(0f, 0f, PITCH_DEG),
                    material = HouseSceneMaterialSlot.ROOF_PITCH, tappable = true),
            )
        } else {
            listOf(HouseSceneBoxNode(Vec3(w + 0.5f, 0.35f, d + 0.5f), Vec3(0f, wallTopY + 0.18f, 0f),
                material = HouseSceneMaterialSlot.ROOF_FLAT, tappable = true))
        }
    }

    fun solarPanels(w: Float, d: Float, wallTopY: Float, style: HomeStyle): List<HouseSceneBoxNode> {
        val isPitched = style == HomeStyle.HOUSE || style == HomeStyle.TOWNHOUSE
        val nodes = mutableListOf<HouseSceneBoxNode>()
        if (isPitched) {
            val overhang = 0.6f
            val halfSpan = w / 2f + overhang
            val slantLen = (halfSpan / cos(PITCH_RAD)).toFloat()
            val ridgeH = (halfSpan * tan(PITCH_RAD)).toFloat()
            val cx = halfSpan / 2f
            val cy = wallTopY + ridgeH / 2f
            val panelW = slantLen * 0.62f
            val panelD = (d - 0.8f).coerceAtLeast(1.5f)
            val normX = sin(PITCH_RAD).toFloat()
            val normY = cos(PITCH_RAD).toFloat()
            val off = 0.08f
            repeat(2) { r ->
                val pz = if (r == 0) -panelD / 2f - 0.05f else panelD / 2f + 0.05f
                nodes += HouseSceneBoxNode(Vec3(panelW, 0.025f, panelD / 2f - 0.05f), Vec3(cx + normX * off, cy + normY * off, pz),
                    rotationDeg = Vec3(0f, 0f, -PITCH_DEG), material = HouseSceneMaterialSlot.SOLAR, tappable = true)
            }
            repeat(2) { r ->
                val pz = if (r == 0) -panelD / 2f - 0.05f else panelD / 2f + 0.05f
                nodes += HouseSceneBoxNode(Vec3(panelW, 0.025f, panelD / 2f - 0.05f), Vec3(-cx - normX * off, cy + normY * off, pz),
                    rotationDeg = Vec3(0f, 0f, PITCH_DEG), material = HouseSceneMaterialSlot.SOLAR, tappable = true)
            }
        } else {
            val pW = (w - 1.0f) / 3f
            val pDp = (d - 1.0f) / 2f
            val gp = 0.12f
            for (col in 0..2) for (row in 0..1) {
                nodes += HouseSceneBoxNode(
                    Vec3(pW - gp, 0.04f, pDp - gp),
                    Vec3(-w / 2f + 0.5f + col * pW + (pW - gp) / 2f, wallTopY + 0.40f, -d / 2f + 0.5f + row * pDp + (pDp - gp) / 2f),
                    material = HouseSceneMaterialSlot.SOLAR, tappable = true)
            }
        }
        return nodes
    }

    fun yardPlane(w: Float, d: Float): List<HouseSceneBoxNode> =
        listOf(HouseSceneBoxNode(Vec3(w + 16f, 0.06f, d + 16f), Vec3(0f, -0.13f, 0f), material = HouseSceneMaterialSlot.YARD))

    // The usable-yard drag region — smaller than yardPlane's purely-visual footprint above,
    // so there's always a grass margin past the farthest a feature can be dragged. HOUSE gets
    // a full yard; TOWNHOUSE a tighter one (garage moves inside instead); CONDO none at all.
    fun yardBounds(style: HomeStyle, w: Float, d: Float): YardBounds? {
        val margin = when (style) {
            HomeStyle.HOUSE     -> 6f
            HomeStyle.TOWNHOUSE -> 2.5f
            HomeStyle.CONDO     -> return null
        }
        return YardBounds(-(w / 2f + margin), w / 2f + margin, -(d / 2f + margin), d / 2f + margin)
    }

    // Which house wall a live (x,z) position is currently closest to — drives the interior
    // garage door's along-wall tracking, the driveway's attached/detached margin choice, and
    // wall-snapping for garage/deck drags. [candidates] restricts which walls are considered
    // (the garage only ever attaches to FRONT/RIGHT/LEFT — see garageBaseAnchor's kdoc — so
    // it must never "snap" to BACK, whose door-orientation math doesn't exist).
    fun nearestHouseSide(
        x: Float, z: Float, w: Float, d: Float,
        candidates: List<FeatureSide> = listOf(FeatureSide.FRONT, FeatureSide.BACK, FeatureSide.LEFT, FeatureSide.RIGHT),
    ): FeatureSide {
        val hw = w / 2f; val hd = d / 2f
        return candidates.map { side ->
            side to when (side) {
                FeatureSide.RIGHT -> abs(x - hw)
                FeatureSide.LEFT  -> abs(x + hw)
                FeatureSide.FRONT -> abs(z - hd)
                FeatureSide.BACK  -> abs(z + hd)
            }
        }.minBy { it.second }.first
    }

    // The world axis a wall-snapped feature slides along on [side] — X for the front/back
    // walls, Z for the side walls.
    fun alongCoordinate(side: FeatureSide, x: Float, z: Float): Float =
        if (side == FeatureSide.FRONT || side == FeatureSide.BACK) x else z

    // Half the length of [side]'s own wall — the along-wall coordinate's natural range before
    // a feature of zero width would slide past the house's corner.
    fun wallHalfLength(side: FeatureSide, w: Float, d: Float): Float =
        if (side == FeatureSide.FRONT || side == FeatureSide.BACK) w / 2f else d / 2f

    // Clamps a wall-snapped feature's along-wall coordinate so its own footprint (alongSize,
    // its extent along that same axis) never slides past the wall's corner.
    fun clampAlongWall(side: FeatureSide, along: Float, w: Float, d: Float, alongSize: Float): Float {
        val maxOff = (wallHalfLength(side, w, d) - alongSize / 2f).coerceAtLeast(0f)
        return along.coerceIn(-maxOff, maxOff)
    }

    // Pushes a freely-2D-placed feature's box (pool) out of the house's own footprint rectangle
    // along whichever axis needs the smaller nudge — a simple AABB separation, not a full
    // nearest-free-spot search. Features with no drag freedom toward the house (garage/deck,
    // now wall-snapped) never need this: their perpendicular-to-wall offset is fixed.
    fun keepOutsideHouse(cx: Float, cz: Float, boxW: Float, boxD: Float, w: Float, d: Float): Pair<Float, Float> {
        val halfX = w / 2f + boxW / 2f
        val halfZ = d / 2f + boxD / 2f
        if (abs(cx) >= halfX || abs(cz) >= halfZ) return cx to cz
        val pushX = halfX - abs(cx)
        val pushZ = halfZ - abs(cz)
        return if (pushX <= pushZ) (if (cx >= 0f) halfX else -halfX) to cz
        else cx to (if (cz >= 0f) halfZ else -halfZ)
    }

    private fun garageBaseAnchor(side: FeatureSide, w: Float, d: Float, gW: Float, gD: Float): Pair<Float, Float> = when (side) {
        FeatureSide.LEFT  -> -(w / 2f + gW / 2f) to 0f
        FeatureSide.RIGHT -> w / 2f + gW / 2f to 0f
        // FRONT is the only other side validSides ever offers; BACK is unreachable through
        // the UI but falls back to the same shape rather than crashing if it somehow occurs.
        else              -> w / 4f to (d / 2f + gD / 2f)
    }

    /** The garage's resolved box: [side]'s default anchor plus a live drag offset. The single
     *  source of truth for "where is the garage" — both [com.homerenderer.renderspec.CarLotGeometry.carLot]
     *  and the shell renderer read from this instead of independently re-deriving an anchor,
     *  which is exactly the duplication that let the two drift out of agreement before. */
    fun garageBox(w: Float, d: Float, side: FeatureSide, dx: Float = 0f, dz: Float = 0f): FeatureBox {
        val gW = (w * 0.55f).coerceIn(2.5f, 4.0f)
        val gD = minOf(d, 5.5f)
        val (bx, bz) = garageBaseAnchor(side, w, d, gW, gD)
        return FeatureBox(bx + dx, bz + dz, gW, gD)
    }

    // TOWNHOUSE's garage door height when cut directly into the real front wall — distinct
    // from garageShell's door (a free-standing shell's own proportional door), since this one
    // is sized for a full-height house wall opening instead.
    const val TOWNHOUSE_GARAGE_DOOR_H = 2.3f

    /**
     * TOWNHOUSE's interior GARAGE room's real-world box (FloorLayout.withGarageRoom() always
     * seeds it at row 0 / the rightmost columns, so its front edge IS the house's actual front
     * wall and its right edge IS the house's actual right wall) — the single source of truth
     * for cutting a real door into frontWall, the exterior driveway (CarLotGeometry.
     * townhouseCarLot), and GarageScene's shell dimensions when entered, so all three agree on
     * where "the garage" is exactly like garageBox already does for HOUSE's free-standing one.
     */
    fun townhouseGarageBox(floorLayout: FloorLayout): FeatureBox? {
        val zone = floorLayout.toMergedZones(0).firstOrNull { it.type == RoomType.GARAGE } ?: return null
        return FeatureBox(zone.cx, zone.cz, zone.xMax - zone.xMin, zone.zMax - zone.zMin)
    }

    /** The garage door's X-range within [box] — narrower than the room's full width, like a
     *  real double garage door leaves wall on either side, centered on the room. */
    fun townhouseGarageDoorXRange(box: FeatureBox): Pair<Float, Float> {
        val doorW = minOf(box.w * 0.7f, 4.5f)
        return (box.cx - doorW / 2f) to (box.cx + doorW / 2f)
    }

    /** The door PANEL filling [townhouseGarageDoorXRange]'s opening in the real front wall —
     *  reuses GARAGE_DOOR material/tap dispatch (see emitHouseSceneNodes's onGarageDoorTap),
     *  same as garageShell's door, but as a flat panel matching frontDoorAndGlass's convention
     *  instead of a pivoting shell door (the open/roll-up animation only plays inside
     *  GarageScene, exactly like the exterior HOUSE garage door never animates in place). */
    fun townhouseGarageDoor(box: FeatureBox, d: Float, p: Float, yBase: Float): List<HouseSceneBoxNode> {
        val (x0, x1) = townhouseGarageDoorXRange(box)
        val doorW = x1 - x0
        return listOf(HouseSceneBoxNode(
            Vec3(doorW, TOWNHOUSE_GARAGE_DOOR_H, p), Vec3(box.cx, yBase + TOWNHOUSE_GARAGE_DOOR_H / 2f, d / 2f + p / 2f),
            material = HouseSceneMaterialSlot.GARAGE_DOOR, tappable = true,
        ))
    }

    private fun deckBaseAnchor(side: FeatureSide, w: Float, d: Float, dkD: Float): Pair<Float, Float> = when (side) {
        FeatureSide.FRONT -> 0f to (d / 2f + dkD / 2f)
        FeatureSide.BACK  -> 0f to -(d / 2f + dkD / 2f)
        FeatureSide.RIGHT -> (w / 2f + dkD / 2f) to 0f
        FeatureSide.LEFT  -> -(w / 2f + dkD / 2f) to 0f
    }

    /** The deck stays wall-attached (its full-house-width-strip shape doesn't support a free
     *  2D drop) — [along] slides it along whichever wall it's on: world X for FRONT/BACK,
     *  world Z for LEFT/RIGHT. */
    fun deckBox(w: Float, d: Float, side: FeatureSide, along: Float = 0f): FeatureBox {
        val dkW = w / 3f; val dkD = 2.5f
        val (bx, bz) = deckBaseAnchor(side, w, d, dkD)
        return if (side == FeatureSide.FRONT || side == FeatureSide.BACK)
            FeatureBox(bx + along, bz, dkW, dkD)
        else
            FeatureBox(bx, bz + along, dkD, dkW)
    }

    private fun poolBaseAnchor(side: FeatureSide, w: Float, d: Float, pW: Float, pD: Float, cope: Float, gap: Float): Pair<Float, Float> = when (side) {
        FeatureSide.RIGHT -> (w / 2f + gap + cope + pW / 2f) to 0f
        FeatureSide.LEFT  -> -(w / 2f + gap + cope + pW / 2f) to 0f
        FeatureSide.FRONT -> 0f to (d / 2f + gap + cope + pD / 2f)
        FeatureSide.BACK  -> 0f to -(d / 2f + gap + cope + pD / 2f)
    }

    /** The pool's resolved coping footprint — pool gets full free 2D placement (dx AND dz),
     *  unlike the deck, since it has no wall/sliding-door coupling constraining it. */
    fun poolBox(w: Float, d: Float, side: FeatureSide, dx: Float = 0f, dz: Float = 0f): FeatureBox {
        val pW = 3.5f; val pD = minOf(d, 5.5f); val cope = 0.35f; val gap = 1.5f
        val alongX = side == FeatureSide.RIGHT || side == FeatureSide.LEFT
        val (bx, bz) = poolBaseAnchor(side, w, d, pW, pD, cope, gap)
        val (boxW, boxD) = if (alongX) (pW + cope * 2f) to (pD + cope * 2f) else (pD + cope * 2f) to (pW + cope * 2f)
        return FeatureBox(bx + dx, bz + dz, boxW, boxD)
    }

    /**
     * 5-panel shell + 1 overhead door that pivots/folds against the ceiling as [doorFraction]
     * goes 0→1. Reused by both HouseScene (static, doorFraction = 0f) and GarageScene
     * (animated, doorFraction = door.value) — ports HollowGarage's geometry verbatim.
     */
    fun garageShell(cx: Float, cz: Float, gW: Float, gH: Float, gD: Float, doorFacesPositiveZ: Boolean, doorFraction: Float): List<HouseSceneBoxNode> {
        val gWT = 0.15f
        val dP = 0.08f
        val doorH = (gH - gWT - 0.05f).coerceIn(0.3f, gD - 0.2f)
        val cy = gH / 2f
        val inwardDir = if (doorFacesPositiveZ) -1f else 1f
        val frontFaceZ = if (doorFacesPositiveZ) cz + gD / 2f else cz - gD / 2f
        val backZ = cz + inwardDir * (gD / 2f - gWT / 2f)

        val nodes = mutableListOf<HouseSceneBoxNode>()
        nodes += HouseSceneBoxNode(Vec3(gW, gWT, gD), Vec3(cx, gWT / 2f, cz), material = HouseSceneMaterialSlot.GARAGE_SHELL)
        nodes += HouseSceneBoxNode(Vec3(gW, gWT, gD), Vec3(cx, gH - gWT / 2f, cz), material = HouseSceneMaterialSlot.GARAGE_SHELL)
        nodes += HouseSceneBoxNode(Vec3(gW, gH, gWT), Vec3(cx, cy, backZ), material = HouseSceneMaterialSlot.GARAGE_SHELL)
        nodes += HouseSceneBoxNode(Vec3(gWT, gH, gD), Vec3(cx - gW / 2f + gWT / 2f, cy, cz), material = HouseSceneMaterialSlot.GARAGE_SHELL)
        nodes += HouseSceneBoxNode(Vec3(gWT, gH, gD), Vec3(cx + gW / 2f - gWT / 2f, cy, cz), material = HouseSceneMaterialSlot.GARAGE_SHELL)

        val theta = doorFraction * (kotlin.math.PI / 2.0).toFloat()
        val topY = gH - gWT
        val doorY = topY - doorH / 2f * cos(theta)
        val doorZ = frontFaceZ + inwardDir * (doorH / 2f) * sin(theta)
        val rotX = 90f * doorFraction * inwardDir
        nodes += HouseSceneBoxNode(Vec3(gW * 0.84f, doorH, dP), Vec3(cx, doorY, doorZ), rotationDeg = Vec3(rotX, 0f, 0f),
            material = HouseSceneMaterialSlot.GARAGE_DOOR, tappable = true)
        return nodes
    }

    // Tracks the garage's LIVE box (HouseSceneGeometry.garageBox's resolved center + size)
    // instead of a frozen side: the door hole slides along whichever wall the garage is
    // nearest as it's dragged, and disappears once the garage's near edge (not its center —
    // that's always offset from the wall by half the garage's own width/depth) pulls more
    // than a small clearance away from that wall, falling back to a plain unbroken wall
    // rather than a separate "detached" mode.
    fun interiorDoorToGarage(garageBox: FeatureBox, w: Float, d: Float, p: Float): List<HouseSceneBoxNode> {
        val intDoorY = 2.1f / 2f
        val hw = w / 2f; val hd = d / 2f
        val gcx = garageBox.cx; val gcz = garageBox.cz
        val nearest = nearestHouseSide(gcx, gcz, w, d)
        val clearance = 1.5f
        val distToWall = when (nearest) {
            FeatureSide.RIGHT -> abs((gcx - garageBox.w / 2f) - hw)
            FeatureSide.LEFT  -> abs((gcx + garageBox.w / 2f) + hw)
            FeatureSide.FRONT -> abs((gcz - garageBox.d / 2f) - hd)
            FeatureSide.BACK  -> abs((gcz + garageBox.d / 2f) + hd)
        }
        if (distToWall > clearance) return emptyList()
        val margin = 0.6f
        return when (nearest) {
            FeatureSide.LEFT -> listOf(HouseSceneBoxNode(Vec3(p, 2.1f, 0.95f),
                Vec3(-hw - p / 2f, intDoorY, gcz.coerceIn(-hd + margin, hd - margin)),
                material = HouseSceneMaterialSlot.GARAGE_INTERIOR_DOOR, tappable = true))
            FeatureSide.RIGHT -> listOf(HouseSceneBoxNode(Vec3(p, 2.1f, 0.95f),
                Vec3(hw + p / 2f, intDoorY, gcz.coerceIn(-hd + margin, hd - margin)),
                material = HouseSceneMaterialSlot.GARAGE_INTERIOR_DOOR, tappable = true))
            FeatureSide.FRONT -> listOf(HouseSceneBoxNode(Vec3(0.95f, 2.1f, p),
                Vec3(gcx.coerceIn(-hw + margin, hw - margin), intDoorY, hd + p / 2f),
                material = HouseSceneMaterialSlot.GARAGE_INTERIOR_DOOR, tappable = true))
            // BACK is unreachable through the UI (GARAGE's validSides never include it) — no
            // rear-wall door variant exists, so the garage is treated as detached from here.
            FeatureSide.BACK -> emptyList()
        }
    }

    fun deck(deckSide: FeatureSide, w: Float, d: Float, along: Float = 0f): List<HouseSceneBoxNode> {
        val box = deckBox(w, d, deckSide, along)
        return listOf(HouseSceneBoxNode(Vec3(box.w, 0.18f, box.d), Vec3(box.cx, 0.09f, box.cz), material = HouseSceneMaterialSlot.DECK))
    }

    fun pool(poolSide: FeatureSide, w: Float, d: Float, dx: Float = 0f, dz: Float = 0f): List<HouseSceneBoxNode> {
        val pW = 3.5f; val pD = minOf(d, 5.5f)
        val alongX = poolSide == FeatureSide.RIGHT || poolSide == FeatureSide.LEFT
        val box = poolBox(w, d, poolSide, dx, dz)
        val (waterSx, waterSz) = if (alongX) pW to pD else pD to pW
        return listOf(
            HouseSceneBoxNode(Vec3(box.w, 0.14f, box.d), Vec3(box.cx, 0f, box.cz), material = HouseSceneMaterialSlot.POOL_COPING),
            HouseSceneBoxNode(Vec3(waterSx, 0.06f, waterSz), Vec3(box.cx, 0.10f, box.cz), material = HouseSceneMaterialSlot.POOL_WATER),
        )
    }

    fun resolveHvacSide(poolSide: FeatureSide?, garageSide: FeatureSide?): FeatureSide {
        fun opposite(s: FeatureSide) = when (s) {
            FeatureSide.RIGHT -> FeatureSide.LEFT
            FeatureSide.LEFT -> FeatureSide.RIGHT
            FeatureSide.FRONT -> FeatureSide.BACK
            FeatureSide.BACK -> FeatureSide.FRONT
        }
        return when {
            poolSide != null -> opposite(poolSide)
            garageSide != null -> opposite(garageSide)
            else -> FeatureSide.RIGHT
        }
    }

    // Picks an offset along a wall (measured from the wall's centre) for a fixed-footprint
    // exterior fixture, preferring [default] but sliding clear of any door openings on that
    // wall. Falls back to whichever candidate clears the doors, or [default] if none do.
    fun clearWallOffset(
        default: Float, wallHalfLen: Float, unitHalf: Float,
        doorRanges: List<Pair<Float, Float>>, edgeMargin: Float = 0.30f,
    ): Float {
        val maxOff = wallHalfLen - unitHalf - edgeMargin
        if (maxOff <= 0f) return default
        fun clear(off: Float) = doorRanges.none { (lo, hi) -> off - unitHalf < hi && off + unitHalf > lo }
        val candidates = listOf(default.coerceIn(-maxOff, maxOff), -default.coerceIn(-maxOff, maxOff), maxOff, -maxOff)
        return candidates.firstOrNull { clear(it) } ?: default
    }

    fun hvac(hvacSide: FeatureSide, w: Float, d: Float, offset: Float): List<HouseSceneBoxNode> {
        val unitH = 0.90f; val unitY = unitH / 2f; val gap = 0.15f
        fun pair(bodySize: Vec3, bodyPos: Vec3, grilleSize: Vec3, grillePos: Vec3) = listOf(
            HouseSceneBoxNode(bodySize, bodyPos, material = HouseSceneMaterialSlot.HVAC_BODY, tappable = true),
            HouseSceneBoxNode(grilleSize, grillePos, material = HouseSceneMaterialSlot.HVAC_GRILLE),
        )
        return when (hvacSide) {
            FeatureSide.RIGHT -> {
                val ux = w / 2f + gap + 0.30f
                pair(Vec3(0.60f, unitH, 0.85f), Vec3(ux, unitY, offset), Vec3(0.56f, 0.06f, 0.81f), Vec3(ux, unitH + 0.03f, offset))
            }
            FeatureSide.LEFT -> {
                val ux = -(w / 2f + gap + 0.30f)
                pair(Vec3(0.60f, unitH, 0.85f), Vec3(ux, unitY, offset), Vec3(0.56f, 0.06f, 0.81f), Vec3(ux, unitH + 0.03f, offset))
            }
            FeatureSide.FRONT -> {
                val uz = d / 2f + gap + 0.30f
                pair(Vec3(0.85f, unitH, 0.60f), Vec3(offset, unitY, uz), Vec3(0.81f, 0.06f, 0.56f), Vec3(offset, unitH + 0.03f, uz))
            }
            FeatureSide.BACK -> {
                val uz = -(d / 2f + gap + 0.30f)
                pair(Vec3(0.85f, unitH, 0.60f), Vec3(offset, unitY, uz), Vec3(0.81f, 0.06f, 0.56f), Vec3(offset, unitH + 0.03f, uz))
            }
        }
    }

    fun roomFloorTiles(floorLayout: FloorLayout, floor: Int, yBase: Float): List<HouseSceneBoxNode> {
        val tileT = 0.04f
        return floorLayout.toMergedZones(floor).map { zone ->
            HouseSceneBoxNode(
                Vec3((zone.w - 0.1f).coerceAtLeast(0.1f), tileT, (zone.d - 0.1f).coerceAtLeast(0.1f)),
                Vec3(zone.cx, yBase + WALL_T / 2f + tileT / 2f, zone.cz),
                material = HouseSceneMaterialSlot.ROOM_FLOOR, roomType = zone.type,
            )
        }
    }
}
