package com.homerenderer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.homerenderer.model.*
import com.homerenderer.renderspec.CarLotGeometry
import com.homerenderer.renderspec.RoomMaterialSlot
import com.homerenderer.renderspec.RoomSceneGeometry
import com.homerenderer.scene.FLOOR_HEIGHT_M
import com.homerenderer.scene.WALL_T
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import kotlin.math.abs

// ── Room interior view ────────────────────────────────────────────────────────
// Coordinate axes:  Y = vertical (up).  X = left/right.  Z = front/back depth.
//
// Room geometry is always centred at world origin (0, 0, 0).
// The zone supplies the room's dimensions (w, d) — NOT its world position.
// The camera in MainActivity stands at eye level (≈1.6 m) in the doorway and looks
// across the room, so doors are rendered swung OPEN (leaves hinged at a jamb) to
// read as an inviting interior rather than a sealed box.

// DEFAULT items the user can drag to a new spot (placed items are always movable). With
// every furniture piece, appliance, and bathroom fixture now a placed item, the only
// default left in place is the garage door (part of the wall) — this set survives for
// the legacy entries below, which only matter to saves still rendering old defaults.
internal val MOVABLE_ITEMS = setOf(
    RoomItem.SOFA, RoomItem.COFFEE_TABLE, RoomItem.TV_STAND,
    RoomItem.BED, RoomItem.DRESSER, RoomItem.NIGHTSTAND,
    RoomItem.DINING_TABLE, RoomItem.DINING_CHAIRS,
    RoomItem.DESK, RoomItem.OFFICE_CHAIR, RoomItem.BOOKSHELF, RoomItem.COMPUTER,
    RoomItem.TREADMILL, RoomItem.EXERCISE_BIKE, RoomItem.WEIGHTS,
    RoomItem.REFRIGERATOR, RoomItem.STOVE, RoomItem.DISHWASHER,
    RoomItem.MICROWAVE, RoomItem.OVEN, RoomItem.RANGE_HOOD,
    RoomItem.WASHER, RoomItem.DRYER, RoomItem.WATER_HEATER,
    RoomItem.VANITY, RoomItem.TREASURE,
)

