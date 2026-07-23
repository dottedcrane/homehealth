package com.homerenderer.renderspec

import com.homerenderer.model.FeatureSide
import com.homerenderer.model.FloorLayout
import com.homerenderer.model.PlacedItem
import com.homerenderer.model.RoomItem
import kotlin.math.abs

/**
 * A vehicle lot's coordinate frame: an anchor slot (the in-garage parked spot, or the front
 * apron when there's no garage) plus the driveway strip running out to the street. Vehicle
 * dx/dz offsets (PlacedItem.dx/dz) are always relative to [anchorX]/[anchorZ], so the same
 * fleet renders identically wherever a CarLot is derived from — the exterior driveway
 * (CarLotGeometry.carLot) or the garage-interior view (CarLotGeometry.garageSceneLot).
 */
data class CarLot(
    val anchorX: Float,   // the in-garage parked slot (front apron when there's no garage)
    val anchorZ: Float,
    val doorFaceZ: Float, // world Z of the garage door face — the driveway starts here
    val farZ: Float,      // world Z where the driveway meets the street (tear-out edge)
    val halfW: Float,     // driveway half-width, symmetric around anchorX
    // The rendered pad's own center/half-width, when they need to differ from the anchor —
    // a side garage's pad shouldn't use the same margin on its inner (house-facing) edge as
    // its outer edge, or the pad (and any car on it) overhangs back into the house's own
    // footprint. Defaults to the anchor's own values, so every lot shape that doesn't set
    // them (front garage, no garage) keeps the old symmetric behavior exactly.
    val slabCenterX: Float = anchorX,
    val slabHalfW: Float = halfW,
) {
    // Valid dx range (relative to anchorX) for a vehicle's own center to stay on the pad —
    // callers still inset by their own half-width before clamping/scanning within this.
    val dxMin: Float get() = slabCenterX - slabHalfW - anchorX
    val dxMax: Float get() = slabCenterX + slabHalfW - anchorX
}

object CarLotGeometry {
    const val CAR_LEN = 3.6f
    const val CAR_WID = 1.8f

    // Parked footprint (length along the driveway, width across it) per vehicle type.
    fun vehicleLen(item: RoomItem): Float = when (item) {
        RoomItem.BOAT       -> 4.4f
        RoomItem.MOTORCYCLE -> 2.2f
        else                -> CAR_LEN
    }

    fun vehicleWid(item: RoomItem): Float = when (item) {
        RoomItem.BOAT       -> 1.9f
        RoomItem.MOTORCYCLE -> 0.8f
        else                -> CAR_WID
    }

    // The in-garage parked slot holds ONE vehicle. Anything else stacking onto (0,0) is how
    // cars used to disappear inside each other.
    fun anchorOccupied(vehicles: List<PlacedItem>, excludeId: String? = null): Boolean =
        vehicles.any { it.id != excludeId && it.item.isVehicle && abs(it.dx) < 0.6f && abs(it.dz) < 0.6f }

    // Legal dx/dz for [item] on [lot] — the single definition both the render path and the
    // drag path use, so a vehicle's on-screen position and its draggable bounds can never
    // disagree. Needed because a lot's bounds can narrow after a vehicle's dx/dz was already
    // saved (e.g. the side-garage inner-margin tightening in [carLot]) — without this, a stale
    // offset renders outside its own parent driveway slab's hit box and becomes permanently
    // un-draggable.
    fun clampVehicle(lot: CarLot, item: RoomItem, dx: Float, dz: Float): Pair<Float, Float> {
        val wid = vehicleWid(item)
        val len = vehicleLen(item)
        return dx.coerceIn(lot.dxMin + wid / 2f, lot.dxMax - wid / 2f) to
            dz.coerceIn(0f, lot.farZ - lot.anchorZ - len / 2f + 0.6f)
    }

    // Occupied lot footprints as (item, dx, dz) triples, using each vehicle's RENDERED position:
    // an anchor-parked car rolls out onto the driveway as the overhead door opens (see
    // VehiclesOnLot's pullZ), so its blocking footprint must follow [doorFraction] — checking
    // its stored (0,0) offset would let drops land right on top of the visibly pulled-out car.
    fun occupiedSlots(
        lot: CarLot,
        vehicles: List<PlacedItem>,
        doorFraction: Float,
        garagePresent: Boolean,
        excludeId: String? = null,
    ): List<Triple<RoomItem, Float, Float>> = vehicles
        .filter { it.id != excludeId && it.item.isVehicle }
        .map { o ->
            val atAnchor = garagePresent && abs(o.dx) < 0.4f && abs(o.dz) < 0.4f
            val pullZ = if (atAnchor && o.item == RoomItem.CAR)
                (lot.doorFaceZ + vehicleLen(o.item) / 2f + 0.7f - lot.anchorZ) * doorFraction
            else 0f
            Triple(o.item, o.dx, o.dz + pullZ)
        }

