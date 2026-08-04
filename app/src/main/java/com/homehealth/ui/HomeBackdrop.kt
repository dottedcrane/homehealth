package com.homehealth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.homehealth.model.*
import com.homehealth.renderspec.BackdropMaterialSlot
import com.homehealth.renderspec.CarLot
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.FeatureBox
import com.homehealth.renderspec.HomeBackdropGeometry
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.renderspec.RoomPalette
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.ITEM_SCALE_REFERENCE
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf

/**
 * Everything a room needs to draw what's around it, in one bag — the storey it belongs to, the
 * home's placed items (so other rooms can be ghosted and its vehicles drawn), and the exterior
 * features (which the world layer needs to keep its trees out of the yard, and which decide
 * where the vehicle lot is). Passed as one value because none of it describes the room itself:
 * RoomScene's own geometry is built from its zone alone.
 *
 * The vehicle derivations below are the single answer to "where does this home park", shared by
 * the driveway zone that owns the fleet, every scene that shows it as scenery, and the drop
 * handler — deriving it twice is what let the interior and exterior views disagree before.
 */
data class RoomSurroundings(
    val layout: FloorLayout,
    val floor: Int,
    val placedItems: List<PlacedItem> = emptyList(),
    val featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    val featureOffsets: Map<HomeFeature, Pair<Float, Float>> = emptyMap(),
) {
    val vehicles: List<PlacedItem> get() = placedItems.filter { it.item.isVehicle }

    /**
     * The home's one lot, in world coordinates centred on the home — garage's live position
     * included, so dragging it to another wall takes the driveway and the fleet with it.
     * [extra] sizes the lot for a vehicle about to be dropped as well as the ones already
     * parked; without it the Nth vehicle would be dropped into a driveway sized for N−1.
     */
    fun vehicleLotFor(extra: RoomItem? = null): CarLot = CarLotGeometry.homeLot(
        layout, featurePlacements, featureOffsets,
        vehicles = vehicles.map { it.item } + listOfNotNull(extra),
    )

    val vehicleLot: CarLot get() = vehicleLotFor()

    /** The lot as a place you can walk into and stand on — see CarLotGeometry.drivewayZone. */
    val drivewayZone: RoomZone get() = CarLotGeometry.drivewayZone(vehicleLot)

    /** The space behind the garage door, where non-vehicle belongings sit — one rectangle whether
     *  this home's garage is an attached wing or a real room (see CarLotGeometry.garageZone). */
    val garageZone: RoomZone get() = CarLotGeometry.garageZone(vehicleLot, garageW, garageD)

    /**
     * Which bucket a thing put in the garage belongs to, so that one physical space never ends up
     * with two sets of contents:
     *  - a garage ROOM (TOWNHOUSE always, some HOUSE presets) → that room's own placement id, so a
     *    water heater dropped from the driveway is the same object the floor plan shows inside it;
     *  - an attached wing → [GARAGE_PLACEMENT_ID], since there is no RoomPlacement to hang off;
     *  - no garage at all (a CONDO, a HOUSE before one is added) → null: nowhere to put anything.
     *
     * The room is found through the layout's OWN merged zone rather than a zone rebuilt from
     * [garageZone]'s centre and width — placementIdsInZone matches on zone equality, and a
     * round-tripped RoomZone won't reliably float-compare equal to the one it came from.
     */
    val garagePlacementId: String? get() {
        layout.toMergedZones(0).firstOrNull { it.type == RoomType.GARAGE }?.let { zone ->
            layout.placementIdsInZone(zone, 0).minOrNull()?.let { return it }
        }
        return if (garageEnclosed) GARAGE_PLACEMENT_ID else null
    }

    // The garage that encloses the lot's anchor slot, in either of the two forms a home can own
    // it: a real interior room, or an attached wing. Neither means the vehicles park in the open
    // (a CONDO, or a HOUSE before a garage is dragged on) — the fallback dimensions are only
    // there so callers have something to size an un-drawn shell with.
    private val garageBox: FeatureBox? get() {
        HouseSceneGeometry.townhouseGarageBox(layout)?.let { return it }
        val side  = featurePlacements[HomeFeature.GARAGE] ?: return null
        val along = featureOffsets[HomeFeature.GARAGE]?.first ?: 0f
        val (gdx, gdz) = if (side == FeatureSide.FRONT) along to 0f else 0f to along
        return HouseSceneGeometry.garageBox(layout.totalW, layout.totalD, side, gdx, gdz)
    }

    val garageEnclosed: Boolean get() = garageBox != null
    val garageW: Float get() = garageBox?.w ?: (layout.totalW * 0.55f).coerceIn(2.5f, 4.0f)
    val garageD: Float get() = garageBox?.d ?: minOf(layout.totalD, 5.5f)
}

