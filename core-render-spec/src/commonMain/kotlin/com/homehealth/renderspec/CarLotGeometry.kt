package com.homehealth.renderspec

import com.homehealth.model.FeatureSide
import com.homehealth.model.FloorLayout
import com.homehealth.model.HomeFeature
import com.homehealth.model.PlacedItem
import com.homehealth.model.RoomItem
import com.homehealth.model.RoomType
import com.homehealth.model.RoomZone
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

    // How much driveway DEPTH (garage door → street) is needed to fit every vehicle, packing
    // them side by side across the lot's own width before adding another row — matches how
    // freeDrivewaySlot's grid search actually fills (width first, then depth), so the lot only
    // grows once vehicles genuinely no longer fit shoulder to shoulder, not the moment a 3rd
    // vehicle appears while there's still room beside the first two. Never shrinks below the
    // historical fixed run, so a home with few vehicles (or one already-wide-enough lot)
    // renders exactly as before.
    private const val BASE_DRIVEWAY_RUN = 7.5f
    private const val VEHICLE_GAP = 0.25f
    private fun neededDrivewayRun(vehicles: List<RoomItem>, laneWidth: Float): Float {
        if (vehicles.isEmpty()) return BASE_DRIVEWAY_RUN
        // Conservative per-row count: uses the widest vehicle present so a boat in the mix
        // never gets estimated more rows of room than it actually needs.
        val widestVehicle = vehicles.maxOf { vehicleWid(it) }
        val perRow = maxOf(1, ((laneWidth + VEHICLE_GAP) / (widestVehicle + VEHICLE_GAP)).toInt())
        val rows = (vehicles.size + perRow - 1) / perRow
        val rowDepth = vehicles.maxOf { vehicleLen(it) } + VEHICLE_GAP
        return maxOf(BASE_DRIVEWAY_RUN, rows * rowDepth + 1.0f)
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
    private fun lotFromBox(box: FeatureBox, sideSign: Float, vehicles: List<RoomItem> = emptyList()): CarLot {
        val outerMargin = 1.2f
        val doorFaceZ = box.cz + box.d / 2f
        val halfW = box.w / 2f + outerMargin
        val slabHalfW = if (sideSign != 0f) box.w / 2f + outerMargin / 2f else halfW
        return CarLot(box.cx, box.cz, doorFaceZ, doorFaceZ + neededDrivewayRun(vehicles, slabHalfW * 2f), halfW,
            slabCenterX = box.cx + sideSign * outerMargin / 2f,
            slabHalfW   = slabHalfW)
    }

    /**
     * The same lot, moved. Every scene draws the ONE lot [carLot]/[townhouseCarLot] describes,
     * each in its own frame: the exterior and the floor plan use it as-is (both are centred on
     * the home), and a room scene — which renders its own room at the origin — shifts it by minus
     * that room's centre.
     *
     * A pure translation leaves `dxMin`/`dxMax` unchanged, since they're anchor-relative. That is
     * what guarantees a vehicle's dx/dz is legal in one scene iff it's legal in all of them: the
     * same PlacedItems render in the same real-world spot, and stay equally draggable, wherever
     * you're standing. (Deriving a second frame with its own anchor instead — as the old
     * room-garage lot did — is exactly how the interior and exterior views drifted apart.)
     */
    fun translate(lot: CarLot, dx: Float, dz: Float): CarLot = lot.copy(
        anchorX = lot.anchorX + dx,
        anchorZ = lot.anchorZ + dz,
        doorFaceZ = lot.doorFaceZ + dz,
        farZ = lot.farZ + dz,
        slabCenterX = lot.slabCenterX + dx,
    )

    /**
     * The lot as a room-shaped zone — the "driveway", a place you walk into and manage vehicles
     * from, rendered by RoomScene like any other zone (see RoomPlace.DRIVEWAY).
     *
     * Deliberately typed [RoomType.GARAGE] rather than introducing a room type of its own: no
     * saved layout changes, and every existing "is this the garage?" branch — the vehicle tray,
     * the maintenance strip's vehicle case, the pane's icon and label — keeps working untouched.
     * It spans the pad plus the garage box behind the door, so standing in it frames both.
     */
    /**
     * The garage's INTERIOR as a room-shaped zone — the box the shell is drawn around, centred on
     * the lot anchor. [drivewayZone] above is the pad outside it; this is the space behind the
     * door, and the place non-vehicle belongings sit.
     *
     * It is the same rectangle whether the home's garage is an attached wing or a real interior
     * room, because [lotFromBox] anchors both on their box's own centre — so one zone describes
     * both, and one set of contents renders identically from the driveway and (for a room) from
     * inside it.
     *
     * Anchored rather than zone-derived on purpose: the DRIVEWAY's extent grows with the fleet, so
     * offsets stored against it would shift every time a car was added. The garage box moves only
     * when the garage itself does — and then its contents should move with it, exactly as the
     * anchor-relative vehicles already do.
     */
    fun garageZone(lot: CarLot, gW: Float, gD: Float): RoomZone = RoomZone(
        type = RoomType.GARAGE,
        xMin = lot.anchorX - gW / 2f, xMax = lot.anchorX + gW / 2f,
        zMin = lot.anchorZ - gD / 2f, zMax = lot.anchorZ + gD / 2f,
    )

    fun drivewayZone(lot: CarLot): RoomZone = RoomZone(
        type = RoomType.GARAGE,
        xMin = lot.slabCenterX - lot.slabHalfW,
        xMax = lot.slabCenterX + lot.slabHalfW,
        // Back edge: behind the door face by the anchor slot's own depth, so the parked vehicle
        // inside the garage is inside the zone rather than clipped off its back edge.
        zMin = minOf(lot.doorFaceZ, lot.anchorZ - CAR_LEN / 2f) - 0.6f,
        zMax = lot.farZ,
    )

    fun carLot(
        w: Float, d: Float, garageSide: FeatureSide?, dx: Float = 0f, dz: Float = 0f,
        vehicles: List<RoomItem> = emptyList(),
    ): CarLot {
        if (garageSide == FeatureSide.LEFT || garageSide == FeatureSide.RIGHT || garageSide == FeatureSide.FRONT) {
            val box = HouseSceneGeometry.garageBox(w, d, garageSide, dx, dz)
            // Side garages: keep the outer (away-from-house) margin the same as the front/no-
            // garage cases, but stop the pad flush with the garage's own wall on the inner
            // (house-facing) edge — a symmetric margin there would let the pad, and any car
            // dragged onto it, overhang back into the house's own footprint.
            val sideSign = when (garageSide) { FeatureSide.LEFT -> -1f; FeatureSide.RIGHT -> 1f; else -> 0f }
            return lotFromBox(box, sideSign, vehicles)
        }
        // No garage: park on a front apron beside the entry.
        val gW = (w * 0.55f).coerceIn(2.5f, 4.0f)
        val halfW = gW / 2f + 1.2f
        val doorFaceZ = d / 2f + 0.3f
        return CarLot(w / 4f, d / 2f + CAR_LEN / 2f + 1.0f, doorFaceZ, doorFaceZ + neededDrivewayRun(vehicles, halfW * 2f), halfW)
    }

    // TOWNHOUSE's exterior driveway — approaches the real interior garage room's door, cut
    // directly into the house's actual front wall (HouseSceneGeometry.townhouseGarageBox),
    // rather than a free-standing shell positioned by a generic side-dependent formula. Null
    // when the home has no garage room yet (shouldn't happen post-migration, but a save mid-
    // migration or missing the room defensively renders no driveway rather than crashing).
    fun townhouseCarLot(floorLayout: FloorLayout, vehicles: List<RoomItem> = emptyList()): CarLot? =
        HouseSceneGeometry.townhouseGarageBox(floorLayout)?.let { lotFromBox(it, sideSign = 0f, vehicles) }

    /**
     * The home's one vehicle lot, whichever form its garage takes: the real interior garage room
     * if it has one, otherwise the attached garage feature, otherwise a front apron. Every scene
     * resolves it through here so there is exactly one answer to "where do this home's vehicles
     * live", in world coordinates centred on the home.
     */
    fun homeLot(
        floorLayout: FloorLayout,
        garageSide: FeatureSide?,
        dx: Float = 0f,
        dz: Float = 0f,
        vehicles: List<RoomItem> = emptyList(),
    ): CarLot =
        townhouseCarLot(floorLayout, vehicles)
            ?: carLot(floorLayout.totalW, floorLayout.totalD, garageSide, dx, dz, vehicles)

    /**
     * [homeLot] straight from the home's feature state — including the garage's own drag offset,
     * which is stored as a single "along the wall" value whose axis depends on which wall it's
     * on. Every scene that needs the lot goes through here, so a garage dragged to a new spot
     * takes its driveway (and the fleet parked on it) with it everywhere at once; deriving the
     * offset by hand at each call site is how the floor plan and the drop handler came to
     * disagree with the exterior about where a dragged garage's cars were.
     */
    fun homeLot(
        floorLayout: FloorLayout,
        featurePlacements: Map<HomeFeature, FeatureSide>,
        featureOffsets: Map<HomeFeature, Pair<Float, Float>> = emptyMap(),
        vehicles: List<RoomItem> = emptyList(),
    ): CarLot {
        val side  = featurePlacements[HomeFeature.GARAGE]
        val along = featureOffsets[HomeFeature.GARAGE]?.first ?: 0f
        val (dx, dz) = if (side == FeatureSide.FRONT) along to 0f else 0f to along
        return homeLot(floorLayout, side, dx, dz, vehicles)
    }
}
