package com.homerenderer.ar

/**
 * Device-independent representation of a plane detected during an AR scan.
 *
 * Populated from ARCore's com.google.ar.core.Plane when arsceneview is enabled.
 * See the adapter snippet in [RoomPlaneResolver] for mapping instructions.
 */
data class DetectedPlane(
    val type: DetectedPlaneType,
    val extentX: Float,
    val extentZ: Float,
    val centerTranslation: FloatArray,   // [x, y, z] in world space
)

enum class DetectedPlaneType { FLOOR, CEILING, WALL }
