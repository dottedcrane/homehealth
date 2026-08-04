package com.homehealth.renderspec

import com.homehealth.scene.FLOOR_HEIGHT_M
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class NeighborhoodMaterialSlot {
    GROUND, ROAD,
    LOT, LOT_STRIPE,
    NBR_SLAB, NBR_CONDO_BASE, NBR_WALL, NBR_DOOR, NBR_WINDOW,
    NBR_ROOF, NBR_FLAT_ROOF,
    // Facade detail — see [HomeFacade]. NBR_TRIM doubles as window casing, fascia and porch
    // posts; the paving slots are shared with the park's path.
    NBR_TRIM, NBR_SHUTTER, NBR_GARAGE_DOOR, NBR_PAVING, NBR_CHIMNEY,
    TREE_TRUNK, TREE_LEAF,
    PARK_LAWN, PARK_STRUCTURE, PARK_ACCENT,
    CAR_BODY, CAR_CABIN, CAR_WHEEL,
    MARKER_POST, MARKER_FLAG,
}

/**
 * Per-home exterior detail for [NeighborhoodSceneGeometry.blockHome]. The neighborhood's homes
 * used to be one extruded block each — slab, walls, a door, four windows a floor, a roof — so
 * five different model homes read as five identical cream boxes, and the two models whose floor
 * plans bake in a real garage bay showed no garage at all.
 *
 * [wallVariant] indexes the body-colour palette (same idea as [NeighborhoodBoxNode.materialVariant]
 * for tree foliage and car paint). [garageDoorX] is the door's centre offset along the front
 * wall in the home's OWN un-rotated frame, +X to the right — resolved by the caller from that
 * model's actual layout rather than guessed here, so the massing agrees with the home you get on
 * claiming it. Null means no garage.
 */
data class HomeFacade(
    val wallVariant: Int = 0,
    val garageDoorX: Float? = null,
    val garageDoorW: Float = 4.0f,
    val chimney: Boolean = false,
)

/** A rectangular keep-out on the ground plane — parking pads and the park — that the scenery
 *  scatter steers trees and parked cars around. Replaces the old fixed LOT_W/LOT_D pair, which
 *  could only describe one pad size. */
data class GroundRect(val cx: Float, val cz: Float, val halfW: Float, val halfD: Float)

data class NeighborhoodBoxNode(
    val size: Vec3,
    val position: Vec3,
    // Reuses Vec3 for a 3rd purpose — matches io.github.sceneview.math.Rotation(x,y,z).
    val rotationDeg: Vec3 = Vec3(0f, 0f, 0f),
    val material: NeighborhoodMaterialSlot,
    // Palette index for the two "pick from a small fixed list" cases: tree foliage
    // size (0 = large/leafMat, 1 = small/leafMat2), car body color (i % palette.size).
    val materialVariant: Int = 0,
    // Every tappable box within one build() call resolves to the exact same closure,
    // bound at the composable call site — callers must invoke each build function
    // once per lot/instance and wire its own result, never batch multiple instances'
    // nodes into one list before mapping, or every tap would resolve to whichever
    // instance's callback the last forEach iteration captured.
    val tappable: Boolean = false,
)

private const val NBR_PITCH_DEG = 25f
private val NBR_PITCH_RAD = kotlin.math.PI / 180.0 * NBR_PITCH_DEG

// Pad dimensions are no longer a fixed pair of constants — the lot is sized from its stall count
// by NeighborhoodSceneGeometry.condoLotRect, which the scenery filter reads too.

object NeighborhoodSceneGeometry {

    private fun box(
        nodes: MutableList<NeighborhoodBoxNode>,
        size: Vec3, position: Vec3,
        material: NeighborhoodMaterialSlot,
        rotationDeg: Vec3 = Vec3(0f, 0f, 0f),
        materialVariant: Int = 0,
        tappable: Boolean = false,
    ) {
        nodes += NeighborhoodBoxNode(size, position, rotationDeg, material, materialVariant, tappable)
    }

    // ── The neighbourhood's plan ─────────────────────────────────────────────────────────────
    // A green in the middle, the homes gathered round it, and the traffic kept outside them: the
    // main road is a loop enclosing every home, with spurs running off to the edges of the map.
    // Nothing crosses the middle — you reach the park from your own front path (see [walkway]),
    // which is what makes it read as a shared green rather than a roundabout.
    //
    // Every position in this file, in NeighborPresets' lots and in the scene's own placements is
    // measured against these, so the plan can be re-proportioned from one place. The user's home
    // sits at the origin and cannot move (every other scene is built around that), so the loop is
    // laid out around IT: the north side is the road its driveway already runs out to.
    const val PARK_CX = 0f
    const val PARK_CZ = -30f
    private const val PARK_W = 26f
    private const val PARK_D = 22f

    const val RING_N =  15f
    const val RING_S = -82f
    const val RING_W = -38f
    const val RING_E =  38f
    private const val ROAD_W = 8f
    private const val SPUR_W = 6f

