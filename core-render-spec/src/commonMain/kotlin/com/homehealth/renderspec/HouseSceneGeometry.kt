package com.homehealth.renderspec

import com.homehealth.model.AtticType
import com.homehealth.model.FeatureSide
import com.homehealth.model.FloorLayout
import com.homehealth.model.HiddenAsset
import com.homehealth.model.HomeStyle
import com.homehealth.model.HomeSystem
import com.homehealth.model.RoomType
import com.homehealth.model.RoomZone
import com.homehealth.model.YardDecorKind
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** The tracked asset an attic item IS, or null when it belongs to a [HomeSystem] instead (the air
 *  handler is the indoor half of HVAC). Top level rather than a member of [HouseSceneGeometry] so
 *  the renderer can read it unqualified: one mapping then serves the tap that opens an item's
 *  maintenance card, the attic pane's row that toggles it, and [HouseSceneGeometry.atticContents]. */
val HouseSceneGeometry.AtticItem.hiddenAsset: HiddenAsset? get() = when (this) {
    HouseSceneGeometry.AtticItem.AIR_HANDLER -> null
    HouseSceneGeometry.AtticItem.DUCTWORK    -> HiddenAsset.DUCTWORK
    HouseSceneGeometry.AtticItem.INSULATION  -> HiddenAsset.ATTIC_INSULATION
}

/** The [HomeSystem] an attic item belongs to, or null when it's a [HiddenAsset] instead — the
 *  mirror of [hiddenAsset], so between them every item has exactly one tracking key. The air
 *  handler is HVAC's indoor half, so it answers to the same "not in my home" key the outdoor
 *  condenser does: switch the system off and the whole of it goes, inside and out. */
val HouseSceneGeometry.AtticItem.homeSystem: HomeSystem? get() = when (this) {
    HouseSceneGeometry.AtticItem.AIR_HANDLER -> HomeSystem.HVAC
    HouseSceneGeometry.AtticItem.DUCTWORK    -> null
    HouseSceneGeometry.AtticItem.INSULATION  -> null
}

