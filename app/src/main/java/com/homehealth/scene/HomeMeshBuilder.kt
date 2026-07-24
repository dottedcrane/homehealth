package com.homehealth.scene

import com.google.android.filament.Engine
import com.homehealth.model.HomeSize
import com.homehealth.model.HomeStyle
import com.homehealth.model.HomeType
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.Node
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.tan

object HomeMeshBuilder {

    private const val WALL_T = 0.2f
    private const val PITCH_DEG = 25f
    private val PITCH_RAD = Math.toRadians(PITCH_DEG.toDouble())

    fun build(engine: Engine, materialLoader: MaterialLoader, homeType: HomeType): List<Node> {
        val (w, d) = homeType.size.toFootprintMeters()
        val floors = homeType.floors.count

        return buildList {
            repeat(floors) { floor ->
                val yBase = floor * FLOOR_HEIGHT_M
                add(floorSlab(engine, materialLoader, w, d, yBase))
                addAll(walls(engine, materialLoader, w, d, yBase))
                addAll(openings(engine, materialLoader, homeType.size, w, d, yBase))
            }
            addAll(roof(engine, materialLoader, homeType.style, w, d, floors * FLOOR_HEIGHT_M))
        }
    }

    private fun floorSlab(
        engine: Engine, materialLoader: MaterialLoader,
        w: Float, d: Float, yBase: Float
    ): Node = BoxNode(
        engine,
        Size(w, WALL_T, d),
        materialLoader.createColorInstance(colorOf(0.75f, 0.70f, 0.65f))
    ).apply { position = Position(0f, yBase, 0f) }

    private fun walls(
        engine: Engine, materialLoader: MaterialLoader,
        w: Float, d: Float, yBase: Float
    ): List<Node> {
        val mat = materialLoader.createColorInstance(colorOf(0.90f, 0.88f, 0.84f))
        val wallY = yBase + FLOOR_HEIGHT_M / 2f
        val t = WALL_T
        return listOf(
            BoxNode(engine, Size(w, FLOOR_HEIGHT_M, t), mat)
                .apply { position = Position(0f, wallY, d / 2f) },
            BoxNode(engine, Size(w, FLOOR_HEIGHT_M, t), mat)
                .apply { position = Position(0f, wallY, -d / 2f) },
            BoxNode(engine, Size(t, FLOOR_HEIGHT_M, d), mat)
                .apply { position = Position(-w / 2f, wallY, 0f) },
            BoxNode(engine, Size(t, FLOOR_HEIGHT_M, d), mat)
                .apply { position = Position(w / 2f, wallY, 0f) }
        )
    }

    private fun openings(
        engine: Engine, materialLoader: MaterialLoader,
        size: HomeSize, w: Float, d: Float, yBase: Float
    ): List<Node> {
        val winW = size.windowWidth()
        val winH = size.windowHeight()
        val glassMat = materialLoader.createColorInstance(colorOf(0.13f, 0.20f, 0.33f, 0.24f))
        val doorMat  = materialLoader.createColorInstance(colorOf(0.42f, 0.26f, 0.13f)) // Solid wood brown
        val p = 0.11f                           // protrusion proud of wall face
        val windowY = yBase + 0.9f + winH / 2f // sill at 0.9 m
        val doorW = 0.95f
        val doorH = 2.1f
        val doorY = yBase + doorH / 2f

        return buildList {
            // Front wall (+Z): door left, window right
            add(BoxNode(engine, Size(doorW, doorH, p), doorMat).apply {
                position = Position(-w / 4f, doorY, d / 2f + p / 2f)
            })
            add(BoxNode(engine, Size(winW, winH, p), glassMat).apply {
                position = Position(w / 4f, windowY, d / 2f + p / 2f)
            })
            // Back wall (-Z): two windows
            for (xOff in listOf(-w / 4f, w / 4f)) {
                add(BoxNode(engine, Size(winW, winH, p), glassMat).apply {
                    position = Position(xOff, windowY, -d / 2f - p / 2f)
                })
            }
            // Left wall (-X): two windows
            for (zOff in listOf(-d / 4f, d / 4f)) {
                add(BoxNode(engine, Size(p, winH, winW), glassMat).apply {
                    position = Position(-w / 2f - p / 2f, windowY, zOff)
                })
            }
            // Right wall (+X): two windows
            for (zOff in listOf(-d / 4f, d / 4f)) {
                add(BoxNode(engine, Size(p, winH, winW), glassMat).apply {
                    position = Position(w / 2f + p / 2f, windowY, zOff)
                })
            }
        }
    }

    private fun roof(
        engine: Engine, materialLoader: MaterialLoader,
        style: HomeStyle, w: Float, d: Float, wallTopY: Float
    ): List<Node> = when (style) {
        HomeStyle.HOUSE, HomeStyle.TOWNHOUSE -> pitchedRoof(engine, materialLoader, w, d, wallTopY)
        else                                 -> listOf(flatRoof(engine, materialLoader, w, d, wallTopY))
    }

    private fun pitchedRoof(
        engine: Engine, materialLoader: MaterialLoader,
        w: Float, d: Float, wallTopY: Float
    ): List<Node> {
        val mat = materialLoader.createColorInstance(colorOf(0.58f, 0.26f, 0.20f))
        val overhang = 0.6f
        val halfSpan = w / 2f + overhang
        val ridgeH = (halfSpan * tan(PITCH_RAD)).toFloat()
        val slantLen = (halfSpan / cos(PITCH_RAD)).toFloat()
        val cx = halfSpan / 2f
        val cy = wallTopY + ridgeH / 2f
        val depth = d + overhang * 2f

        return listOf(
            // Right slope (tilts down toward +X)
            BoxNode(engine, Size(slantLen, 0.15f, depth), mat).apply {
                position = Position(cx, cy, 0f)
                rotation = Rotation(0f, 0f, -PITCH_DEG)
            },
            // Left slope (tilts down toward -X)
            BoxNode(engine, Size(slantLen, 0.15f, depth), mat).apply {
                position = Position(-cx, cy, 0f)
                rotation = Rotation(0f, 0f, PITCH_DEG)
            }
        )
    }

    private fun flatRoof(
        engine: Engine, materialLoader: MaterialLoader,
        w: Float, d: Float, wallTopY: Float
    ): Node = BoxNode(
        engine,
        Size(w + 0.5f, 0.35f, d + 0.5f),
        materialLoader.createColorInstance(colorOf(0.38f, 0.38f, 0.40f))
    ).apply { position = Position(0f, wallTopY + 0.18f, 0f) }
}
