package com.homehealth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.homehealth.model.*
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.renderspec.RoomMaterialSlot
import com.homehealth.renderspec.RoomPalette
import com.homehealth.renderspec.RoomSceneGeometry
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T
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
// The floor SLAB is centred on y = 0 and WALL_T thick, so the walkable surface — what the Eye
// camera's height is measured from, and the plane furniture drags resolve against — is y = +0.1.
// The Eye camera stands just inside the doorway at 1.62 m above that and looks across the room,
// so doors are rendered swung OPEN (leaves hinged at a jamb) to read as an inviting interior
// rather than a sealed box.

// DEFAULT items the user can drag to a new spot (placed items are always movable). With
// every furniture piece, appliance, and bathroom fixture now a placed item, the only
// default left in place is the garage door (part of the wall) — this set survives for
// the legacy entries below, which only matter to saves still rendering old defaults.
internal val MOVABLE_ITEMS = setOf(
    RoomItem.SOFA, RoomItem.COFFEE_TABLE, RoomItem.TV_STAND,
    RoomItem.BED, RoomItem.DRESSER, RoomItem.NIGHTSTAND,
    RoomItem.DINING_TABLE,
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
    // The storey and the home this room sits in. Only the backdrop reads it: the room's own
    // geometry is built from [zone] alone and centred on the origin, which is what keeps every
    // camera framing, drag plane and tray-drop ray in the app true (see the coordinate note
    // above). Note its placedItems are the whole HOME's — [placedItems] below stays what it has
    // always been, this room's own pieces, the only ones that can be tapped, dragged or torn out.
    surroundings: RoomSurroundings,
    // Which kind of place this is (see RoomPlace). The two synthetic ones — the driveway and the
    // attic — are zones you stand in rather than rooms you're inside: no walls, no floor slab and
    // no default fittings, their content supplied by [DrivewayLot] and [AtticInterior] instead.
    // Everything else the room view provides (camera, pane, tray, backdrop, world) is identical,
    // which is the whole reason both are modelled as zones instead of scenes of their own.
    place: RoomPlace = RoomPlace.INDOOR,
    // The attic's resolved shape and contents, supplied only in [RoomPlace.ATTIC]. Both are
    // decided outside (the owner's AtticType and their "not in my home" choices, see
    // HouseSceneGeometry.atticVolume/atticContents), so this scene stays type-agnostic.
    atticVolume: HouseSceneGeometry.AtticVolume? = null,
    atticContents: List<HouseSceneGeometry.AtticItem> = emptyList(),
    onAtticItemTap: (HouseSceneGeometry.AtticItem) -> Unit = {},
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
    // The fleet, and what it can do — only ever wired for the driveway zone, which is where
    // vehicles are managed from. Every other room shows the same vehicles as scenery through
    // the backdrop instead (see HomeBackdrop), so these stay empty/no-op there.
    vehicles: List<PlacedItem> = emptyList(),
    onVehicleTap: (PlacedItem) -> Unit = {},
    onVehicleMoved: (PlacedItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onVehicleRemoved: (PlacedItem) -> Unit = {},
    // Mirrors the garage door's live open fraction out to MainActivity's vehicle drop, which
    // needs it to know whether an anchor-parked car is still rolling out — see DrivewayLot.
    onDoorFractionChanged: (Float) -> Unit = {},
    // Tapping bare floor (not furniture/a vehicle) opens the same room change/remove card the
    // floor plan's long-press menu uses — lets floor-plan editing reach in from inside the room.
    // In the attic the same gesture asks what kind of space this is (attic or utility closet).
    onFloorTap: () -> Unit = {},
) {
    val indoor = place == RoomPlace.INDOOR
    val ht  = FLOOR_HEIGHT_M   // 2.7 m — wall height (Y axis)
    val wt  = WALL_T           // 0.2 m — slab thickness (Y axis)
    val hHt = ht / 2f
    val hw  = zone.w / 2f     // half-width  in X
    val hd  = zone.d / 2f     // half-depth  in Z

    // One palette for every scene that draws a room — the floor plan above, the room from
    // inside, and the backdrop below all read the same colours (see RoomPalette).
    val floorColor = RoomPalette.floor(zone.type).let { colorOf(it.r, it.g, it.b) }

    val floorMat      = remember { materialLoader.createColorInstance(floorColor) }
    // See-through walls (alpha < 1 makes createColorInstance pick the transparent material, the
    // same way the exterior's roof and this room's own glass do). The room still reads as an
    // enclosed space and keeps its cream colour, but the storey and the street around it — which
    // HomeBackdrop draws precisely so you can see where you're standing — are actually visible
    // from inside rather than only over the wall tops. Door leaves (WOOD) stay opaque, so an
    // opening still reads as an opening.
    val wallMat       = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.92f, 0.82f, 0.45f)) }
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

    // The rest of the storey, drawn around this room — see HomeBackdrop. It opts out of
    // hit-testing entirely (isTouchable = false on the shells, interactive = false on the ghost
    // furniture), so wherever it lands on screen it can neither be acted on nor swallow a tap
    // meant for this room.
    HomeBackdrop(
        surroundings     = surroundings,
        focus            = zone,
        itemOffsets      = itemOffsets,
        removedInstances = removedInstances,
        showVehicles     = place != RoomPlace.DRIVEWAY,
        // From the attic there is no storey to cut away: a full attic's own opaque floor covers
        // the entire footprint, and the storey the backdrop would draw is the one BELOW it. The
        // world layer still runs, which is what makes orbiting up here read as being on top of
        // this home on this street rather than a box in a void.
        showStorey       = place != RoomPlace.ATTIC,
    )

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
    //
    // The first move event of a drag only seeds this anchor, so it moves nothing. That costs
    // nothing now: SceneDragController fires the first onDrag as soon as the finger moves after
    // the pick-up, where the old path threw away the first 31.6 px of travel before reporting
    // anything at all.
    var dragLast by remember(zone) { mutableStateOf<Pair<Float, Float>?>(null) }
    val dragMargin = 0.4f

    // ── Floor slab and 4 walls at local origin ────────────────────────────────
    // Floor spans (-hw → +hw) in X and (-hd → +hd) in Z, centred at (0, 0, 0).
    // wallModes drives each of the 4 sides independently — SOLID (plain wall), DOOR (swung
    // open into the room), WINDOW (glazed opening), or OPEN (no wall at all, a true threshold
    // into the next room) — mirroring FloorPlanScene/wallOverrides so this view reads as a
    // continuation of the floor plan rather than a generic box.
    fun modeOf(side: FeatureSide) = wallModes[side] ?: WallMode.SOLID

    // ── Placed items — pieces dragged in from the pane's tray (plus legacy extra appliance
    // copies). Always freestanding/draggable, so no wall-anchored geometry (leftWallBlocked/
    // backWallDoor/etc.) is ever relevant here. Furniture may be dragged THROUGH a wall —
    // releasing it outside the zone tears it out (removes it); everything else stays clamped in.
    //
    // Hoisted out of the floor node so the attic can render its keepsakes the same way. Nesting
    // was never what made dragging work — SceneDragController projects the finger onto the floor
    // PLANE, not the floor NODE — so emitting these at the top level with the right [yBase]
    // behaves identically. A room's slab is centred on y = 0 with its surface at +WALL_T/2, which
    // is where ItemNode puts an item's feet for yBase = 0; the attic's floor has its TOP face at
    // y = 0 instead (see atticShell), hence the half-slab drop there.
    // [itemZone] is the zone these items BELONG to, which is the focus zone everywhere except the
    // driveway: things dropped there go into the garage behind it, so they're bounded by the garage
    // box and drawn at [zoneOffX]/[zoneOffZ] — the offset between the two centres, since the scene
    // renders the place you're STANDING in at the origin. Same trick HomeBackdrop.ghost() uses to
    // put one zone's furniture inside another's frame.
    @Composable
    fun PlacedItems(
        yBase: Float = 0f,
        itemZone: RoomZone = zone,
        zoneOffX: Float = 0f,
        zoneOffZ: Float = 0f,
    ) {
        // Keyed on identity AND orientation: a rotate/flip rebuilds the item's nodes from
        // scratch (fresh geometry + collision) instead of morphing buffers in place, which
        // can leave the renderable in a stale/culled state.
        placedItems.forEach { pi -> key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
            val tearable = pi.item.isFurniture || pi.item.isPortable
            val reach    = if (tearable) -1.2f else dragMargin  // negative margin ⇒ can exit
            val baseCx = pi.item.xFrac * itemZone.w * 0.38f
            val baseCz = pi.item.zFrac * itemZone.d * 0.38f
            val (offX, offZ) = livePlacedOffsets[pi.id] ?: (pi.dx to pi.dz)
            ItemNode(
                item            = pi.item,
                cx              = zoneOffX + baseCx + offX,
                cz              = zoneOffZ + baseCz + offZ,
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
                zoneW           = if (pi.item.isFurniture || pi.item.isPortable) 0f else itemZone.w,
                zoneD           = if (pi.item.isFurniture || pi.item.isPortable) 0f else itemZone.d,
                zoneCx          = zoneOffX,
                zoneCz          = zoneOffZ,
                yBase           = yBase,
                movable         = true,
                rotDeg          = pi.rotDeg,
                flipX           = pi.flipX,
                flipZ           = pi.flipZ,
                onDrag          = { wx: Float, wz: Float ->
                    dragLast?.let { (lastX, lastZ) ->
                        val (curX, curZ) = livePlacedOffsets[pi.id] ?: (pi.dx to pi.dz)
                        val newCx = (baseCx + curX + (wx - lastX))
                            .coerceIn(-itemZone.w / 2f + reach, itemZone.w / 2f - reach)
                        val newCz = (baseCz + curZ + (wz - lastZ))
                            .coerceIn(-itemZone.d / 2f + reach, itemZone.d / 2f - reach)
                        livePlacedOffsets = livePlacedOffsets + (pi.id to (newCx - baseCx to newCz - baseCz))
                    }
                    dragLast = wx to wz
                },
                onDragEnd       = {
                    dragLast = null
                    livePlacedOffsets[pi.id]?.let { (dx, dz) ->
                        val outside = abs(baseCx + dx) > itemZone.w / 2f + 0.05f ||
                                      abs(baseCz + dz) > itemZone.d / 2f + 0.05f
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

    // Furniture is nested inside the floor node (see below) so it inherits the floor's transform.
    // Dragging no longer depends on that nesting (see PlacedItems above).
    // Keyed on zone (not strictly required for this closure — onFloorTap only touches
    // MainActivity's own mutable state, always read live at tap time — but matches this
    // codebase's established convention for interactive nodes, see sceneview-node-identity skill).
    if (indoor) {
    key(zone) {
    CubeNode(size = Size(zone.w, wt, zone.d), materialInstance = floorMat,
        position = Position(0f, 0f, 0f),
        apply = { onSingleTapConfirmed = { onFloorTap(); true } }) {
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
        // Keyed on the item type, not the slot index: hiding one default ("not in my home")
        // shifts every later item up a slot, which without a key would hand each ItemNode the
        // PREVIOUS item's already-created nodes and drag handlers (see sceneview-node-identity).
        zone.type.defaultItems().filter { "$defaultInstancePrefix:${it.name}" !in removedInstances }.forEach { ri -> key(ri) {
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
        } }

        PlacedItems()
    }
    }
    }

    // ── The driveway ─────────────────────────────────────────────────────────
    // Standing on the lot: the garage shell, its overhead door, and the fleet — the one place
    // vehicles are managed from (see DrivewayLot). The lot arrives in world coordinates and is
    // shifted into this scene's frame the same way the backdrop and the world are, so a car
    // parked halfway down the driveway is in the same real place whichever scene you view it
    // from. Elsewhere the same fleet appears as untouchable scenery (see HomeBackdrop).
    if (place == RoomPlace.DRIVEWAY) {
        val lot = CarLotGeometry.translate(surroundings.vehicleLot, -zone.cx, -zone.cz)
        DrivewayLot(
            lot                   = lot,
            gW                    = surroundings.garageW,
            gD                    = surroundings.garageD,
            enclosed              = surroundings.garageEnclosed,
            vehicles              = vehicles,
            onVehicleTap          = onVehicleTap,
            onVehicleMoved        = onVehicleMoved,
            onVehicleRemoved      = onVehicleRemoved,
            onDoorFractionChanged = onDoorFractionChanged,
        )
        // Whatever is kept in the garage behind the door — the water heater, the spare fridge, a
        // keepsake. Bounded by the garage box rather than the driveway (which grows with the
        // fleet), and drawn at the offset between the two centres since this scene renders the
        // driveway at the origin. Visible from out here now that the shell is see-through.
        if (surroundings.garageEnclosed) {
            val garage = surroundings.garageZone
            PlacedItems(
                yBase    = DRIVEWAY_PLANE_Y - WALL_T / 2f,
                itemZone = garage,
                zoneOffX = garage.cx - zone.cx,
                zoneOffZ = garage.cz - zone.cz,
            )
        }
    }

    // ── The attic ────────────────────────────────────────────────────────────
    // The space over the top storey: its own shell and framing, the mechanicals inside it, and
    // whatever keepsakes have been stashed up there. Both the shape and the contents are decided
    // outside (see AtticType) — this just renders what it's handed, and the keepsakes go through
    // the very same placed-item pass every room uses.
    if (place == RoomPlace.ATTIC && atticVolume != null) {
        AtticInterior(
            volume     = atticVolume,
            contents   = atticContents,
            onItemTap  = onAtticItemTap,
            onFloorTap = onFloorTap,
        )
        // The attic floor's TOP face is y = 0, unlike a room's slab (centred on 0), so keepsakes
        // drop half a slab to rest on it rather than floating a centimetre proud.
        PlacedItems(yBase = -wt / 2f)
    }

    // Walls (with door/window/slider openings) and corner pillars are pure geometry —
    // computed portably in :core-render-spec and rendered here by mapping each node's
    // material slot onto this composable's already-built MaterialInstances. Neither synthetic
    // place has any of it: four OPEN sides would still raise a pillar at every corner, and the
    // attic brings its own shell.
    if (indoor) RoomSceneGeometry.build(zone, wallModes, exteriorSlider).forEach { n ->
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