enum class HouseSceneMaterialSlot {
    SLAB, WALL, GLASS, FRONT_DOOR, GARAGE_INTERIOR_DOOR, ROOM_FLOOR,
    ROOF_PITCH, ROOF_FLAT, YARD,
    GARAGE_SHELL, GARAGE_DOOR,
    DECK, POOL_COPING, POOL_WATER,
    SOLAR, HVAC_BODY, HVAC_GRILLE,
    // ATTIC_SHELL is the floor you stand on and tap; ATTIC_WALL is the enclosure around it,
    // rendered see-through so no wall hides the equipment from any orbit angle.
    ATTIC_SHELL, ATTIC_WALL, ATTIC_HATCH, ATTIC_FRAMING, DUCT, INSULATION,
    EV_BATTERY_BODY, EV_BATTERY_LED,
    TREE_TRUNK, TREE_LEAF, GAZEBO_POST, GAZEBO_ROOF,
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

/** One of the two pitched-roof slope planes (see [HouseSceneGeometry.roof]/[.solarArray]), for
 *  genuine ray/plane placement math so solar can be dropped/placed accurately from ANY camera
 *  angle, not just a near-vertical one. [point] is the plane's own reference point (the slab's
 *  center); [normal] its outward unit normal (the flush-mount offset direction, matching
 *  solarArray's own off/normX/normY); [tangent] the unit direction across the slope from ridge
 *  toward eave; [slantLen] the slope's finite extent along that tangent, centered on [point] —
 *  used to tell a genuine hit on the physical roof slab from one that only lands on the plane's
 *  infinite extension past the ridge/eave (see [HouseSceneGeometry.slopeOffset]). */
data class RoofSlopePlane(val point: Vec3, val normal: Vec3, val tangent: Vec3, val slantLen: Float)

// Distance along [RoofSlopePlane.tangent] from the slab's own center (0 at [RoofSlopePlane.
// point], negative toward the ridge, positive toward the eave) — [hit] must already lie on this
// same plane (e.g. from a ray/plane intersection against it). A magnitude beyond slantLen/2
// means the hit only landed on the plane's infinite extension, not the real roof slab.
fun RoofSlopePlane.slopeOffset(hit: Vec3): Float =
    (hit.x - point.x) * tangent.x + (hit.y - point.y) * tangent.y

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
        // Null = this elevation has no ground-floor window. That's what a home with an interior
        // garage bay gets: the ground floor is the entry and the garage door, nothing else. See
        // frontDoorAndGlass, which skips the matching pane on the same signal.
        doorWx: Float, groundWinX: Float?, winW: Float, winLo: Float, winHi: Float,
        // TOWNHOUSE only — the interior garage room's door, cut directly into this real wall
        // (see townhouseGarageDoor) rather than a separate free-standing shell.
        garageDoorXRange: Pair<Float, Float>? = null,
    ): List<HouseSceneBoxNode> {
        val openings = if (floor == 0) {
            buildList {
                add(WallOpening(doorWx - 0.95f / 2f, doorWx + 0.95f / 2f, 0f, 2.1f))
                groundWinX?.let { add(WallOpening(it - winW / 2f, it + winW / 2f, winLo, winHi)) }
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
        floor: Int, doorWx: Float, groundWinX: Float?, winW: Float, winH: Float, winY: Float,
        w: Float, d: Float, p: Float, yBase: Float,
    ): List<HouseSceneBoxNode> = if (floor == 0) {
        buildList {
            add(HouseSceneBoxNode(Vec3(0.95f, 2.1f, p), Vec3(doorWx, yBase + 2.1f / 2f, d / 2f + p / 2f),
                material = HouseSceneMaterialSlot.FRONT_DOOR, tappable = true))
            // Null when the bay takes the rest of the elevation — see frontWall.
            groundWinX?.let {
                add(HouseSceneBoxNode(Vec3(winW, winH, p), Vec3(it, winY, d / 2f + p / 2f),
                    material = HouseSceneMaterialSlot.GLASS, tappable = true))
            }
        }
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

    // Whether [style]'s roof is a pitched gable (with a real void under it — see [atticVolume])
    // or a flat slab. The single predicate every roof-shaped decision reads, so "which styles
    // are pitched" is stated once.
    fun isPitchedRoof(style: HomeStyle) = style == HomeStyle.HOUSE || style == HomeStyle.TOWNHOUSE

    /** The pitched roof's resolved dimensions — the one derivation of the slope math that
     *  [roof], [solarArray], [roofSlopePlane], [clampSolarAlong] and [atticVolume] all read,
     *  instead of each re-deriving the same six lines (they had drifted into five near-copies).
     *  [cx]/[cy] are one slope slab's centre; [ridgeH] the ridge's height above [wallTopY]. */
    data class RoofPitch(
        val overhang: Float, val halfSpan: Float, val slantLen: Float,
        val ridgeH: Float, val cx: Float, val cy: Float, val depth: Float,
    )

    fun roofPitch(w: Float, d: Float, wallTopY: Float): RoofPitch {
        val overhang = 0.6f
        val halfSpan = w / 2f + overhang
        val slantLen = (halfSpan / cos(PITCH_RAD)).toFloat()
        val ridgeH = (halfSpan * tan(PITCH_RAD)).toFloat()
        return RoofPitch(
            overhang = overhang, halfSpan = halfSpan, slantLen = slantLen, ridgeH = ridgeH,
            cx = halfSpan / 2f, cy = wallTopY + ridgeH / 2f, depth = d + overhang * 2f,
        )
    }

    // The flat (CONDO) roof slab's own dimensions, named rather than inlined so the slab and
    // anything measuring against it can't drift apart.
    private const val FLAT_ROOF_THICKNESS = 0.35f
    private const val FLAT_ROOF_RISE = 0.18f

    fun roof(w: Float, d: Float, wallTopY: Float, style: HomeStyle): List<HouseSceneBoxNode> {
        return if (isPitchedRoof(style)) {
            val p = roofPitch(w, d, wallTopY)
            val slantLen = p.slantLen
            val cx = p.cx
            val cy = p.cy
            val depth = p.depth
            listOf(
                HouseSceneBoxNode(Vec3(slantLen, 0.15f, depth), Vec3(cx, cy, 0f), rotationDeg = Vec3(0f, 0f, -PITCH_DEG),
                    material = HouseSceneMaterialSlot.ROOF_PITCH, tappable = true),
                HouseSceneBoxNode(Vec3(slantLen, 0.15f, depth), Vec3(-cx, cy, 0f), rotationDeg = Vec3(0f, 0f, PITCH_DEG),
                    material = HouseSceneMaterialSlot.ROOF_PITCH, tappable = true),
            )
        } else {
            listOf(HouseSceneBoxNode(Vec3(w + 0.5f, FLAT_ROOF_THICKNESS, d + 0.5f), Vec3(0f, wallTopY + FLAT_ROOF_RISE, 0f),
                material = HouseSceneMaterialSlot.ROOF_FLAT, tappable = true))
        }
    }

    private const val SOLAR_TILE_COLS = 4
    private const val SOLAR_TILE_ROWS = 5
    private const val SOLAR_TILE_GAP = 0.04f

    // A single user-placed solar array — a grid of small tiles — on one of the two pitched
    // roof slopes (only HOUSE and TOWNHOUSE have a pitched roof — the flat-roof branch this
    // replaced is intentionally not carried forward, since solar is already excluded from
    // attached styles' tray entirely). [along] is the array's position along the ridge
    // (world Z), relative to the roof centre. Replaces the old single-big-panel design (now
    // one item — a grid of many small tiles — instead of several independently-placed panels,
    // both for a nicer look and so there's exactly one region to exempt from the roof's own
    // "tap enters Floor Plan" fallback while placing it, see HouseScene.kt's roofEnterHome).
    fun solarArray(w: Float, wallTopY: Float, onLeftSlope: Boolean, along: Float): List<HouseSceneBoxNode> {
        // d only affects the roof's depth/overhang, which this function never reads — pass 0f.
        val p = roofPitch(w, 0f, wallTopY)
        val slantLen = p.slantLen
        val cx = p.cx
        val cy = p.cy
        val panelW = slantLen * 0.62f
        val normX = sin(PITCH_RAD).toFloat()
        val normY = cos(PITCH_RAD).toFloat()
        // Flush-mount offset, same idiom as the window glass (wall half-thickness + pane
        // half-thickness) and the HVAC grille (body half-thickness + grille half-thickness):
        // roof half-thickness (0.15/2) + panel half-thickness (0.025/2) = 0.0875, plus a
        // small margin so the array reliably clears the roof slab for tap-picking instead of
        // being ~30% embedded in it (the old off=0.08f undershot flush by 7.5mm, letting the
        // much larger roof box win every tap where a panel should have been hit first).
        val off = 0.10f
        val sign = if (onLeftSlope) -1f else 1f
        val baseX = sign * (cx + normX * off)
        val baseY = cy + normY * off

        val tileW = (panelW - SOLAR_TILE_GAP * (SOLAR_TILE_COLS - 1)) / SOLAR_TILE_COLS
        val tileD = (SOLAR_PANEL_DEPTH - SOLAR_TILE_GAP * (SOLAR_TILE_ROWS - 1)) / SOLAR_TILE_ROWS
        val nodes = mutableListOf<HouseSceneBoxNode>()
        for (col in 0 until SOLAR_TILE_COLS) {
            // Local offset across the slope (u), before the roof's own tilt is applied — the
            // array plane is rotated -sign*PITCH_DEG about Z, so a pure local-X offset maps to
            // world (X,Y) via the standard 2D rotation matrix at that angle: worldDX = u*cos,
            // worldDY = -sign*u*sin (cosine is even, sine is odd — matches the single-tile
            // (u=0) case reducing exactly to the old solarPanel's own center formula).
            val u = (col - (SOLAR_TILE_COLS - 1) / 2f) * (tileW + SOLAR_TILE_GAP)
            val worldDX = u * normY
            val worldDY = -sign * u * normX
            for (row in 0 until SOLAR_TILE_ROWS) {
                // Local offset along the ridge (v) — Z is the rotation axis, so no adjustment
                // needed; it's just added straight onto along.
                val v = (row - (SOLAR_TILE_ROWS - 1) / 2f) * (tileD + SOLAR_TILE_GAP)
                nodes += HouseSceneBoxNode(
                    Vec3(tileW, 0.025f, tileD),
                    Vec3(baseX + worldDX, baseY + worldDY, along + v),
                    rotationDeg = Vec3(0f, 0f, -sign * PITCH_DEG),
                    material = HouseSceneMaterialSlot.SOLAR, tappable = true,
                )
            }
        }
        return nodes
    }

    // Same slope math as solarArray() above (halfSpan/slantLen/ridgeH/cx/cy/normX/normY,
    // sign-per-side) but returning the plane itself rather than a grid of tiles on it — the
    // single source of truth both share, so they can't drift apart.
    fun roofSlopePlane(w: Float, wallTopY: Float, onLeftSlope: Boolean): RoofSlopePlane {
        val p = roofPitch(w, 0f, wallTopY)
        val slantLen = p.slantLen
        val cx = p.cx
        val cy = p.cy
        val normX = sin(PITCH_RAD).toFloat()
        val normY = cos(PITCH_RAD).toFloat()
        val sign = if (onLeftSlope) -1f else 1f
        return RoofSlopePlane(
            point = Vec3(sign * cx, cy, 0f),
            normal = Vec3(sign * normX, normY, 0f),
            tangent = Vec3(sign * normY, -normX, 0f),
            slantLen = slantLen,
        )
    }

    const val SOLAR_PANEL_DEPTH = 1.6f

    // Clamps a solar panel's along-ridge position so its own footprint never slides past the
    // roof's ridge-line extent (own function, not clampAlongWall — a ridge isn't a wall).
    fun clampSolarAlong(along: Float, d: Float): Float {
        // w/wallTopY only affect the slope's span and height, neither of which the ridge-line
        // extent depends on — only the roof's depth (d + overhang*2) matters here.
        val maxOff = (roofPitch(0f, d, 0f).depth / 2f - SOLAR_PANEL_DEPTH / 2f).coerceAtLeast(0f)
        return along.coerceIn(-maxOff, maxOff)
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

    // Nudges a wall-snapped item's desired along-wall position away from any other same-wall
    // item's footprint. Garage/HVAC/EV battery can each land on the same wall independently via
    // their own rotate-to-next-side action, which always re-centers at along=0 — without this,
    // two re-centered items just overlap, one rendering behind/inside the other. [occupants] is
    // each other same-side item's (along, alongWidth); tries [desired] first, and if it
    // conflicts, pushes to whichever side of that occupant is closer, repeating until clear of
    // every occupant (bounded to occupants.size+1 passes, enough to converge for the handful of
    // items this ever resolves). Falls back to clampAlongWall's own wall-edge clamp if there
    // simply isn't room to fully clear everyone.
    fun nonOverlappingAlong(
        side: FeatureSide, desired: Float, myWidth: Float,
        occupants: List<Pair<Float, Float>>, w: Float, d: Float, gap: Float = 0.3f,
    ): Float {
        var along = desired
        repeat(occupants.size + 1) {
            val conflict = occupants.firstOrNull { (oAlong, oWidth) -> abs(along - oAlong) < (myWidth + oWidth) / 2f + gap }
            if (conflict != null) {
                val (oAlong, oWidth) = conflict
                val pushDist = (myWidth + oWidth) / 2f + gap
                along = if (along >= oAlong) oAlong + pushDist else oAlong - pushDist
            }
        }
        return clampAlongWall(side, along, w, d, myWidth)
    }

    // Along-wall extents used by nonOverlappingAlong for each wall-snapped item — must match
    // the geometry each renders with (hvac()'s/evBattery()'s body depth on Right/Left/Front/
    // Back, or garageBox()'s w/d depending on which axis [side] slides along).
    const val HVAC_ALONG_WIDTH = 0.85f
    const val EV_BATTERY_ALONG_WIDTH = 0.60f
    fun garageAlongWidth(side: FeatureSide, w: Float, d: Float): Float {
        val box = garageBox(w, d, side)
        return if (side == FeatureSide.FRONT || side == FeatureSide.BACK) box.w else box.d
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
     *  source of truth for "where is the garage" — both [com.homehealth.renderspec.CarLotGeometry.carLot]
     *  and the shell renderer read from this instead of independently re-deriving an anchor,
     *  which is exactly the duplication that let the two drift out of agreement before. */
    fun garageBox(w: Float, d: Float, side: FeatureSide, dx: Float = 0f, dz: Float = 0f): FeatureBox {
        val gW = (w * 0.55f).coerceIn(2.5f, 4.0f)
        val gD = minOf(d, 5.5f)
        val (bx, bz) = garageBaseAnchor(side, w, d, gW, gD)
        return FeatureBox(bx + dx, bz + dz, gW, gD)
    }

    // The along-wall (Z) offset that aligns a Left/Right-mounted garage's own front (door)
    // face flush with the house's actual front wall, instead of centered on the side wall's
    // depth (garageBaseAnchor's un-offset dz=0) — matches how an attached side garage commonly
    // sits in practice. Reuses garageBox's own gD rather than re-deriving the minOf(d,5.5f)
    // formula independently, the same drift risk every other garageBox caller already avoids.
    // Meaningless for FRONT (that garage already IS the house's front face); callers only use
    // this for LEFT/RIGHT.
    fun garageFrontAlignedAlong(side: FeatureSide, w: Float, d: Float): Float {
        val box = garageBox(w, d, side)
        return d / 2f - box.d / 2f
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

    /** The garage door's X-range within [box] — narrower than the room's full width so a strip
     *  of wall remains on either side, centered on the room. Scales with the room's own width
     *  (capped at 4.5m) rather than assuming a fixed car count, since the room itself is no
     *  longer pinned to a fixed minimum size. */
    fun townhouseGarageDoorXRange(box: FeatureBox): Pair<Float, Float> {
        val doorW = minOf(box.w * 0.7f, 4.5f)
        return (box.cx - doorW / 2f) to (box.cx + doorW / 2f)
    }

    /** The door PANEL filling [townhouseGarageDoorXRange]'s opening in the real front wall —
     *  reuses GARAGE_DOOR material/tap dispatch (see emitHouseSceneNodes's onGarageDoorTap),
     *  same as garageShell's door, but as a flat panel matching frontDoorAndGlass's convention
     *  instead of a pivoting shell door (the open/roll-up animation only plays on the driveway,
     *  exactly like the exterior HOUSE garage door never animates in place).
     *
     *  It hangs on the OUTER face of the wall — inner face flush with it — rather than sharing
     *  the wall's own plane the way the front door does. That difference is load-bearing: this
     *  is the one panel on this elevation whose tap does something OTHER than what the wall
     *  around it does (driveway, not floor plan), and at frontDoorAndGlass's placement it stood
     *  proud of the wall by a single millimetre. For picking that's coplanar, so a tap anywhere
     *  near the door's edge resolved to the wall as often as to the door and the same gesture
     *  took you to two different places. Clearing the wall by its half-thickness means the ray
     *  that hits the door's silhouette hits the DOOR, from any angle. */
    fun townhouseGarageDoor(box: FeatureBox, d: Float, p: Float, yBase: Float): List<HouseSceneBoxNode> {
        val (x0, x1) = townhouseGarageDoorXRange(box)
        val doorW = x1 - x0
        return listOf(HouseSceneBoxNode(
            Vec3(doorW, TOWNHOUSE_GARAGE_DOOR_H, p),
            Vec3(box.cx, yBase + TOWNHOUSE_GARAGE_DOOR_H / 2f, d / 2f + WALL_T / 2f + p / 2f),
            material = HouseSceneMaterialSlot.GARAGE_DOOR, tappable = true,
        ))
    }

    private fun deckBaseAnchor(side: FeatureSide, w: Float, d: Float, dkD: Float): Pair<Float, Float> = when (side) {
        FeatureSide.FRONT -> 0f to (d / 2f + dkD / 2f)
        FeatureSide.BACK  -> 0f to -(d / 2f + dkD / 2f)
        FeatureSide.RIGHT -> (w / 2f + dkD / 2f) to 0f
        FeatureSide.LEFT  -> -(w / 2f + dkD / 2f) to 0f
    }

    /** The deck is always full-house-width and always on the BACK wall (single slot, no more
     *  1/3-width multi-instance pieces on any wall) — [side]/[along] are kept only so callers
     *  (PlacedDeck, sdFace derivation) don't need their own shape; in practice [side] is always
     *  BACK and [along] is always 0f, since a full-width box has no room left to slide. */
    fun deckBox(w: Float, d: Float, side: FeatureSide, along: Float = 0f): FeatureBox {
        val dkW = w; val dkD = 2.5f
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
    /** How far the overhead door swings — short of the 90° that would hide it. See [garageShell]. */
    const val DOOR_OPEN_MAX_DEG = 78f

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

        // The door stops just short of flat. A full 90° sweep put it horizontal against the
        // ceiling AND pushed it inward by half its height, leaving nothing at the opening: to
        // close an open door you had to orbit the camera inside the garage to find the panel.
        // Stopping a few degrees out leaves a hand's width of it hanging below the header —
        // visible and tappable from the driveway, where you're standing. The dip is
        // (doorH/2)·cos(78°) ≈ 25 cm on a 2.4 m door, a metre clear of a car rolling out beneath.
        val theta = doorFraction * (DOOR_OPEN_MAX_DEG * kotlin.math.PI / 180.0).toFloat()
        val topY = gH - gWT
        val doorY = topY - doorH / 2f * cos(theta)
        val doorZ = frontFaceZ + inwardDir * (doorH / 2f) * sin(theta)
        val rotX = DOOR_OPEN_MAX_DEG * doorFraction * inwardDir
        nodes += HouseSceneBoxNode(Vec3(gW * 0.84f, doorH, dP), Vec3(cx, doorY, doorZ), rotationDeg = Vec3(rotX, 0f, 0f),
            material = HouseSceneMaterialSlot.GARAGE_DOOR, tappable = true)
        return nodes
    }

    // ── Attic / rooftop mechanical space ──────────────────────────────────────────────────
    //
    // HOUSE/TOWNHOUSE have a genuine void under the pitched roof; CONDO's roof is a flat slab
    // (see roof()) with no void at all, so its equivalent is a small rooftop equipment room.
    // One scene renders both — only the resolved dimensions differ, the same way GarageScene
    // takes gwOverride/gdOverride to render TOWNHOUSE's real garage room with the shell/door
    // machinery it already had.
    //
    // Everything here is in the scene's OWN LOCAL FRAME: the attic floor's top surface is
    // y = 0 and the space is centred on the origin, rather than sitting at its real-world
    // height up on the roof. Same reasoning as CarLotGeometry.recenter/garageSceneLot doing
    // this for the garage: a dedicated sub-scene has no other content to stay aligned with,
    // and SceneView re-centres content on its own bounds every frame (autoCenterContent
    // defaults true), so geometry authored at real roof height would be silently translated
    // out from under a camera written in absolute coordinates.
    const val ATTIC_SIZE = 1.8f
    const val ATTIC_HEIGHT = 1.7f
    private const val ATTIC_SHELL_T = 0.12f
    private const val ATTIC_ITEM_MARGIN = 0.35f
    // Headroom of a FULL attic under a flat roof — a real storey-shaped void rather than the
    // crawl space a pitch gives you, since there's no slope to duck under. Only reachable by
    // choosing AtticType.FULL on a CONDO.
    private const val ATTIC_FLAT_H = 2.2f

    // Framing and insulation module sizes. A pitched attic is the WHOLE house footprint —
    // 15 m x 12 m on the Classic — so everything below is a count derived from the resolved
    // volume rather than a fixed number of pieces: three ~1 m objects adrift on a 180 m² floor
    // is what made this space read as an empty box. Each count is capped so a very large home
    // doesn't turn the attic into a node-count problem.
    private const val ATTIC_BAY_SPACING = 1.0f    // joist bays: joist lines + the batts between
    private const val ATTIC_RAFTER_SPACING = 1.1f
    private const val ATTIC_MAX_BAYS = 10
    private const val ATTIC_MAX_RAFTERS = 12
    private const val ATTIC_JOIST_T = 0.10f
    private const val ATTIC_BATT_H = 0.18f
    // Gable-end framing (see atticShell): studs on centres, and the plates they sit on and run up
    // to. Capped like every other count here so a 20 m gable doesn't become a node-count problem.
    private const val ATTIC_STUD_SPACING = 0.60f
    private const val ATTIC_MAX_STUDS = 16
    private const val ATTIC_STUD_T = 0.09f
    private const val ATTIC_PLATE_H = 0.10f
    // The boarded walk-way down the middle: the air handler stands on it and the duct trunk runs
    // above it, so it's the one strip deliberately NOT insulated — and, since batts cover
    // everything else, it's the bare floor you tap to say what kind of space this is (see
    // AtticType). A fixed 1 m aisle made that needlessly fiddly at any real size: a quarter of a
    // 4 m attic's width, but only a fifteenth of the Classic's 15 m one — a slot between two vast
    // fields of batt rather than somewhere you could walk. So it scales with the attic, floored at
    // the old width and capped so the insulation still reads as covering the floor.
    private const val ATTIC_PATH_MIN = 0.50f
    private const val ATTIC_PATH_MAX = 1.60f
    private const val ATTIC_PATH_FRAC = 0.22f

    /** Half-width of the walk-way for an attic whose insulated span reaches [edge] from centre.
     *  Zero for a space too narrow to give up the middle of — there one batt spans the full bay. */
    private fun atticPathHalf(edge: Float): Float =
        if (edge > ATTIC_PATH_MIN * 2f) (edge * ATTIC_PATH_FRAC).coerceIn(ATTIC_PATH_MIN, ATTIC_PATH_MAX)
        else 0f

    /** Number of joist bays across the attic's depth — shared by the framing in [atticShell] and
     *  the insulation batts in [atticItemNodes] so the batts always land BETWEEN the joists. */
    private fun atticBayCount(v: AtticVolume): Int =
        ((v.halfD * 2f) / ATTIC_BAY_SPACING).toInt().coerceIn(2, ATTIC_MAX_BAYS)

    /** The attic's resolved interior dimensions — the single answer to "how big is the attic",
     *  mirroring how [garageBox]/[townhouseGarageBox] answer it for the garage. [height] is the
     *  headroom at the tallest point (a pitched roof's ridge, or the rooftop room's ceiling). */
    data class AtticVolume(val halfW: Float, val halfD: Float, val height: Float, val pitched: Boolean)

    /** What a home of this style has up top unless the owner says otherwise (see [AtticType]).
     *  A pitched roof comes with a real void; a flat one doesn't, so its default is the closet. */
    fun defaultAtticType(style: HomeStyle): AtticType =
        if (isPitchedRoof(style)) AtticType.FULL else AtticType.CLOSET

    /**
     * The two questions this answers are deliberately separate. **Size** comes from [type] — the
     * owner's call, a walk-in attic over the whole footprint or a mechanical closet. **Pitch**
     * comes from the roof, which is not: rafters and a ridge belong to a gable the exterior
     * actually has. That's why [AtticVolume] carries `pitched` as its own flag rather than
     * inferring it from the dimensions, and it's what lets a CONDO have a full attic (flat-ceilinged,
     * still joisted and insulated) and a HOUSE have a closet without either contradicting its roof.
     */
    fun atticVolume(w: Float, d: Float, wallTopY: Float, style: HomeStyle, type: AtticType): AtticVolume =
        when {
            type == AtticType.CLOSET ->
                AtticVolume(halfW = ATTIC_SIZE / 2f, halfD = ATTIC_SIZE / 2f, height = ATTIC_HEIGHT, pitched = false)
            isPitchedRoof(style) ->
                AtticVolume(halfW = w / 2f, halfD = d / 2f, height = roofPitch(w, d, wallTopY).ridgeH, pitched = true)
            else ->
                AtticVolume(halfW = w / 2f, halfD = d / 2f, height = ATTIC_FLAT_H, pitched = false)
        }

    /**
     * The attic as a place you can walk into and stand in, centred on the home's origin — the
     * synthetic zone the room view focuses on, exactly as [CarLotGeometry.drivewayZone] is for the
     * driveway. Typed [RoomType.STORAGE] for the same reason that one reuses GARAGE: it keeps the
     * save format, the floor plan's type picker, [RoomPalette] and the backdrop's material table
     * all untouched. The attic is never a RoomPlacement, so it can't appear on the floor plan.
     */
    fun atticZone(v: AtticVolume): RoomZone = RoomZone(
        type = RoomType.STORAGE,
        xMin = -v.halfW, xMax = v.halfW,
        zMin = -v.halfD, zMax = v.halfD,
    )

    /**
     * The attic's enclosure: floor, back wall, and two side walls — deliberately **no ceiling
     * and no near (+Z) wall**, so the camera looks in through the open face and down over the
     * walls. Same cutaway convention RoomScene uses (it renders no ceiling for exactly this
     * reason). The previous version reused garageShell() verbatim, which sealed the equipment
     * inside a closed box that both hid it and — because SceneView dispatches a tap only to the
     * frontmost touchable node, never falling through — swallowed every tap aimed at it.
     *
     * A pitched attic's side walls are short knee walls at the eaves (where the roof meets the
     * top plate); a closet's are full height.
     *
     * Every wall is [HouseSceneMaterialSlot.ATTIC_WALL] — rendered see-through — while the floor
     * alone is opaque [HouseSceneMaterialSlot.ATTIC_SHELL]. Height couldn't solve this on its own:
     * the air handler stands at the BACK, so the natural way to get a close look at it is to orbit
     * round behind, which puts the full-height gable end squarely in front of it, and a closet's
     * side walls are full height too. None of them ever swallowed a tap (they're untouchable, and
     * SceneView dispatches only to the frontmost TOUCHABLE node), so the problem was always purely
     * one of sight — which is exactly what transparency fixes, and what RoomScene's own walls
     * already do for the same reason.
     */
    fun atticShell(v: AtticVolume, hatchFraction: Float): List<HouseSceneBoxNode> {
        val t = ATTIC_SHELL_T
        val wallH = if (v.pitched) (v.height * 0.28f).coerceIn(0.35f, 1.0f) else v.height
        val nodes = mutableListOf<HouseSceneBoxNode>()
        // Floor — its TOP face sits at y = 0, so items placed at y = 0 rest on it rather than
        // sinking into it (the old version's unit was buried 5 cm below the floor's surface).
        //
        // Tappable, unlike every other shell panel: bare boards are what you tap to ask what this
        // space IS (attic or utility closet — see AtticType), the same gesture RoomScene's floor
        // already uses to open a room's change/remove card. It sits under everything, so making it
        // touchable can't intercept a tap meant for the contents. In a full attic the batts cover
        // most of it, leaving the boarded walk-way and the perimeter strip as the target — which
        // are exactly the parts that read as bare floor.
        nodes += HouseSceneBoxNode(Vec3(v.halfW * 2f, t, v.halfD * 2f), Vec3(0f, -t / 2f, 0f),
            material = HouseSceneMaterialSlot.ATTIC_SHELL, tappable = true)
        // ── Gable end ─────────────────────────────────────────────────────────────────────
        // The far (-Z) end, FRAMED rather than sheeted: a sole plate, studs on regular centres
        // each cut to the roof line above it, and — under a pitch — the sloping top chords the
        // studs run up to. That's how a gable end is actually built, and it's structure rather
        // than a slab, which is the same thing that tells you the rest of this space is an attic.
        //
        // It also solves what a solid panel here could not. The air handler stands at the BACK, so
        // the natural way to get a close look at it is to orbit round behind — where a full-height
        // gable slab sat squarely in front of it. Framing is open between the studs, so the
        // equipment stays visible from every angle without anything having to be faded out.
        val studZ = -v.halfD + t / 2f
        nodes += HouseSceneBoxNode(Vec3(v.halfW * 2f, ATTIC_PLATE_H, t),
            Vec3(0f, ATTIC_PLATE_H / 2f, studZ), material = HouseSceneMaterialSlot.ATTIC_FRAMING)
        val studs = ((v.halfW * 2f) / ATTIC_STUD_SPACING).toInt().coerceIn(2, ATTIC_MAX_STUDS)
        for (i in 0..studs) {
            val sx = -v.halfW + i * (v.halfW * 2f / studs)
            // Under a pitch the roof closes in toward the eaves, so each stud is shorter than the
            // last — the stepped row that reads instantly as a gable. A flat ceiling gives a
            // plain full-height row instead.
            val studH = if (v.pitched) v.height * (1f - abs(sx) / v.halfW) else v.height
            if (studH < ATTIC_PLATE_H * 2f) continue   // nothing worth framing right at the eave
            nodes += HouseSceneBoxNode(Vec3(ATTIC_STUD_T, studH, t),
                Vec3(sx, studH / 2f, studZ), material = HouseSceneMaterialSlot.ATTIC_FRAMING)
        }
        if (v.pitched) {
            // Top chords: the same slope the rafters run at, closing the triangle the studs fill.
            val chordLen = sqrt(v.halfW * v.halfW + v.height * v.height)
            val chordDeg = (atan2(v.height.toDouble(), v.halfW.toDouble()) * 180.0 / kotlin.math.PI).toFloat()
            for (sign in listOf(1f, -1f)) {
                nodes += HouseSceneBoxNode(
                    Vec3(chordLen, ATTIC_STUD_T, t),
                    Vec3(sign * v.halfW / 2f, v.height / 2f, studZ),
                    rotationDeg = Vec3(0f, 0f, -sign * chordDeg),
                    material = HouseSceneMaterialSlot.ATTIC_FRAMING,
                )
            }
        } else {
            nodes += HouseSceneBoxNode(Vec3(v.halfW * 2f, ATTIC_PLATE_H, t),
                Vec3(0f, v.height - ATTIC_PLATE_H / 2f, studZ),
                material = HouseSceneMaterialSlot.ATTIC_FRAMING)
        }
        // Side walls.
        for (sign in listOf(-1f, 1f)) {
            nodes += HouseSceneBoxNode(Vec3(t, wallH, v.halfD * 2f), Vec3(sign * (v.halfW - t / 2f), wallH / 2f, 0f),
                material = HouseSceneMaterialSlot.ATTIC_WALL)
        }
        // Access hatch in the floor, hinged at its far edge and swinging up/open as
        // [hatchFraction] goes 0→1 (a floor hatch, unlike the garage's overhead door — there's
        // no ceiling here for an overhead panel to fold against, which is what left the old
        // version's hatch interpenetrating the ceiling and unreachable once open).
        //
        // Deliberately small and parked hard against the near (+Z) edge, in front of every item
        // slot: standing upright at full open it must not intersect the contents — which it did
        // at a rooftop room's size, where the panel came up right through the air handler — and
        // it stays short enough that the camera's downward sight line clears it.
        // ── Framing ───────────────────────────────────────────────────────────────────────
        // Rafters, ridge beam and floor joists. Structure is most of what tells you a space is
        // an attic rather than a small room, and it's what gives the insulation batts below
        // something to sit between. Pitched roofs only — CONDO's rooftop equipment room is a
        // finished room with a flat ceiling, not a framed void.
        //
        // All non-tappable: the same frontmost-touchable-node rule the shell panels above
        // document applies here, and a rafter hanging between the camera and the ductwork would
        // otherwise eat every tap aimed at it.
        if (v.pitched) {
            // Rafters land on the floor plate at the eaves and meet at the ridge, so the slope
            // is derived from the volume itself — roofPitch()'s 25° is measured over halfSpan,
            // which includes the eave OVERHANG the attic's interior half-width doesn't have.
            val rafterLen = sqrt(v.halfW * v.halfW + v.height * v.height)
            val rafterDeg = (atan2(v.height.toDouble(), v.halfW.toDouble()) * 180.0 / kotlin.math.PI).toFloat()
            val rafters = ((v.halfD * 2f) / ATTIC_RAFTER_SPACING).toInt().coerceIn(2, ATTIC_MAX_RAFTERS)
            for (i in 0 until rafters) {
                val rz = -v.halfD + (i + 0.5f) / rafters * v.halfD * 2f
                // Sign convention matches roof(): the +X slab tilts by -pitch, the -X by +pitch.
                for (sign in listOf(1f, -1f)) {
                    nodes += HouseSceneBoxNode(
                        Vec3(rafterLen, 0.10f, 0.09f),
                        Vec3(sign * v.halfW / 2f, v.height / 2f, rz),
                        rotationDeg = Vec3(0f, 0f, -sign * rafterDeg),
                        material = HouseSceneMaterialSlot.ATTIC_FRAMING,
                    )
                }
            }
            nodes += HouseSceneBoxNode(
                Vec3(0.14f, 0.22f, v.halfD * 2f - 0.1f), Vec3(0f, v.height - 0.11f, 0f),
                material = HouseSceneMaterialSlot.ATTIC_FRAMING,
            )
            // Joists on the bay boundaries — [atticBayCount] is shared with the batts so the
            // insulation always fills the gaps rather than covering the timber.
            val bays = atticBayCount(v)
            val step = (v.halfD * 2f) / bays
            for (i in 0..bays) {
                nodes += HouseSceneBoxNode(
                    Vec3(v.halfW * 2f - t * 2f, ATTIC_JOIST_T, 0.09f),
                    Vec3(0f, ATTIC_JOIST_T / 2f, -v.halfD + i * step),
                    material = HouseSceneMaterialSlot.ATTIC_FRAMING,
                )
            }
        }

        val hatchW = minOf(0.80f, v.halfW * 0.5f)
        val hatchD = minOf(0.50f, v.halfD * 0.45f)
        val hz = v.halfD - hatchD / 2f - 0.12f
        val theta = hatchFraction * (kotlin.math.PI / 2.0).toFloat()
        val hingeZ = hz - hatchD / 2f
        nodes += HouseSceneBoxNode(
            Vec3(hatchW, 0.06f, hatchD),
            Vec3(0f, (hatchD / 2f) * sin(theta), hingeZ + (hatchD / 2f) * cos(theta)),
            rotationDeg = Vec3(-90f * hatchFraction, 0f, 0f),
            material = HouseSceneMaterialSlot.ATTIC_HATCH, tappable = true,
        )
        return nodes
    }

    /** Callers render one item per entry, so adding an item later is a one-line change here. */
    enum class AtticItem { AIR_HANDLER, DUCTWORK, INSULATION }

    /**
     * What's actually up there. A closet is the air handler and nothing else — it's a cupboard
     * for the equipment, not an insulated void — while a full attic gets the whole set.
     *
     * [removed] holds bare [HiddenAsset] and [HomeSystem] names (the convention `removedInstances`
     * already uses), so an item the owner has switched off in the attic pane's inventory row stops
     * being drawn, the same way a room stops drawing a default it was told isn't there. Both
     * key kinds are checked: the air handler is HVAC's, not a HiddenAsset's (see [homeSystem]).
     *
     * The filter applies to the closet too — a utility closet holding an air handler the owner
     * says isn't there is just as wrong as a full attic holding one.
     */
    fun atticContents(type: AtticType, removed: Set<String> = emptySet()): List<AtticItem> =
        (if (type == AtticType.CLOSET) listOf(AtticItem.AIR_HANDLER)
         else listOf(AtticItem.AIR_HANDLER, AtticItem.DUCTWORK, AtticItem.INSULATION))
            .filterNot { it.hiddenAsset?.name in removed || it.homeSystem?.name in removed }

    /**
     * Where [item] sits on the attic floor (y = 0 is the floor's surface). Per-item rather than
     * an even spread over an index, because these three things are not peers competing for
     * shelf space: the air handler stands on the boarded centre walk-way, the duct system grows
     * out of the air handler, and the insulation is the floor itself. The old index/count spread
     * placed them at thirds of the attic's WIDTH — which on a real pitched attic (the whole
     * house footprint) meant three small objects marooned metres apart.
     */
    fun atticSlot(v: AtticVolume, item: AtticItem): Vec3 = when {
        // The rooftop equipment room is barely wider than the unit itself — nothing to lay out.
        !v.pitched -> Vec3(0f, 0f, -v.halfD * 0.2f)
        // Back of the walk-way, so the trunk has the attic's whole depth to run toward the
        // open (+Z) face the camera looks in through.
        item == AtticItem.AIR_HANDLER || item == AtticItem.DUCTWORK ->
            Vec3(0f, 0f, -v.halfD + ATTIC_ITEM_MARGIN + 0.45f)
        else -> Vec3(0f, 0f, 0f)   // insulation covers the floor; it has no single spot
    }

    /** One attic item's geometry, resting on the floor at [at] (y = 0 is the floor's surface),
     *  sized to [v] so a 15 m x 12 m pitched attic gets a real duct system and a floor-wide
     *  blanket rather than the same three fixed boxes a 1.8 m rooftop closet gets.
     *  Every tappable node returned belongs to that one item, so the caller binds a single
     *  shared tap closure per call — the "one geometry call per target, one forEach per call"
     *  contract the other render-spec functions already follow. */
    fun atticItemNodes(item: AtticItem, at: Vec3, v: AtticVolume): List<HouseSceneBoxNode> {
        val nodes = mutableListOf<HouseSceneBoxNode>()
        when (item) {
            // Indoor air handler / furnace cabinet — the merged HVAC system's indoor half (the
            // outdoor condenser is the wall-mounted unit, see hvac()).
            AtticItem.AIR_HANDLER -> {
                nodes += HouseSceneBoxNode(Vec3(0.60f, 0.90f, 0.50f), Vec3(at.x, at.y + 0.45f, at.z),
                    material = HouseSceneMaterialSlot.HVAC_BODY, tappable = true)
                nodes += HouseSceneBoxNode(Vec3(0.56f, 0.06f, 0.46f), Vec3(at.x, at.y + 0.93f, at.z),
                    material = HouseSceneMaterialSlot.HVAC_GRILLE)
            }
            // A real trunk-and-branch system: one trunk leaving the air handler and running the
            // attic's depth above the walk-way, branch runs taking off both sides, and a boot
            // dropping from each branch end through the ceiling below.
            AtticItem.DUCTWORK -> {
                // Held under the roof plane, which closes in as you move out from the ridge: a
                // branch whose OUTER END is at xEnd has headroom height * (1 - xEnd / halfW), so
                // solving that for the trunk height is what keeps the runs from spearing through
                // the rafters. The 0.62 backs off the trunk's own half-width (0.17), the branch's
                // half-height, and a clearance margin — measuring to the branch's centre instead
                // leaves the far tip poking a few centimetres through the roof.
                val ductY = (v.height * 0.42f).coerceIn(0.55f, 1.30f)
                val reach = (v.halfW * (1f - ductY / v.height) - 0.62f).coerceAtLeast(0.40f)
                val farZ = v.halfD - ATTIC_ITEM_MARGIN - 0.60f
                val trunkLen = (farZ - at.z).coerceAtLeast(0.8f)
                val trunkCz = at.z + trunkLen / 2f
                nodes += HouseSceneBoxNode(Vec3(0.34f, 0.30f, trunkLen), Vec3(at.x, at.y + ductY, trunkCz),
                    material = HouseSceneMaterialSlot.DUCT, tappable = true)
                val pairs = (trunkLen / 1.7f).toInt().coerceIn(1, 4)
                for (i in 0 until pairs) {
                    val bz = at.z + (i + 0.7f) / pairs * trunkLen
                    for (sign in listOf(1f, -1f)) {
                        nodes += HouseSceneBoxNode(
                            Vec3(reach, 0.20f, 0.20f),
                            Vec3(at.x + sign * (0.17f + reach / 2f), at.y + ductY, bz),
                            material = HouseSceneMaterialSlot.DUCT, tappable = true)
                        // Register boot — drops from the branch end toward the ceiling below.
                        val dropH = (ductY - 0.22f).coerceAtLeast(0.16f)
                        nodes += HouseSceneBoxNode(
                            Vec3(0.22f, dropH, 0.22f),
                            Vec3(at.x + sign * (0.17f + reach), at.y + ductY - dropH / 2f, bz),
                            material = HouseSceneMaterialSlot.DUCT, tappable = true)
                    }
                }
            }
            // Batts laid between the joists, covering the whole floor except the boarded centre
            // walk-way the air handler stands on and the duct trunk runs above. Bay boundaries
            // come from the same [atticBayCount] the joists in [atticShell] use, so a batt never
            // lands on top of a joist.
            AtticItem.INSULATION -> {
                val bays = atticBayCount(v)
                val step = (v.halfD * 2f) / bays
                val battD = (step - 0.16f).coerceAtLeast(0.12f)
                val edge = v.halfW - ATTIC_SHELL_T - 0.08f
                val path = atticPathHalf(edge)
                val battW = (edge - path).coerceAtLeast(0.10f)
                for (i in 0 until bays) {
                    val bz = -v.halfD + (i + 0.5f) * step
                    val signs = if (path > 0f) listOf(1f, -1f) else listOf(0f)
                    for (sign in signs) {
                        nodes += HouseSceneBoxNode(
                            Vec3(if (path > 0f) battW else edge * 2f, ATTIC_BATT_H, battD),
                            Vec3(at.x + sign * (path + battW / 2f), at.y + ATTIC_BATT_H / 2f, bz),
                            material = HouseSceneMaterialSlot.INSULATION, tappable = true)
                    }
                }
            }
        }
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

    /** The walls a wall-mounted utility (HVAC condenser, EV battery) may sit on. FRONT is
     *  excluded everywhere — a condenser or battery parked across the home's front elevation
     *  looks wrong, which is why the actions bar's rotate has always skipped it. Drop handlers
     *  and live drags both read this, so no path can produce a FRONT unit the rotate button
     *  then can't cycle away from. */
    val UTILITY_SIDES = listOf(FeatureSide.RIGHT, FeatureSide.LEFT, FeatureSide.BACK)

    /** Moves a wall-mounted utility off FRONT if it somehow got there. Idempotent — any
     *  already-valid side passes through. */
    fun coerceUtilitySide(side: FeatureSide): FeatureSide =
        if (side in UTILITY_SIDES) side else FeatureSide.RIGHT

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

    // A wall-mounted EV battery pack — single slot like hvac(), any of the 4 walls. Same height
    // as the HVAC unit now (was 0.60f, too short a tap target on screen next to HVAC's 0.90f);
    // footprint is bigger too, though still slimmer than HVAC's condenser bulk.
    fun evBattery(evSide: FeatureSide, w: Float, d: Float, offset: Float): List<HouseSceneBoxNode> {
        val unitH = 0.90f; val unitY = unitH / 2f; val gap = 0.10f
        fun pair(bodySize: Vec3, bodyPos: Vec3, ledSize: Vec3, ledPos: Vec3) = listOf(
            HouseSceneBoxNode(bodySize, bodyPos, material = HouseSceneMaterialSlot.EV_BATTERY_BODY, tappable = true),
            HouseSceneBoxNode(ledSize, ledPos, material = HouseSceneMaterialSlot.EV_BATTERY_LED),
        )
        return when (evSide) {
            FeatureSide.RIGHT -> {
                val ux = w / 2f + gap + 0.16f
                pair(Vec3(0.32f, unitH, 0.60f), Vec3(ux, unitY, offset), Vec3(0.04f, 0.14f, 0.08f), Vec3(ux + 0.16f, unitY, offset))
            }
            FeatureSide.LEFT -> {
                val ux = -(w / 2f + gap + 0.16f)
                pair(Vec3(0.32f, unitH, 0.60f), Vec3(ux, unitY, offset), Vec3(0.04f, 0.14f, 0.08f), Vec3(ux - 0.16f, unitY, offset))
            }
            FeatureSide.FRONT -> {
                val uz = d / 2f + gap + 0.16f
                pair(Vec3(0.60f, unitH, 0.32f), Vec3(offset, unitY, uz), Vec3(0.08f, 0.14f, 0.04f), Vec3(offset, unitY, uz + 0.16f))
            }
            FeatureSide.BACK -> {
                val uz = -(d / 2f + gap + 0.16f)
                pair(Vec3(0.60f, unitH, 0.32f), Vec3(offset, unitY, uz), Vec3(0.08f, 0.14f, 0.04f), Vec3(offset, unitY, uz - 0.16f))
            }
        }
    }

    // Approximate footprint (w, d) used to keep a freely-placed tree/gazebo clear of the house
    // footprint (keepOutsideHouse) and within the yard's clamp rectangle at drop/drag time.
    fun yardDecorFootprint(kind: YardDecorKind): Pair<Float, Float> = when (kind) {
        YardDecorKind.TREE   -> 1.7f to 1.7f
        YardDecorKind.GAZEBO -> 3.2f to 3.2f
    }

    // A simple layered tree: trunk + two stacked foliage cubes tapering upward — port of
    // NeighborhoodSceneGeometry.tree() at a fixed scale for the player's own yard.
    fun treeDecor(cx: Float, cz: Float): List<HouseSceneBoxNode> {
        val trunkH = 1.3f
        return listOf(
            HouseSceneBoxNode(Vec3(0.30f, trunkH, 0.30f), Vec3(cx, trunkH / 2f, cz), material = HouseSceneMaterialSlot.TREE_TRUNK, tappable = true),
            HouseSceneBoxNode(Vec3(1.7f, 1.3f, 1.7f), Vec3(cx, trunkH + 0.65f, cz), material = HouseSceneMaterialSlot.TREE_LEAF, tappable = true),
            HouseSceneBoxNode(Vec3(1.1f, 1.0f, 1.1f), Vec3(cx, trunkH + 1.8f, cz), material = HouseSceneMaterialSlot.TREE_LEAF, tappable = true),
        )
    }

    // A small open-sided gazebo: floor slab (reuses DECK's material, no new slot needed) + 4
    // corner posts + a peaked two-slope roof cap, reusing roof()'s slanted-box math at a small
    // scale (own fixed pitch — a gazebo roof doesn't need to track the house's PITCH_DEG).
    fun gazebo(cx: Float, cz: Float): List<HouseSceneBoxNode> {
        val span = 2.6f; val half = span / 2f; val postH = 2.3f; val postT = 0.12f
        val gazeboPitchDeg = 30f
        val gazeboPitchRad = kotlin.math.PI / 180.0 * gazeboPitchDeg
        val slantLen = (half / cos(gazeboPitchRad)).toFloat()
        val ridgeH = (half * tan(gazeboPitchRad)).toFloat()
        val roofCy = postH + ridgeH / 2f
        val roofCx = half / 2f
        val nodes = mutableListOf<HouseSceneBoxNode>()
        nodes += HouseSceneBoxNode(Vec3(span, 0.15f, span), Vec3(cx, 0.075f, cz), material = HouseSceneMaterialSlot.DECK, tappable = true)
        listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).forEach { (sx, sz) ->
            nodes += HouseSceneBoxNode(
                Vec3(postT, postH, postT),
                Vec3(cx + sx * (half - postT / 2f), postH / 2f, cz + sz * (half - postT / 2f)),
                material = HouseSceneMaterialSlot.GAZEBO_POST, tappable = true,
            )
        }
        nodes += HouseSceneBoxNode(Vec3(slantLen, 0.10f, span + 0.3f), Vec3(cx + roofCx, roofCy, cz), rotationDeg = Vec3(0f, 0f, -gazeboPitchDeg),
            material = HouseSceneMaterialSlot.GAZEBO_ROOF, tappable = true)
        nodes += HouseSceneBoxNode(Vec3(slantLen, 0.10f, span + 0.3f), Vec3(cx - roofCx, roofCy, cz), rotationDeg = Vec3(0f, 0f, gazeboPitchDeg),
            material = HouseSceneMaterialSlot.GAZEBO_ROOF, tappable = true)
        return nodes
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