    /**
     * Finds a non-overlapping parking offset for [item] on the driveway strip: the desired spot
     * when it's already clear, otherwise the free spot nearest to it. Falls back to the clamped
     * desired spot if the whole strip is packed — better a visible overlap than a lost vehicle.
     */
    fun freeDrivewaySlot(
        lot: CarLot,
        item: RoomItem,
        desiredDx: Float,
        desiredDz: Float,
        occupied: List<Triple<RoomItem, Float, Float>>,
    ): Pair<Float, Float> {
        val len = vehicleLen(item)
        val wid = vehicleWid(item)
        val minDx = lot.dxMin + wid / 2f
        val maxDx = lot.dxMax - wid / 2f
        val minDz = lot.doorFaceZ - lot.anchorZ + len / 2f
        val maxDz = lot.farZ - lot.anchorZ - len / 2f - 0.4f
        val startDx = desiredDx.coerceIn(minDx, maxDx)
        val startDz = desiredDz.coerceIn(minDz, maxDz)
        fun overlaps(ax: Float, az: Float) = occupied.any { (oi, ox, oz) ->
            abs(ax - ox) < (wid + vehicleWid(oi)) / 2f + 0.25f &&
            abs(az - oz) < (len + vehicleLen(oi)) / 2f + 0.25f
        }
        if (!overlaps(startDx, startDz)) return startDx to startDz
        // Scan the whole strip on a half-metre grid (edges included) and take the free spot
        // NEAREST the aim point — the vehicle settles beside whatever it was dropped onto,
        // instead of at whichever slot a coarse edge-march happened to reach first (three x
        // candidates per row meant one mid-strip vehicle could "fill" every row it spanned).
        val xs = generateSequence(minDx) { it + 0.5f }.takeWhile { it < maxDx }.toList() + maxDx
        val zs = generateSequence(minDz) { it + 0.5f }.takeWhile { it < maxDz }.toList() + maxDz
        var best: Pair<Float, Float>? = null
        var bestDistSq = Float.MAX_VALUE
        for (z in zs) for (x in xs) {
            if (overlaps(x, z)) continue
            val ddx = x - startDx
            val ddz = z - startDz
            val distSq = ddx * ddx + ddz * ddz
            if (distSq < bestDistSq) { bestDistSq = distSq; best = x to z }
        }
        return best ?: (startDx to startDz)
    }

    /**
     * Re-settles every vehicle in [vehicles] onto [lot]: a car near its OLD anchor-relative (0,0)
     * re-claims the anchor (still the right slot regardless of where the garage physically sits),
     * everything else re-resolves via [freeDrivewaySlot] against the lot's actual bounds. Needed
     * whenever the lot's shape can have changed since a vehicle's dx/dz was saved — restoring an
     * old save, claiming a different model (a different lot entirely), or moving the garage to a
     * different side of the SAME home (same lot function, different anchor/orientation) — since
     * dx/dz are meaningless offsets against a lot they weren't computed against.
     */
    fun settleVehiclesOntoLot(vehicles: List<PlacedItem>, lot: CarLot): List<PlacedItem> {
        val settled = mutableListOf<PlacedItem>()
        for (v in vehicles) {
            val keepsAnchor = v.item == RoomItem.CAR &&
                abs(v.dx) < 0.6f && abs(v.dz) < 0.6f && !anchorOccupied(settled)
            settled += if (keepsAnchor) v.copy(dx = 0f, dz = 0f) else {
                val (nx, nz) = freeDrivewaySlot(lot, v.item, v.dx, v.dz,
                    settled.map { Triple(it.item, it.dx, it.dz) })
                v.copy(dx = nx, dz = nz)
            }
        }
        return settled
    }

