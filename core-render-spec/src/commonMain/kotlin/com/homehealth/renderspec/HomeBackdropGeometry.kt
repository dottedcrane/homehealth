package com.homehealth.renderspec

import com.homehealth.model.FloorLayout
import com.homehealth.model.RoomType
import com.homehealth.model.RoomZone
import com.homehealth.scene.WALL_T

/** Which of the backdrop's pre-built MaterialInstances a node renders with. */
enum class BackdropMaterialSlot { GROUND, ROOM_PAD, CURB, PERIMETER }

data class BackdropBoxNode(
    val size: Vec3,
    val position: Vec3,
    val material: BackdropMaterialSlot,
    /** Set on [BackdropMaterialSlot.ROOM_PAD] only — picks that room type's muted floor tint. */
    val roomType: RoomType? = null,
)

/**
 * The rest of the home, drawn around whichever room you're standing in — the backdrop every
 * room view renders behind its own room (see HomeBackdrop, called from RoomScene).
 *
 * Every other room on the floor appears at its TRUE position and size, with its walls cut down
 * to knee height: the classic cutaway model. Orbiting the room you're in therefore shows where
 * it actually sits in the home — hallway that way, living room beyond it — without any of that
 * backdrop rising far enough to stand between the camera and the room's contents.
 *
 * Coordinates are relative to [focus]'s CENTRE, because a room scene renders its own room at
 * the world origin (see RoomScene's kdoc) and every camera framing, drag plane and tray-drop ray
 * in the app is written against that. Shifting the backdrop instead of the room keeps all of it
 * true.
 *
 * Nothing here is interactive: the renderer marks every node untouchable (see HomeBackdrop), so
 * the backdrop can neither be tapped nor swallow a tap meant for the real room.
 */
object HomeBackdropGeometry {

    /** Knee-height walls around each backdrop room — low enough to always see over. */
    const val CURB_H = 0.45f
    private const val CURB_T = 0.09f

    /** The home's outer shell, drawn a little taller so the footprint reads as a boundary. */
    private const val PERIMETER_H = 0.85f
    private const val PERIMETER_T = 0.10f

    /**
     * Clearance between the perimeter and the outermost room walls. Those walls are centred on
     * the footprint edge and WALL_T thick, so the perimeter sits clear of the half that pokes
     * out — coincident faces would z-fight against the focus room's own exterior wall.
     */
    private const val PERIMETER_GAP = WALL_T / 2f + 0.02f + PERIMETER_T / 2f

    /**
     * How far each room's pad is inset from its zone. Half a wall plus clearance, so a pad never
     * runs into the base of the focus room's real full-height wall, and the resulting gap between
     * two backdrop rooms is a wall's own thickness — which is exactly how it reads.
     */
    private const val PAD_INSET = WALL_T / 2f + 0.02f

    private const val GROUND_T = 0.06f
    private const val GROUND_MARGIN = 1.2f

    /**
     * The rooms the backdrop is made of: every merged zone on [floor] except the one being stood
     * in. Public because the renderer walks the same list a second time to ghost each room's
     * furniture (see HomeBackdrop) — one definition of "a backdrop room" keeps the two passes
     * from ever disagreeing about which rooms those are.
     *
     * The focus room is matched by centre-inside-focus rather than equality: zones never overlap,
     * so it's the same test, and it can't be defeated by a float that round-trips imprecisely.
     */
    fun surroundingZones(layout: FloorLayout, floor: Int, focus: RoomZone): List<RoomZone> =
        layout.toMergedZones(floor).filterNot { z ->
            z.cx >= focus.xMin && z.cx <= focus.xMax && z.cz >= focus.zMin && z.cz <= focus.zMax
        }

