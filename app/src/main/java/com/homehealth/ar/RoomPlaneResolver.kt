package com.homehealth.ar

import kotlin.math.atan2

/**
 * Converts a set of AR-detected planes into a [RoomGeometry] ready for rendering.
 *
 * This class is intentionally free of ARCore imports so it compiles without arsceneview.
 * When implementing the Scan Room feature, add arsceneview to build.gradle and map
 * ARCore planes to [DetectedPlane] before calling resolve():
 *
 *   val planes = arFrame
 *       .getUpdatedTrackables(Plane::class.java)
 *       .filter { it.trackingState == TrackingState.TRACKING }
 *       .map { plane ->
 *           DetectedPlane(
 *               type = when (plane.type) {
 *                   Plane.Type.HORIZONTAL_UPWARD_FACING   -> DetectedPlaneType.FLOOR
 *                   Plane.Type.HORIZONTAL_DOWNWARD_FACING -> DetectedPlaneType.CEILING
 *                   else                                  -> DetectedPlaneType.WALL
 *               },
 *               extentX           = plane.extentX,
 *               extentZ           = plane.extentZ,
 *               centerTranslation = plane.centerPose.translation,
 *           )
 *       }
 *   val geometry = RoomPlaneResolver().resolve(planes)
 */
class RoomPlaneResolver {

    fun resolve(planes: Collection<DetectedPlane>): RoomGeometry? {
        val floor = planes
            .filter { it.type == DetectedPlaneType.FLOOR }
            .maxByOrNull { it.extentX * it.extentZ }
            ?: return null

        val walls = planes.filter { it.type == DetectedPlaneType.WALL }
        if (walls.isEmpty()) return null

        // Project each wall centre onto world XZ, sort clockwise around their centroid
        val centres: List<Pair<Float, Float>> = walls.map { plane ->
            plane.centerTranslation[0] to plane.centerTranslation[2]
        }
        val cx = centres.map { it.first }.average().toFloat()
        val cz = centres.map { it.second }.average().toFloat()
        val sorted = centres.sortedBy { (x, z) ->
            atan2((z - cz).toDouble(), (x - cx).toDouble())
        }

        return RoomGeometry(
            floorY  = floor.centerTranslation[1],
            corners = sorted,
        )
    }
}
