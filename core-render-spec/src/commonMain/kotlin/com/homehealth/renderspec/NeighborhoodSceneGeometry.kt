package com.homerenderer.renderspec

import com.homerenderer.scene.FLOOR_HEIGHT_M
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class NeighborhoodMaterialSlot {
    GROUND, ROAD,
    LOT, LOT_STRIPE,
    NBR_SLAB, NBR_CONDO_BASE, NBR_WALL, NBR_DOOR, NBR_WINDOW,
    NBR_ROOF, NBR_FLAT_ROOF,
    TREE_TRUNK, TREE_LEAF,
    CAR_BODY, CAR_CABIN, CAR_WHEEL,
}

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

// Condo parking-lot pad dimensions (x across × z deep) — shared with the scenery filter.
const val LOT_W = 8f
const val LOT_D = 7f

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

    /** The 9 fixed ground/road slabs — always identical, no inputs. */
    fun groundAndRoads(): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        box(nodes, Vec3(130f, 0.10f, 130f), Vec3(0f, -0.12f, -20f), NeighborhoodMaterialSlot.GROUND)
        box(nodes, Vec3(8f, 0.08f, 60f), Vec3(0f, -0.06f, -10f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(24f, 0.08f, 10f), Vec3(0f, -0.06f, -30f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(10f, 0.08f, 24f), Vec3(0f, -0.06f, -30f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(8f, 0.08f, 16f), Vec3(-15f, -0.06f, -22f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(8f, 0.08f, 16f), Vec3(15f, -0.06f, -22f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(8f, 0.08f, 16f), Vec3(-15f, -0.06f, -40f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(8f, 0.08f, 16f), Vec3(15f, -0.06f, -40f), NeighborhoodMaterialSlot.ROAD)
        box(nodes, Vec3(6f, 0.08f, 14f), Vec3(0f, -0.06f, -44f), NeighborhoodMaterialSlot.ROAD)
        return nodes
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

    /** A surface parking lot for the condo tower: asphalt pad flush with the roads, painted
     *  stall lines on the building side, and a row of 3 parked cars nosed into the stalls. */
    fun condoParkingLot(wx: Float, wz: Float): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        box(nodes, Vec3(LOT_W, 0.08f, LOT_D), Vec3(wx, -0.06f, wz), NeighborhoodMaterialSlot.LOT)
        val stallD = 4.4f
        val stallZ = wz - LOT_D / 2f + stallD / 2f
        listOf(-3.6f, -1.2f, 1.2f, 3.6f).forEach { ox ->
            box(nodes, Vec3(0.12f, 0.04f, stallD), Vec3(wx + ox, 0.0f, stallZ), NeighborhoodMaterialSlot.LOT_STRIPE)
        }
        listOf(-2.4f, 0f, 2.4f).forEachIndexed { i, ox ->
            nodes += decorCar(wx + ox, stallZ, 180f, i % 3)
        }
        return nodes
    }

    private fun clearOfHome(x: Float, z: Float, ownHalfX: Float, ownZMax: Float, ownZMin: Float) =
        abs(x) > ownHalfX || z > ownZMax || z < ownZMin

    private fun clearOfPads(x: Float, z: Float, parkingPads: List<Pair<Float, Float>>) =
        parkingPads.none { (px, pz) -> abs(x - px) < LOT_W / 2f + 1f && abs(z - pz) < LOT_D / 2f + 1f }

    /** Position-table + clearOfHome/clearOfPads filtering + per-tree box expansion. */
    fun scatterTrees(
        ownHalfX: Float, ownZMax: Float, ownZMin: Float,
        parkingPads: List<Pair<Float, Float>>,
    ): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        listOf(
            Triple(-9f, 16f, 1.1f), Triple(9f, 12f, 0.9f),
            Triple(-9f, 4f, 1.0f), Triple(9f, -2f, 1.2f),
            Triple(-8f, -25f, 0.9f), Triple(8f, -35f, 1.0f),
            Triple(-22f, -29f, 1.2f), Triple(22f, -29f, 1.1f),
            Triple(-10f, -55f, 1.0f), Triple(10f, -55f, 1.2f),
            Triple(-30f, -10f, 1.3f), Triple(30f, -10f, 1.1f),
            Triple(-30f, -50f, 1.0f), Triple(30f, -50f, 1.3f),
        ).filter { (tx, tz, _) -> clearOfHome(tx, tz, ownHalfX, ownZMax, ownZMin) && clearOfPads(tx, tz, parkingPads) }
            .forEach { (tx, tz, s) -> nodes += tree(tx, tz, s) }
        return nodes
    }

    /** Same scenery-scatter machinery, for the lane-side decorative cars. */
    fun scatterDecorCars(
        ownHalfX: Float, ownZMax: Float, ownZMin: Float,
        parkingPads: List<Pair<Float, Float>>,
    ): List<NeighborhoodBoxNode> {
        val nodes = mutableListOf<NeighborhoodBoxNode>()
        listOf(
            Triple(15f, -20f, 90f), Triple(-15f, -24f, -90f),
            Triple(15f, -42f, 90f), Triple(-15f, -38f, -90f),
        ).filter { (cx, cz, _) -> clearOfHome(cx, cz, ownHalfX, ownZMax, ownZMin) && clearOfPads(cx, cz, parkingPads) }
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

        box(nodes, Vec3(homeW, 0.20f, homeD), Vec3(wx, -0.10f, wz),
            NeighborhoodMaterialSlot.NBR_SLAB, rotationDeg = Vec3(0f, yRotDeg, 0f), tappable = true)
        box(nodes, Vec3(homeW, totalH, homeD), Vec3(wx, totalH / 2f, wz),
            NeighborhoodMaterialSlot.NBR_WALL, rotationDeg = Vec3(0f, yRotDeg, 0f), tappable = true)

        val fOff = homeD / 2f + 0.06f
        val fx = wx + sinY * fOff
        val fz = wz + cosY * fOff
        box(nodes, Vec3(1.0f, 2.2f, 0.10f), Vec3(fx, 1.1f, fz),
            NeighborhoodMaterialSlot.NBR_DOOR, rotationDeg = Vec3(0f, yRotDeg, 0f))

        val wdx = cosY * 2.5f
        val wdz = sinY * 2.5f
        val sOff = homeW / 2f + 0.06f
        val rx = wx + cosY * sOff
        val rz = wz - sinY * sOff
        val lx = wx - cosY * sOff
        val lz = wz + sinY * sOff
        for (fl in 0 until floors) {
            val wy = fl * FLOOR_HEIGHT_M + FLOOR_HEIGHT_M * 0.60f
            box(nodes, Vec3(1.2f, 0.9f, 0.10f), Vec3(fx - wdx, wy, fz + wdz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            box(nodes, Vec3(1.2f, 0.9f, 0.10f), Vec3(fx + wdx, wy, fz - wdz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(rx, wy, rz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
            box(nodes, Vec3(0.10f, 0.9f, 1.5f), Vec3(lx, wy, lz),
                NeighborhoodMaterialSlot.NBR_WINDOW, rotationDeg = Vec3(0f, yRotDeg, 0f))
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

        return nodes
    }
}