    // The garage's anchor/footprint always comes from HouseSceneGeometry.garageBox — the same
    // source of truth the shell renderer reads from — instead of re-deriving an equivalent
    // formula independently. That duplication (this function once had its own copy of the
    // LEFT/RIGHT/FRONT anchor math) is exactly how the exterior view and the interior
    // garage-scene view drifted out of agreement before; box.cx/box.cz also already carry
    // [dx]/[dz]'s live drag offset, so a dragged garage's driveway follows it automatically.
    // Shared by every attached-garage lot (HOUSE's LEFT/RIGHT/FRONT and TOWNHOUSE's real
    // interior-room box below) — a box plus which way (if any) to tighten the inner margin so
    // the pad doesn't overhang back into the house's own footprint.
    private fun lotFromBox(box: FeatureBox, sideSign: Float): CarLot {
        val outerMargin = 1.2f
        val doorFaceZ = box.cz + box.d / 2f
        val halfW = box.w / 2f + outerMargin
        return CarLot(box.cx, box.cz, doorFaceZ, doorFaceZ + 7.5f, halfW,
            slabCenterX = box.cx + sideSign * outerMargin / 2f,
            slabHalfW   = if (sideSign != 0f) box.w / 2f + outerMargin / 2f else halfW)
    }

    // The garage-scene coordinate frame: derived from a lot (not a separate hand-written copy —
    // that duplication is exactly how the exterior and interior views drifted out of agreement
    // before), re-centered so the parked slot sits at the ORIGIN with the door facing +Z. A pure
    // translation of the whole frame leaves dxMin/dxMax unchanged (they're anchor-relative), so
    // this guarantees a vehicle's dx/dz is legal here iff it's legal in the exterior lot for the
    // same garage — the same PlacedItems render identically (and stay equally draggable) here
    // and in the exterior view.
    private fun recenter(lot: CarLot): CarLot = lot.copy(
        anchorX = 0f,
        anchorZ = 0f,
        doorFaceZ = lot.doorFaceZ - lot.anchorZ,
        farZ = lot.farZ - lot.anchorZ,
        slabCenterX = lot.slabCenterX - lot.anchorX,
    )

    fun carLot(w: Float, d: Float, garageSide: FeatureSide?, dx: Float = 0f, dz: Float = 0f): CarLot {
        if (garageSide == FeatureSide.LEFT || garageSide == FeatureSide.RIGHT || garageSide == FeatureSide.FRONT) {
            val box = HouseSceneGeometry.garageBox(w, d, garageSide, dx, dz)
            // Side garages: keep the outer (away-from-house) margin the same as the front/no-
            // garage cases, but stop the pad flush with the garage's own wall on the inner
            // (house-facing) edge — a symmetric margin there would let the pad, and any car
            // dragged onto it, overhang back into the house's own footprint.
            val sideSign = when (garageSide) { FeatureSide.LEFT -> -1f; FeatureSide.RIGHT -> 1f; else -> 0f }
            return lotFromBox(box, sideSign)
        }
        // No garage: park on a front apron beside the entry.
        val gW = (w * 0.55f).coerceIn(2.5f, 4.0f)
        return CarLot(w / 4f, d / 2f + CAR_LEN / 2f + 1.0f, d / 2f + 0.3f, d / 2f + 7.5f, gW / 2f + 1.2f)
    }

    fun garageSceneLot(w: Float, d: Float, garageSide: FeatureSide?): CarLot =
        recenter(carLot(w, d, garageSide))

    // TOWNHOUSE's exterior driveway — approaches the real interior garage room's door, cut
    // directly into the house's actual front wall (HouseSceneGeometry.townhouseGarageBox),
    // rather than a free-standing shell positioned by a generic side-dependent formula. Null
    // when the home has no garage room yet (shouldn't happen post-migration, but a save mid-
    // migration or missing the room defensively renders no driveway rather than crashing).
    fun townhouseCarLot(floorLayout: FloorLayout): CarLot? =
        HouseSceneGeometry.townhouseGarageBox(floorLayout)?.let { lotFromBox(it, sideSign = 0f) }

    fun townhouseGarageSceneLot(floorLayout: FloorLayout): CarLot? =
        townhouseCarLot(floorLayout)?.let { recenter(it) }

    // Interior "garage room" lot (TOWNHOUSE) — vehicles are confined entirely within the
    // room's own floor instead of a driveway running out to a street, so unlike every other
    // CarLot there's no far edge to tear a vehicle off onto (callers pass allowTearOff = false
    // to VehiclesOnLot). The pad covers nearly the whole room; the anchor (default single-car
    // slot) sits toward the front so a car has room to roam back toward the far wall.
    fun roomGarageLot(w: Float, d: Float): CarLot {
        val margin    = 0.4f
        val padStartZ = -d / 2f + margin
        val farZ      = d / 2f - margin
        val anchorZ   = padStartZ + CAR_LEN / 2f
        val halfW     = (w / 2f - margin).coerceAtLeast(CAR_WID / 2f + margin)
        return CarLot(anchorX = 0f, anchorZ = anchorZ, doorFaceZ = padStartZ, farZ = farZ, halfW = halfW)
    }
}