    /**
     * [ground] draws a plain pad under the footprint. Off when the caller is also drawing the
     * neighbourhood, whose own ground plane covers the same area and sits a few centimetres
     * higher — two grounds there would be a wasted node and a z-fight.
     */
    fun build(
        layout: FloorLayout,
        floor: Int,
        focus: RoomZone,
        ground: Boolean = true,
    ): List<BackdropBoxNode> {
        val nodes = mutableListOf<BackdropBoxNode>()
        fun box(
            sx: Float, sy: Float, sz: Float,
            px: Float, py: Float, pz: Float,
            mat: BackdropMaterialSlot,
            type: RoomType? = null,
        ) {
            nodes += BackdropBoxNode(Vec3(sx, sy, sz), Vec3(px, py, pz), mat, type)
        }

        // Everything below is expressed in the focus room's local frame.
        val ox = -focus.cx
        val oz = -focus.cz
        val w  = layout.totalW
        val d  = layout.totalD

        // Ground the model sits on. Its top stops 2 cm below the room slabs' underside rather
        // than sharing their plane, which would z-fight across the whole footprint.
        if (ground) {
            val groundTop = -WALL_T / 2f - 0.02f
            box(w + GROUND_MARGIN * 2f, GROUND_T, d + GROUND_MARGIN * 2f,
                ox, groundTop - GROUND_T / 2f, oz, BackdropMaterialSlot.GROUND)
        }

        // ── One pad + curb ring per other room ────────────────────────────────────
        // Pads share the focus room's floor plane, so the whole storey reads as one continuous
        // floor rather than the room hovering over a separate diagram of the house.
        val curbY = WALL_T / 2f + CURB_H / 2f
        surroundingZones(layout, floor, focus).forEach { z ->
            val pw = (z.w - PAD_INSET * 2f).coerceAtLeast(0.2f)
            val pd = (z.d - PAD_INSET * 2f).coerceAtLeast(0.2f)
            val cx = z.cx + ox
            val cz = z.cz + oz
            box(pw, WALL_T, pd, cx, 0f, cz, BackdropMaterialSlot.ROOM_PAD, z.type)

            // Curbs stand on the pad's own edge. The Z-runs are shortened by a curb thickness at
            // each end so the four never overlap — stacked translucent faces would darken every
            // corner of the model.
            val hx = pw / 2f
            val hz = pd / 2f
            val zRun = (pd - CURB_T * 2f).coerceAtLeast(0f)
            box(pw, CURB_H, CURB_T, cx, curbY, cz + hz - CURB_T / 2f, BackdropMaterialSlot.CURB)
            box(pw, CURB_H, CURB_T, cx, curbY, cz - hz + CURB_T / 2f, BackdropMaterialSlot.CURB)
            if (zRun > 0f) {
                box(CURB_T, CURB_H, zRun, cx - hx + CURB_T / 2f, curbY, cz, BackdropMaterialSlot.CURB)
                box(CURB_T, CURB_H, zRun, cx + hx - CURB_T / 2f, curbY, cz, BackdropMaterialSlot.CURB)
            }
        }

        // ── The home's outer shell ────────────────────────────────────────────────
        // Four unbroken runs just outside the footprint — including past the focus room, whose
        // own full-height exterior wall then reads as the one solid piece of an otherwise
        // knocked-down house.
        val perimX = w / 2f + PERIMETER_GAP
        val perimZ = d / 2f + PERIMETER_GAP
        val perimY = WALL_T / 2f + PERIMETER_H / 2f
        val perimXRun = perimX * 2f + PERIMETER_T
        val perimZRun = (perimZ * 2f - PERIMETER_T).coerceAtLeast(0f)
        box(perimXRun, PERIMETER_H, PERIMETER_T, ox, perimY, oz + perimZ, BackdropMaterialSlot.PERIMETER)
        box(perimXRun, PERIMETER_H, PERIMETER_T, ox, perimY, oz - perimZ, BackdropMaterialSlot.PERIMETER)
        box(PERIMETER_T, PERIMETER_H, perimZRun, ox - perimX, perimY, oz, BackdropMaterialSlot.PERIMETER)
        box(PERIMETER_T, PERIMETER_H, perimZRun, ox + perimX, perimY, oz, BackdropMaterialSlot.PERIMETER)

        return nodes
    }
}