    /** Ground plane, the ring road round the homes, and the spurs that leave the neighbourhood. */
    fun groundAndRoads(): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val groundCz = -25f
        val groundHalf = 65f
        box(nodes, Vec3(130f, 0.10f, 130f), Vec3(0f, -0.12f, groundCz), NeighborhoodMaterialSlot.GROUND)

        // The loop. North and south run the full width including the corners; east and west stop
        // short of them, so no two slabs overlap — stacked road slabs read as a darker seam.
        val loopW = (RING_E - RING_W) + ROAD_W
        val loopD = (RING_N - RING_S) - ROAD_W
        val loopCx = (RING_E + RING_W) / 2f
        val loopCz = (RING_N + RING_S) / 2f
        box(nodes, Vec3(loopW, 0.08f, ROAD_W), Vec3(loopCx, -0.06f, RING_N), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(loopW, 0.08f, ROAD_W), Vec3(loopCx, -0.06f, RING_S), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(ROAD_W, 0.08f, loopD), Vec3(RING_W, -0.06f, loopCz), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(ROAD_W, 0.08f, loopD), Vec3(RING_E, -0.06f, loopCz), NeighborhoodMaterialSlot.ROAD)

        // Spurs off the loop to the edge of the map — the way out of the neighbourhood. The north
        // one continues the line the user's own driveway already meets.
        val northSpur = (groundCz + groundHalf) - (RING_N + ROAD_W / 2f)
        box(nodes, Vec3(SPUR_W, 0.08f, northSpur), Vec3(0f, -0.06f, RING_N + ROAD_W / 2f + northSpur / 2f),
            NeighborhoodMaterialSlot.ROAD)
        val sideSpur = -groundHalf - (RING_W - ROAD_W / 2f)
        box(nodes, Vec3(-sideSpur, 0.08f, SPUR_W), Vec3(RING_W - ROAD_W / 2f + sideSpur / 2f, -0.06f, PARK_CZ),
            NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(-sideSpur, 0.08f, SPUR_W), Vec3(RING_E + ROAD_W / 2f - sideSpur / 2f, -0.06f, PARK_CZ),
            NeighborhoodMaterialSlot.ROAD)
        return nodes
    }

    /**
     * A footpath from a home's front door to the edge of the park — the short walk that makes the
     * green a shared back yard rather than scenery you drive past. Drawn as one paving slab
     * rotated onto the line between the two points, so it works from any lot on the ring without
     * per-home elbows; it stops [stopShort] before the park so it meets the lawn rather than
     * riding over it.
     */
    fun walkway(
        fromX: Float, fromZ: Float,
        toX: Float = PARK_CX, toZ: Float = PARK_CZ,
        width: Float = 1.6f,
        stopShort: Float = PARK_W.coerceAtMost(PARK_D) / 2f,
    ): List<NeighborhoodBoxNode> {
        val dx = toX - fromX
        val dz = toZ - fromZ
        val dist = kotlin.math.sqrt(dx * dx + dz * dz)
        val len = dist - stopShort
        if (len <= 0.4f) return emptyList()
        val t = (len / 2f) / dist
        // Yaw measured the same way decorCar/blockHome measure theirs: +Z is 0°, turning toward +X.
        val yaw = (kotlin.math.atan2(dx.toDouble(), dz.toDouble()) * 180.0 / kotlin.math.PI).toFloat()
        return listOf(
            NeighborhoodBoxNode(
                size = Vec3(width, 0.06f, len),
                position = Vec3(fromX + dx * t, 0.02f, fromZ + dz * t),
                rotationDeg = Vec3(0f, yaw, 0f),
                material = NeighborhoodMaterialSlot.NBR_PAVING,
            )
        )
    }

    /** A simple layered tree: trunk + two stacked foliage cubes tapering upward. [s] scales
     *  the whole tree; foliage palette index mirrors the source's `s > 1.05f` threshold. */
    fun tree(wx: Float, wz: Float, s: Float): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val variant = if (s > 1.05f) 0 else 1
        val trunkH = 1.3f * s
        box(nodes, Vec3(0.30f * s, trunkH, 0.30f * s), Vec3(wx, trunkH / 2f, wz), NeighborhoodMaterialSlot.TREE_TRUNK)
        box(nodes, Vec3(1.7f * s, 1.3f * s, 1.7f * s), Vec3(wx, trunkH + 0.65f * s, wz), NeighborhoodMaterialSlot.TREE_LEAF, materialVariant = variant)
        box(nodes, Vec3(1.1f * s, 1.0f * s, 1.1f * s), Vec3(wx, trunkH + 1.3f * s + 0.5f * s, wz), NeighborhoodMaterialSlot.TREE_LEAF, materialVariant = variant)
        return nodes
    }

    /** A decorative parked car (body + cabin + 4 wheels), yaw-rotated: each cube's local
     *  offset is rotated into world space and the cube itself gets the yaw. */
    fun decorCar(wx: Float, wz: Float, yawDeg: Float, bodyVariant: Int): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val rad = kotlin.math.PI / 180.0 * yawDeg
        val sinY = sin(rad).toFloat()
        val cosY = cos(rad).toFloat()
        fun at(ox: Float, y: Float, oz: Float) =
            Vec3(wx + cosY * ox + sinY * oz, y, wz - sinY * ox + cosY * oz)
        val rot = Vec3(0f, yawDeg, 0f)
        box(nodes, Vec3(1.7f, 0.5f, 3.4f), at(0f, 0.55f, 0f), NeighborhoodMaterialSlot.CAR_BODY, rotationDeg = rot, materialVariant = bodyVariant)
        box(nodes, Vec3(1.2f, 0.45f, 1.7f), at(0f, 1.0f, -0.15f), NeighborhoodMaterialSlot.CAR_CABIN, rotationDeg = rot)
        listOf(-0.75f to 1.15f, 0.75f to 1.15f, -0.75f to -1.15f, 0.75f to -1.15f).forEach { (ox, oz) ->
            box(nodes, Vec3(0.25f, 0.32f, 0.52f), at(ox, 0.16f, oz), NeighborhoodMaterialSlot.CAR_WHEEL, rotationDeg = rot)
        }
        return nodes
    }

    // Surface-lot module sizes. [condoLotRect] derives the pad from these so the scenery
    // keep-out and the geometry can't disagree about how big the lot is — the old fixed
    // LOT_W/LOT_D constants were read independently by both.
    private const val LOT_STALL_W = 2.7f
    private const val LOT_STALL_D = 5.0f
    private const val LOT_AISLE_D = 6.2f
    private const val LOT_EDGE = 0.8f

    /** The pad [condoParkingLot] will cover for [stalls] stalls per row — also the keep-out the
     *  scenery scatter is filtered against. */
    fun condoLotRect(wx: Float, wz: Float, stalls: Int = LOT_STALLS): GroundRect = GroundRect(
        cx = wx, cz = wz,
        halfW = (stalls * LOT_STALL_W + LOT_EDGE * 2f) / 2f,
        halfD = (LOT_STALL_D * 2f + LOT_AISLE_D) / 2f,
    )

    /** Default stalls per row — a real residents' lot for a five-storey tower, rather than the
     *  three-space pad the condo used to get. */
    const val LOT_STALLS = 6

    /**
     * The condo tower's surface parking lot: asphalt pad flush with the roads, two facing rows
     * of striped stalls either side of a central drive aisle, and cars nosed into some of them
     * (deliberately not all — a full lot reads as a texture, a partly full one as a place).
     */
    fun condoParkingLot(wx: Float, wz: Float, stalls: Int = LOT_STALLS): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val rect = condoLotRect(wx, wz, stalls)
        box(nodes, Vec3(rect.halfW * 2f, 0.08f, rect.halfD * 2f), Vec3(wx, -0.06f, wz),
            NeighborhoodMaterialSlot.LOT)
        // Row -1 is the far (-Z) side, row +1 the near side; each row's cars nose toward the
        // aisle between them, so their yaw is opposite.
        for (row in listOf(-1f, 1f)) {
            val stallZ = wz + row * (rect.halfD - LOT_EDGE - LOT_STALL_D / 2f)
            // One stripe per stall boundary — stalls + 1 lines.
            for (i in 0..stalls) {
                val ox = -stalls * LOT_STALL_W / 2f + i * LOT_STALL_W
                box(nodes, Vec3(0.12f, 0.04f, LOT_STALL_D), Vec3(wx + ox, 0.0f, stallZ),
                    NeighborhoodMaterialSlot.LOT_STRIPE)
            }
            // Leave a couple of spaces empty on each row.
            val parked = (stalls - 2).coerceAtLeast(1)
            for (i in 0 until parked) {
                val ox = -stalls * LOT_STALL_W / 2f + (i + 0.5f) * LOT_STALL_W
                nodes += decorCar(wx + ox, stallZ, if (row < 0f) 0f else 180f, i % 3)
            }
        }
        return nodes
    }

    // ── The park ─────────────────────────────────────────────────────────────────────────
    /** The park's footprint — same derive-once relationship [condoLotRect] has with the lot. */
    fun parkRect(cx: Float, cz: Float) = GroundRect(cx, cz, PARK_W / 2f, PARK_D / 2f)

    /**
     * A neighborhood park: mown lawn, a crossing path, a gazebo, benches, a swing set and a
     * slide, ringed with trees. The gazebo reuses the same two-slope roof construction
     * [HouseSceneGeometry.gazebo] uses in the back yard — a pair of slabs tilted about Z from a
     * shared ridge height — at its own fixed pitch, since a gazebo roof doesn't track the
     * neighborhood's house pitch.
     *
     * Purely decorative: nothing here is tappable, so it never competes with a lot's
     * tap-to-preview the way the homes' facade detail deliberately does.
     */
    fun park(cx: Float, cz: Float): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        box(nodes, Vec3(PARK_W, 0.10f, PARK_D), Vec3(cx, -0.04f, cz), NeighborhoodMaterialSlot.PARK_LAWN)
        // Crossing paths, a hair proud of the lawn so they read as paving.
        box(nodes, Vec3(PARK_W, 0.06f, 1.6f), Vec3(cx, 0.02f, cz), NeighborhoodMaterialSlot.NBR_PAVING)
        box(nodes, Vec3(1.6f, 0.06f, PARK_D), Vec3(cx, 0.02f, cz), NeighborhoodMaterialSlot.NBR_PAVING)

        // Gazebo at the back-left quarter.
        val gx = cx - PARK_W * 0.26f
        val gz = cz - PARK_D * 0.26f
        val span = 3.6f; val half = span / 2f; val postH = 2.5f; val postT = 0.14f
        val gPitchDeg = 30f
        val gPitchRad = kotlin.math.PI / 180.0 * gPitchDeg
        val gSlant = (half / cos(gPitchRad)).toFloat()
        val gRidge = (half * tan(gPitchRad)).toFloat()
        box(nodes, Vec3(span, 0.18f, span), Vec3(gx, 0.09f, gz), NeighborhoodMaterialSlot.PARK_STRUCTURE)
        listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).forEach { (sx, sz) ->
            box(nodes, Vec3(postT, postH, postT),
                Vec3(gx + sx * (half - postT), postH / 2f, gz + sz * (half - postT)),
                NeighborhoodMaterialSlot.PARK_STRUCTURE)
        }
        box(nodes, Vec3(gSlant, 0.12f, span + 0.4f), Vec3(gx + half / 2f, postH + gRidge / 2f, gz),
            NeighborhoodMaterialSlot.PARK_ACCENT, rotationDeg = Vec3(0f, 0f, -gPitchDeg))
        box(nodes, Vec3(gSlant, 0.12f, span + 0.4f), Vec3(gx - half / 2f, postH + gRidge / 2f, gz),
            NeighborhoodMaterialSlot.PARK_ACCENT, rotationDeg = Vec3(0f, 0f, gPitchDeg))

        // Benches along the paths — seat slab on two plinths.
        listOf(
            Triple(cx - 4.5f, cz + 1.6f, 0f), Triple(cx + 4.5f, cz + 1.6f, 0f),
            Triple(cx + 1.6f, cz + 5.5f, 90f),
        ).forEach { (bx, bz, yaw) ->
            val rad = kotlin.math.PI / 180.0 * yaw
            val s = sin(rad).toFloat(); val c = cos(rad).toFloat()
            fun at(ox: Float, y: Float, oz: Float) = Vec3(bx + c * ox + s * oz, y, bz - s * ox + c * oz)
            val rot = Vec3(0f, yaw, 0f)
            box(nodes, Vec3(1.8f, 0.10f, 0.45f), at(0f, 0.44f, 0f), NeighborhoodMaterialSlot.PARK_STRUCTURE, rotationDeg = rot)
            box(nodes, Vec3(1.8f, 0.40f, 0.10f), at(0f, 0.68f, -0.18f), NeighborhoodMaterialSlot.PARK_STRUCTURE, rotationDeg = rot)
            listOf(-0.7f, 0.7f).forEach { ox ->
                box(nodes, Vec3(0.12f, 0.40f, 0.40f), at(ox, 0.20f, 0f), NeighborhoodMaterialSlot.PARK_STRUCTURE, rotationDeg = rot)
            }
        }

        // Playground, front-right: swing set (two A-frame legs, top bar, two seats) and a slide.
        val px = cx + PARK_W * 0.24f
        val pz = cz + PARK_D * 0.24f
        val swingH = 2.4f
        listOf(-1.8f, 1.8f).forEach { ox ->
            listOf(-0.7f, 0.7f).forEach { oz ->
                box(nodes, Vec3(0.12f, swingH, 0.12f), Vec3(px + ox, swingH / 2f, pz + oz),
                    NeighborhoodMaterialSlot.PARK_ACCENT)
            }
        }
        box(nodes, Vec3(4.0f, 0.14f, 0.14f), Vec3(px, swingH, pz), NeighborhoodMaterialSlot.PARK_ACCENT)
        listOf(-0.9f, 0.9f).forEach { ox ->
            box(nodes, Vec3(0.06f, 1.4f, 0.06f), Vec3(px + ox, swingH - 0.7f, pz), NeighborhoodMaterialSlot.PARK_STRUCTURE)
            box(nodes, Vec3(0.50f, 0.08f, 0.24f), Vec3(px + ox, swingH - 1.4f, pz), NeighborhoodMaterialSlot.PARK_STRUCTURE)
        }
        // Slide — platform on legs with a ramp running down off its front edge.
        val sx = px - 5.2f
        val platH = 1.5f
        box(nodes, Vec3(1.2f, 0.12f, 1.2f), Vec3(sx, platH, pz), NeighborhoodMaterialSlot.PARK_STRUCTURE)
        listOf(-0.5f to -0.5f, -0.5f to 0.5f, 0.5f to -0.5f, 0.5f to 0.5f).forEach { (ox, oz) ->
            box(nodes, Vec3(0.10f, platH, 0.10f), Vec3(sx + ox, platH / 2f, pz + oz), NeighborhoodMaterialSlot.PARK_STRUCTURE)
        }
        box(nodes, Vec3(0.90f, 0.08f, 2.6f), Vec3(sx, platH * 0.55f, pz + 1.75f),
            NeighborhoodMaterialSlot.PARK_ACCENT, rotationDeg = Vec3(-32f, 0f, 0f))

        // Trees around the edge, inside the lawn so they never overhang the road.
        listOf(
            (-PARK_W / 2f + 2.2f) to (-PARK_D / 2f + 2.2f),
            (PARK_W / 2f - 2.2f) to (-PARK_D / 2f + 2.2f),
            (-PARK_W / 2f + 2.2f) to (PARK_D / 2f - 2.2f),
            (PARK_W / 2f - 2.6f) to (PARK_D / 2f - 6.5f),
        ).forEachIndexed { i, (ox, oz) ->
            nodes += tree(cx + ox, cz + oz, if (i % 2 == 0) 1.25f else 1.0f)
        }
        return nodes
    }

    private fun clearOfHome(x: Float, z: Float, ownHalfX: Float, ownZMax: Float, ownZMin: Float) =
        abs(x) > ownHalfX || z > ownZMax || z < ownZMin

    private fun clearOf(x: Float, z: Float, keepOut: List<GroundRect>) =
        keepOut.none { abs(x - it.cx) < it.halfW + 1f && abs(z - it.cz) < it.halfD + 1f }

    /** Position-table + clearOfHome/keep-out filtering + per-tree box expansion. */
    fun scatterTrees(
        ownHalfX: Float, ownZMax: Float, ownZMin: Float,
        keepOut: List<GroundRect>,
    ): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        // Verges: the strip between the homes and the loop road, and the gaps between lots.
        // Nothing sits inside the ring's middle — that's the park's, and the walkways cross it.
        listOf(
            Triple(-20f, 8f, 1.1f), Triple(20f, 8f, 0.9f),
            Triple(-33f, -8f, 1.3f), Triple(33f, -8f, 1.1f),
            Triple(-33f, -40f, 1.0f), Triple(33f, -40f, 1.2f),
            Triple(-10f, -8f, 0.9f), Triple(10f, -8f, 1.0f),
            Triple(-10f, -50f, 1.0f), Triple(-33f, -66f, 1.2f),
            Triple(-24f, -74f, 1.1f), Triple(30f, -74f, 1.0f),
            Triple(0f, -74f, 1.3f), Triple(33f, -38f, 0.9f),
        ).filter { (tx, tz, _) -> clearOfHome(tx, tz, ownHalfX, ownZMax, ownZMin) && clearOf(tx, tz, keepOut) }
            .forEach { (tx, tz, s) -> nodes += tree(tx, tz, s) }
        return nodes
    }

    /** Same scenery-scatter machinery, for the lane-side decorative cars. */
    fun scatterDecorCars(
        ownHalfX: Float, ownZMax: Float, ownZMin: Float,
        keepOut: List<GroundRect>,
    ): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        // Parked along the loop itself, nosed the way its traffic runs: north/south on the
        // side roads, east/west on the top and bottom.
        listOf(
            Triple(RING_W, -12f, 0f), Triple(RING_W, -48f, 180f),
            Triple(RING_E, -16f, 0f), Triple(RING_E, -44f, 180f),
            Triple(-22f, RING_N, 90f), Triple(22f, RING_S, -90f),
        ).filter { (cx, cz, _) -> clearOfHome(cx, cz, ownHalfX, ownZMax, ownZMin) && clearOf(cx, cz, keepOut) }
            .forEachIndexed { i, (cx, cz, yaw) -> nodes += decorCar(cx, cz, yaw, i % 3) }
        return nodes
    }

    /** One neighbor's/the origin's "massing block" — slab + solid exterior walls + a
     *  few decorative door/window boxes + roof (pitched or flat), or, when [isCondoTop],
     *  a condo-tower variant (base + windows + condo unit + flat roof cap).
     *
     *  NOTE: the condo branch's 5 tappable boxes (slab, base wall, base cap, condo wall,
     *  roof cap) intentionally do NOT rotate by [yRotDeg] — only their door/window trim
     *  does. The standard branch's slab+wall DO rotate. This asymmetry is currently
     *  invisible in production (isCondoTop is only ever exercised at yRotDeg=0f) but is
     *  preserved literally here, not "fixed," since this is a zero-behavior-change port. */
    fun blockHome(
        wx: Float, wz: Float, yRotDeg: Float,
        homeW: Float, homeD: Float, floors: Int,
        isPitched: Boolean, isCondoTop: Boolean,
        facade: HomeFacade = HomeFacade(),
    ): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val rad = kotlin.math.PI / 180.0 * yRotDeg
        val sinY = sin(rad).toFloat()
        val cosY = cos(rad).toFloat()

        if (isCondoTop) {
            val baseFloors = 4
            val baseH = baseFloors * FLOOR_HEIGHT_M
            val condoH = floors * FLOOR_HEIGHT_M
            val baseW = homeW + 0.4f
            val baseD = homeD + 0.4f

            box(nodes, Vec3(homeW + 0.6f, 0.20f, homeD + 0.6f), Vec3(wx, -0.10f, wz),
                NeighborhoodMaterialSlot.NBR_SLAB, tappable = true)
            box(nodes, Vec3(baseW, baseH, baseD), Vec3(wx, baseH / 2f, wz),
                NeighborhoodMaterialSlot.NBR_CONDO_BASE, tappable = true)

            val bfOff = baseD / 2f + 0.06f
            val bfx = wx + sinY * bfOff
            val bfz = wz + cosY * bfOff
            box(nodes, Vec3(1.2f, 2.4f, 0.10f), Vec3(bfx, 1.2f, bfz),
                NeighborhoodMaterialSlot.NBR_DOOR, rotationDeg = Vec3(0f, yRotDeg, 0f))
            val bdx = cosY * 2.8f
            val bdz = sinY * 2.8f
            for (fl in 0 until baseFloors) {
                val wy = fl * FLOOR_HEIGHT_M + FLOOR_HEIGHT_M * 0.60f
                box(nodes, Vec3(1.4f, 0.9f, 0.10f), Vec3(bfx - bdx, wy, bfz + bdz),
                    NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
                box(nodes, Vec3(1.4f, 0.9f, 0.10f), Vec3(bfx + bdx, wy, bfz - bdz),
                    NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            }
            val bsOff = baseW / 2f + 0.06f
            val brx = wx + cosY * bsOff
            val brz = wz - sinY * bsOff
            val blx = wx - cosY * bsOff
            val blz = wz + sinY * bsOff
            for (fl in 0 until baseFloors) {
                val wy = fl * FLOOR_HEIGHT_M + FLOOR_HEIGHT_M * 0.60f
                box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(brx, wy, brz),
                    NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
                box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(blx, wy, blz),
                    NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            }

            box(nodes, Vec3(homeW + 0.8f, 0.30f, homeD + 0.8f), Vec3(wx, baseH + 0.15f, wz),
                NeighborhoodMaterialSlot.NBR_CONDO_BASE, tappable = true)

            val condoY0 = baseH + 0.30f
            box(nodes, Vec3(homeW, condoH, homeD), Vec3(wx, condoY0 + condoH / 2f, wz),
                NeighborhoodMaterialSlot.NBR_WALL, tappable = true)

            val cfOff = homeD / 2f + 0.06f
            val cfx = wx + sinY * cfOff
            val cfz = wz + cosY * cfOff
            val cwy = condoY0 + condoH * 0.55f
            val cwdx = cosY * 2.5f
            val cwdz = sinY * 2.5f
            box(nodes, Vec3(1.2f, 0.9f, 0.10f), Vec3(cfx - cwdx, cwy, cfz + cwdz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            box(nodes, Vec3(1.2f, 0.9f, 0.10f), Vec3(cfx + cwdx, cwy, cfz - cwdz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            val csOff = homeW / 2f + 0.06f
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(wx + cosY * csOff, cwy, wz - sinY * csOff),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(wx - cosY * csOff, cwy, wz + sinY * csOff),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))

            box(nodes, Vec3(homeW + 0.4f, 0.30f, homeD + 0.4f), Vec3(wx, condoY0 + condoH + 0.15f, wz),
                NeighborhoodMaterialSlot.NBR_FLAT_ROOF, tappable = true)

            return nodes
        }

        val totalH = floors * FLOOR_HEIGHT_M

        // Local-frame placement, same rotate-the-offset idiom decorCar uses: local +X is the
        // home's own right, local +Z its own front. Everything added below is authored in that
        // frame and rotated here, so a lot's yaw never has to be reasoned about twice.
        val rot = Vec3(0f, yRotDeg, 0f)
        fun at(ox: Float, y: Float, oz: Float) =
            Vec3(wx + cosY * ox + sinY * oz, y, wz - sinY * ox + cosY * oz)

        box(nodes, Vec3(homeW, 0.20f, homeD), Vec3(wx, -0.10f, wz),
            NeighborhoodMaterialSlot.NBR_SLAB, rotationDeg = rot, tappable = true)
        box(nodes, Vec3(homeW, totalH, homeD), Vec3(wx, totalH / 2f, wz),
            NeighborhoodMaterialSlot.NBR_WALL, rotationDeg = rot,
            materialVariant = facade.wallVariant, tappable = true)

        val fOff = homeD / 2f + 0.06f
        val fx = wx + sinY * fOff
        val fz = wz + cosY * fOff
        // Front door — shifted clear of the garage bay when the model has one, so the entry and
        // the garage door don't land on top of each other on the same elevation.
        val doorX = facade.garageDoorX?.let { g ->
            val away = if (g >= 0f) -1f else 1f
            (away * (homeW / 2f - 1.6f)).coerceIn(-homeW / 2f + 0.9f, homeW / 2f - 0.9f)
        } ?: 0f
        box(nodes, Vec3(1.0f, 2.2f, 0.10f), at(doorX, 1.1f, fOff),
            NeighborhoodMaterialSlot.NBR_DOOR, rotationDeg = rot, tappable = true)
        // Casing around the door, and the stoop + porch canopy it opens onto.
        box(nodes, Vec3(1.36f, 2.44f, 0.06f), at(doorX, 1.22f, fOff - 0.03f),
            NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
        box(nodes, Vec3(2.0f, 0.18f, 1.1f), at(doorX, 0.09f, fOff + 0.55f),
            NeighborhoodMaterialSlot.NBR_PAVING, rotationDeg = rot, tappable = true)
        box(nodes, Vec3(2.4f, 0.14f, 1.5f), at(doorX, 2.62f, fOff + 0.60f),
            NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
        listOf(-1f, 1f).forEach { s ->
            box(nodes, Vec3(0.14f, 2.55f, 0.14f), at(doorX + s * 1.05f, 1.27f, fOff + 1.20f),
                NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
        }
        // Front walk running out toward the street.
        box(nodes, Vec3(1.3f, 0.06f, 5.0f), at(doorX, 0.01f, fOff + 3.6f),
            NeighborhoodMaterialSlot.NBR_PAVING, rotationDeg = rot, tappable = true)

        // Garage door cut into the front elevation, with its apron running out to the spur road.
        // Position and width come from the model's real layout (see HomeFacade), so the block
        // agrees with the home you get on claiming it.
        facade.garageDoorX?.let { gx ->
            val gw = facade.garageDoorW
            val gh = 2.3f
            box(nodes, Vec3(gw, gh, 0.12f), at(gx, gh / 2f, fOff),
                NeighborhoodMaterialSlot.NBR_GARAGE_DOOR, rotationDeg = rot, tappable = true)
            box(nodes, Vec3(gw + 0.34f, gh + 0.30f, 0.06f), at(gx, (gh + 0.30f) / 2f, fOff - 0.03f),
                NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
            box(nodes, Vec3(gw + 0.6f, 0.08f, 6.5f), at(gx, 0.0f, fOff + 3.3f),
                NeighborhoodMaterialSlot.NBR_PAVING, rotationDeg = rot, tappable = true)
        }

        val sOff = homeW / 2f + 0.06f
        // Front windows are laid out as bays evenly across the elevation, and each bay is kept
        // only if it clears the wall's ends AND whatever already occupies that stretch — the
        // entry on the ground floor, the garage door likewise. Anchoring them to the door
        // instead (the obvious thing) throws one clean off the end of the wall the moment a
        // garage pushes the door aside, which is exactly what a 15 m Classic does.
        val bays = (homeW / 3.2f).toInt().coerceIn(2, 5)
        val winSlots = (0 until bays).map { -homeW / 2f + (it + 0.5f) / bays * homeW }
        for (fl in 0 until floors) {
            val wy = fl * FLOOR_HEIGHT_M + FLOOR_HEIGHT_M * 0.60f
            // Both the entry and the garage door are ground-floor openings only.
            val blocked = if (fl > 0) emptyList() else buildList {
                add(doorX to 2.0f)
                facade.garageDoorX?.let { add(it to facade.garageDoorW) }
            }
            winSlots.filter { ox ->
                abs(ox) <= homeW / 2f - 1.15f &&
                    blocked.none { (bx, bw) -> abs(ox - bx) < bw / 2f + 0.95f }
            }.forEach { ox ->
                box(nodes, Vec3(1.2f, 0.9f, 0.10f), at(ox, wy, fOff),
                    NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = rot, tappable = true)
                box(nodes, Vec3(1.44f, 1.14f, 0.06f), at(ox, wy, fOff - 0.03f),
                    NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
                listOf(-1f, 1f).forEach { sh ->
                    box(nodes, Vec3(0.34f, 1.0f, 0.06f), at(ox + sh * 0.86f, wy, fOff + 0.01f),
                        NeighborhoodMaterialSlot.NBR_SHUTTER, rotationDeg = rot, tappable = true)
                }
            }
            // Side pair — plain, they're barely seen from the street.
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), at(sOff, wy, 0f),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = rot, tappable = true)
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), at(-sOff, wy, 0f),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = rot, tappable = true)
        }

        if (isPitched) {
            val overhang = 0.5f
            val halfSpan = homeW / 2f + overhang
            val slantLen = (halfSpan / cos(NBR_PITCH_RAD)).toFloat()
            val ridgeH = (halfSpan * tan(NBR_PITCH_RAD)).toFloat()
            val cx = halfSpan / 2f
            val depth = homeD + overhang * 2f
            val cy = totalH + ridgeH / 2f
            val ridgeAlongZ = abs(yRotDeg) < 1f || abs(abs(yRotDeg) - 180f) < 1f
            if (ridgeAlongZ) {
                box(nodes, Vec3(slantLen, 0.25f, depth), Vec3(wx + cx, cy, wz),
                    NeighborhoodMaterialSlot.NBR_ROOF, rotationDeg = Vec3(0f, 0f, -NBR_PITCH_DEG), tappable = true)
                box(nodes, Vec3(slantLen, 0.25f, depth), Vec3(wx - cx, cy, wz),
                    NeighborhoodMaterialSlot.NBR_ROOF, rotationDeg = Vec3(0f, 0f, NBR_PITCH_DEG), tappable = true)
                box(nodes, Vec3(0.25f, 0.22f, depth), Vec3(wx, totalH + ridgeH, wz),
                    NeighborhoodMaterialSlot.NBR_ROOF, tappable = true)
            } else {
                box(nodes, Vec3(depth, 0.25f, slantLen), Vec3(wx, cy, wz + cx),
                    NeighborhoodMaterialSlot.NBR_ROOF, rotationDeg = Vec3(NBR_PITCH_DEG, 0f, 0f), tappable = true)
                box(nodes, Vec3(depth, 0.25f, slantLen), Vec3(wx, cy, wz - cx),
                    NeighborhoodMaterialSlot.NBR_ROOF, rotationDeg = Vec3(-NBR_PITCH_DEG, 0f, 0f), tappable = true)
                box(nodes, Vec3(depth, 0.22f, 0.25f), Vec3(wx, totalH + ridgeH, wz),
                    NeighborhoodMaterialSlot.NBR_ROOF, tappable = true)
            }
        } else {
            box(nodes, Vec3(homeW + 0.4f, 0.30f, homeD + 0.4f), Vec3(wx, totalH + 0.15f, wz),
                NeighborhoodMaterialSlot.NBR_FLAT_ROOF, rotationDeg = Vec3(0f, yRotDeg, 0f), tappable = true)
        }

        // Chimney on a side wall, rising clear of the ridge. Sized off the roof's own pitch so
        // it clears whatever ridge height this home's width produced.
        if (facade.chimney && isPitched) {
            val overhang = 0.5f
            val ridgeH = ((homeW / 2f + overhang) * tan(NBR_PITCH_RAD)).toFloat()
            val chX = homeW * 0.32f
            // Roof height directly above the stack, from the slope's linear fall off the ridge.
            val roofYatX = totalH + ridgeH * (1f - chX / (homeW / 2f + overhang))
            val topY = totalH + ridgeH + 0.9f
            box(nodes, Vec3(0.85f, topY - roofYatX + 0.6f, 0.85f),
                at(chX, (roofYatX + topY) / 2f - 0.3f, -homeD * 0.22f),
                NeighborhoodMaterialSlot.NBR_CHIMNEY, rotationDeg = rot, tappable = true)
            box(nodes, Vec3(1.05f, 0.16f, 1.05f), at(chX, topY + 0.08f, -homeD * 0.22f),
                NeighborhoodMaterialSlot.NBR_TRIM, rotationDeg = rot, tappable = true)
        }

        return nodes
    }

    /** A small "this is yours" flag marker — thin post + a bright flag — planted just beside the
     *  front walk of whichever PLACED_NEIGHBORS lot matches the player's current claim, oriented
     *  with the lot (same front-offset math as blockHome's door). Purely decorative: every box
     *  here is non-tappable, so it never competes with the lot's own tap-to-preview interaction. */
    fun claimedMarker(wx: Float, wz: Float, yRotDeg: Float, homeD: Float): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        val rad = kotlin.math.PI / 180.0 * yRotDeg
        val sinY = sin(rad).toFloat(); val cosY = cos(rad).toFloat()
        val fOff = homeD / 2f + 1.8f  // beside the front walk, clear of the door/steps
        val mx = wx + sinY * fOff + cosY * 1.6f
        val mz = wz + cosY * fOff - sinY * 1.6f
        box(nodes, Vec3(0.08f, 1.8f, 0.08f), Vec3(mx, 0.9f, mz), NeighborhoodMaterialSlot.MARKER_POST)
        box(nodes, Vec3(0.55f, 0.38f, 0.05f), Vec3(mx + 0.30f, 1.55f, mz),
            NeighborhoodMaterialSlot.MARKER_FLAG, rotationDeg = Vec3(0f, yRotDeg, 0f))
        return nodes
    }
}