// ── The rest of the home, around the room you're standing in ──────────────────
// Rendered by RoomScene behind its own room, so stepping into a bedroom shows where that bedroom
// actually sits: every other room on the storey at its true position and size, walls cut down to
// knee height, its furniture in flat ghost outline, inside the home's outer boundary. Orbit and
// the home is there — hallway that way, kitchen beyond it.
//
// Untouchable is enforced, not merely implied. Shell nodes set isTouchable = false and ghost
// furniture goes through ItemNode with interactive = false (whose commonApply sets the same
// flag). That both stops the backdrop responding and — the reason it matters — stops it
// SWALLOWING a tap meant for the real room, since SceneView dispatches only to the frontmost
// touchable node and never falls through. Drags and tray drops resolve against a plane rather
// than a node, so they don't see the backdrop either: an item released over the ghost dining
// room still lands in the room you're in.
@Composable
internal fun SceneScope.HomeBackdrop(
    surroundings: RoomSurroundings,
    focus: RoomZone,
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    removedInstances: Set<String> = emptySet(),
    // False when the caller is the driveway zone itself, which renders the same fleet fully
    // interactive (see DrivewayLot) — drawing both would double every vehicle.
    showVehicles: Boolean = true,
    // False from the attic, which has no storey to cut away: what the backdrop would draw is the
    // floor BELOW it, hidden under a full attic's own opaque floor — and on an empty storey
    // HomeBackdropGeometry.build emits only the perimeter runs, which land exactly where a full
    // attic's side walls are and would z-fight them. The world layer above still runs, which is
    // what places the attic on top of this home on this street rather than in a void.
    showStorey: Boolean = true,
) {
    val layout      = surroundings.layout
    val floor       = surroundings.floor
    val placedItems = surroundings.placedItems

    // ── The world beyond the home ─────────────────────────────────────────────
    // The same street the exterior and the floor plan stand on. A room scene renders its own room
    // at the world origin, so the world shifts by minus the room's centre; the storey lift makes
    // an upstairs room look DOWN on the neighbourhood, and the extra 5 cm keeps the world's ground
    // (top −0.07) clear of this room's floor slab underside (−0.10).
    NeighborhoodBackdrop(
        floorLayout       = layout,
        featurePlacements = surroundings.featurePlacements,
        offset            = Position(
            -focus.cx,
            -(floor * FLOOR_HEIGHT_M + 0.05f),
            -focus.cz,
        ),
    )

    // The fleet, where it really is. Vehicles live on one world lot (see RoomSurroundings), so
    // the car on the driveway shows through the front wall from the living room and sits inside
    // the garage room when you walk into it — the same real spot, seen from wherever you stand.
    // Scenery here: only the driveway zone manages vehicles.
    val vehicles = if (showVehicles) surroundings.vehicles else emptyList()
    if (vehicles.isNotEmpty()) {
        VehiclesOnLot(
            lot = CarLotGeometry.translate(
                surroundings.vehicleLot,
                -focus.cx,
                -focus.cz,
            ),
            vehicles         = vehicles,
            // No door in sight from in here, so nothing is mid-roll-out: a parked car sits at
            // its stored offset.
            doorFraction     = 0f,
            garagePresent    = surroundings.garageEnclosed,
            movable          = false,
            interactive      = false,
            // Down with the world for an upstairs room, so the cars stay on the ground below.
            yOffset          = -(floor * FLOOR_HEIGHT_M),
            onVehicleTap     = {},
            onVehicleMoved   = { _, _ -> },
            onVehicleRemoved = {},
        )
    }

    // Muted room tints (RoomPalette.muted). The floor pads and the ghost furniture are OPAQUE:
    // now that the room's own walls are see-through (see RoomScene's wallMat), the backdrop is
    // what you look AT rather than through, and a sight line already crosses a room wall, a knee
    // wall and the world in one ray — every avoidable transparent layer is one less chance of a
    // depth-sorting artefact. Only the verticals stay half-transparent, so a knee wall between
    // you and the far side of the home never hides it. All 16 types are built once here, same as
    // FloorPlanScene's floor palette.
    val padMats = remember {
        RoomType.entries.associateWith { type ->
            val c = RoomPalette.muted(type)
            materialLoader.createColorInstance(colorOf(c.r, c.g, c.b))
        }
    }
    val groundMat    = remember { materialLoader.createColorInstance(colorOf(0.46f, 0.48f, 0.46f)) }
    val curbMat      = remember { materialLoader.createColorInstance(colorOf(0.78f, 0.76f, 0.72f, 0.50f)) }
    val perimeterMat = remember { materialLoader.createColorInstance(colorOf(0.88f, 0.86f, 0.78f, 0.45f)) }
    // ONE material behind every part of every ghost item — a backdrop sofa reads as a silhouette
    // of a sofa rather than a second fully-furnished room competing with the one you're in.
    val ghostMat     = remember { materialLoader.createColorInstance(colorOf(0.52f, 0.54f, 0.58f)) }
    DisposableEffect(Unit) {
        onDispose {
            padMats.values.forEach { engine.destroyMaterialInstance(it) }
            listOf(groundMat, curbMat, perimeterMat, ghostMat)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // ── Room shells and the home's outer boundary ─────────────────────────────
    // No ground pad of its own: the world's ground plane above already covers the footprint and
    // sits a few centimetres higher, so a second one would be a wasted node and a z-fight.
    if (!showStorey) return

    HomeBackdropGeometry.build(layout, floor, focus, ground = false).forEach { n ->
        val mat = when (n.material) {
            BackdropMaterialSlot.GROUND    -> groundMat
            BackdropMaterialSlot.ROOM_PAD  -> padMats[n.roomType] ?: groundMat
            BackdropMaterialSlot.CURB      -> curbMat
            BackdropMaterialSlot.PERIMETER -> perimeterMat
        }
        CubeNode(
            size             = Size(n.size.x, n.size.y, n.size.z),
            materialInstance = mat,
            position         = Position(n.position.x, n.position.y, n.position.z),
            apply            = { isTouchable = false },
        ) {}
    }

    // ── Ghosted furniture ─────────────────────────────────────────────────────
    // Same shape as the floor plan's own furniture pass (see FloorPlanScene), so a backdrop room
    // holds exactly what looking down at it from the floor plan would show — including items the
    // user has marked "not in my home", which are absent from both.
    //
    // Coordinates are the focus room's local frame (RoomScene renders itself at the world
    // origin), hence the zoneCx/zoneCz shift. yBase stays 0: ItemNode puts an item's floor at
    // yBase + WALL_T/2, which is exactly a backdrop pad's top surface.
    HomeBackdropGeometry.surroundingZones(layout, floor, focus).forEach { zone ->
        // Circulation space is furnished with nothing — same exclusion the floor plan applies.
        if (zone.type == RoomType.HALLWAY || zone.type == RoomType.FOYER ||
            zone.type == RoomType.STAIRCASE) return@forEach

        val zoneCx = zone.cx - focus.cx
        val zoneCz = zone.cz - focus.cz
        val scale  = (minOf(zone.w, zone.d) / ITEM_SCALE_REFERENCE).coerceAtMost(1.0f)
        // A kitchen's counter run notches around whichever wall carries a door. The sliding-glass
        // flip the floor plan also applies is deliberately not mirrored here: it would mean
        // threading the home's decks through RoomScene to move a knee-high ghost of a counter in
        // a room the user isn't standing in.
        val wallModes = if (zone.type == RoomType.KITCHEN) layout.wallModesForZone(zone, floor) else emptyMap()
        val placementIds = layout.placementIdsInZone(zone, floor)
        val repId = placementIds.minOrNull() ?: ""

        @Composable
        fun ghost(
            item: RoomItem,
            cx: Float,
            cz: Float,
            rotDeg: Int = 0,
            flipX: Boolean = false,
            flipZ: Boolean = false,
        ) {
            ItemNode(
                item            = item,
                cx              = cx,
                cz              = cz,
                sofaMat         = ghostMat,
                woodMat         = ghostMat,
                applMat         = ghostMat,
                steelMat        = ghostMat,
                fixMat          = ghostMat,
                fabMat          = ghostMat,
                screenMat       = ghostMat,
                sinkMat         = ghostMat,
                dishwasherMat   = ghostMat,
                bathtubRimMat   = ghostMat,
                bathtubBasinMat = ghostMat,
                scale           = scale,
                // Freestanding pieces stand where they were dropped; built-ins stay anchored to
                // their room's walls — the same split RoomScene and FloorPlanScene both make.
                zoneW           = if (item.isFurniture || item.isPortable) 0f else zone.w,
                zoneD           = if (item.isFurniture || item.isPortable) 0f else zone.d,
                zoneCx          = zoneCx,
                zoneCz          = zoneCz,
                interactive     = false,
                leftWallDoor    = wallModes[FeatureSide.LEFT] == WallMode.DOOR,
                backWallDoor    = wallModes[FeatureSide.BACK] == WallMode.DOOR,
                rotDeg          = rotDeg,
                flipX           = flipX,
                flipZ           = flipZ,
            )
        }

        // Defaults the room type comes with. GARAGE_DOOR is skipped outright: it's a wall panel,
        // and a backdrop garage has only a knee-high outline for it to belong to.
        zone.type.defaultItems()
            .filter { it != RoomItem.GARAGE_DOOR && "$repId:${it.name}" !in removedInstances }
            .forEach { item ->
                val (offX, offZ) = itemOffsets[item] ?: (0f to 0f)
                ghost(
                    item = item,
                    cx   = zoneCx + item.xFrac * zone.w * 0.38f + offX,
                    cz   = zoneCz + item.zFrac * zone.d * 0.38f + offZ,
                )
            }

        // Pieces the user placed in this room. Keyed on identity AND orientation for the same
        // reason every other scene is: a rotate/flip must rebuild the nodes rather than morph
        // geometry in place (see the sceneview-node-identity notes in RoomScene).
        placedItems.filter { it.placementId in placementIds }.forEach { pi ->
            key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
                ghost(
                    item   = pi.item,
                    cx     = zoneCx + pi.item.xFrac * zone.w * 0.38f + pi.dx,
                    cz     = zoneCz + pi.item.zFrac * zone.d * 0.38f + pi.dz,
                    rotDeg = pi.rotDeg,
                    flipX  = pi.flipX,
                    flipZ  = pi.flipZ,
                )
            }
        }
    }
}
