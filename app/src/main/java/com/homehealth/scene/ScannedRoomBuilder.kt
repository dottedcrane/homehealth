package com.homehealth.scene

import com.google.android.filament.Engine
import com.homehealth.ar.RoomGeometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.Node
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

object ScannedRoomBuilder {

    private const val WALL_T = 0.12f

    fun build(engine: Engine, materialLoader: MaterialLoader, geometry: RoomGeometry): List<Node> {
        if (!geometry.isValid) return emptyList()

        val wallMat = materialLoader.createColorInstance(colorOf(0.20f, 0.72f, 0.62f))
        val floorMat = materialLoader.createColorInstance(colorOf(0.18f, 0.65f, 0.55f))

        val corners = geometry.corners
        val n = corners.size
        val wallH = geometry.estimatedWallHeight
        val floorY = geometry.floorY

        return buildList {
            // Floor slab — axis-aligned bounding box of the detected corners
            val minX = corners.minOf { it.first }
            val maxX = corners.maxOf { it.first }
            val minZ = corners.minOf { it.second }
            val maxZ = corners.maxOf { it.second }
            add(
                BoxNode(engine, Size(maxX - minX, 0.05f, maxZ - minZ), floorMat).apply {
                    position = Position((minX + maxX) / 2f, floorY, (minZ + maxZ) / 2f)
                }
            )

            // Wall panel between every pair of adjacent detected wall centres
            for (i in 0 until n) {
                val (x1, z1) = corners[i]
                val (x2, z2) = corners[(i + 1) % n]
                val dx = x2 - x1
                val dz = z2 - z1
                val wallLen = sqrt(dx * dx + dz * dz)
                val angleDeg = atan2(dz.toDouble(), dx.toDouble()).toFloat() * (180f / PI.toFloat())

                add(
                    BoxNode(engine, Size(wallLen, wallH, WALL_T), wallMat).apply {
                        position = Position((x1 + x2) / 2f, floorY + wallH / 2f, (z1 + z2) / 2f)
                        rotation = Rotation(0f, angleDeg, 0f)
                    }
                )
            }
        }
    }
}
