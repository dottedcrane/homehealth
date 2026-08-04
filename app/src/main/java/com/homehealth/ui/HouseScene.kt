package com.homehealth.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.homehealth.model.*
import com.homehealth.renderspec.CarLot
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.HouseSceneBoxNode
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.renderspec.HouseSceneMaterialSlot
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T
import io.github.sceneview.SceneScope
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.CubeNode as CubeNodeImpl
import com.google.android.filament.MaterialInstance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun SceneScope.HouseScene(
    homeType: HomeType,
    floorLayout: FloorLayout,
    featurePlacements: Map<HomeFeature, FeatureSide> = emptyMap(),
    // Live drag offset for garage/pool: (along, 0) for the wall-snapped garage, (dx, dz) for
    // the freely-2D-placed pool — relative to the default anchor for the current side. See
    // HouseSceneGeometry.garageBox/poolBox.
    featureOffsets: Map<HomeFeature, Pair<Float, Float>> = emptyMap(),
    // Placed decks — unlike garage/pool, multiple are allowed; each is independently
    // wall-snapped and draggable. Added via tear-drag from the sidebar tray (see
    // MainActivity's dropDeckAt), not a toggle icon.
    placedDecks: List<PlacedDeck> = emptyList(),
    // HVAC unit — single slot like garage, but valid on all 4 walls. Side + along-wall
    // offset; null hides it entirely. Added via tear-drag (MainActivity's dropHvacAt), not
    // a toggle icon — unlike before, its position is no longer auto-derived every frame.
    hvacPlacement: Pair<FeatureSide, Float>? = null,
    onHvacMoved: (FeatureSide, Float) -> Unit = { _, _ -> },
    onHvacTap: () -> Unit = {},
    // Solar array — single slot like HVAC/EV battery, rendered as a grid of small tiles on
    // one roof slope. Added via tear-drag from the sidebar tray (MainActivity's dropSolarAt,
    // only offered in the Top camera view), not a toggle.
    solarArrayPlacement: Pair<Boolean, Float>? = null,
    onSolarMoved: (Boolean, Float) -> Unit = { _, _ -> },
    onSolarTap: () -> Unit = {},
    // EV Battery — single slot like HVAC, wall-mounted on any of the 4 walls. Added via
    // tear-drag (MainActivity's dropEvBatteryAt), not a toggle.
    evBatteryPlacement: Pair<FeatureSide, Float>? = null,
    onEvBatteryMoved: (FeatureSide, Float) -> Unit = { _, _ -> },
    onEvBatteryTap: () -> Unit = {},
    // Trees/gazebo — any number of instances of either kind, free-2D-placed anywhere in the
    // yard (no wall-snap). Added via tear-drag (MainActivity's dropYardDecorAt), not a toggle.
    placedYardDecor: List<PlacedYardDecor> = emptyList(),
    onYardDecorMoved: (String, Float, Float) -> Unit = { _, _, _ -> },
    onYardDecorTap: (String) -> Unit = {},
    removedInstances: Set<String> = emptySet(),
    itemOffsets: Map<RoomItem, Pair<Float, Float>> = emptyMap(),
    placedItems: List<PlacedItem> = emptyList(),
    // A placed garage/pool was dragged to a new spot — carries the (possibly changed, for the
    // wall-snapped garage) side alongside the offset, so both commit atomically.
    onFeatureMoved: (HomeFeature, FeatureSide, Pair<Float, Float>) -> Unit = { _, _, _ -> },
    // Tapped a placed garage/pool (not its door — that's onEnterGarage) — opens its actions
    // bar (Remove).
    onFeatureTap: (HomeFeature) -> Unit = {},
    // Tapped the placed deck — opens its actions bar (Remove/Track). Single slot, not
    // draggable (always the full back wall), so there's no onDeckMoved anymore.
    onDeckTap: (String) -> Unit = {},
    // Steps inside to the floor plan — fired by the front door, the garage's interior door,
    // and anywhere on the house shell (walls, windows, roof). Only the ground slab keeps
    // onHomeTap (the home-info/claim card).
    onEnterHome: () -> Unit = {},
    onHomeTap: () -> Unit = {},
    onTapSystem: (HomeSystem) -> Unit = {},
    // Vehicles (cars, boats, motorcycles) — placed items with CAR_PLACEMENT_ID, parked at
    // the garage/driveway. Tap opens its actions bar (paint color, fuel type, track-to-
    // maintenance-card, remove); long-press-drag moves it along the driveway (dragging it
    // to the street end removes it too).
    cars: List<PlacedItem> = emptyList(),
    // What's kept INSIDE the garage — the water heater, a spare fridge, a keepsake. Scenery only
    // out here (managed from the driveway, see RoomScene), but worth drawing now that the shell is
    // see-through: an opaque box was the only reason it never mattered what stood in it. The zone
    // arrives ready-made from RoomSurroundings so nothing re-derives the garage's footprint.
    garageContents: List<PlacedItem> = emptyList(),
    garageZone: RoomZone? = null,
    onCarTap: (PlacedItem) -> Unit = {},
    onCarMoved: (PlacedItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onCarRemoved: (PlacedItem) -> Unit = {},
    // Tapping the garage door steps into the dedicated garage scene (like the front door
    // enters the floor plan) — the door itself only opens there.
    onEnterGarage: () -> Unit = {},
    // When set, every tappable surface (slab, walls, windows, doors, roof, HVAC, solar,
    // garage) routes here instead — used for a compact "preview" render where any tap
    // should just navigate away, rather than triggering doors/dialogs/animations in place.
    onShellTap: (() -> Unit)? = null,
    // When true, use fixed model positions (e.g. door at -1/4) rather than dynamic ones
    // tied to the floor layout's hallway.
    isModelPreview: Boolean = false,
    // Draw the street, the park and the neighbouring homes around this house — the exterior view
    // of a home you actually own. Off for a previewed model (a staged show home stands on its own,
    // not on your street) and off inside NeighborhoodScene, which draws the world itself and would
    // otherwise get two copies of it.
    showNeighborhood: Boolean = false,
) {
    val effHomeTap:   () -> Unit            = onShellTap ?: onHomeTap
    val effEnterHome: () -> Unit            = onShellTap ?: onEnterHome
    val effTapSystem: (HomeSystem) -> Unit  = { sys -> onShellTap?.invoke() ?: onTapSystem(sys) }
    // ── Materials ─────────────────────────────────────────────────────────────

    val slabMat      = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.50f, 0.45f)) }
    val wallMat      = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.92f, 0.82f)) }
    // Alpha < 1 makes createColorInstance pick the transparent material — see-through glass.
    val glassMat     = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.75f, 0.92f, 0.24f)) }
    val frontDoorMat = remember { materialLoader.createColorInstance(colorOf(0.42f, 0.26f, 0.13f)) }  // Solid wood brown
    // Alpha < 1 makes createColorInstance pick the transparent material — the roof reads
    // as "a roof, tinted see-through" (rather than glassMat's much lighter window tint) so
    // the room-colored floor tiles below stay visible from the exterior view.
    val roofMat      = remember { materialLoader.createColorInstance(colorOf(0.70f, 0.22f, 0.14f, 0.42f)) }
    val flatRoofMat  = remember { materialLoader.createColorInstance(colorOf(0.28f, 0.28f, 0.32f, 0.42f)) }
    val yardMat      = remember { materialLoader.createColorInstance(colorOf(0.18f, 0.52f, 0.12f)) }
    // See-through, like the roof above it and the room walls inside: the garage is somewhere you
    // keep things (a water heater, a keepsake, the car), and an opaque box makes all of that
    // invisible from the one view you spend most time in. Alpha < 1 makes createColorInstance pick
    // the transparent material. The DOOR stays solid - it's the tap target, and an opening only
    // reads as an opening if the panel over it doesn't.
    val garageMat    = remember { materialLoader.createColorInstance(colorOf(0.68f, 0.65f, 0.60f, 0.35f)) }
    val gDoorMat     = remember { materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)) }
    val deckMat      = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.35f, 0.18f)) }
    val poolCopeMat  = remember { materialLoader.createColorInstance(colorOf(0.82f, 0.80f, 0.76f)) }
    val poolWaterMat = remember { materialLoader.createColorInstance(colorOf(0.05f, 0.50f, 0.88f)) }
    val solarMat     = remember { materialLoader.createColorInstance(colorOf(0.05f, 0.10f, 0.38f)) }
    val hvacBodyMat  = remember { materialLoader.createColorInstance(colorOf(0.92f, 0.92f, 0.90f)) }
    val hvacGrillMat = remember { materialLoader.createColorInstance(colorOf(0.40f, 0.40f, 0.40f)) }
    val evBatteryBodyMat = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.17f, 0.20f)) }
    val evBatteryLedMat  = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.85f, 0.45f)) }
    val treeTrunkMat  = remember { materialLoader.createColorInstance(colorOf(0.38f, 0.26f, 0.13f)) }
    val treeLeafMat   = remember { materialLoader.createColorInstance(colorOf(0.16f, 0.42f, 0.14f)) }
    val gazeboPostMat = remember { materialLoader.createColorInstance(colorOf(0.90f, 0.87f, 0.78f)) }
    val gazeboRoofMat = remember { materialLoader.createColorInstance(colorOf(0.60f, 0.18f, 0.10f)) }
    // Item / furniture materials
    val sofaMat      = remember { materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.58f)) }
    val woodItemMat  = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.35f, 0.18f)) }
    val applianceMat = remember { materialLoader.createColorInstance(colorOf(0.85f, 0.85f, 0.88f)) }
    val steelItemMat = remember { materialLoader.createColorInstance(colorOf(0.45f, 0.47f, 0.50f)) }
    val fixtureMat   = remember { materialLoader.createColorInstance(colorOf(0.95f, 0.95f, 0.95f)) }
    val fabricMat    = remember { materialLoader.createColorInstance(colorOf(0.65f, 0.80f, 0.92f)) }
    val screenMat    = remember { materialLoader.createColorInstance(colorOf(0.08f, 0.08f, 0.10f)) }
    val sinkItemMat  = remember { materialLoader.createColorInstance(colorOf(0.20f, 0.50f, 0.85f)) }
    val dishwItemMat = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.65f, 0.60f)) }
    val tubRimMat    = remember { materialLoader.createColorInstance(colorOf(0.96f, 0.96f, 0.96f)) }
    val tubBasinMat  = remember { materialLoader.createColorInstance(colorOf(0.35f, 0.65f, 0.90f)) }
    // Per-room floor-tile colours — the interior seen through the glass.
    val roomFloorMats = remember {
        mapOf(
            RoomType.LIVING_ROOM  to materialLoader.createColorInstance(colorOf(0.96f, 0.86f, 0.66f)),
            RoomType.KITCHEN      to materialLoader.createColorInstance(colorOf(0.76f, 0.93f, 0.70f)),
            RoomType.DINING_ROOM  to materialLoader.createColorInstance(colorOf(0.96f, 0.82f, 0.66f)),
            RoomType.HALLWAY      to materialLoader.createColorInstance(colorOf(0.83f, 0.83f, 0.83f)),
            RoomType.FOYER        to materialLoader.createColorInstance(colorOf(0.94f, 0.91f, 0.84f)),
            RoomType.STAIRCASE    to materialLoader.createColorInstance(colorOf(0.70f, 0.70f, 0.72f)),
            RoomType.BEDROOM      to materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.96f)),
            RoomType.BATHROOM     to materialLoader.createColorInstance(colorOf(0.68f, 0.85f, 0.96f)),
            RoomType.LAUNDRY      to materialLoader.createColorInstance(colorOf(0.80f, 0.88f, 0.92f)),
            RoomType.OFFICE       to materialLoader.createColorInstance(colorOf(0.72f, 0.82f, 0.88f)),
            RoomType.PANTRY       to materialLoader.createColorInstance(colorOf(0.95f, 0.93f, 0.75f)),
            RoomType.STORAGE      to materialLoader.createColorInstance(colorOf(0.75f, 0.75f, 0.73f)),
            RoomType.GYM          to materialLoader.createColorInstance(colorOf(0.78f, 0.90f, 0.76f)),
            RoomType.HOME_THEATER to materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)),
            RoomType.POWDER_ROOM  to materialLoader.createColorInstance(colorOf(0.78f, 0.90f, 0.96f)),
            RoomType.GARAGE       to materialLoader.createColorInstance(colorOf(0.62f, 0.60f, 0.56f)),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            (listOf(slabMat, wallMat, glassMat, frontDoorMat, roofMat, flatRoofMat,
                   yardMat, garageMat, gDoorMat, deckMat, poolCopeMat, poolWaterMat,
                   solarMat, hvacBodyMat, hvacGrillMat, evBatteryBodyMat, evBatteryLedMat,
                   treeTrunkMat, treeLeafMat, gazeboPostMat, gazeboRoofMat,
                   sofaMat, woodItemMat, applianceMat, steelItemMat, fixtureMat, fabricMat, screenMat,
                   sinkItemMat, dishwItemMat, tubRimMat, tubBasinMat) + roomFloorMats.values)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // ── The street this house stands on ───────────────────────────────────────
    // Untouchable scenery (see NeighborhoodBackdrop), so it can never intercept a tap or a
    // tear-drag meant for the house or the yard in front of it. Owns its own materials, which is
    // why it sits after this scene's — one block of remembers per composable.
    if (showNeighborhood) {
        NeighborhoodBackdrop(
            floorLayout       = floorLayout,
            featurePlacements = featurePlacements,
        )
    }

    val w      = floorLayout.totalW
    val d      = floorLayout.totalD
    val floors = floorLayout.maxFloor() + 1
    val winW   = if (w < 6f) 0.80f else if (w < 9f) 0.90f else if (w < 12f) 1.00f else 1.20f
    val winH   = if (w < 6f) 1.00f else if (w < 9f) 1.10f else if (w < 12f) 1.20f else 1.40f
    val p       = 0.11f

    // Front-door anchor on the ground floor — the foyer if present, else the hallway.
    val hwZone = floorLayout.toZones(0).let { zs ->
        zs.find { it.type == RoomType.FOYER } ?: zs.find { it.type == RoomType.HALLWAY }
    }
    val hwCx   = hwZone?.cx ?: 0f
    val hwCz   = hwZone?.cz ?: 0f

    // Sliding door faces whichever wall the first placed deck is on (decks can be multiple
    // now, on any wall — picking one is an arbitrary but harmless simplification), else BACK.
    val sdFace = placedDecks.firstOrNull()?.side ?: FeatureSide.BACK
    val sdD    = (d * 0.36f).coerceIn(1.2f, 2.2f)   // used for left/right side door
    val sdH    = FLOOR_HEIGHT_M * 0.95f

    // Two sliding glass doors on back wall: living room and master bedroom
    val lrZone  = floorLayout.toZones(0).find { it.type == RoomType.LIVING_ROOM }
    val mbZone  = floorLayout.toZones(0).filter { it.type == RoomType.BEDROOM }.minByOrNull { it.zMin }
    val sd1CX   = lrZone?.cx ?: -w / 4f
    val sd1W    = ((lrZone?.w ?: w / 3f) * 0.75f).coerceIn(1.2f, 2.4f)
    val sd2CX   = mbZone?.cx ?: w / 4f
    val sd2W    = ((mbZone?.w ?: w / 3f) * 0.75f).coerceIn(1.2f, 2.4f)

    val slidingDoor1 = remember { Animatable(0f) }
    var door1Open    by remember { mutableStateOf(false) }
    val slidingDoor2 = remember { Animatable(0f) }
    var door2Open    by remember { mutableStateOf(false) }
    val doorScope    = rememberCoroutineScope()
    val onDoor1Tap: () -> Unit = {
        door1Open = !door1Open
        val target = if (door1Open) 1f else 0f
        doorScope.launch { slidingDoor1.animateTo(target, tween(900, easing = FastOutSlowInEasing)) }
    }
    val onDoor2Tap: () -> Unit = {
        door2Open = !door2Open
        val target = if (door2Open) 1f else 0f
        doorScope.launch { slidingDoor2.animateTo(target, tween(900, easing = FastOutSlowInEasing)) }
    }
    // kept for left/right side deck door (single door, centred on hallway Z)
    val sdW      = (w * 0.36f).coerceIn(1.2f, 2.2f)
    val slidingDoor = remember { Animatable(0f) }
    var doorOpen    by remember { mutableStateOf(false) }
    val onDoorTap: () -> Unit = {
        doorOpen = !doorOpen
        val target = if (doorOpen) 1f else 0f
        doorScope.launch { slidingDoor.animateTo(target, tween(900, easing = FastOutSlowInEasing)) }
    }

    // Does this zone's -X wall or -Z wall carry a sliding glass door? (Ground floor only —
    // used to keep the kitchen counter/appliances from being rendered on top of the door.)
    fun overlaps1D(aMin: Float, aMax: Float, bMin: Float, bMax: Float) = aMin < bMax && aMax > bMin
    fun kitchenWallBlocks(zone: RoomZone, floor: Int): Pair<Boolean, Boolean> {
        if (floor != 0) return false to false
        val leftBlocked = sdFace == FeatureSide.LEFT &&
            zone.cx - zone.w / 2f <= -w / 2f + 0.05f &&
            overlaps1D(zone.cz - zone.d / 2f, zone.cz + zone.d / 2f, hwCz - sdD / 2f, hwCz + sdD / 2f)
        val backBlocked = sdFace == FeatureSide.BACK &&
            zone.cz - zone.d / 2f <= -d / 2f + 0.05f &&
            (overlaps1D(zone.cx - zone.w / 2f, zone.cx + zone.w / 2f, sd1CX - sd1W / 2f, sd1CX + sd1W / 2f) ||
             overlaps1D(zone.cx - zone.w / 2f, zone.cx + zone.w / 2f, sd2CX - sd2W / 2f, sd2CX + sd2W / 2f))
        return leftBlocked to backBlocked
    }

    // Maps a portable HouseSceneGeometry node list to actual CubeNode calls. Sliding-door panes
    // carry their own doorId (1/2/3) — each returned from a fixed 0-or-1-node function call, so
    // plain if/else dispatch below suffices, no key() needed (see HouseSceneBoxNode's kdoc).
    // Every other tappable node in one call shares whichever closure that call passes in.
    @Composable
    fun SceneScope.emitHouseSceneNodes(
        nodes: List<HouseSceneBoxNode>,
        onSlabTap: () -> Unit = {},
        onEnterHome: () -> Unit = {},
        onTapSystem: (HomeSystem) -> Unit = {},
        onGarageDoorTap: () -> Unit = {},
    ) {
        val effDoor1Tap = onShellTap ?: onDoor1Tap
        val effDoor2Tap = onShellTap ?: onDoor2Tap
        val effDoorTap  = onShellTap ?: onDoorTap
        nodes.forEach { n ->
            val mat = when (n.material) {
                HouseSceneMaterialSlot.SLAB -> slabMat
                HouseSceneMaterialSlot.WALL -> wallMat
                HouseSceneMaterialSlot.GLASS -> glassMat
                HouseSceneMaterialSlot.FRONT_DOOR -> frontDoorMat
                HouseSceneMaterialSlot.GARAGE_INTERIOR_DOOR -> glassMat
                HouseSceneMaterialSlot.ROOM_FLOOR -> roomFloorMats[n.roomType] ?: slabMat
                HouseSceneMaterialSlot.ROOF_PITCH -> roofMat
                HouseSceneMaterialSlot.ROOF_FLAT -> flatRoofMat
                HouseSceneMaterialSlot.YARD -> yardMat
                HouseSceneMaterialSlot.GARAGE_SHELL -> garageMat
                HouseSceneMaterialSlot.GARAGE_DOOR -> gDoorMat
                HouseSceneMaterialSlot.DECK -> deckMat
                HouseSceneMaterialSlot.POOL_COPING -> poolCopeMat
                HouseSceneMaterialSlot.POOL_WATER -> poolWaterMat
                HouseSceneMaterialSlot.SOLAR -> solarMat
                HouseSceneMaterialSlot.HVAC_BODY -> hvacBodyMat
                HouseSceneMaterialSlot.HVAC_GRILLE -> hvacGrillMat
                // EV Battery/tree/gazebo are only ever rendered via FeaturesOnYard()'s own
                // per-instance CubeNode calls, and the attic slots only inside AtticInterior
                // (which builds its own materials) — never through this shared-closure path.
                // These branches exist solely to keep the lookup table exhaustive.
                HouseSceneMaterialSlot.ATTIC_SHELL -> garageMat
                HouseSceneMaterialSlot.ATTIC_WALL -> garageMat
                HouseSceneMaterialSlot.ATTIC_HATCH -> gDoorMat
                HouseSceneMaterialSlot.ATTIC_FRAMING -> garageMat
                HouseSceneMaterialSlot.DUCT -> hvacGrillMat
                HouseSceneMaterialSlot.INSULATION -> deckMat
                HouseSceneMaterialSlot.EV_BATTERY_BODY -> evBatteryBodyMat
                HouseSceneMaterialSlot.EV_BATTERY_LED -> evBatteryLedMat
                HouseSceneMaterialSlot.TREE_TRUNK -> treeTrunkMat
                HouseSceneMaterialSlot.TREE_LEAF -> treeLeafMat
                HouseSceneMaterialSlot.GAZEBO_POST -> gazeboPostMat
                HouseSceneMaterialSlot.GAZEBO_ROOF -> gazeboRoofMat
            }
            val rot = n.rotationDeg
            CubeNode(
                size             = Size(n.size.x, n.size.y, n.size.z),
                materialInstance = mat,
                position         = Position(n.position.x, n.position.y, n.position.z),
                rotation         = Rotation(rot.x, rot.y, rot.z),
                apply = {
                    when {
                        n.doorId == 1 -> onSingleTapConfirmed = { effDoor1Tap(); true }
                        n.doorId == 2 -> onSingleTapConfirmed = { effDoor2Tap(); true }
                        n.doorId == 3 -> onSingleTapConfirmed = { effDoorTap(); true }
                        n.material == HouseSceneMaterialSlot.SLAB && n.tappable ->
                            onSingleTapConfirmed = { onSlabTap(); true }
                        n.material == HouseSceneMaterialSlot.FRONT_DOOR ||
                        n.material == HouseSceneMaterialSlot.GARAGE_INTERIOR_DOOR ->
                            onSingleTapConfirmed = { playDoorCreak(); onEnterHome(); true }
                        n.material == HouseSceneMaterialSlot.GARAGE_DOOR ->
                            onSingleTapConfirmed = { playGarageDoorSound(); onGarageDoorTap(); true }
                        n.tappable -> onSingleTapConfirmed = { onEnterHome(); true }
                    }
                },
            ) {}
        }
    }

    // ── HVAC unit — wall-snapped like the garage, but valid on all 4 sides (no
    // door-orientation constraint). Single slot; added via tear-drag from the sidebar tray
    // (MainActivity's dropHvacAt) rather than a toggle icon — the user drops it wherever they
    // like (no automatic door-avoidance the way the old auto-placed version had; visually
    // avoiding a door is now on them, same as any other drop), then drags freely along
    // whichever wall it's nearest, exactly like the garage.
    //
    // Emitted from TWO call sites, which is why it's factored out of FeaturesOnYard(): nested
    // under the yard plane for styles that have one, and standalone for CONDO, which has no yard
    // at all (yardBounds == null, making FeaturesOnYard return early) yet still mounts its unit
    // on the building wall. The unit is wall-relative either way — its own drag math
    // (nearestHouseSide/alongCoordinate/clampAlongWall) never reads yardBounds — so only
    // draggability differs; CONDO repositions via the actions dialog's rotate button instead
    // (tap the unit → Attic scene → tap the equipment inside). Note the drag resolves against
    // YARD_PLANE_Y whether or not a yard slab is actually rendered: it's a plane, not a node.
    @Composable
    fun SceneScope.HvacUnit(movable: Boolean) {
        var liveHvac by remember { mutableStateOf<Pair<FeatureSide, Float>?>(null) }
        hvacPlacement?.let { (storedSide, storedAlong) ->
            // See the Garage block below for why this key includes the committed side/along
            // rather than the live-drag override.
            key("hvac", storedSide, storedAlong) {
                val (side, along) = liveHvac ?: (storedSide to storedAlong)
                val onTapFeature: () -> Unit = { (onShellTap ?: onHvacTap)() }
                val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                    // Snaps only to the walls a utility may sit on — dragging toward the front
                    // elevation slides it to the nearest legal wall instead (UTILITY_SIDES).
                    val newSide = HouseSceneGeometry.nearestHouseSide(wx, wz, w, d, HouseSceneGeometry.UTILITY_SIDES)
                    val raw = HouseSceneGeometry.alongCoordinate(newSide, wx, wz)
                    liveHvac = newSide to HouseSceneGeometry.clampAlongWall(newSide, raw, w, d, 0.85f)
                }
                val onDragEnd: (() -> Unit)? = if (!movable) null else {
                    {
                        val (s, a) = liveHvac ?: (side to along)
                        onHvacMoved(s, a)
                        liveHvac = null
                    }
                }
                val applyAll = rememberDragApply(YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd)
                HouseSceneGeometry.hvac(side, w, d, along).forEach { n ->
                    val mat = if (n.material == HouseSceneMaterialSlot.HVAC_BODY) hvacBodyMat else hvacGrillMat
                    CubeNode(
                        size             = Size(n.size.x, n.size.y, n.size.z),
                        materialInstance = mat,
                        position         = Position(n.position.x, n.position.y, n.position.z),
                        apply            = applyAll,
                    ) {}
                }
            }
        }
    }

    // ── Features on the yard — garage (wall-snapped), pool (free 2D, kept clear of the
    // house), decks (any number, each wall-snapped). Nested as children of the yard-plane
    // ground node so they inherit its transform; dragging no longer depends on that nesting the
    // way it did when SceneView resolved a move by hit-testing the dragged node's PARENT (which
    // is exactly what froze a drag the moment the finger left the plane's on-screen area — see
    // SceneDragController). Live drag state: written every move event, persisted once on release.
    @Composable
    fun SceneScope.FeaturesOnYard() {
        val bounds = HouseSceneGeometry.yardBounds(homeType.style, w, d) ?: return
        val wallTopY = floors * FLOOR_HEIGHT_M
        // Model-preview (viewing a neighbor's home) renders these statically too — nothing
        // here belongs to the player's own home, so it must never be draggable.
        val movable = onShellTap == null && !isModelPreview
        // The garage only ever attaches to these three walls — its door always faces +Z
        // (HouseSceneGeometry.garageShell), which only reads as "facing the street" there.
        val garageSides = listOf(FeatureSide.FRONT, FeatureSide.RIGHT, FeatureSide.LEFT)

        var liveGarage by remember { mutableStateOf<Pair<FeatureSide, Float>?>(null) }
        var livePool   by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        var liveSolar  by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }
        var liveEvBattery by remember { mutableStateOf<Pair<FeatureSide, Float>?>(null) }
        var liveYardDecor by remember { mutableStateOf(mapOf<String, Pair<Float, Float>>()) }

        CubeNode(
            size             = Size(w + 16f, 0.06f, d + 16f),
            materialInstance = yardMat,
            position         = Position(0f, -0.13f, 0f),
        ) {
            // ── Garage — wall-snapped: slides along whichever of FRONT/RIGHT/LEFT it's
            // nearest as it's dragged. The perpendicular-to-wall offset is always fixed at
            // the wall-flush distance, so it can never float free or cross into the house.
            featurePlacements[HomeFeature.GARAGE]?.let { storedSide ->
                // Keyed on the COMMITTED side/offset (never the live-drag override) so a
                // rotate-to-next-side click — which writes straight to featurePlacements/
                // featureOffsets with no live override involved — forces this node to be
                // disposed and recreated with a correct transform and freshly-bound gesture
                // handlers, instead of silently rendering stale until the view is torn down
                // and rebuilt (see sceneview-node-identity skill). Never changes mid-drag,
                // since the committed value only updates once, atomically, on drag end.
                key(HomeFeature.GARAGE, storedSide, featureOffsets[HomeFeature.GARAGE]?.first ?: 0f) {
                    val (side, along) = liveGarage
                        ?: (storedSide to (featureOffsets[HomeFeature.GARAGE]?.first ?: 0f))
                    val box = if (side == FeatureSide.FRONT) HouseSceneGeometry.garageBox(w, d, side, along, 0f)
                              else HouseSceneGeometry.garageBox(w, d, side, 0f, along)
                    val onTapFeature: () -> Unit = { (onShellTap ?: { onFeatureTap(HomeFeature.GARAGE) })() }
                    val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                        val newSide = HouseSceneGeometry.nearestHouseSide(wx, wz, w, d, garageSides)
                        val raw = HouseSceneGeometry.alongCoordinate(newSide, wx, wz)
                        val alongSize = if (newSide == FeatureSide.FRONT) box.w else box.d
                        liveGarage = newSide to HouseSceneGeometry.clampAlongWall(newSide, raw, w, d, alongSize)
                    }
                    val onDragEnd: (() -> Unit)? = if (!movable) null else {
                        {
                            val (s, a) = liveGarage ?: (side to along)
                            onFeatureMoved(HomeFeature.GARAGE, s, a to 0f)
                            // Once committed, the live override must not keep shadowing
                            // featurePlacements/featureOffsets forever — otherwise any LATER
                            // non-drag change to the committed position (e.g. a future
                            // programmatic move) would be silently ignored until this whole
                            // composable is torn down and rebuilt (switching views).
                            liveGarage = null
                        }
                    }
                    // The door keeps its own "enter the garage" tap; every other panel opens the
                    // feature's actions bar (Remove). Two targets, one per tap behaviour, but
                    // both carrying the same drag — so the garage moves whichever panel you grab.
                    val applyPanel = rememberDragApply(YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd)
                    val applyDoor  = rememberDragApply(
                        YARD_PLANE_Y, onShellTap ?: onEnterGarage, onDrag, onDragEnd)
                    HouseSceneGeometry.garageShell(box.cx, box.cz, box.w, FLOOR_HEIGHT_M, box.d,
                        doorFacesPositiveZ = true, doorFraction = 0f).forEach { n ->
                        val isDoor = n.material == HouseSceneMaterialSlot.GARAGE_DOOR
                        val mat = if (isDoor) gDoorMat else garageMat
                        val rot = n.rotationDeg
                        CubeNode(
                            size             = Size(n.size.x, n.size.y, n.size.z),
                            materialInstance = mat,
                            position         = Position(n.position.x, n.position.y, n.position.z),
                            rotation         = Rotation(rot.x, rot.y, rot.z),
                            apply            = if (isDoor) applyDoor else applyPanel,
                        ) {}
                    }
                }
            }

            // ── Pool — full free 2D placement (no wall coupling), kept clear of the house's
            // own footprint via a simple push-out-along-least-overlap correction.
            featurePlacements[HomeFeature.POOL]?.let { side ->
                // See the Garage block above for why this key includes the committed side/
                // offset rather than the live-drag override.
                key(HomeFeature.POOL, side, featureOffsets[HomeFeature.POOL] ?: (0f to 0f)) {
                    val (offX, offZ) = livePool ?: featureOffsets[HomeFeature.POOL] ?: (0f to 0f)
                    val base = HouseSceneGeometry.poolBox(w, d, side)
                    val box  = HouseSceneGeometry.poolBox(w, d, side, offX, offZ)
                    val alongX = side == FeatureSide.RIGHT || side == FeatureSide.LEFT
                    val pW = 3.5f; val pD = minOf(d, 5.5f)
                    val (waterSx, waterSz) = if (alongX) pW to pD else pD to pW
                    val onTapFeature: () -> Unit = { (onShellTap ?: { onFeatureTap(HomeFeature.POOL) })() }
                    val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                        val cx = wx.coerceIn(bounds.xMin, bounds.xMax)
                        val cz = wz.coerceIn(bounds.zMin, bounds.zMax)
                        val (kx, kz) = HouseSceneGeometry.keepOutsideHouse(cx, cz, box.w, box.d, w, d)
                        livePool = (kx - base.cx) to (kz - base.cz)
                    }
                    val onDragEnd: (() -> Unit)? = if (!movable) null else {
                        {
                            onFeatureMoved(HomeFeature.POOL, side, livePool ?: (offX to offZ))
                            // Clear the override once committed — otherwise it keeps shadowing
                            // featureOffsets even after a later non-drag change (e.g. the
                            // rotate-to-next-side action), leaving the pool rendered at a stale
                            // offset computed against the OLD side's anchor — easily far enough
                            // off to look like it vanished until the view is torn down and
                            // rebuilt (switching scenes resets this state, which is why that
                            // "fixes" it).
                            livePool = null
                        }
                    }
                    val applyBoth = rememberDragApply(YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd)
                    CubeNode(size = Size(box.w, 0.14f, box.d), materialInstance = poolCopeMat,
                        position = Position(box.cx, 0f, box.cz), apply = applyBoth) {}
                    CubeNode(size = Size(waterSx, 0.06f, waterSz), materialInstance = poolWaterMat,
                        position = Position(box.cx, 0.10f, box.cz), apply = applyBoth) {}
                }
            }

            // ── Deck — single slot, always the full width of the back wall (see
            // HouseSceneGeometry.deckBox). Added via a single tear-drag from the sidebar tray
            // (MainActivity's dropDeckAt); not draggable afterward — there's nowhere else for
            // a full-width piece to go, so tapping it only opens its actions bar (Remove/Track).
            placedDecks.forEach { deck -> key(deck.id) {
                val box = HouseSceneGeometry.deckBox(w, d, deck.side, deck.along)
                val onTapFeature: () -> Unit = { (onShellTap ?: { onDeckTap(deck.id) })() }
                CubeNode(
                    size             = Size(box.w, 0.18f, box.d),
                    materialInstance = deckMat,
                    position         = Position(box.cx, 0.09f, box.cz),
                    apply            = rememberDragApply(YARD_PLANE_Y, onTapFeature),
                ) {}
            } }

            HvacUnit(movable = movable)

            // ── EV Battery — wall-snapped like HVAC, valid on all 4 sides, single slot.
            // Added via tear-drag from the sidebar tray (MainActivity's dropEvBatteryAt).
            evBatteryPlacement?.let { (storedSide, storedAlong) ->
                // See the Garage block above for why this key includes the committed side/
                // along rather than the live-drag override.
                key("evBattery", storedSide, storedAlong) {
                    val (side, along) = liveEvBattery ?: (storedSide to storedAlong)
                    val onTapFeature: () -> Unit = { (onShellTap ?: onEvBatteryTap)() }
                    val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                        // Same legal-wall restriction as HVAC's drag above (UTILITY_SIDES).
                        val newSide = HouseSceneGeometry.nearestHouseSide(wx, wz, w, d, HouseSceneGeometry.UTILITY_SIDES)
                        val raw = HouseSceneGeometry.alongCoordinate(newSide, wx, wz)
                        liveEvBattery = newSide to HouseSceneGeometry.clampAlongWall(newSide, raw, w, d, 0.60f)
                    }
                    val onDragEnd: (() -> Unit)? = if (!movable) null else {
                        {
                            val (s, a) = liveEvBattery ?: (side to along)
                            onEvBatteryMoved(s, a)
                            liveEvBattery = null
                        }
                    }
                    val applyAll = rememberDragApply(YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd)
                    HouseSceneGeometry.evBattery(side, w, d, along).forEach { n ->
                        val mat = if (n.material == HouseSceneMaterialSlot.EV_BATTERY_BODY) evBatteryBodyMat else evBatteryLedMat
                        CubeNode(
                            size             = Size(n.size.x, n.size.y, n.size.z),
                            materialInstance = mat,
                            position         = Position(n.position.x, n.position.y, n.position.z),
                            apply            = applyAll,
                        ) {}
                    }
                }
            }

            // ── Trees / gazebo — any number of instances of either kind, free 2D placement
            // anywhere in the yard (no wall coupling), kept clear of the house footprint like
            // the pool. Added via tear-drag from the sidebar tray (MainActivity's
            // dropYardDecorAt), not a toggle.
            placedYardDecor.forEach { d0 -> key(d0.id) {
                val (dx, dz) = liveYardDecor[d0.id] ?: (d0.dx to d0.dz)
                val footprint = HouseSceneGeometry.yardDecorFootprint(d0.kind)
                val onTapFeature: () -> Unit = { (onShellTap ?: { onYardDecorTap(d0.id) })() }
                val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                    val cx = wx.coerceIn(bounds.xMin, bounds.xMax)
                    val cz = wz.coerceIn(bounds.zMin, bounds.zMax)
                    val (kx, kz) = HouseSceneGeometry.keepOutsideHouse(cx, cz, footprint.first, footprint.second, w, d)
                    liveYardDecor = liveYardDecor + (d0.id to (kx to kz))
                }
                val onDragEnd: (() -> Unit)? = if (!movable) null else {
                    {
                        val (ndx, ndz) = liveYardDecor[d0.id] ?: (dx to dz)
                        onYardDecorMoved(d0.id, ndx, ndz)
                        liveYardDecor = liveYardDecor - d0.id
                    }
                }
                val nodes = when (d0.kind) {
                    YardDecorKind.TREE   -> HouseSceneGeometry.treeDecor(dx, dz)
                    YardDecorKind.GAZEBO -> HouseSceneGeometry.gazebo(dx, dz)
                }
                val applyAll = rememberDragApply(YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd)
                nodes.forEach { n ->
                    val mat = when (n.material) {
                        HouseSceneMaterialSlot.TREE_TRUNK  -> treeTrunkMat
                        HouseSceneMaterialSlot.TREE_LEAF   -> treeLeafMat
                        HouseSceneMaterialSlot.GAZEBO_POST -> gazeboPostMat
                        HouseSceneMaterialSlot.GAZEBO_ROOF -> gazeboRoofMat
                        else                                -> deckMat  // gazebo's floor slab
                    }
                    CubeNode(
                        size             = Size(n.size.x, n.size.y, n.size.z),
                        materialInstance = mat,
                        position         = Position(n.position.x, n.position.y, n.position.z),
                        rotation         = Rotation(n.rotationDeg.x, n.rotationDeg.y, n.rotationDeg.z),
                        apply            = applyAll,
                    ) {}
                }
            } }

            // ── Solar array — single slot like HVAC/EV battery, a grid of small tiles on
            // one of the two pitched roof slopes. Added via tear-drag from the sidebar tray
            // (MainActivity's dropSolarAt), not a toggle. Repositioning an already-placed array
            // now resolves against the roof's REAL tilted slope planes, exactly like that drop
            // does — it used to resolve against this flat yard plane far below, which is the one
            // place a horizontal plane was genuinely the wrong surface. Every tile shares one
            // DragTarget: they're facets of a single placed instance, not independent targets.
            solarArrayPlacement?.let { (storedOnLeftSlope, storedAlong) ->
                // See the Garage block above for why this key includes the committed slope/
                // along rather than the live-drag override.
                key("solarArray", storedOnLeftSlope, storedAlong) {
                    val (onLeftSlope, along) = liveSolar ?: (storedOnLeftSlope to storedAlong)
                    val onTapFeature: () -> Unit = { (onShellTap ?: onSolarTap)() }
                    val onDrag: ((Float, Float) -> Unit)? = if (!movable) null else { wx, wz ->
                        liveSolar = (wx < 0f) to HouseSceneGeometry.clampSolarAlong(wz, d)
                    }
                    val onDragEnd: (() -> Unit)? = if (!movable) null else {
                        {
                            val (s, a) = liveSolar ?: (onLeftSlope to along)
                            onSolarMoved(s, a)
                            liveSolar = null
                        }
                    }
                    // These tiles are children of the yard plane above (position = Position(0f,
                    // -0.13f, 0f)), so a CubeNode `position` here is LOCAL to that plane, not
                    // world-space, despite what SceneScope.CubeNode's kdoc says (verified against
                    // the library's own Node.kt: `position` composes with the PARENT's world
                    // position; only `worldPosition` is absolute). The roof, by contrast, is
                    // emitted unparented, so ITS position is already world-space directly.
                    // Passing solarArray()'s Y straight through (as the old single-panel version
                    // did, harmlessly, since it never had a "no-op" surface stacked right behind
                    // it) would therefore render every tile 0.13m too low — sunk from 0.10m ABOVE
                    // the roof to just BEHIND it, so the roof (now correctly a dead tap in Top
                    // mode per Fix 1) always intercepts the ray first and the tile is untappable.
                    // Add the parent's offset back so the tile's WORLD Y matches what
                    // solarArray() actually computed.
                    //
                    // The drag tracks whichever slope the finger is actually over — nearest of
                    // the two, so the array flips sides cleanly as it crosses the ridge. The
                    // consumer above reads the sign of the hit's world X to decide the slope,
                    // which is why this has to be the roof and not a plane underneath it.
                    val slopePick = remember(w, wallTopY) {
                        nearestPick(
                            HouseSceneGeometry.roofSlopePlane(w, wallTopY, onLeftSlope = true).let {
                                tiltedPick(Float3(it.point.x, it.point.y, it.point.z),
                                           Float3(it.normal.x, it.normal.y, it.normal.z))
                            },
                            HouseSceneGeometry.roofSlopePlane(w, wallTopY, onLeftSlope = false).let {
                                tiltedPick(Float3(it.point.x, it.point.y, it.point.z),
                                           Float3(it.normal.x, it.normal.y, it.normal.z))
                            },
                        )
                    }
                    val applyAll = rememberDragApply(
                        YARD_PLANE_Y, onTapFeature, onDrag, onDragEnd, pick = slopePick)
                    HouseSceneGeometry.solarArray(w, wallTopY, onLeftSlope, along).forEach { n ->
                        CubeNode(
                            size             = Size(n.size.x, n.size.y, n.size.z),
                            materialInstance = solarMat,
                            position         = Position(n.position.x, n.position.y + 0.13f, n.position.z),
                            rotation         = Rotation(n.rotationDeg.x, n.rotationDeg.y, n.rotationDeg.z),
                            apply            = applyAll,
                        ) {}
                    }
                }
            }
        }
    }

    // ── House floors ──────────────────────────────────────────────────────────

    // Any style's interior garage room (TOWNHOUSE always has one; some HOUSE presets like The
    // Classic now bake one in too), real-world positioned — its front edge IS the house's
    // actual front wall (see townhouseGarageBox), so its door is cut directly into frontWall
    // below instead of a separate free-standing shell. townhouseGarageBox itself already
    // returns null when the layout has no GARAGE room, so no style check is needed here.
    val townhouseGarageBox = HouseSceneGeometry.townhouseGarageBox(floorLayout)

    repeat(floors) { floor ->
        val yBase = floor * FLOOR_HEIGHT_M
        val winY  = yBase + 0.9f + winH / 2f
        val winLo = 0.9f
        val winHi = 0.9f + winH

        emitHouseSceneNodes(
            HouseSceneGeometry.groundSlab(w, d, yBase, tappable = floor == 0 || onShellTap != null),
            onSlabTap = effHomeTap,
        )

        // Front (+Z): entry door on the ground floor, windows above/beside. The door's X
        // depends on the layout's hallway, so the ground-floor window slides clear of it
        // instead of sitting at a fixed offset — otherwise a hallway near w/4 would stack
        // the glass right on top of the solid wood door (see clearWallOffset).
        val doorWx = if (isModelPreview) -w / 4f else hwCx
        // A home whose garage is a room in the plan gives its whole ground-floor elevation to the
        // entry and the garage door — no third opening. It used to get a window anyway, placed
        // before anything had resolved where the bay was, so on both models that bake a garage in
        // (the Classic and the Townhouse) the default w/4 offset dropped the glass straight onto
        // the garage door. Upper floors are unaffected: their windows sit at a fixed ±w/4.
        val garageDoorXRange = townhouseGarageBox?.let { HouseSceneGeometry.townhouseGarageDoorXRange(it) }
        val groundWinX: Float? =
            if (garageDoorXRange != null) null
            else HouseSceneGeometry.clearWallOffset(
                w / 4f, w / 2f, winW / 2f,
                listOf(doorWx - 0.95f / 2f to doorWx + 0.95f / 2f),
            )
        emitHouseSceneNodes(HouseSceneGeometry.frontWall(floor, w, d, yBase, doorWx, groundWinX, winW, winLo, winHi, garageDoorXRange), onEnterHome = effEnterHome)
        emitHouseSceneNodes(HouseSceneGeometry.backWall(floor, w, d, yBase, sdFace, sd1CX, sd1W, sd2CX, sd2W, sdH, winW, winLo, winHi), onEnterHome = effEnterHome)
        emitHouseSceneNodes(HouseSceneGeometry.leftWall(floor, w, d, yBase, sdFace, hwCz, sdD, sdH, winW, winLo, winHi), onEnterHome = effEnterHome)
        emitHouseSceneNodes(HouseSceneGeometry.rightWall(floor, w, d, yBase, sdFace, hwCz, sdD, sdH, winW, winLo, winHi), onEnterHome = effEnterHome)

        // Front (+Z) entry door centred on hallway, window on upper floors
        emitHouseSceneNodes(HouseSceneGeometry.frontDoorAndGlass(floor, doorWx, groundWinX, winW, winH, winY, w, d, p, yBase), onEnterHome = effEnterHome)

        // TOWNHOUSE garage door — a flat panel filling the opening frontWall just cut, tappable
        // just like the front door but routing to onEnterGarage (the door itself only animates
        // open inside GarageScene, same as HOUSE's exterior door staying closed here).
        if (floor == 0 && townhouseGarageBox != null) {
            emitHouseSceneNodes(
                HouseSceneGeometry.townhouseGarageDoor(townhouseGarageBox, d, p, yBase),
                onGarageDoorTap = onShellTap ?: onEnterGarage,
            )
        }

        // Back (-Z) glass: two sliding doors (living room + master bedroom), windows on upper floors
        emitHouseSceneNodes(
            HouseSceneGeometry.backGlass(floor, sdFace, sd1CX, sd1W, sd2CX, sd2W, sdH, winW, winH, winY, w, d, p, yBase,
                door1Fraction = slidingDoor1.value, door2Fraction = slidingDoor2.value),
            onEnterHome = effEnterHome,
        )

        // Left/right (-X/+X) glass: sliding door centred on hallway Z, windows otherwise
        emitHouseSceneNodes(
            HouseSceneGeometry.sideGlass(FeatureSide.LEFT, floor, sdFace, hwCz, sdD, sdH, winW, winH, winY, w, d, p, yBase, doorFraction = slidingDoor.value),
            onEnterHome = effEnterHome,
        )
        emitHouseSceneNodes(
            HouseSceneGeometry.sideGlass(FeatureSide.RIGHT, floor, sdFace, hwCz, sdD, sdH, winW, winH, winY, w, d, p, yBase, doorFraction = slidingDoor.value),
            onEnterHome = effEnterHome,
        )
    }

    // ── Interior — read-only rooms + furniture, seen through the transparent glass ──
    repeat(floors) { floor ->
        val yBase = floor * FLOOR_HEIGHT_M
        emitHouseSceneNodes(HouseSceneGeometry.roomFloorTiles(floorLayout, floor, yBase))
        floorLayout.toMergedZones(floor).forEach { zone ->
            // Furniture — same placement math as the room/floor-plan views, but read-only.
            val (leftBlocked, backBlocked) = if (zone.type == RoomType.KITCHEN)
                kitchenWallBlocks(zone, floor) else false to false
            val wallModes = if (zone.type == RoomType.KITCHEN) floorLayout.wallModesForZone(zone, floor) else emptyMap()
            val leftDoor  = (if (leftBlocked) wallModes[FeatureSide.RIGHT] else wallModes[FeatureSide.LEFT]) == WallMode.DOOR
            val backDoor  = (if (backBlocked) wallModes[FeatureSide.FRONT] else wallModes[FeatureSide.BACK]) == WallMode.DOOR
            val placementIds = floorLayout.placementIdsInZone(zone, floor)
            val repId = placementIds.minOrNull() ?: ""
            // A canonical interior garage room (front edge = front wall, right edge = right
            // wall — see townhouseGarageBox) already has a REAL structural door cut into the
            // front wall — its decorative GARAGE_DOOR default item would float redundantly
            // inside the room, so skip it here specifically (a hand-added Garage room
            // anywhere else in the grid still gets it, since it has no real exterior wall to
            // cut a door into).
            val skipGarageDoorItem = zone.type == RoomType.GARAGE && townhouseGarageBox != null
            zone.type.defaultItems().filter { it != RoomItem.GARAGE_DOOR || !skipGarageDoorItem }
                .filter { "$repId:${it.name}" !in removedInstances }.forEach { ri ->
                // Same drag offsets the room view applies, so a moved sofa reads consistently here.
                val (offX, offZ) = itemOffsets[ri] ?: (0f to 0f)
                ItemNode(
                    item            = ri,
                    cx              = zone.cx + ri.xFrac * zone.w * 0.38f + offX,
                    cz              = zone.cz + ri.zFrac * zone.d * 0.38f + offZ,
                    sofaMat         = sofaMat,
                    woodMat         = woodItemMat,
                    applMat         = applianceMat,
                    steelMat        = steelItemMat,
                    fixMat          = fixtureMat,
                    fabMat          = fabricMat,
                    screenMat       = screenMat,
                    sinkMat         = sinkItemMat,
                    dishwasherMat   = dishwItemMat,
                    bathtubRimMat   = tubRimMat,
                    bathtubBasinMat = tubBasinMat,
                    zoneW           = zone.w,
                    zoneD           = zone.d,
                    zoneCx          = zone.cx,
                    zoneCz          = zone.cz,
                    yBase           = yBase,
                    interactive     = false,
                    leftWallBlocked = leftBlocked,
                    backWallBlocked = backBlocked,
                    leftWallDoor    = leftDoor,
                    backWallDoor    = backDoor,
                )
            }
            // Placed furniture (and legacy extra copies) belonging to this (possibly merged)
            // room — read-only here. Keyed on identity + orientation so a rotate/flip
            // rebuilds the nodes instead of morphing geometry in place.
            placedItems.filter { it.placementId in placementIds }.forEach { pi ->
                key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
                ItemNode(
                    item            = pi.item,
                    cx              = zone.cx + pi.item.xFrac * zone.w * 0.38f + pi.dx,
                    cz              = zone.cz + pi.item.zFrac * zone.d * 0.38f + pi.dz,
                    sofaMat         = sofaMat,
                    woodMat         = woodItemMat,
                    applMat         = applianceMat,
                    steelMat        = steelItemMat,
                    fixMat          = fixtureMat,
                    fabMat          = fabricMat,
                    screenMat       = screenMat,
                    sinkMat         = sinkItemMat,
                    dishwasherMat   = dishwItemMat,
                    bathtubRimMat   = tubRimMat,
                    bathtubBasinMat = tubBasinMat,
                    // Freestanding for furniture/portables — see RoomScene's placed-item note.
                    zoneW           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.w,
                    zoneD           = if (pi.item.isFurniture || pi.item.isPortable) 0f else zone.d,
                    zoneCx          = zone.cx,
                    zoneCz          = zone.cz,
                    yBase           = yBase,
                    interactive     = false,
                    rotDeg          = pi.rotDeg,
                    flipX           = pi.flipX,
                    flipZ           = pi.flipZ,
                )
                }
            }
        }
    }

    // ── Roof ──────────────────────────────────────────────────────────────────

    val wallTopY = floors * FLOOR_HEIGHT_M
    // A bare-roof tap enters Floor Plan exactly like every other exterior surface — including a
    // miss on the solar array while placing it in the Top camera view. The array's own tiles have
    // their own dedicated tap handler (see FeaturesOnYard's solarArrayPlacement block below) and
    // take priority whenever a tap actually lands on one, so this only affects genuine misses.
    emitHouseSceneNodes(HouseSceneGeometry.roof(w, d, wallTopY, homeType.style), onEnterHome = effEnterHome)

    // ── Home features ─────────────────────────────────────────────────────────

    // Yard (grass ground plane) + garage/deck/pool, freely draggable on it. Yard presence is
    // now purely style-driven (yardBounds != null) rather than a user toggle.
    FeaturesOnYard()
    // CONDO has no yard, so FeaturesOnYard() returns before emitting anything — but its HVAC
    // unit hangs off the building wall, not the ground, so it still renders (tap-only; see
    // HvacUnit's kdoc). Without this the unit was placeable and tracked but invisible and
    // untappable, which also made the Attic scene unreachable, since that wall unit is its
    // only entry point.
    if (HouseSceneGeometry.yardBounds(homeType.style, w, d) == null) {
        HvacUnit(movable = false)
    }

    val garageSide   = featurePlacements[HomeFeature.GARAGE]
    // The garage is wall-snapped now — featureOffsets[GARAGE] always stores its along-wall
    // position in .first (see FeaturesOnYard's onDragEnd), regardless of which axis "along"
    // actually is for the current side. Map it onto the right axis before handing it to
    // anything that (like garageBox/carLot) still takes a plain (dx, dz) pair.
    val garageAlong  = featureOffsets[HomeFeature.GARAGE]?.first ?: 0f
    val (garageDx, garageDz) = if (garageSide == FeatureSide.FRONT) garageAlong to 0f else 0f to garageAlong

    // Interior garage-to-house door — on the house wall shared with the garage, tracking its
    // LIVE position as it's dragged. Always ground-floor only; tap to enter the home just
    // like the front door. TOWNHOUSE has no separate interior room to connect (its garage IS
    // the house's own front wall — see townhouseGarageBox/frontWall's garageDoorXRange).
    if (garageSide != null) {
        val garageBox = HouseSceneGeometry.garageBox(w, d, garageSide, garageDx, garageDz)
        emitHouseSceneNodes(HouseSceneGeometry.interiorDoorToGarage(garageBox, w, d, p), onEnterHome = effEnterHome)
    }

    // ── Vehicles & driveway — shared with GarageScene via VehiclesOnLot ──────────
    // The exterior door stays closed (opening it happens inside the garage scene), so a
    // car parked at the anchor is hidden here until you step into the garage. The driveway
    // follows the garage's live drag offset automatically (carLot reads the same garageBox).
    // An interior garage's driveway instead approaches the real room's door (cut into the
    // actual front wall), so it uses townhouseCarLot instead of the generic side formula.
    val vehicleLot = if (townhouseGarageBox != null) CarLotGeometry.townhouseCarLot(floorLayout, cars.map { it.item })
                      else if (cars.isNotEmpty() || garageSide != null) CarLotGeometry.carLot(w, d, garageSide, garageDx, garageDz, cars.map { it.item })
                      else null
    if (vehicleLot != null) {
        VehiclesOnLot(
            lot              = vehicleLot,
            vehicles         = cars,
            doorFraction     = 0f,
            garagePresent    = townhouseGarageBox != null || garageSide != null,
            movable          = onShellTap == null,
            onVehicleTap     = onCarTap,
            onVehicleMoved   = onCarMoved,
            onVehicleRemoved = onCarRemoved,
            onShellTap       = onShellTap,
        )
    }

    // The garage's contents, as scenery — in the same full palette this scene already draws room
    // furniture with, so a water heater seen through the garage wall matches one seen through the
    // roof. Untouchable via interactive = false, so they can neither be acted on out here nor
    // swallow a tap meant for the garage door in front of them.
    if (garageZone != null && garageContents.isNotEmpty()) {
        garageContents.forEach { pi -> key(pi.id, pi.rotDeg, pi.flipX, pi.flipZ) {
            ItemNode(
                item            = pi.item,
                cx              = garageZone.cx + pi.item.xFrac * garageZone.w * 0.38f + pi.dx,
                cz              = garageZone.cz + pi.item.zFrac * garageZone.d * 0.38f + pi.dz,
                sofaMat         = sofaMat,
                woodMat         = woodItemMat,
                applMat         = applianceMat,
                steelMat        = steelItemMat,
                fixMat          = fixtureMat,
                fabMat          = fabricMat,
                screenMat       = screenMat,
                sinkMat         = sinkItemMat,
                dishwasherMat   = dishwItemMat,
                bathtubRimMat   = tubRimMat,
                bathtubBasinMat = tubBasinMat,
                zoneCx          = garageZone.cx,
                zoneCz          = garageZone.cz,
                yBase           = DRIVEWAY_PLANE_Y - WALL_T / 2f,
                interactive     = false,
                rotDeg          = pi.rotDeg,
                flipX           = pi.flipX,
                flipZ           = pi.flipZ,
            )
        } }
    }
}