@Composable
fun SceneScope.RoomScene(
    zone: RoomZone,
    wallModes: Map<FeatureSide, WallMode> = emptyMap(),
    exteriorSlider: Pair<FeatureSide, ClosedFloatingPointRange<Float>>? = null,
    removedInstances: Set<String> = emptySet(),
    defaultInstancePrefix: String = "",
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    placedItems: List<PlacedItem> = emptyList(),
    onTapItem: (RoomItem) -> Unit = {},
    onItemMoved: (RoomItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onPlacedItemTap: (PlacedItem) -> Unit = {},
    onPlacedItemMoved: (PlacedItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onPlacedItemRemoved: (PlacedItem) -> Unit = {},
    // Vehicles parked in an interior GARAGE room (TOWNHOUSE) — empty/no-op for every other
    // room type, which never receives any vehicles from MainActivity to begin with.
    vehicles: List<PlacedItem> = emptyList(),
    onVehicleTap: (PlacedItem) -> Unit = {},
    onVehicleMoved: (PlacedItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onVehicleRemoved: (PlacedItem) -> Unit = {},
) {
    val ht  = FLOOR_HEIGHT_M   // 2.7 m — wall height (Y axis)
    val wt  = WALL_T           // 0.2 m — slab thickness (Y axis)
    val hHt = ht / 2f
    val hw  = zone.w / 2f     // half-width  in X
    val hd  = zone.d / 2f     // half-depth  in Z

    val floorColor = when (zone.type) {
        RoomType.LIVING_ROOM  -> colorOf(0.96f, 0.86f, 0.66f)
        RoomType.KITCHEN      -> colorOf(0.76f, 0.93f, 0.70f)
        RoomType.DINING_ROOM  -> colorOf(0.96f, 0.82f, 0.66f)
        RoomType.HALLWAY      -> colorOf(0.83f, 0.83f, 0.83f)
        RoomType.FOYER        -> colorOf(0.94f, 0.91f, 0.84f)
        RoomType.STAIRCASE    -> colorOf(0.70f, 0.70f, 0.72f)
        RoomType.BEDROOM      -> colorOf(0.82f, 0.72f, 0.96f)
        RoomType.BATHROOM     -> colorOf(0.68f, 0.85f, 0.96f)
        RoomType.LAUNDRY      -> colorOf(0.80f, 0.88f, 0.92f)
        RoomType.OFFICE       -> colorOf(0.72f, 0.82f, 0.88f)
        RoomType.PANTRY       -> colorOf(0.95f, 0.93f, 0.75f)
        RoomType.STORAGE      -> colorOf(0.75f, 0.75f, 0.73f)
        RoomType.GYM          -> colorOf(0.78f, 0.90f, 0.76f)
        RoomType.HOME_THEATER -> colorOf(0.25f, 0.25f, 0.28f)
        RoomType.POWDER_ROOM  -> colorOf(0.78f, 0.90f, 0.96f)
        RoomType.GARAGE       -> colorOf(0.62f, 0.60f, 0.56f)
    }

    val floorMat      = remember { materialLoader.createColorInstance(floorColor) }
    val wallMat       = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.92f, 0.82f)) }
    val sofaMat       = remember { materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.58f)) }
    val woodMat       = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.35f, 0.18f)) }
    val applMat       = remember { materialLoader.createColorInstance(colorOf(0.85f, 0.85f, 0.88f)) }
    val steelMat      = remember { materialLoader.createColorInstance(colorOf(0.45f, 0.47f, 0.50f)) }
    val fixMat        = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.95f, 0.95f)) }
    val fabMat        = remember { materialLoader.createColorInstance(colorOf(0.65f, 0.80f, 0.92f)) }
    val screenMat     = remember { materialLoader.createColorInstance(colorOf(0.08f, 0.08f, 0.10f)) }
    val sinkMat       = remember { materialLoader.createColorInstance(colorOf(0.20f, 0.50f, 0.85f)) }
    val dishwasherMat = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.65f, 0.60f)) }
    val bathtubRimMat = remember { materialLoader.createColorInstance(colorOf(0.96f, 0.96f, 0.96f)) }
    val bathtubBsnMat = remember { materialLoader.createColorInstance(colorOf(0.35f, 0.65f, 0.90f)) }
    val glassMat      = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.78f, 0.92f, 0.35f)) }

    DisposableEffect(Unit) {
        onDispose {
            listOf(floorMat, wallMat, sofaMat, woodMat, applMat, steelMat,
                   fixMat, fabMat, screenMat,
                   sinkMat, dishwasherMat, bathtubRimMat, bathtubBsnMat, glassMat)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Drag state for freestanding furniture — seeded from the persisted [itemOffsets] and
    // updated live during a drag so all of an item's cubes (which all read the same cx/cz)
    // move together; onItemMoved persists the final offset only once the drag ends.
    var liveOffsets by remember(zone) { mutableStateOf(itemOffsets) }

    // Same live/persist split as [liveOffsets], but keyed by each PlacedItem's own instance id
    // rather than its RoomItem type, since a room can hold more than one copy of the same item.
    // Deliberately starts EMPTY (only ids actively dragged this visit get an entry): an item
    // whose offsets are changed externally — e.g. the range hood snapping to a moved stove —
    // must render from the updated [placedItems], not a stale room-entry snapshot.
    var livePlacedOffsets by remember(zone) {
        mutableStateOf(mapOf<String, Pair<Float, Float>>())
    }

    // Where the finger's floor-plane hit was on the PREVIOUS move event of the drag in
    // progress (null between drags). Drags apply the finger's per-event delta to the item's
    // offset rather than snapping the item to the finger: wall-anchored appliances (fridge,
    // oven, …) render at positions DERIVED from cx — not at cx itself — so an absolute
    // "cx = finger" would teleport them half a room on the first event. Delta mode also
    // means plain furniture no longer jumps to center itself under the grab point.
    var dragLast by remember(zone) { mutableStateOf<Pair<Float, Float>?>(null) }
    val dragMargin = 0.4f

    // ── Floor slab and 4 walls at local origin ────────────────────────────────
    // Floor spans (-hw → +hw) in X and (-hd → +hd) in Z, centred at (0, 0, 0).
    // wallModes drives each of the 4 sides independently — SOLID (plain wall), DOOR (swung
    // open into the room), WINDOW (glazed opening), or OPEN (no wall at all, a true threshold
    // into the next room) — mirroring FloorPlanScene/wallOverrides so this view reads as a
    // continuation of the floor plan rather than a generic box.
    fun modeOf(side: FeatureSide) = wallModes[side] ?: WallMode.SOLID

    // Furniture is nested inside the floor node (see below) so a movable item's drag gesture
    // hit-tests against its parent (the floor) to resolve the new floor-plane position.
    CubeNode(size = Size(zone.w, wt, zone.d), materialInstance = floorMat,
        position = Position(0f, 0f, 0f)) {
        // ── Furniture and fixtures at local coordinates ───────────────────────
        // cx/cz = fractional offset from room centre scaled by room dimensions, plus any
        // live drag offset. ItemNode kitchen items compute  xMin = cx − zoneW/2  which with
        // cx=0 gives  −zone.w/2  = left wall in local space. ✓
        // The exterior slider (if any) blocks the same wall here as it does in HouseScene/
        // FloorPlanScene, so the counter flips/notches identically — a true continuation.
        val leftBlocked = exteriorSlider?.first == FeatureSide.LEFT
        val backBlocked = exteriorSlider?.first == FeatureSide.BACK
        val leftDoor    = (if (leftBlocked) modeOf(FeatureSide.RIGHT) else modeOf(FeatureSide.LEFT)) == WallMode.DOOR
        val backDoor    = (if (backBlocked) modeOf(FeatureSide.FRONT) else modeOf(FeatureSide.BACK)) == WallMode.DOOR

        // Everything a room usually has is placed by default; items the user marked
        // "not in my home" (removedInstances, keyed per specific room via defaultInstancePrefix)
        // are hidden.
        zone.type.defaultItems().filter { "$defaultInstancePrefix:${it.name}" !in removedInstances }.forEach { ri ->
            val baseCx  = ri.xFrac * zone.w * 0.38f
            val baseCz  = ri.zFrac * zone.d * 0.38f
            val (offX, offZ) = liveOffsets[ri] ?: (0f to 0f)
            val movable = ri in MOVABLE_ITEMS
            ItemNode(
                item            = ri,
                cx              = baseCx + offX,
                cz              = baseCz + offZ,
                sofaMat         = sofaMat,
                woodMat         = woodMat,
                applMat         = applMat,
                steelMat        = steelMat,
                fixMat          = fixMat,
                fabMat          = fabMat,
                screenMat       = screenMat,
                sinkMat         = sinkMat,
                dishwasherMat   = dishwasherMat,
                bathtubRimMat   = bathtubRimMat,
                bathtubBasinMat = bathtubBsnMat,
                onTap           = { onTapItem(ri) },
                zoneW           = zone.w,
                zoneD           = zone.d,
                leftWallBlocked = if (zone.type == RoomType.KITCHEN) leftBlocked else false,
                backWallBlocked = if (zone.type == RoomType.KITCHEN) backBlocked else false,
                leftWallDoor    = if (zone.type == RoomType.KITCHEN) leftDoor else false,
                backWallDoor    = if (zone.type == RoomType.KITCHEN) backDoor else false,
                movable         = movable,
                onDrag          = if (!movable) null else { wx: Float, wz: Float ->
                    dragLast?.let { (lastX, lastZ) ->
                        val (curX, curZ) = liveOffsets[ri] ?: (0f to 0f)
                        val newCx = (baseCx + curX + (wx - lastX))
                            .coerceIn(-zone.w / 2f + dragMargin, zone.w / 2f - dragMargin)
                        val newCz = (baseCz + curZ + (wz - lastZ))
                            .coerceIn(-zone.d / 2f + dragMargin, zone.d / 2f - dragMargin)
                        liveOffsets = liveOffsets + (ri to (newCx - baseCx to newCz - baseCz))
                    }
                    dragLast = wx to wz
                },
                onDragEnd       = if (!movable) null else {
                    {
                        dragLast = null
                        liveOffsets[ri]?.let { onItemMoved(ri, it) }
                    }
                },
            )
        }

        // ── Placed items — furniture dragged in from the room pane's tray (plus legacy
        // extra appliance copies). Always freestanding/draggable, so no wall-anchored
        // geometry (leftWallBlocked/backWallDoor/etc.) is ever relevant here. Furniture may
        // be dragged THROUGH a wall — releasing it outside the room tears it out (removes
        // it); everything else stays clamped inside as before.
        // Keyed on identity AND orientation: a rotate/flip rebuilds the item's nodes from
        // scratch (fresh geometry + collision) instead of morphing buffers in place, which
        // can leave the renderable in a stale/culled state.
        placedItems.forEach { pi -> key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
            val tearable = pi.item.isFurniture || pi.item.isPortable
            val reach    = if (tearable) -1.2f else dragMargin  // negative margin ⇒ can exit
            val baseCx = pi.item.xFrac * zone.w * 0.38f
            val baseCz = pi.item.zFrac * zone.d * 0.38f
            val (offX, offZ) = livePlacedOffsets[pi.id] ?: (pi.dx to pi.dz)
            ItemNode(
                item            = pi.item,
                cx              = baseCx + offX,
                cz              = baseCz + offZ,
                sofaMat         = sofaMat,
                woodMat         = woodMat,
                applMat         = applMat,
                steelMat        = steelMat,
                fixMat          = fixMat,
                fabMat          = fabMat,
                screenMat       = screenMat,
                sinkMat         = sinkMat,
                dishwasherMat   = dishwasherMat,
                bathtubRimMat   = bathtubRimMat,
                bathtubBasinMat = bathtubBsnMat,
                onTap           = { onPlacedItemTap(pi) },
                // Placed furniture/portable appliances are freestanding — zone dims of 0
                // keep them out of the wall/slot-anchored branches (a placed fridge must
                // stand where it was dropped, not snap into the kitchen counter bay).
                // Legacy placed copies of built-ins keep the slotted look.
                zoneW           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.w,
                zoneD           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.d,
                movable         = true,
                rotDeg          = pi.rotDeg,
                flipX           = pi.flipX,
                flipZ           = pi.flipZ,
                onDrag          = { wx: Float, wz: Float ->
                    dragLast?.let { (lastX, lastZ) ->
                        val (curX, curZ) = livePlacedOffsets[pi.id] ?: (pi.dx to pi.dz)
                        val newCx = (baseCx + curX + (wx - lastX))
                            .coerceIn(-zone.w / 2f + reach, zone.w / 2f - reach)
                        val newCz = (baseCz + curZ + (wz - lastZ))
                            .coerceIn(-zone.d / 2f + reach, zone.d / 2f - reach)
                        livePlacedOffsets = livePlacedOffsets + (pi.id to (newCx - baseCx to newCz - baseCz))
                    }
                    dragLast = wx to wz
                },
                onDragEnd       = {
                    dragLast = null
                    livePlacedOffsets[pi.id]?.let { (dx, dz) ->
                        val outside = abs(baseCx + dx) > zone.w / 2f + 0.05f ||
                                      abs(baseCz + dz) > zone.d / 2f + 0.05f
                        if (tearable && outside) onPlacedItemRemoved(pi)
                        else onPlacedItemMoved(pi, dx to dz)
                    }
                    // Drop back to reading from the fresh `placedItems` value once the drag
                    // is over — otherwise this cached offset would keep overriding any later
                    // EXTERNAL update to pi.dx/dz (e.g. a range hood following a moved stove,
                    // see onPlacedItemMoved in MainActivity) forever, not just during the drag.
                    livePlacedOffsets = livePlacedOffsets - pi.id
                },
            )
        } }
    }

    // Vehicles parked in an interior garage room — same rendering/drag machinery as the
    // exterior driveway and garage-scene views (VehiclesOnLot), bounded to the room's own
    // footprint instead of a driveway. No door-open animation (doorFraction = 0) and no
    // tear-off edge (allowTearOff = false) since there's no street to pull a car onto/off of.
    if (zone.type == RoomType.GARAGE) {
        VehiclesOnLot(
            lot              = CarLotGeometry.roomGarageLot(zone.w, zone.d),
            vehicles         = vehicles,
            doorFraction     = 0f,
            garagePresent    = true,
            movable          = true,
            allowTearOff     = false,
            onVehicleTap     = onVehicleTap,
            onVehicleMoved   = onVehicleMoved,
            onVehicleRemoved = onVehicleRemoved,
        )
    }

    // Walls (with door/window/slider openings) and corner pillars are pure geometry —
    // computed portably in :core-render-spec and rendered here by mapping each node's
    // material slot onto this composable's already-built MaterialInstances.
    RoomSceneGeometry.build(zone, wallModes, exteriorSlider).forEach { n ->
        val mat = when (n.material) {
            RoomMaterialSlot.WALL  -> wallMat
            RoomMaterialSlot.WOOD  -> woodMat
            RoomMaterialSlot.GLASS -> glassMat
        }
        CubeNode(
            size = Size(n.size.x, n.size.y, n.size.z),
            materialInstance = mat,
            position = Position(n.position.x, n.position.y, n.position.z),
        ) {}
    }
}