// ── Car lot geometry — pure math lives in CarLotGeometry (:core-render-spec), shared with
// MainActivity's vehicle-drop handler so a dropped vehicle's dx/dz offset means the same
// thing there as it does when rendered here. ────────────────────────────────────────────

/**
 * World Y of the driveway slab's top face — the plane a vehicle drag resolves against, and the
 * height the slab itself is built at. Shared with MainActivity's dropVehicleAt so a vehicle lands
 * in the same place whether it was dropped from the tray or dragged there.
 */
internal const val DRIVEWAY_PLANE_Y = 0.15f

/**
 * World Y of the yard slab's top face — the drag surface for every yard feature (garage, pool,
 * HVAC, EV battery, trees, gazebo). The slab sits at y = -0.13 and is 0.06 thick. It's centred on
 * the origin in X/Z, so its local X/Z and world X/Z agree, which is what lets the yard features'
 * wall-snapping maths consume the projected world position directly.
 */
internal const val YARD_PLANE_Y = -0.10f

// ── Vehicles on the lot — the driveway slab + every parked vehicle, shared verbatim by
// HouseScene (exterior) and GarageScene. Vehicles are placed items (CAR_PLACEMENT_ID)
// whose dx/dz offsets are relative to the lot anchor — the parked slot inside the garage —
// so the fleet follows the garage if it changes sides. The anchor is single-occupancy: a
// CAR still sitting AT it rolls out onto the driveway as the overhead door opens; vehicles
// dragged elsewhere stay put and always land on a free slot (never stacked). Vehicles are
// children of the driveway slab so they inherit its transform; the drag itself projects onto
// [DRIVEWAY_PLANE_Y] in world space rather than hit-testing that slab, so pushing a car to the
// street no longer stalls the moment the finger leaves the slab's on-screen area. ────────────
@Composable
internal fun SceneScope.VehiclesOnLot(
    lot: CarLot,
    vehicles: List<PlacedItem>,
    doorFraction: Float,
    garagePresent: Boolean,
    movable: Boolean,
    onVehicleTap: (PlacedItem) -> Unit,
    onVehicleMoved: (PlacedItem, Pair<Float, Float>) -> Unit,
    onVehicleRemoved: (PlacedItem) -> Unit,
    onShellTap: (() -> Unit)? = null,
    // False for a room-bound lot (interior garage) — there's no street to tear a vehicle off
    // onto inside a room, so a drag that reaches the pad's far edge just stops there instead
    // of deleting the vehicle.
    allowTearOff: Boolean = true,
    // False renders the fleet as scenery: the floor plan and every room but the driveway show
    // the cars where they really are, but nothing there responds. It opts the slab and every
    // vehicle out of hit-testing rather than just dropping the handlers — SceneView dispatches
    // to the frontmost TOUCHABLE node and never falls through, so a handler-less car would
    // silently swallow taps meant for whatever is behind it (same rule as ItemNode's own
    // `interactive`).
    interactive: Boolean = true,
    // Drops the whole lot vertically. Scenes that draw an upper storey at y ≈ 0 (the floor plan,
    // a room) push the ground down by that storey's height, and the cars belong to the ground.
    // Applied to the slab alone — every vehicle is its child, so they ride along.
    yOffset: Float = 0f,
) {
    val carBodyMats = remember { VEHICLE_BODY_COLORS.map { (r, g, b) ->
        materialLoader.createColorInstance(colorOf(r, g, b))
    } }
    val carDarkMat  = remember { materialLoader.createColorInstance(colorOf(0.14f, 0.14f, 0.16f)) }
    val carGlassMat = remember { materialLoader.createColorInstance(colorOf(0.18f, 0.38f, 0.65f)) }
    val carWheelMat = remember { materialLoader.createColorInstance(colorOf(0.07f, 0.07f, 0.08f)) }
    val hullMat     = remember { materialLoader.createColorInstance(colorOf(0.93f, 0.93f, 0.90f)) }
    val driveMat    = remember { materialLoader.createColorInstance(colorOf(0.52f, 0.52f, 0.54f)) }
    DisposableEffect(Unit) {
        onDispose {
            (carBodyMats + listOf(carDarkMat, carGlassMat, carWheelMat, hullMat, driveMat))
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Live drag offsets — written every move event, persisted once on release.
    var liveCarOffsets by remember { mutableStateOf(mapOf<String, Pair<Float, Float>>()) }

    // Concrete apron from the garage door face to the street, level with the garage floor.
    val dwH    = DRIVEWAY_PLANE_Y
    val dwMidZ = (lot.doorFaceZ + lot.farZ) / 2f
    CubeNode(
        size             = Size(lot.slabHalfW * 2f, dwH, lot.farZ - lot.doorFaceZ),
        materialInstance = driveMat,
        position         = Position(lot.slabCenterX, dwH / 2f + yOffset, dwMidZ),
        apply            = { if (!interactive) isTouchable = false },
    ) {
        vehicles.forEach { car -> key(car.id) {
            val (offX, offZ) = liveCarOffsets[car.id]
                ?: CarLotGeometry.clampVehicle(lot, car.item, car.dx, car.dz)
            val vLen = CarLotGeometry.vehicleLen(car.item)
            val vWid = CarLotGeometry.vehicleWid(car.item)
            // Parked at the anchor = inside the garage; the overhead door pulls a CAR out.
            val atAnchor = garagePresent && abs(offX) < 0.4f && abs(offZ) < 0.4f
            val pullZ = if (atAnchor && car.item == RoomItem.CAR)
                (lot.doorFaceZ + vLen / 2f + 0.7f - lot.anchorZ) * doorFraction
            else 0f
            // Children of the driveway node live in ITS local space (the node sits
            // at x = slabCenterX, z = dwMidZ, y = dwH/2 — subtract those from world).
            val vcx = lot.anchorX + offX - lot.slabCenterX
            val vcz = lot.anchorZ + offZ + pullZ - dwMidZ
            val onTap: () -> Unit = { (onShellTap ?: { onVehicleTap(car) })() }
            val onDrag: ((Float, Float) -> Unit)? = if (!movable || !interactive) null else { wx, wz ->
                val nx = (wx - lot.anchorX)
                    .coerceIn(lot.dxMin + vWid / 2f, lot.dxMax - vWid / 2f)
                val nz = (wz - lot.anchorZ)
                    .coerceIn(0f, lot.farZ - lot.anchorZ - vLen / 2f + 0.6f)
                liveCarOffsets = liveCarOffsets + (car.id to (nx to nz))
            }
            val settleDrag: () -> Unit = {
                val (dx, dz) = liveCarOffsets[car.id] ?: (car.dx to car.dz)
                val endZ = lot.anchorZ + dz
                when {
                    // Shoved to the street end — torn off the lot, removed.
                    allowTearOff && endZ > lot.farZ - vLen / 2f - 0.3f -> onVehicleRemoved(car)
                    // A car nudged back against the garage door parks inside again —
                    // but only if it's also close to the anchor in X (matching
                    // anchorOccupied's threshold below), and no other vehicle already
                    // holds the garage slot.
                    car.item == RoomItem.CAR && garagePresent &&
                        abs(dx) < 0.6f &&
                        endZ < lot.doorFaceZ + vLen / 2f + 0.6f &&
                        !CarLotGeometry.anchorOccupied(vehicles, excludeId = car.id) -> {
                        onVehicleMoved(car, 0f to 0f)
                    }
                    else -> {
                        // Land on a free slot so vehicles can never stack.
                        val slot = CarLotGeometry.freeDrivewaySlot(
                            lot, car.item, dx, dz,
                            occupied = CarLotGeometry.occupiedSlots(lot, vehicles, doorFraction,
                                garagePresent, excludeId = car.id),
                        )
                        onVehicleMoved(car, slot)
                    }
                }
                // Clear the override once committed — otherwise it keeps shadowing car.dx/dz
                // even after a LATER non-drag change (e.g. vehicles re-settling onto a new
                // driveway shape when the garage itself is added/moved/removed), leaving this
                // car rendered at a stale pre-move spot until the view is torn down and rebuilt.
                liveCarOffsets = liveCarOffsets - car.id
            }
            val onDragEnd: (() -> Unit)? = if (movable) settleDrag else null
            when (car.item) {
                RoomItem.BOAT -> BoatNode(
                    cx = vcx, cz = vcz, flY = dwH / 2f,
                    bodyMat = carBodyMats[car.colorIndex.mod(carBodyMats.size)], hullMat = hullMat,
                    darkMat = carDarkMat, glassMat = carGlassMat, wheelMat = carWheelMat,
                    onTap = onTap, onDrag = onDrag, onDragEnd = onDragEnd,
                    interactive = interactive,
                )
                RoomItem.MOTORCYCLE -> MotorcycleNode(
                    cx = vcx, cz = vcz, flY = dwH / 2f,
                    bodyMat = carBodyMats[car.colorIndex.mod(carBodyMats.size)],
                    darkMat = carDarkMat, wheelMat = carWheelMat,
                    onTap = onTap, onDrag = onDrag, onDragEnd = onDragEnd,
                    interactive = interactive,
                )
                else -> CarNode(
                    cx = vcx, cz = vcz, flY = dwH / 2f,
                    bodyMat  = carBodyMats[car.colorIndex.mod(carBodyMats.size)],
                    darkMat  = carDarkMat,
                    glassMat = carGlassMat,
                    wheelMat = carWheelMat,
                    onTap = onTap, onDrag = onDrag, onDragEnd = onDragEnd,
                    interactive = interactive,
                )
            }
        } }
    }
}

// ── The garage enclosure around the driveway — the shell and the overhead door that rolls open
// as you arrive, pulling a parked car out with it, and toggles on tap.
//
// This is what's left of the old GarageScene: the driveway is now a zone RoomScene renders (see
// its `outdoor` mode), so the pane, the camera and the vehicles come from the room's own chrome
// and only the enclosure itself lives here. Everything is authored around the lot's anchor —
// [lot] arrives already translated into the calling scene's frame, so the shell lands on the
// garage wherever that scene has put it. ─────────────────────────────────────────────────────
@Composable
internal fun SceneScope.DrivewayLot(
    lot: CarLot,
    vehicles: List<PlacedItem> = emptyList(),
    onVehicleTap: (PlacedItem) -> Unit = {},
    onVehicleMoved: (PlacedItem, Pair<Float, Float>) -> Unit = { _, _ -> },
    onVehicleRemoved: (PlacedItem) -> Unit = {},
    // The garage's own footprint. For a home with a real interior garage ROOM these come from
    // that room; for an attached garage feature, from its box; a home with neither passes
    // enclosed = false and gets the lawn, apron and street with no shell — boxing in a garage
    // the home doesn't have would be a fiction, and the no-garage CarLot already puts its parked
    // slot out on the apron rather than behind a door (see CarLotGeometry.carLot).
    gW: Float,
    gD: Float,
    enclosed: Boolean = true,
    // Mirrors the door's live open-fraction out to the caller — MainActivity's dropVehicleAt
    // needs the CURRENT value (not assumed-fully-open) to correctly treat an anchor-parked
    // car's mid-animation pulled-out position as occupied; without this a vehicle dropped
    // during the ~1.2s opening animation could land right where the first car is still
    // sliding out to, since the drop's own occupancy check has no other way to see this
    // composable's local animation state.
    onDoorFractionChanged: (Float) -> Unit = {},
) {
    val grassMat  = remember { materialLoader.createColorInstance(colorOf(0.18f, 0.52f, 0.12f)) }
    // See-through for the same reason the exterior's is (see HouseScene's garageMat): standing on
    // the driveway you look INTO the garage, and everything kept in there is behind these panels.
    val garageMat = remember { materialLoader.createColorInstance(colorOf(0.68f, 0.65f, 0.60f, 0.35f)) }
    val gDoorMat  = remember { materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)) }
    val streetMat = remember { materialLoader.createColorInstance(colorOf(0.30f, 0.30f, 0.33f)) }
    DisposableEffect(Unit) {
        onDispose {
            listOf(grassMat, garageMat, gDoorMat, streetMat)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Overhead door — rolls open on entry, toggles on tap. An open lot has no door, so it
    // stays pinned at 0: the fraction also tells the caller's dropVehicleAt how far an
    // anchor-parked car has been pulled out, and with no door nothing ever pulls out.
    val door      = remember { Animatable(0f) }
    var doorOpen  by remember { mutableStateOf(true) }
    val doorScope = rememberCoroutineScope()
    LaunchedEffect(enclosed) { if (enclosed) door.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    SideEffect { onDoorFractionChanged(if (enclosed) door.value else 0f) }
    val toggleDoor: () -> Unit = {
        val target = if (doorOpen) 0f else 1f
        doorOpen = !doorOpen
        doorScope.launch { door.animateTo(target, tween(1000, easing = FastOutSlowInEasing)) }
        Unit
    }

    // Lawn under everything, and the street the driveway runs out to.
    CubeNode(size = Size(lot.halfW * 2f + 14f, 0.06f, lot.farZ + gD / 2f + 10f), materialInstance = grassMat,
        position = Position(lot.anchorX, -0.13f, lot.anchorZ + (lot.farZ - lot.anchorZ - gD / 2f) / 2f),
        apply = { isTouchable = false }) {}
    CubeNode(size = Size(lot.halfW * 2f + 14f, 0.10f, 3.5f), materialInstance = streetMat,
        position = Position(lot.anchorX, -0.05f, lot.farZ + 1.75f),
        apply = { isTouchable = false }) {}

    if (enclosed) HouseSceneGeometry.garageShell(cx = lot.anchorX, cz = lot.anchorZ, gW = gW, gH = FLOOR_HEIGHT_M, gD = gD,
        doorFacesPositiveZ = true, doorFraction = door.value).forEach { n ->
        val mat = if (n.material == HouseSceneMaterialSlot.GARAGE_DOOR) gDoorMat else garageMat
        val rot = n.rotationDeg
        CubeNode(
            size             = Size(n.size.x, n.size.y, n.size.z),
            materialInstance = mat,
            position         = Position(n.position.x, n.position.y, n.position.z),
            rotation         = Rotation(rot.x, rot.y, rot.z),
            apply = {
                // Only the door is interactive; the shell panels opt out of hit-testing so they
                // can never swallow a tap meant for something in front of or behind them (see
                // ItemNode's commonApply — SceneView dispatches to the frontmost touchable node
                // and never falls through). Works today only because the door happens to sit on
                // the shell's outer face with nothing in front of it.
                isTouchable = n.tappable
                if (n.tappable) onSingleTapConfirmed = { playGarageDoorSound(); toggleDoor(); true }
            },
        ) {}
    }

    // The fleet, fully interactive — this is the one place vehicles are managed from. It stays
    // inside this composable because the door owns the animation a parked car rides out on:
    // hoisting the vehicles out would mean plumbing the live fraction back down to them.
    VehiclesOnLot(
        lot              = lot,
        vehicles         = vehicles,
        doorFraction     = if (enclosed) door.value else 0f,
        // Drives the "parked inside, so the door pulls it out" behaviour — meaningless on an
        // open lot, where the anchor slot is just the first parking space on the apron.
        garagePresent    = enclosed,
        movable          = true,
        onVehicleTap     = onVehicleTap,
        onVehicleMoved   = onVehicleMoved,
        onVehicleRemoved = onVehicleRemoved,
    )
}

// ── The attic, from inside — the home's mechanical space, rendered by RoomScene in its ATTIC
// place (see RoomPlace) rather than by a scene of its own. A full attic is the real void over the
// whole footprint; a utility closet is a small equipment cupboard. Which one a home has is the
// owner's choice, not the roof's (see AtticType) — this composable is type-agnostic, it just
// renders whatever volume and contents it's handed, the same way DrivewayLot renders a
// TOWNHOUSE's interior garage through its own overrides.
//
// The enclosure is deliberately open — no ceiling, no near wall — so the camera looks in and
// down over the walls, matching RoomScene's own cutaway convention. The floor hatch swings
// open on entry and toggles on tap; each item inside is independently tappable and opens its
// own maintenance card; the bare floor asks which kind of space this is.
// ────────────────────────────────────────────────────────────────────────────────────────────
@Composable
internal fun SceneScope.AtticInterior(
    volume: HouseSceneGeometry.AtticVolume,
    contents: List<HouseSceneGeometry.AtticItem>,
    onItemTap: (HouseSceneGeometry.AtticItem) -> Unit = {},
    // Tapping the bare boards — opens the attic-type card, the same way tapping a room's floor
    // opens its change/remove card.
    onFloorTap: () -> Unit = {},
) {

    val shellMat     = remember { materialLoader.createColorInstance(colorOf(0.62f, 0.58f, 0.52f)) }
    // The enclosure around the floor, see-through for the same reason RoomScene's own walls are:
    // a closet's side walls run full height, and orbiting to get a close look at the equipment
    // would otherwise put one of them squarely in front of it. Alpha < 1 makes createColorInstance
    // pick the transparent material. The floor stays opaque — it's what you stand on and tap.
    val wallMat      = remember { materialLoader.createColorInstance(colorOf(0.62f, 0.58f, 0.52f, 0.35f)) }
    val hatchMat     = remember { materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)) }
    val hvacBodyMat  = remember { materialLoader.createColorInstance(colorOf(0.92f, 0.92f, 0.90f)) }
    val hvacGrillMat = remember { materialLoader.createColorInstance(colorOf(0.40f, 0.40f, 0.40f)) }
    val ductMat      = remember { materialLoader.createColorInstance(colorOf(0.72f, 0.74f, 0.78f)) }
    val insulMat     = remember { materialLoader.createColorInstance(colorOf(0.93f, 0.55f, 0.66f)) }
    // Bare softwood — rafters, ridge, joists and the gable-end studs read as timber against the
    // shell's grey board.
    val framingMat   = remember { materialLoader.createColorInstance(colorOf(0.76f, 0.63f, 0.44f)) }
    DisposableEffect(Unit) {
        onDispose {
            listOf(shellMat, wallMat, hatchMat, hvacBodyMat, hvacGrillMat, ductMat, insulMat, framingMat)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    fun matFor(slot: HouseSceneMaterialSlot) = when (slot) {
        HouseSceneMaterialSlot.ATTIC_WALL    -> wallMat
        HouseSceneMaterialSlot.ATTIC_HATCH   -> hatchMat
        HouseSceneMaterialSlot.ATTIC_FRAMING -> framingMat
        HouseSceneMaterialSlot.HVAC_BODY     -> hvacBodyMat
        HouseSceneMaterialSlot.HVAC_GRILLE   -> hvacGrillMat
        HouseSceneMaterialSlot.DUCT          -> ductMat
        HouseSceneMaterialSlot.INSULATION    -> insulMat
        else                                 -> shellMat
    }

    // Hatch — swings open on entry, toggles on tap. Same shape as GarageScene's door animation.
    val hatch      = remember { Animatable(0f) }
    var hatchOpen  by remember { mutableStateOf(true) }
    val hatchScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { hatch.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val toggleHatch: () -> Unit = {
        val target = if (hatchOpen) 0f else 1f
        hatchOpen = !hatchOpen
        hatchScope.launch { hatch.animateTo(target, tween(1000, easing = FastOutSlowInEasing)) }
        Unit
    }

    // Keyed on the volume, because the volume decides how many nodes this emits: whether there
    // are rafters and a ridge at all (pitched), and how many joists (depth). Switching between a
    // full attic and a utility closet therefore changes the LIST LENGTH at runtime, and a CubeNode's
    // apply block runs only once — without this key the node that used to be a rafter would be
    // handed the hatch's geometry while keeping the rafter's untouchable apply block, leaving the
    // hatch dead (see the sceneview-node-identity notes on the contents pass below).
    key(volume) {
    HouseSceneGeometry.atticShell(volume, hatch.value).forEach { n ->
        val rot = n.rotationDeg
        CubeNode(
            size             = Size(n.size.x, n.size.y, n.size.z),
            materialInstance = matFor(n.material),
            position         = Position(n.position.x, n.position.y, n.position.z),
            rotation         = Rotation(rot.x, rot.y, rot.z),
            apply = {
                // Only the hatch and the floor are interactive; the walls and framing MUST opt out
                // of hit-testing: SceneView dispatches a tap to the single frontmost touchable node
                // and never falls through, so a handler-less shell panel standing between the
                // camera and an item would swallow the tap silently — exactly the dead-tap trap
                // ItemNode's commonApply documents, and the reason this space's equipment was
                // untappable. The floor is under everything, so it can't intercept anything.
                isTouchable = n.tappable
                if (n.tappable) {
                    val hatch = n.material == HouseSceneMaterialSlot.ATTIC_HATCH
                    onSingleTapConfirmed = { if (hatch) toggleHatch() else onFloorTap(); true }
                }
            },
        ) {}
    }
    }

    // One geometry call per item, one forEach per call — so every node in an item's list shares
    // that item's single tap closure. key(item) keeps each item's gesture handlers bound to the
    // right item: a CubeNode's apply block runs only once, so without it a changed list (switching
    // to a closet, or switching ductwork off in the pane) would leave stale closures behind.
    contents.forEach { item ->
        key(item) {
            val at = HouseSceneGeometry.atticSlot(volume, item)
            HouseSceneGeometry.atticItemNodes(item, at, volume).forEach { n ->
                CubeNode(
                    size             = Size(n.size.x, n.size.y, n.size.z),
                    materialInstance = matFor(n.material),
                    position         = Position(n.position.x, n.position.y, n.position.z),
                    apply = {
                        isTouchable = n.tappable
                        if (n.tappable) onSingleTapConfirmed = { onItemTap(item); true }
                    },
                ) {}
            }
        }
    }
}

// ── Car — the 10-cube sedan (formerly baked into HollowGarage), now a freestanding,
// tappable, long-press-draggable node used for every parked PlacedItem car. Faces +Z
// (all garages' doors face +Z). Coordinates are in the DRIVEWAY node's local space —
// cars are its children so their drag gesture resolves against the driveway surface. ──
@Composable
private fun SceneScope.CarNode(
    cx: Float, cz: Float, flY: Float,
    bodyMat: MaterialInstance,
    darkMat: MaterialInstance,
    glassMat: MaterialInstance,
    wheelMat: MaterialInstance,
    onTap: () -> Unit,
    onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /** False = scenery: no tap, no drag, out of hit-testing (see rememberDragApply). */
    interactive: Boolean = true,
) {
    val carW  = CarLotGeometry.CAR_WID
    val carL  = CarLotGeometry.CAR_LEN
    val dP    = 0.08f
    val uH    = 0.47f    // underbody / door-sill height
    val topH  = 0.42f    // cabin height
    val hoodH = 0.20f    // hood / trunk step height (sits on top of uH)
    val wH    = 0.31f    // wheel height
    val wW    = carW * 0.14f
    val wD    = carL * 0.14f
    // Vehicle cubes are children of the driveway slab, but the drag resolves against that
    // slab's top face in WORLD space (0.15) � which is what the consumer's lot.anchorX/Z
    // maths already expects, and what dropVehicleAt uses for the same vehicle.
    val applyAll = rememberDragApply(DRIVEWAY_PLANE_Y, onTap, onDrag, onDragEnd, interactive = interactive)
    val uBodyY  = flY + wH + uH / 2f
    val cabY    = flY + wH + uH + topH / 2f
    val hoodY   = flY + wH + uH + hoodH / 2f
    val wY      = flY + wH / 2f
    val hoodCZ  = cz + carL * 0.35f
    val trunkCZ = cz - carL * 0.35f
    val cabCZ   = cz - carL * 0.04f
    val fWhlZ   = cz + (carL / 2f - wD * 0.75f)
    val rWhlZ   = cz - (carL / 2f - wD * 0.75f)
    val wX      = carW / 2f - wW * 0.6f

    // Underbody (full length — doors, sills, lower body)
    CubeNode(size = Size(carW, uH, carL), materialInstance = bodyMat,
        position = Position(cx, uBodyY, cz), apply = applyAll) {}
    // Hood
    CubeNode(size = Size(carW * 0.92f, hoodH, carL * 0.30f), materialInstance = bodyMat,
        position = Position(cx, hoodY, hoodCZ), apply = applyAll) {}
    // Trunk lid
    CubeNode(size = Size(carW * 0.88f, hoodH * 0.80f, carL * 0.22f), materialInstance = bodyMat,
        position = Position(cx, flY + wH + uH + hoodH * 0.80f / 2f, trunkCZ), apply = applyAll) {}
    // Cabin roof (dark, narrower)
    CubeNode(size = Size(carW * 0.68f, topH, carL * 0.46f), materialInstance = darkMat,
        position = Position(cx, cabY, cabCZ), apply = applyAll) {}
    // Front + rear windshields
    CubeNode(size = Size(carW * 0.62f, topH * 0.82f, dP), materialInstance = glassMat,
        position = Position(cx, cabY, cabCZ + carL * 0.46f / 2f), apply = applyAll) {}
    CubeNode(size = Size(carW * 0.58f, topH * 0.78f, dP), materialInstance = glassMat,
        position = Position(cx, cabY, cabCZ - carL * 0.46f / 2f), apply = applyAll) {}
    // Front bumper strip
    CubeNode(size = Size(carW * 0.88f, uH * 0.40f, dP * 1.5f), materialInstance = darkMat,
        position = Position(cx, flY + wH + uH * 0.20f, cz + carL / 2f), apply = applyAll) {}
    // 4 wheels
    listOf(cx - wX to fWhlZ, cx + wX to fWhlZ, cx - wX to rWhlZ, cx + wX to rWhlZ).forEach { (x, z) ->
        CubeNode(size = Size(wW, wH, wD), materialInstance = wheelMat,
            position = Position(x, wY, z), apply = applyAll) {}
    }
}

/**
 * One [DragTarget] for one feature or vehicle, plus the `apply` block that binds every cube of it
 * to that target — so grabbing any panel (a garage wall, a motorcycle's fork, a solar tile) drags
 * the whole thing. Call this ONCE per item, above the loop that emits its cubes.
 *
 * The target's callbacks are refreshed in a [SideEffect] rather than captured in the apply block,
 * because SceneScope.CubeNode runs `apply` exactly once, at node creation — a closure captured
 * there would keep calling the state the cube was born with.
 *
 * None of SceneView's own per-node gesture hooks are used: [SceneDragController] owns the gesture
 * from ACTION_DOWN. See its kdoc for why partial ownership isn't possible.
 */
@Composable
private fun rememberDragApply(
    planeY: Float,
    onTap: () -> Unit,
    onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    // Overrides the flat plane at [planeY] — solar tracks the roof slopes it renders on.
    pick: ((Float3, Float3) -> Float3?)? = null,
    // False for a view-only copy of something (a car shown in the floor plan, in another room):
    // no tap, no drag, and out of hit-testing entirely, so it can't swallow a tap meant for
    // whatever is behind it. Same rule as ItemNode's `interactive`.
    interactive: Boolean = true,
): CubeNodeImpl.() -> Unit {
    val registry = LocalSceneDragRegistry.current
    val target = remember { DragTarget() }
    SideEffect {
        target.pick      = pick ?: horizontalPick(planeY)
        target.onTap     = onTap
        target.onDrag    = onDrag
        target.onDragEnd = onDragEnd
    }
    return remember(registry, interactive) {
        if (!interactive) {
            { isTouchable = false }
        } else {
            {
                onSingleTapConfirmed = { target.onTap?.invoke(); true }
                registry.bind(this, target)
            }
        }
    }
}

// ── Boat — a trailered runabout facing +Z (bow toward the street), in the driveway
// node's local space like CarNode. Hull in the cycled body color so multiple boats stay
// tellable apart; sheer stripe and cockpit interior stay fixed trim colors. ────────────
@Composable
private fun SceneScope.BoatNode(
    cx: Float, cz: Float, flY: Float,
    bodyMat: MaterialInstance,
    hullMat: MaterialInstance,
    darkMat: MaterialInstance,
    glassMat: MaterialInstance,
    wheelMat: MaterialInstance,
    onTap: () -> Unit,
    onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /** False = scenery: no tap, no drag, out of hit-testing (see rememberDragApply). */
    interactive: Boolean = true,
) {
    val bL = CarLotGeometry.vehicleLen(RoomItem.BOAT)
    val bW = CarLotGeometry.vehicleWid(RoomItem.BOAT)
    // Vehicle cubes are children of the driveway slab, but the drag resolves against that
    // slab's top face in WORLD space (0.15) � which is what the consumer's lot.anchorX/Z
    // maths already expects, and what dropVehicleAt uses for the same vehicle.
    val applyAll = rememberDragApply(DRIVEWAY_PLANE_Y, onTap, onDrag, onDragEnd, interactive = interactive)
    val trailerH = 0.26f    // hull sits this far above the slab, on the trailer
    val hullH    = 0.55f
    val hullTopY = flY + trailerH + hullH
    // Trailer: tongue bar toward the bow + two wheels near the stern
    CubeNode(size = Size(0.10f, 0.08f, bL * 0.40f), materialInstance = darkMat,
        position = Position(cx, flY + 0.10f, cz + bL * 0.32f), apply = applyAll) {}
    listOf(-(bW / 2f - 0.08f), bW / 2f - 0.08f).forEach { ox ->
        CubeNode(size = Size(0.16f, 0.30f, 0.48f), materialInstance = wheelMat,
            position = Position(cx + ox, flY + 0.15f, cz - bL * 0.14f), apply = applyAll) {}
    }
    // Hull: main body + two tapering bow segments, in the cycled body color
    CubeNode(size = Size(bW * 0.92f, hullH, bL * 0.60f), materialInstance = bodyMat,
        position = Position(cx, flY + trailerH + hullH / 2f, cz - bL * 0.08f), apply = applyAll) {}
    CubeNode(size = Size(bW * 0.70f, hullH * 0.85f, bL * 0.16f), materialInstance = bodyMat,
        position = Position(cx, flY + trailerH + hullH * 0.85f / 2f, cz + bL * 0.30f), apply = applyAll) {}
    CubeNode(size = Size(bW * 0.42f, hullH * 0.68f, bL * 0.10f), materialInstance = bodyMat,
        position = Position(cx, flY + trailerH + hullH * 0.68f / 2f, cz + bL * 0.43f), apply = applyAll) {}
    // Accent stripe along the sheer line — fixed trim color, not the cycled body color
    CubeNode(size = Size(bW * 0.96f, 0.10f, bL * 0.58f), materialInstance = hullMat,
        position = Position(cx, hullTopY - 0.09f, cz - bL * 0.08f), apply = applyAll) {}
    // Cockpit interior + windshield
    CubeNode(size = Size(bW * 0.66f, 0.10f, bL * 0.34f), materialInstance = darkMat,
        position = Position(cx, hullTopY + 0.02f, cz - bL * 0.12f), apply = applyAll) {}
    CubeNode(size = Size(bW * 0.62f, 0.28f, 0.06f), materialInstance = glassMat,
        position = Position(cx, hullTopY + 0.16f, cz + bL * 0.08f), apply = applyAll) {}
    // Outboard motor on the stern
    CubeNode(size = Size(0.26f, 0.48f, 0.20f), materialInstance = darkMat,
        position = Position(cx, flY + trailerH + hullH * 0.55f, cz - bL * 0.42f), apply = applyAll) {}
}

// ── Motorcycle — facing +Z, in the driveway node's local space like CarNode ───────────
@Composable
private fun SceneScope.MotorcycleNode(
    cx: Float, cz: Float, flY: Float,
    bodyMat: MaterialInstance,
    darkMat: MaterialInstance,
    wheelMat: MaterialInstance,
    onTap: () -> Unit,
    onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /** False = scenery: no tap, no drag, out of hit-testing (see rememberDragApply). */
    interactive: Boolean = true,
) {
    val mL = CarLotGeometry.vehicleLen(RoomItem.MOTORCYCLE)
    // Vehicle cubes are children of the driveway slab, but the drag resolves against that
    // slab's top face in WORLD space (0.15) � which is what the consumer's lot.anchorX/Z
    // maths already expects, and what dropVehicleAt uses for the same vehicle.
    val applyAll = rememberDragApply(DRIVEWAY_PLANE_Y, onTap, onDrag, onDragEnd, interactive = interactive)
    val whlZ = mL / 2f - 0.38f
    // Wheels (thin tall boxes)
    listOf(cz + whlZ, cz - whlZ).forEach { z ->
        CubeNode(size = Size(0.14f, 0.56f, 0.56f), materialInstance = wheelMat,
            position = Position(cx, flY + 0.28f, z), apply = applyAll) {}
    }
    // Engine block
    CubeNode(size = Size(0.42f, 0.34f, 0.72f), materialInstance = darkMat,
        position = Position(cx, flY + 0.47f, cz - 0.05f), apply = applyAll) {}
    // Fuel tank (body color)
    CubeNode(size = Size(0.34f, 0.26f, 0.55f), materialInstance = bodyMat,
        position = Position(cx, flY + 0.76f, cz + 0.22f), apply = applyAll) {}
    // Rear fender (body color, arches over the rear wheel) + seat (always black)
    CubeNode(size = Size(0.34f, 0.14f, 0.30f), materialInstance = bodyMat,
        position = Position(cx, flY + 0.74f, cz - 0.57f), apply = applyAll) {}
    CubeNode(size = Size(0.34f, 0.14f, 0.30f), materialInstance = darkMat,
        position = Position(cx, flY + 0.74f, cz - 0.27f), apply = applyAll) {}
    // Front fork + handlebar
    CubeNode(size = Size(0.10f, 0.62f, 0.10f), materialInstance = darkMat,
        position = Position(cx, flY + 0.62f, cz + whlZ + 0.06f), apply = applyAll) {}
    CubeNode(size = Size(0.58f, 0.07f, 0.07f), materialInstance = darkMat,
        position = Position(cx, flY + 0.96f, cz + whlZ - 0.02f), apply = applyAll) {}
}

// ── Door creak — dry-hinge stick-slip: a tone that jitters between frequencies in little
// steps (the "catch" of an unlubricated hinge), over an uneven, slowly decaying envelope. ──

// Builds a static-mode AudioTrack from [buf], plays it, blocks the calling (background)
// thread for [holdMs], then releases it — shared by every synthesised sound in this file so
// each one only has to supply its own waveform.
private fun playSonification(sampleRate: Int, buf: ShortArray, holdMs: Long) {
    val minBuf = AudioTrack.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
        .setTransferMode(AudioTrack.MODE_STATIC)
        .build()
    track.write(buf, 0, buf.size)
    track.play()
    Thread.sleep(holdMs)
    track.stop()
    track.release()
}

private fun playDoorCreak() {
    Thread {
        val sampleRate = 44100
        val durationMs = 420
        val samples    = sampleRate * durationMs / 1000
        val buf   = ShortArray(samples)
        val rng   = Random(System.nanoTime())
        var freq  = 340.0
        var phase = 0.0
        for (i in buf.indices) {
            val t = i.toDouble() / sampleRate
            if (i % 90 == 0) freq = 280.0 + rng.nextDouble() * 260.0
            phase += 2.0 * PI * freq / sampleRate
            val tone  = sin(phase)
            val noise = (rng.nextDouble() * 2.0 - 1.0) * 0.25
            val env   = if (t < 0.03) t / 0.03
                        else exp(-(t - 0.03) * 5.5) * (0.7 + 0.3 * sin(t * 40.0))
            val wave  = (tone * 0.75 + noise) * env
            buf[i] = (wave * Short.MAX_VALUE * 0.6)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        playSonification(sampleRate, buf, holdMs = durationMs + 80L)
    }.start()
}

// ── Garage door — low motor rumble (tremolo'd like a chain drive under load) with a creaky
// metal-on-metal whine riding on top and sparse rattle from panels shaking in their track.
// Longer and rougher than the house door's creak so it reads as mechanical, not wooden. ──
private fun playGarageDoorSound() {
    Thread {
        val sampleRate  = 44100
        val durationMs  = 900
        val durationS   = durationMs / 1000.0
        val samples     = sampleRate * durationMs / 1000
        val buf = ShortArray(samples)
        val rng = Random(System.nanoTime())
        var creakFreq  = 210.0
        var creakPhase = 0.0
        for (i in buf.indices) {
            val t = i.toDouble() / sampleRate
            val rumble = sin(2.0 * PI * 55.0 * t) * (0.6 + 0.4 * sin(2.0 * PI * 7.0 * t))
            if (i % 220 == 0) creakFreq = 160.0 + rng.nextDouble() * 140.0
            creakPhase += 2.0 * PI * creakFreq / sampleRate
            val creak  = sin(creakPhase) * 0.35
            val rattle = if (rng.nextDouble() < 0.05) (rng.nextDouble() * 2.0 - 1.0) * 0.5 else 0.0
            val env = when {
                t < 0.12             -> t / 0.12
                t > durationS - 0.15 -> (durationS - t) / 0.15
                else                 -> 1.0
            }.coerceIn(0.0, 1.0)
            val wave = (rumble * 0.6 + creak + rattle) * env
            buf[i] = (wave * Short.MAX_VALUE * 0.55)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        playSonification(sampleRate, buf, holdMs = durationMs + 100L)
    }.start()
}

// ── Interior furniture rendered as simple recognisable cube shapes ─────────────

@Composable
internal fun SceneScope.ItemNode(
    item: RoomItem,
    cx: Float, cz: Float,
    sofaMat: MaterialInstance,
    woodMat: MaterialInstance,
    applMat: MaterialInstance,
    steelMat: MaterialInstance,
    fixMat: MaterialInstance,
    fabMat: MaterialInstance,
    screenMat: MaterialInstance,
    sinkMat: MaterialInstance,
    dishwasherMat: MaterialInstance,
    bathtubRimMat: MaterialInstance,
    bathtubBasinMat: MaterialInstance,
    scale: Float = 1.0f,
    onTap: () -> Unit = {},
    zoneW: Float = 0f,
    zoneD: Float = 0f,
    // Zone centre in the caller's coordinate space (0 in RoomScene's room-local space).
    // Kitchen slots anchor on THIS, never on cx/cz — cx/cz carry the item's drag offset,
    // and anchoring the slot frame on them would let a saved offset relocate the whole
    // kitchen (a stale oven offset once landed it outside the house).
    zoneCx: Float = 0f,
    zoneCz: Float = 0f,
    yBase: Float = 0f,
    interactive: Boolean = true,
    // Kitchen counter runs normally hug the back-left corner (-X, -Z walls). When a
    // sliding glass door occupies one of those walls, that run — and everything
    // mounted into it (stove, sink, dishwasher, microwave, oven, range hood) —
    // flips to the opposite wall instead of sitting on top of the door.
    leftWallBlocked: Boolean = false,
    backWallBlocked: Boolean = false,
    // Whichever wall the (possibly flipped) run ends up on may itself carry an interior
    // door — the counter's wood/top slabs then notch around that door's swing instead of
    // running through it (appliances are left as-is; only the counter body notches).
    leftWallDoor: Boolean = false,
    backWallDoor: Boolean = false,
    // Freestanding furniture (RoomScene only) can be dragged. Every constituent cube binds to
    // the SAME DragTarget below, so no matter which part of the item is grabbed — a table leg,
    // a drawer front — the whole assembly slides together off one shared cx/cz offset.
    movable: Boolean = false,
    onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    // Furniture orientation (PlacedItem.rotDeg/flipX/flipZ) — quarter-turn rotation about
    // the item's centre plus mirrors across each floor axis. Only the furniture branches
    // honour it; wall-anchored appliance/fixture geometry ignores it.
    rotDeg: Int = 0,
    flipX: Boolean = false,
    flipZ: Boolean = false,
) {
    val flY = yBase + WALL_T / 2f
    fun s(v: Float) = v * scale
    val p = s(0.04f)
    // A plain tap already opens the actions bar (rotate/flip/track/remove), so dragging stays
    // gated behind a hold — otherwise a quick tap-and-slight-slip would move furniture by
    // accident. The hold, the pick-up and the whole gesture are run by SceneDragController; all
    // this node has to do is say which item it belongs to.
    //
    // None of SceneView's own per-node gesture hooks (isEditable / isPositionEditable / onMove /
    // onLongPress) are used any more. They were actively harmful: isEditable is what let a drag
    // that drifted onto a wall strand the camera mid-orbit, and isPositionEditable is what made
    // every move event hit-test this node's PARENT — returning nothing, and so freezing the drag,
    // as soon as the finger left that parent slab's on-screen area.
    val registry = LocalSceneDragRegistry.current
    val dragTarget = remember { DragTarget() }
    // Refreshed every recomposition because the apply block below runs only ONCE, when the cube
    // is created — anything captured there would be stale for the rest of the node's life.
    SideEffect {
        // The item's own floor level — where it slides. yBase makes this the right storey in
        // HouseScene's cutaway interiors, and 0 + WALL_T/2 in a room view.
        dragTarget.pick      = horizontalPick(flY)
        dragTarget.onTap     = if (interactive) onTap else null
        dragTarget.onDrag    = if (movable) onDrag else null
        dragTarget.onDragEnd = if (movable) onDragEnd else null
    }
    val commonApply: CubeNodeImpl.() -> Unit = {
        // View-only items must not swallow touches: SceneView dispatches a tap to the single
        // FRONTMOST touchable node, so a handler-less furniture cube sitting on a room tile
        // would capture the tap and silently drop it (dead taps in the floor plan). Opting
        // out of hit-testing lets the tap fall through to the tile beneath.
        isTouchable = interactive || movable
        if (interactive) onSingleTapConfirmed = { dragTarget.onTap?.invoke(); true }
        if (movable) registry.bind(this, dragTarget)
    }
    // Orientable cube for furniture: [dx]/[dz] are the box's local offset from the item's
    // centre (cx, cz). All furniture is axis-aligned box assemblies, so orientation is pure
    // bookkeeping — mirror then quarter-turn the offset and swap the box's footprint on odd
    // turns — no rotated transforms, keeping lighting, tap targets, and drag hit-boxes exact.
    val rotQ = ((rotDeg / 90) % 4 + 4) % 4
    @Composable
    fun OCube(size: Size, mat: MaterialInstance, dx: Float, y: Float, dz: Float) {
        var ox = if (flipX) -dx else dx
        var oz = if (flipZ) -dz else dz
        var sx = size.x
        var sz = size.z
        repeat(rotQ) {
            val t = ox; ox = oz; oz = -t
            val ts = sx; sx = sz; sz = ts
        }
        CubeNode(size = Size(sx, size.y, sz), materialInstance = mat,
            position = Position(cx + ox, y, cz + oz), apply = commonApply) {}
    }

    when (item) {
        RoomItem.SOFA -> {
            OCube(Size(s(1.80f), s(0.45f), s(0.70f)), sofaMat, 0f, flY + s(0.225f), 0f)
            OCube(Size(s(1.80f), s(0.50f), s(0.14f)), sofaMat, 0f, flY + s(0.70f), -s(0.28f))
        }
        RoomItem.COFFEE_TABLE -> {
            // Accent table — a slim wood top on four slender steel legs with a thin steel
            // apron underneath, a lighter and more "designed" look than a boxy coffee table.
            OCube(Size(s(0.70f), p, s(0.45f)), woodMat, 0f, flY + s(0.42f), 0f)
            OCube(Size(s(0.66f), s(0.02f), s(0.41f)), steelMat, 0f, flY + s(0.395f), 0f)
            listOf(-s(0.30f) to -s(0.18f), s(0.30f) to -s(0.18f),
                   -s(0.30f) to  s(0.18f), s(0.30f) to  s(0.18f)).forEach { (dx, dz) ->
                OCube(Size(s(0.04f), s(0.40f), s(0.04f)), steelMat, dx, flY + s(0.20f), dz)
            }
        }
        RoomItem.TV_STAND -> {
            OCube(Size(s(1.40f), s(0.45f), s(0.35f)), woodMat, 0f, flY + s(0.225f), 0f)
            OCube(Size(s(1.20f), s(0.68f), p), screenMat, 0f, flY + s(0.79f), 0f)
        }
        RoomItem.BED -> {
            OCube(Size(s(1.60f), s(0.35f), s(2.00f)), fabMat, 0f, flY + s(0.175f), 0f)
            OCube(Size(s(1.60f), s(0.60f), s(0.12f)), woodMat, 0f, flY + s(0.65f), -s(0.94f))
        }
        RoomItem.DRESSER -> {
            OCube(Size(s(0.85f), s(0.85f), s(0.45f)), woodMat, 0f, flY + s(0.425f), 0f)
        }
        RoomItem.NIGHTSTAND -> {
            OCube(Size(s(0.45f), s(0.55f), s(0.40f)), woodMat, 0f, flY + s(0.275f), 0f)
        }
        RoomItem.COUNTER -> {
            // Half-cell counter segment — added/removed from the tray like wall pieces and
            // arranged freely (rotate to run along any wall, or build an island): wood
            // cabinet on a dark toe kick under a light stone top.
            OCube(Size(s(1.00f), s(0.06f), s(0.60f)), fixMat, 0f, flY + s(0.90f), 0f)
            OCube(Size(s(0.96f), s(0.78f), s(0.55f)), woodMat, 0f, flY + s(0.48f), 0f)
            OCube(Size(s(0.96f), s(0.09f), s(0.45f)), screenMat, 0f, flY + s(0.045f), 0f)
        }
        RoomItem.REFRIGERATOR -> {
            // Freestanding stainless fridge — steel body, door seam, bar handles, and a
            // dark water/ice dispenser. Front faces +Z (toward the room when seeded on
            // the back wall); OCube honors rotate/flip.
            OCube(Size(s(0.70f), s(1.75f), s(0.66f)), steelMat, 0f, flY + s(0.875f), 0f)
            OCube(Size(s(0.66f), s(0.03f), p), screenMat, 0f, flY + s(1.15f), s(0.335f))
            OCube(Size(s(0.04f), s(0.50f), p), fixMat, -s(0.25f), flY + s(1.44f), s(0.345f))
            OCube(Size(s(0.04f), s(0.85f), p), fixMat, -s(0.25f), flY + s(0.63f), s(0.345f))
            OCube(Size(s(0.16f), s(0.24f), p), screenMat, s(0.12f), flY + s(1.42f), s(0.34f))
        }
        RoomItem.STOVE -> {
            // Freestanding range: steel body under a black cooktop. Front faces +Z. The
            // oven bay below the cooktop is bare — the OVEN item is the door assembly
            // that fills it (seeded into the same slot), keeping the oven its own
            // tappable, lifespan-tracked appliance.
            OCube(Size(s(0.60f), s(0.90f), s(0.60f)), steelMat, 0f, flY + s(0.45f), 0f)
            OCube(Size(s(0.58f), p, s(0.58f)), screenMat, 0f, flY + s(0.92f), 0f)
        }
        RoomItem.KITCHEN_SINK -> {
            // Sink cabinet: wood base under a stone top with an inset blue basin; the
            // faucet stub rises at the back (-Z) edge. Slides flush against a counter run.
            OCube(Size(s(0.70f), s(0.06f), s(0.60f)), fixMat, 0f, flY + s(0.90f), 0f)
            OCube(Size(s(0.66f), s(0.81f), s(0.55f)), woodMat, 0f, flY + s(0.465f), 0f)
            OCube(Size(s(0.46f), p, s(0.36f)), sinkMat, 0f, flY + s(0.945f), 0f)
            OCube(Size(s(0.05f), s(0.18f), s(0.05f)), steelMat, 0f, flY + s(1.02f), -s(0.20f))
        }
        RoomItem.DISHWASHER -> {
            // Built-under dishwasher: white body, teal door, dark controls, steel handle,
            // capped by its own counter-height stone top so in the kitchen run it reads
            // as tucked under a cutout in the counter line. Front faces +Z.
            OCube(Size(s(0.60f), s(0.06f), s(0.60f)), fixMat, 0f, flY + s(0.90f), 0f)
            OCube(Size(s(0.60f), s(0.85f), s(0.60f)), fixMat, 0f, flY + s(0.425f), 0f)
            OCube(Size(s(0.56f), s(0.62f), p), dishwasherMat, 0f, flY + s(0.35f), s(0.30f))
            OCube(Size(s(0.56f), s(0.08f), p), screenMat, 0f, flY + s(0.78f), s(0.30f))
            OCube(Size(s(0.40f), s(0.03f), p), steelMat, 0f, flY + s(0.68f), s(0.315f))
        }
        RoomItem.MICROWAVE -> {
            // Countertop microwave — floats at counter height so it reads as sitting on a
            // segment: steel body, dark door window, white control strip. Front faces +Z.
            OCube(Size(s(0.55f), s(0.32f), s(0.42f)), steelMat, 0f, flY + s(1.10f), 0f)
            OCube(Size(s(0.34f), s(0.24f), p), screenMat, -s(0.06f), flY + s(1.10f), s(0.22f))
            OCube(Size(s(0.10f), s(0.24f), p), fixMat, s(0.19f), flY + s(1.10f), s(0.22f))
        }
        RoomItem.OVEN -> {
            // Range oven — the door assembly tucked under the stove's cooktop: a steel
            // door slab sitting proud of the range's front face (so its cubes stay the
            // frontmost tap target), with a dark window and bar handle. Front faces +Z;
            // shares the stove's slot and rides along when the stove is dragged.
            OCube(Size(s(0.56f), s(0.55f), s(0.04f)), steelMat, 0f, flY + s(0.355f), s(0.32f))
            OCube(Size(s(0.44f), s(0.30f), p), screenMat, 0f, flY + s(0.38f), s(0.345f))
            OCube(Size(s(0.50f), s(0.04f), p), fixMat, 0f, flY + s(0.62f), s(0.345f))
        }
        RoomItem.RANGE_HOOD -> {
            // Hood canopy + duct — floats at hood height; park it above the range.
            OCube(Size(s(0.60f), s(0.18f), s(0.55f)), steelMat, 0f, flY + s(1.54f), 0f)
            OCube(Size(s(0.22f), s(0.60f), s(0.22f)), steelMat, 0f, flY + s(1.93f), 0f)
        }
        RoomItem.TOILET -> {
            // Toilet assembly (front → +Z): slim tank with a lid and steel flush button
            // at the back, bowl pedestal, and an overhanging seat. Seeded backed onto a
            // door-free wall by bathroomFixtureSlots; a placed item like every other
            // fixture now, so OCube gives it the full rotate/flip treatment.
            OCube(Size(s(0.42f), s(0.44f), s(0.16f)), fixMat, 0f, flY + s(0.64f), -s(0.26f))
            OCube(Size(s(0.44f), s(0.04f), s(0.18f)), fixMat, 0f, flY + s(0.88f), -s(0.25f))
            OCube(Size(s(0.10f), s(0.03f), s(0.06f)), steelMat, 0f, flY + s(0.915f), -s(0.26f))
            OCube(Size(s(0.36f), s(0.40f), s(0.48f)), fixMat, 0f, flY + s(0.20f), s(0.06f))
            OCube(Size(s(0.40f), s(0.05f), s(0.52f)), fixMat, 0f, flY + s(0.425f), s(0.08f))
        }
        RoomItem.BATHTUB -> {
            // Corner tub (x-symmetric, wall end at -Z): white apron rim around a sunken
            // blue basin, steel filler spout, and a shower column rising at the wall
            // end. Seeded into a door-free corner by bathroomFixtureSlots.
            OCube(Size(s(0.70f), s(0.45f), s(1.50f)), bathtubRimMat, 0f, flY + s(0.225f), 0f)
            OCube(Size(s(0.50f), s(0.35f), s(1.25f)), bathtubBasinMat, 0f, flY + s(0.225f), 0f)
            OCube(Size(s(0.06f), s(0.14f), s(0.06f)), steelMat, 0f, flY + s(0.52f), -s(0.66f))
            OCube(Size(s(0.04f), s(1.30f), s(0.04f)), steelMat, 0f, flY + s(1.10f), -s(0.71f))
            OCube(Size(s(0.16f), s(0.04f), s(0.16f)), steelMat, 0f, flY + s(1.73f), -s(0.60f))
        }
        RoomItem.VANITY -> {
            // Sink vanity — the bathroom's sink: wood cabinet under a stone top with an
            // inset blue basin and a steel faucet at its back (+Z) edge.
            OCube(Size(s(0.90f), s(0.76f), s(0.45f)), woodMat, 0f, flY + s(0.38f), 0f)
            OCube(Size(s(0.94f), s(0.05f), s(0.49f)), fixMat, 0f, flY + s(0.785f), 0f)
            OCube(Size(s(0.36f), s(0.04f), s(0.28f)), sinkMat, 0f, flY + s(0.82f), -s(0.02f))
            OCube(Size(s(0.04f), s(0.18f), s(0.04f)), steelMat, 0f, flY + s(0.89f), s(0.17f))
        }
        RoomItem.DINING_TABLE -> {
            // A full "Dining Set" — table, pedestal, a centerpiece bowl, and four chairs —
            // one placed item so the tray and inventory show a single entry instead of a
            // table and chairs tracked (and dropped in) separately.
            OCube(Size(s(1.20f), p, s(0.70f)), woodMat, 0f, flY + s(0.74f), 0f)
            OCube(Size(s(0.10f), s(0.72f), s(0.10f)), woodMat, 0f, flY + s(0.36f), 0f)
            OCube(Size(s(0.18f), s(0.05f), s(0.18f)), fixMat, 0f, flY + s(0.78f), 0f)
            listOf(-s(0.62f) to 0f, s(0.62f) to 0f, 0f to -s(0.44f), 0f to s(0.44f)).forEach { (dx, dz) ->
                val backDz = if (dz < 0f) -s(0.19f) else s(0.19f)
                OCube(Size(s(0.42f), s(0.10f), s(0.42f)), woodMat, dx, flY + s(0.44f), dz)
                OCube(Size(s(0.40f), s(0.42f), p), sofaMat, dx, flY + s(0.65f), dz + backDz)
            }
        }
        RoomItem.DESK -> {
            OCube(Size(s(1.40f), p, s(0.65f)), woodMat, 0f, flY + s(0.74f), 0f)
            OCube(Size(s(1.36f), s(0.72f), s(0.07f)), woodMat, 0f, flY + s(0.36f), -s(0.29f))
        }
        RoomItem.OFFICE_CHAIR -> {
            OCube(Size(s(0.50f), s(0.10f), s(0.50f)), sofaMat, 0f, flY + s(0.44f), 0f)
            OCube(Size(s(0.48f), s(0.50f), s(0.08f)), sofaMat, 0f, flY + s(0.69f), -s(0.21f))
        }
        RoomItem.BOOKSHELF -> {
            OCube(Size(s(0.70f), s(1.80f), s(0.25f)), woodMat, 0f, flY + s(0.90f), 0f)
        }
        RoomItem.COMPUTER -> {
            // Monitor screen + stand, at desk-surface height (drop it onto a desk)
            val deskTop = flY + s(0.76f)
            OCube(Size(s(0.50f), s(0.32f), s(0.04f)), screenMat, 0f, deskTop + s(0.16f), s(0.08f))
            OCube(Size(s(0.08f), s(0.10f), s(0.14f)), applMat, 0f, deskTop + s(0.05f), s(0.08f))
        }
        RoomItem.WASHER -> {
            // Front-loader: white body, steel top, teal door ring around a dark drum
            // window, dark control strip. Front faces -Z; OCube honors rotate/flip.
            OCube(Size(s(0.60f), s(0.85f), s(0.60f)), fixMat, 0f, flY + s(0.425f), 0f)
            OCube(Size(s(0.62f), s(0.05f), s(0.62f)), steelMat, 0f, flY + s(0.875f), 0f)
            OCube(Size(s(0.50f), s(0.10f), p), screenMat, 0f, flY + s(0.76f), -s(0.295f))
            OCube(Size(s(0.42f), s(0.42f), p), dishwasherMat, 0f, flY + s(0.42f), -s(0.30f))
            OCube(Size(s(0.30f), s(0.30f), p), screenMat, 0f, flY + s(0.42f), -s(0.315f))
        }
        RoomItem.DRYER -> {
            // Matches the washer's build but with a steel door ring so the pair reads as a
            // set without being identical. Front faces -Z; OCube honors rotate/flip.
            OCube(Size(s(0.60f), s(0.85f), s(0.60f)), fixMat, 0f, flY + s(0.425f), 0f)
            OCube(Size(s(0.62f), s(0.05f), s(0.62f)), steelMat, 0f, flY + s(0.875f), 0f)
            OCube(Size(s(0.50f), s(0.10f), p), screenMat, 0f, flY + s(0.76f), -s(0.295f))
            OCube(Size(s(0.44f), s(0.44f), p), steelMat, 0f, flY + s(0.42f), -s(0.30f))
            OCube(Size(s(0.32f), s(0.32f), p), screenMat, 0f, flY + s(0.42f), -s(0.315f))
        }
        RoomItem.UTILITY_SINK -> {
            CubeNode(size = Size(s(0.55f), s(0.80f), s(0.50f)), materialInstance = fixMat,
                position = Position(cx, flY + s(0.40f), cz),
                apply = commonApply) {}
            CubeNode(size = Size(s(0.42f), p, s(0.38f)), materialInstance = screenMat,
                position = Position(cx, flY + s(0.82f), cz),
                apply = commonApply) {}
        }
        RoomItem.TREADMILL -> {
            // Running deck
            CubeNode(size = Size(s(0.60f), s(0.14f), s(1.70f)), materialInstance = applMat,
                position = Position(cx, flY + s(0.07f), cz),
                apply = commonApply) {}
            // Left upright
            CubeNode(size = Size(s(0.05f), s(1.10f), s(0.05f)), materialInstance = applMat,
                position = Position(cx - s(0.24f), flY + s(0.62f), cz - s(0.58f)),
                apply = commonApply) {}
            // Right upright
            CubeNode(size = Size(s(0.05f), s(1.10f), s(0.05f)), materialInstance = applMat,
                position = Position(cx + s(0.24f), flY + s(0.62f), cz - s(0.58f)),
                apply = commonApply) {}
            // Handlebar crossbar
            CubeNode(size = Size(s(0.52f), s(0.07f), s(0.28f)), materialInstance = applMat,
                position = Position(cx, flY + s(1.15f), cz - s(0.58f)),
                apply = commonApply) {}
            // Console screen
            CubeNode(size = Size(s(0.26f), s(0.18f), p), materialInstance = screenMat,
                position = Position(cx, flY + s(1.20f), cz - s(0.58f)),
                apply = commonApply) {}
        }
        RoomItem.EXERCISE_BIKE -> {
            // Frame/body
            CubeNode(size = Size(s(0.44f), s(0.82f), s(0.85f)), materialInstance = applMat,
                position = Position(cx, flY + s(0.41f), cz),
                apply = commonApply) {}
            // Seat
            CubeNode(size = Size(s(0.28f), p, s(0.28f)), materialInstance = sofaMat,
                position = Position(cx, flY + s(0.90f), cz - s(0.10f)),
                apply = commonApply) {}
            // Handlebars
            CubeNode(size = Size(s(0.42f), p, s(0.12f)), materialInstance = applMat,
                position = Position(cx, flY + s(1.05f), cz + s(0.28f)),
                apply = commonApply) {}
        }
        RoomItem.WEIGHTS -> {
            // Weight rack frame
            CubeNode(size = Size(s(1.20f), s(1.55f), s(0.45f)), materialInstance = applMat,
                position = Position(cx, flY + s(0.775f), cz),
                apply = commonApply) {}
            // Dumbbell rows (3 shelves shown as dark bars)
            CubeNode(size = Size(s(1.10f), s(0.06f), s(0.40f)), materialInstance = screenMat,
                position = Position(cx, flY + s(0.38f), cz),
                apply = commonApply) {}
            CubeNode(size = Size(s(1.10f), s(0.06f), s(0.40f)), materialInstance = screenMat,
                position = Position(cx, flY + s(0.76f), cz),
                apply = commonApply) {}
            CubeNode(size = Size(s(1.10f), s(0.06f), s(0.40f)), materialInstance = screenMat,
                position = Position(cx, flY + s(1.14f), cz),
                apply = commonApply) {}
        }
        RoomItem.WATER_HEATER -> {
            // Steel tank with a white dome, copper pipe stub, and a blue control panel.
            // Front faces -Z; OCube honors rotate/flip.
            OCube(Size(s(0.55f), s(1.40f), s(0.55f)), steelMat, 0f, flY + s(0.70f), 0f)
            OCube(Size(s(0.30f), s(0.16f), s(0.30f)), fixMat, 0f, flY + s(1.48f), 0f)
            OCube(Size(s(0.06f), s(0.20f), s(0.06f)), woodMat, -s(0.12f), flY + s(1.66f), 0f)
            OCube(Size(s(0.18f), s(0.22f), p), sinkMat, 0f, flY + s(0.55f), -s(0.29f))
        }
        // Vehicles never render as an in-room item — HouseScene's vehicle nodes draw them
        // parked at the exterior garage/driveway.
        RoomItem.CAR, RoomItem.BOAT, RoomItem.MOTORCYCLE -> {}
        RoomItem.TREASURE -> {
            // Treasure chest: wooden trunk + slightly overhanging lid + dark strap and clasp.
            OCube(Size(s(0.60f), s(0.32f), s(0.40f)), woodMat, 0f, flY + s(0.16f), 0f)
            OCube(Size(s(0.64f), s(0.12f), s(0.44f)), woodMat, 0f, flY + s(0.38f), 0f)
            OCube(Size(s(0.08f), s(0.46f), s(0.42f)), screenMat, 0f, flY + s(0.23f), 0f)
            OCube(Size(s(0.10f), s(0.10f), p), fixMat, 0f, flY + s(0.30f), s(0.21f))
        }
        RoomItem.GARAGE_DOOR -> {
            // A door is part of a wall, not furniture standing near one — so it anchors to the
            // room's front (+Z, the home's own front) exactly like the kitchen's counter run
            // anchors to its walls, rather than to the zFrac position every freestanding item
            // uses. At zFrac = 0.75 it rendered at 0.29 × depth, which in a 6 m garage left it
            // hanging a metre short of the wall in mid-air. It's the one default that can't be
            // dragged (see RoomScene's MOVABLE_ITEMS) precisely because it belongs to the wall.
            val doorT  = s(0.06f)
            val doorCz = if (zoneD > 0f) zoneCz + zoneD / 2f - WALL_T / 2f - doorT / 2f else cz
            // Main door panel
            CubeNode(size = Size(s(2.40f), s(2.10f), doorT), materialInstance = applMat,
                position = Position(cx, flY + s(1.05f), doorCz),
                apply = commonApply) {}
            // Horizontal panel seams (4 sections)
            CubeNode(size = Size(s(2.40f), s(0.04f), doorT), materialInstance = screenMat,
                position = Position(cx, flY + s(0.52f), doorCz),
                apply = commonApply) {}
            CubeNode(size = Size(s(2.40f), s(0.04f), doorT), materialInstance = screenMat,
                position = Position(cx, flY + s(1.04f), doorCz),
                apply = commonApply) {}
            CubeNode(size = Size(s(2.40f), s(0.04f), doorT), materialInstance = screenMat,
                position = Position(cx, flY + s(1.56f), doorCz),
                apply = commonApply) {}
        }
    }
}
