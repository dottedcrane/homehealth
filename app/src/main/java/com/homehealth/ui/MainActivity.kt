package com.homehealth.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homehealth.billing.BillingManager
import com.homehealth.db.HomeStateSerialization
import com.homehealth.db.UserHomeEntity
import com.homehealth.model.*
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.utils.screenToWorld
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val billingManager = BillingManager.getInstance(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeHealthScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync the Premium entitlement: picks up purchases finished outside this
        // session (another device, a pending payment completing) and refunds, without
        // requiring an app restart.
        BillingManager.getInstance(this).queryOwnedPurchases()
    }
}

// ── Model helpers ─────────────────────────────────────────────────────────────

private val HomeStyle.icon: ImageVector get() = when (this) {
    HomeStyle.HOUSE     -> Icons.Outlined.Home
    HomeStyle.CONDO     -> Icons.Outlined.Business
    HomeStyle.TOWNHOUSE -> Icons.Outlined.HolidayVillage
}

private val HomeStyle.iconColor: Color get() = when (this) {
    HomeStyle.HOUSE     -> Color(0xFFE65100)   // deep orange  — brick/warmth
    HomeStyle.CONDO     -> Color(0xFF0277BD)   // light blue   — glass/steel
    HomeStyle.TOWNHOUSE -> Color(0xFF2E7D32)   // deep green   — neighbourhood
}

private val FloorCount.shortLabel get() = when (this) {
    FloorCount.ONE        -> "1"
    FloorCount.TWO        -> "2"
    FloorCount.THREE_PLUS -> "3+"
}

enum class PanePosition { RIGHT, BOTTOM }
enum class ViewState    { EXTERIOR, FLOOR_PLAN, ROOM, GARAGE, NEIGHBORHOOD }

// Room-view camera presets. DOORWAY stands at eye level in the door (immersive but framed
// by the walls); CORNER floats above the door-side corner so the whole room fits in view
// (no ceiling is rendered, so looking down over the walls works); OVERHEAD looks straight
// down like a one-room floor plan.
enum class RoomCamAngle(val label: String) {
    DOORWAY("Eye"), CORNER("Corner"), OVERHEAD("Top")
}

/**
 * Returns a unit vector pointing FROM the room centre TOWARD the wall that faces the hallway.
 * When the door is on the +X side, for example, this returns Float3(1,0,0) and the camera
 * is placed at (+hw*0.85, eyeY, 0) looking toward the back wall (–X side).
 * Falls back to the front (+Z) wall when no HALLWAY zone exists on this floor.
 */
private fun doorSide(zone: RoomZone, layout: FloorLayout, floor: Int): Float3 {
    val hallway = layout.toZones(floor).find { it.type == RoomType.HALLWAY }
        ?: return Float3(0f, 0f, 1f)
    val dx = hallway.cx - zone.cx
    val dz = hallway.cz - zone.cz
    return if (abs(dx) >= abs(dz))
        Float3(if (dx > 0f) 1f else -1f, 0f, 0f)
    else
        Float3(0f, 0f, if (dz > 0f) 1f else -1f)
}

/**
 * If [zone] owns a slice of the exterior sliding glass door (see HouseScene's sdFace/sd1CX/sd2CX/
 * sdD), returns which side it's on and the slider's span in the zone's own LOCAL coordinates
 * (offset from zone centre, clamped to the zone's own wall segment). Mirrors HouseScene's
 * geometry so RoomScene shows the same glass opening instead of a plain exterior wall.
 */
private fun exteriorSliderFor(
    zone: RoomZone,
    layout: FloorLayout,
    placedDecks: List<PlacedDeck>,
    floor: Int,
): Pair<FeatureSide, ClosedFloatingPointRange<Float>>? {
    if (floor != 0) return null
    val w = layout.totalW
    val d = layout.totalD
    val sdFace = placedDecks.firstOrNull()?.side ?: FeatureSide.BACK

    fun overlaps1D(aMin: Float, aMax: Float, bMin: Float, bMax: Float) = aMin < bMax && aMax > bMin

    return when (sdFace) {
        FeatureSide.BACK -> {
            if (zone.zMin > -d / 2f + 0.05f) return null
            val lrZone = layout.toZones(0).find { it.type == RoomType.LIVING_ROOM }
            val mbZone = layout.toZones(0).filter { it.type == RoomType.BEDROOM }.minByOrNull { it.zMin }
            val sd1CX = lrZone?.cx ?: -w / 4f
            val sd1W  = ((lrZone?.w ?: w / 3f) * 0.75f).coerceIn(1.2f, 2.4f)
            val sd2CX = mbZone?.cx ?: w / 4f
            val sd2W  = ((mbZone?.w ?: w / 3f) * 0.75f).coerceIn(1.2f, 2.4f)
            val hit = listOf(sd1CX - sd1W / 2f to sd1CX + sd1W / 2f, sd2CX - sd2W / 2f to sd2CX + sd2W / 2f)
                .firstOrNull { (lo, hi) -> overlaps1D(zone.xMin, zone.xMax, lo, hi) } ?: return null
            val lo = hit.first.coerceIn(zone.xMin, zone.xMax) - zone.cx
            val hi = hit.second.coerceIn(zone.xMin, zone.xMax) - zone.cx
            FeatureSide.BACK to (lo..hi)
        }
        FeatureSide.LEFT, FeatureSide.RIGHT -> {
            val onThisSide = if (sdFace == FeatureSide.LEFT) zone.xMin <= -w / 2f + 0.05f else zone.xMax >= w / 2f - 0.05f
            if (!onThisSide) return null
            val hwZone = layout.toZones(0).find { it.type == RoomType.HALLWAY }
            val hwCz   = hwZone?.cz ?: 0f
            val sdD    = (d * 0.36f).coerceIn(1.2f, 2.2f)
            if (!overlaps1D(zone.zMin, zone.zMax, hwCz - sdD / 2f, hwCz + sdD / 2f)) return null
            val lo = (hwCz - sdD / 2f).coerceIn(zone.zMin, zone.zMax) - zone.cz
            val hi = (hwCz + sdD / 2f).coerceIn(zone.zMin, zone.zMax) - zone.cz
            sdFace to (lo..hi)
        }
        FeatureSide.FRONT -> null
    }
}

internal val HiddenAsset.icon: ImageVector get() = when (this) {
    HiddenAsset.ROOF              -> Icons.Outlined.Roofing
    HiddenAsset.SIDING            -> Icons.Outlined.Layers
    HiddenAsset.PLUMBING          -> Icons.Outlined.Plumbing
    HiddenAsset.ELECTRICAL_PANEL  -> Icons.Outlined.ElectricalServices
    HiddenAsset.GARAGE_DOOR       -> Icons.Outlined.Garage
    HiddenAsset.SMOKE_CO_DETECTORS -> Icons.Outlined.Sensors
    HiddenAsset.WINDOWS_DOORS     -> Icons.Outlined.SensorDoor
    HiddenAsset.FIRE_EXTINGUISHER -> Icons.Outlined.FireExtinguisher
    HiddenAsset.DECK              -> Icons.Outlined.Deck
    HiddenAsset.LAWN_EQUIPMENT    -> Icons.Outlined.Grass
    HiddenAsset.POOL_EQUIPMENT    -> Icons.Outlined.Pool
    HiddenAsset.BALCONY           -> Icons.Outlined.Balcony
}

internal val HiddenAsset.iconColor: Color get() = when (this) {
    HiddenAsset.ROOF              -> Color(0xFF8D6E63)
    HiddenAsset.SIDING            -> Color(0xFF6D4C41)
    HiddenAsset.PLUMBING          -> Color(0xFF0277BD)
    HiddenAsset.ELECTRICAL_PANEL  -> Color(0xFFF9A825)
    HiddenAsset.GARAGE_DOOR       -> Color(0xFF546E7A)
    HiddenAsset.SMOKE_CO_DETECTORS -> Color(0xFFC62828)
    HiddenAsset.WINDOWS_DOORS     -> Color(0xFF455A64)
    HiddenAsset.FIRE_EXTINGUISHER -> Color(0xFFC62828)
    HiddenAsset.DECK              -> Color(0xFF795548)
    HiddenAsset.LAWN_EQUIPMENT    -> Color(0xFF388E3C)
    HiddenAsset.POOL_EQUIPMENT    -> Color(0xFF0288D1)
    HiddenAsset.BALCONY           -> Color(0xFF8D6E63)
}

internal val HiddenAsset.expectedLifespanYears: Int get() = when (this) {
    HiddenAsset.ROOF              -> 20
    HiddenAsset.SIDING            -> 25
    HiddenAsset.PLUMBING          -> 50
    HiddenAsset.ELECTRICAL_PANEL  -> 30
    HiddenAsset.GARAGE_DOOR       -> 20
    HiddenAsset.SMOKE_CO_DETECTORS -> 10
    HiddenAsset.WINDOWS_DOORS     -> 25
    HiddenAsset.FIRE_EXTINGUISHER -> 12
    HiddenAsset.DECK              -> 20
    HiddenAsset.LAWN_EQUIPMENT    -> 8
    HiddenAsset.POOL_EQUIPMENT    -> 10
    HiddenAsset.BALCONY           -> 25
}

private val HomeFeature.label: String get() = when (this) {
    HomeFeature.GARAGE -> "Garage"
    HomeFeature.YARD   -> "Yard"
    HomeFeature.POOL   -> "Pool"
    HomeFeature.DECK   -> "Deck"
}

private val HomeFeature.icon: ImageVector get() = when (this) {
    HomeFeature.GARAGE -> Icons.Outlined.Garage
    HomeFeature.YARD   -> Icons.Outlined.Park
    HomeFeature.POOL   -> Icons.Outlined.Pool
    HomeFeature.DECK   -> Icons.Outlined.Deck
}

private val HomeFeature.iconColor: Color get() = when (this) {
    HomeFeature.GARAGE -> Color(0xFF546E7A)
    HomeFeature.YARD   -> Color(0xFF388E3C)
    HomeFeature.POOL   -> Color(0xFF0288D1)
    HomeFeature.DECK   -> Color(0xFF795548)
}

private val HomeSystem.label: String get() = when (this) {
    HomeSystem.SOLAR -> "Solar"
    HomeSystem.HVAC  -> "HVAC"
}

private val HomeSystem.icon: ImageVector get() = when (this) {
    HomeSystem.SOLAR -> Icons.Outlined.WbSunny
    HomeSystem.HVAC  -> Icons.Outlined.AcUnit
}

private val HomeSystem.iconColor: Color get() = when (this) {
    HomeSystem.SOLAR -> Color(0xFFF9A825)   // amber — sun
    HomeSystem.HVAC  -> Color(0xFF0288D1)   // blue  — cooling
}

// Yard wraps the whole house — no directional side needed
private val HomeFeature.hasSide get() = this != HomeFeature.YARD

private val HomeFeature.validSides: List<FeatureSide> get() = when (this) {
    HomeFeature.GARAGE -> listOf(FeatureSide.FRONT, FeatureSide.RIGHT, FeatureSide.LEFT)
    HomeFeature.DECK   -> listOf(FeatureSide.BACK,  FeatureSide.RIGHT, FeatureSide.LEFT)
    HomeFeature.POOL   -> listOf(FeatureSide.BACK,  FeatureSide.RIGHT, FeatureSide.LEFT)
    HomeFeature.YARD   -> emptyList()
}

private val HomeFeature.defaultSide: FeatureSide get() = when (this) {
    HomeFeature.GARAGE -> FeatureSide.FRONT
    HomeFeature.DECK   -> FeatureSide.BACK
    HomeFeature.POOL   -> FeatureSide.BACK
    HomeFeature.YARD   -> FeatureSide.FRONT
}

// ── Inside-view extensions ────────────────────────────────────────────────────

internal val RoomType.icon: ImageVector get() = when (this) {
    RoomType.LIVING_ROOM  -> Icons.Outlined.Weekend
    RoomType.KITCHEN      -> Icons.Outlined.Kitchen
    RoomType.DINING_ROOM  -> Icons.Outlined.Restaurant
    RoomType.HALLWAY      -> Icons.Outlined.Straighten
    RoomType.FOYER        -> Icons.Outlined.Home
    RoomType.STAIRCASE    -> Icons.Outlined.ArrowUpward
    RoomType.BEDROOM      -> Icons.Outlined.Bed
    RoomType.BATHROOM     -> Icons.Outlined.Bathtub
    RoomType.LAUNDRY      -> Icons.Outlined.LocalLaundryService
    RoomType.OFFICE       -> Icons.Outlined.Work
    RoomType.PANTRY       -> Icons.Outlined.Kitchen
    RoomType.STORAGE      -> Icons.Outlined.Archive
    RoomType.GYM          -> Icons.Outlined.FitnessCenter
    RoomType.HOME_THEATER -> Icons.Outlined.Tv
    RoomType.POWDER_ROOM  -> Icons.Outlined.Wc
    RoomType.GARAGE -> Icons.Outlined.Garage
}

internal val RoomType.iconColor: Color get() = when (this) {
    RoomType.LIVING_ROOM  -> Color(0xFFE65100)
    RoomType.KITCHEN      -> Color(0xFF1B5E20)
    RoomType.DINING_ROOM  -> Color(0xFF880E4F)
    RoomType.HALLWAY      -> Color(0xFF455A64)
    RoomType.FOYER        -> Color(0xFF8D6E63)
    RoomType.STAIRCASE    -> Color(0xFF607D8B)
    RoomType.BEDROOM      -> Color(0xFF4A148C)
    RoomType.BATHROOM     -> Color(0xFF0D47A1)
    RoomType.LAUNDRY      -> Color(0xFF0277BD)
    RoomType.OFFICE       -> Color(0xFF37474F)
    RoomType.PANTRY       -> Color(0xFFF57F17)
    RoomType.STORAGE      -> Color(0xFF757575)
    RoomType.GYM          -> Color(0xFF00695C)
    RoomType.HOME_THEATER -> Color(0xFF4527A0)
    RoomType.POWDER_ROOM  -> Color(0xFF01579B)
    RoomType.GARAGE -> Color(0xFF546E7A)
}

internal val RoomItem.icon: ImageVector get() = when (this) {
    RoomItem.SOFA           -> Icons.Outlined.Weekend
    RoomItem.COFFEE_TABLE   -> Icons.Outlined.CropSquare
    RoomItem.TV_STAND       -> Icons.Outlined.Tv
    RoomItem.BED            -> Icons.Outlined.Bed
    RoomItem.DRESSER        -> Icons.Outlined.Archive
    RoomItem.NIGHTSTAND     -> Icons.Outlined.Nightlight
    RoomItem.COUNTER        -> Icons.Outlined.Kitchen
    RoomItem.REFRIGERATOR   -> Icons.Outlined.Kitchen
    RoomItem.STOVE          -> Icons.Outlined.LocalFireDepartment
    RoomItem.KITCHEN_SINK   -> Icons.Outlined.Opacity
    RoomItem.DISHWASHER     -> Icons.Outlined.CleaningServices
    RoomItem.MICROWAVE      -> Icons.Outlined.Microwave
    RoomItem.OVEN           -> Icons.Outlined.LocalFireDepartment
    RoomItem.RANGE_HOOD     -> Icons.Outlined.Air
    RoomItem.TOILET         -> Icons.Outlined.Wc
    RoomItem.BATHTUB        -> Icons.Outlined.Bathtub
    RoomItem.VANITY         -> Icons.Outlined.Face
    RoomItem.DINING_TABLE   -> Icons.Outlined.TableRestaurant
    RoomItem.DINING_CHAIRS  -> Icons.Outlined.EventSeat
    RoomItem.DESK           -> Icons.Outlined.Computer
    RoomItem.OFFICE_CHAIR   -> Icons.Outlined.Chair
    RoomItem.BOOKSHELF      -> Icons.Outlined.MenuBook
    RoomItem.COMPUTER       -> Icons.Outlined.DesktopWindows
    RoomItem.WASHER         -> Icons.Outlined.LocalLaundryService
    RoomItem.DRYER          -> Icons.Outlined.LocalLaundryService
    RoomItem.UTILITY_SINK   -> Icons.Outlined.Wc
    RoomItem.TREADMILL      -> Icons.Outlined.DirectionsRun
    RoomItem.EXERCISE_BIKE  -> Icons.Outlined.DirectionsBike
    RoomItem.WEIGHTS        -> Icons.Outlined.FitnessCenter
    RoomItem.WATER_HEATER   -> Icons.Outlined.Opacity
    RoomItem.GARAGE_DOOR    -> Icons.Outlined.Garage
    RoomItem.CAR            -> Icons.Outlined.DirectionsCar
    RoomItem.BOAT           -> Icons.Outlined.DirectionsBoat
    RoomItem.MOTORCYCLE     -> Icons.Outlined.TwoWheeler
    RoomItem.TREASURE       -> Icons.Outlined.Diamond
}

private val FeatureSide.shortLabel get() = when (this) {
    FeatureSide.FRONT -> "F"
    FeatureSide.RIGHT -> "R"
    FeatureSide.BACK  -> "B"
    FeatureSide.LEFT  -> "L"
}

// Kitchen: only one per home. Hallway: only one per floor. GARAGE also needs real width/depth
// (fitsFootprint), not just area — colSpan/rowSpan default to "unbounded" so callers checking
// general room-creation eligibility (not a specific selection) still see every type.
private fun availableRoomTypes(
    layout: FloorLayout,
    floor: Int,
    excludeId: String? = null,
    colSpan: Int = Int.MAX_VALUE,
    rowSpan: Int = Int.MAX_VALUE,
): List<RoomType> {
    val others = if (excludeId != null) layout.rooms.filter { it.id != excludeId } else layout.rooms
    return RoomType.entries.filter { type ->
        type.fitsFootprint(colSpan, rowSpan, layout.cellW, layout.cellD) && when (type) {
            RoomType.KITCHEN -> others.none { it.type == RoomType.KITCHEN }
            RoomType.HALLWAY -> others.none { it.floor == floor && it.type == RoomType.HALLWAY }
            else             -> true
        }
    }
}

private val HomeStyle.isAttached get() = this == HomeStyle.CONDO

// Garage, pool, and deck not available for attached/multi-unit buildings (condos/penthouses).
// YARD's own toggle icon is gone — presence is now purely style-derived (see
// HouseSceneGeometry.yardBounds), so it's never offered as a user-facing toggle at all. DECK's
// toggle is gone too — it's now added via tear-drag from the sidebar tray (DeckTrayIcon), like
// vehicles, since it supports multiple placed instances.
// GARAGE is HOUSE-only: TOWNHOUSE's garage is an interior RoomType.GARAGE room instead (added
// to the floor plan, vehicles parked there — see RoomScene's GARAGE branch), and CONDO has
// neither (its vehicles belong in the neighborhood parking lot).
private fun availableFeatures(style: HomeStyle): List<HomeFeature> =
    HomeFeature.entries.filter { f ->
        when (f) {
            HomeFeature.GARAGE -> style == HomeStyle.HOUSE
            HomeFeature.POOL   -> !style.isAttached
            HomeFeature.DECK   -> false
            HomeFeature.YARD   -> false
            else               -> true
        }
    }

// Solar panels are not applicable to condos or apartments
private fun availableSystems(style: HomeStyle): List<HomeSystem> =
    HomeSystem.entries.filter { s ->
        s != HomeSystem.SOLAR || !style.isAttached
    }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun HomeHealthScreen() {
    val context = LocalContext.current
    val maint: MaintenanceViewModel = viewModel()

    var showWelcomeDialog by remember { mutableStateOf(false) }
    // Check if welcome has been shown.
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) {
        if (!sharedPrefs.getBoolean("welcome_shown", false)) {
            showWelcomeDialog = true
        }
    }

    var homeStyle         by remember { mutableStateOf(ModelPresets.house.style) }
    var floorLayout       by remember { mutableStateOf(FloorLayout()) }
    var currentFloor      by remember { mutableStateOf(0) }
    var featurePlacements by remember { mutableStateOf(emptyMap<HomeFeature, FeatureSide>()) }
    // Live drag offset (dx, dz) for a freely-placed garage/deck/pool, relative to its default
    // anchor for the current side — same shape/precedent as itemOffsets, kept separate from
    // featurePlacements so "which side" and "how far dragged" stay independent (HouseScene.kt's
    // FeaturesOnYard).
    var featureOffsets    by remember { mutableStateOf(emptyMap<HomeFeature, Pair<Float, Float>>()) }
    // Placed decks — unlike garage/pool (one slot each), multiple are allowed: added via
    // tear-drag from the sidebar tray just like vehicles, each independently wall-snapped
    // and draggable (HouseScene.kt's FeaturesOnYard).
    var placedDecks       by remember { mutableStateOf(emptyList<PlacedDeck>()) }
    var homeSystems       by remember { mutableStateOf(emptySet<HomeSystem>()) }
    var previewedNeighbor by remember { mutableStateOf<NeighborHome?>(null) }
    val previewLayout     = remember(previewedNeighbor) {
        val n = previewedNeighbor
        if (n != null) buildPreviewLayout(n) else FloorLayout()
    }
    // Neighbor previews render furnished too — same seeding a claim would apply.
    val previewFurniture  = remember(previewLayout) { previewLayout.defaultFurniturePlacedItems() }
    var showInfo              by remember { mutableStateOf(false) }
    var panePosition          by remember { mutableStateOf(PanePosition.RIGHT) }
    var viewState             by remember { mutableStateOf(ViewState.NEIGHBORHOOD) }
    var activeZone            by remember { mutableStateOf<RoomZone?>(null) }
    var activePlacement       by remember { mutableStateOf<RoomPlacement?>(null) }
    var removedInstances      by remember { mutableStateOf(emptySet<String>()) }
    var itemOffsets           by remember { mutableStateOf(emptyMap<RoomItem, Pair<Float, Float>>()) }
    // Furniture exists only as placed items (dragged in from the room pane's tray) — the
    // unclaimed starter home is seeded furnished; a DB load below replaces this wholesale.
    var placedItems           by remember { mutableStateOf(floorLayout.defaultFurniturePlacedItems()) }
    // Furniture drag-and-drop: the tray item under the finger plus its current root-space
    // position (drives the ghost icon), the 3D scene's bounds in root coordinates (turns
    // the drop pixel into a camera ray), and the placed piece whose rotate/flip bar is open.
    var trayDrag              by remember { mutableStateOf<Pair<RoomItem, Offset>?>(null) }
    // Same tear-drag pattern as trayDrag, for the deck tray icon — a separate state since
    // decks aren't a RoomItem (trayDrag's type).
    var deckTrayDrag          by remember { mutableStateOf<Offset?>(null) }
    var sceneBounds           by remember { mutableStateOf<Rect?>(null) }
    var selectedFurnitureId   by remember { mutableStateOf<String?>(null) }
    // A selected item's actions bar must not survive a view change — otherwise a vehicle
    // selected in Exterior could resurface (wrongly) after navigating into a Room, since
    // ids are unique across the whole placedItems list regardless of which view a tap came
    // from. Room's own onBack already clears this explicitly for its one transition; this
    // catches every other one (Exterior/Garage/Neighborhood) in a single place.
    LaunchedEffect(viewState) { selectedFurnitureId = null }
    // Selected garage/deck/pool — drives FeatureActionsDialog (Remove). Same
    // don't-survive-a-view-change rule as selectedFurnitureId above.
    var selectedFeature by remember { mutableStateOf<HomeFeature?>(null) }
    LaunchedEffect(viewState) { selectedFeature = null }
    // Selected placed-deck instance — same actions-bar (Remove) pattern, per-instance.
    var selectedDeckId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewState) { selectedDeckId = null }
    // Per-scene camera settings — independent so tuning one scene's camera never affects another.
    var exteriorCamSettings     by remember { mutableStateOf(CameraSettings()) }
    var floorPlanCamSettings    by remember { mutableStateOf(CameraSettings(zoomSpeed = 0.15f)) }
    var roomCamSettings         by remember { mutableStateOf(CameraSettings()) }
    var roomCamAngle            by remember { mutableStateOf(RoomCamAngle.CORNER) }
    var garageCamSettings       by remember { mutableStateOf(CameraSettings()) }
    var neighborhoodCamSettings by remember { mutableStateOf(CameraSettings()) }
    // Where the garage scene was entered from (exterior or floor plan) — Back returns there.
    var garageReturn            by remember { mutableStateOf(ViewState.EXTERIOR) }
    // Maintenance dialogs
    var pendingClaimNeighbor  by remember { mutableStateOf<NeighborHome?>(null) }
    var showHomeDatesDialog   by remember { mutableStateOf(false) }
    var showMaintenanceHub    by remember { mutableStateOf(false) }
    // First-claim celebration: points the brand-new owner at the Maintenance hub, the
    // whole reason to claim. Fires on the savedHome null→non-null transition only.
    var showFirstClaimHint    by remember { mutableStateOf(false) }
    var maintenanceTarget     by remember { mutableStateOf<MaintenanceTarget?>(null) }
    var pendingDocItemKey     by remember { mutableStateOf("") }
    var homeRestoredFromDb    by remember { mutableStateOf(false) }
    // Existing dialogs
    var selectedCells     by remember { mutableStateOf<Set<Pair<Int, Int>>>(emptySet()) }
    // Floor-plan pencil mode: while ON, scene taps EDIT the plan (room options, wall modes,
    // tile selection) and the grid/floor controls card is open; while OFF (default), a tap
    // on a room simply walks into it. Long-press always opens the room options.
    var floorPlanEditMode by remember { mutableStateOf(false) }
    var showRoomTypePicker by remember { mutableStateOf(false) }
    var editingPlacement  by remember { mutableStateOf<RoomPlacement?>(null) }
    var tappedWall        by remember { mutableStateOf<Pair<WallKey, WallMode>?>(null) }

    val savedHome       by maint.savedHome.collectAsStateWithLifecycle()
    val isAdFree        by BillingManager.getInstance(context).isAdFree.collectAsStateWithLifecycle()
    val premiumPrice    by BillingManager.getInstance(context).formattedPrice.collectAsStateWithLifecycle()
    val applianceRecs   by maint.applianceRecords.collectAsStateWithLifecycle()
    val allDocuments    by maint.documents.collectAsStateWithLifecycle()
    val taskRecords     by maint.taskRecords.collectAsStateWithLifecycle()
    val proContacts     by maint.contacts.collectAsStateWithLifecycle()
    val backupResult    by maint.backupResult.collectAsStateWithLifecycle()
    val restoreResult   by maint.restoreResult.collectAsStateWithLifecycle()
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // Restore claimed home from DB on first load
    LaunchedEffect(savedHome) {
        val saved = savedHome
        if (saved != null && !homeRestoredFromDb) {
            homeRestoredFromDb = true
            homeStyle         = HomeStyle.entries.find { it.name == saved.homeStyle } ?: HomeStyle.HOUSE
            featurePlacements = HomeStateSerialization.deserializeFeaturePlacements(saved.featurePlacementsJson)
            featureOffsets    = HomeStateSerialization.deserializeFeatureOffsets(saved.featureOffsetsJson)
            placedDecks       = HomeStateSerialization.deserializePlacedDecks(saved.placedDecksJson)
            homeSystems       = HomeStateSerialization.deserializeHomeSystems(saved.homeSystemsJson)
            floorLayout       = HomeStateSerialization.deserializeFloorLayout(saved.floorLayoutJson)
            itemOffsets       = HomeStateSerialization.deserializeItemOffsets(saved.itemOffsetsJson)
            placedItems       = HomeStateSerialization.deserializePlacedItems(saved.placedItemsJson)
            removedInstances  = HomeStateSerialization.deserializeRemovedInstances(saved.removedInstancesJson)
            // One-time migration: decks used to be a single featurePlacements slot; they now
            // support multiple instances in their own list. Convert a pre-existing slot into
            // the new list once (only if placedDecksJson hadn't been written yet), preserving
            // its side and drag offset, then drop the stale slot so it stops being persisted.
            featurePlacements[HomeFeature.DECK]?.let { oldSide ->
                if (placedDecks.isEmpty()) {
                    val oldAlong = featureOffsets[HomeFeature.DECK]?.first ?: 0f
                    placedDecks = listOf(PlacedDeck(side = oldSide, along = oldAlong))
                }
                featurePlacements = featurePlacements - HomeFeature.DECK
                featureOffsets = featureOffsets - HomeFeature.DECK
            }
            // One-time migration (pre-v3 saves): furniture used to auto-populate rooms as
            // toggleable defaults; it now exists only as drag-and-drop placed items. Convert
            // what this save had — skipping pieces marked "not in my home" and keeping their
            // drag offsets — exactly once; the next autosave stamps the current version.
            val savedVersion = HomeStateSerialization.itemOffsetsVersion(saved.itemOffsetsJson)
            if (savedVersion < 3) {
                placedItems = placedItems + floorLayout.defaultFurniturePlacedItems(
                    removedInstances, itemOffsets, includeItem = { it.isFurniture })
                itemOffsets = itemOffsets.filterKeys { !it.isFurniture }
            }
            // v4: portable appliances — washer/dryer/water heater AND the whole kitchen
            // (counter segments, stove, sink, dishwasher, microwave, oven, hood, fridge) —
            // become placed items, gaining the rotate/flip actions bar. Their maintenance
            // records and documents follow them: the old default instance key
            // ("$repId:WASHER") is re-keyed in the DB to the new placed key ("placed:$id").
            if (savedVersion < 4) {
                val seeded = floorLayout.defaultFurniturePlacedItems(
                    removedInstances, itemOffsets, includeItem = { it.isPortable })
                placedItems = placedItems + seeded
                itemOffsets = itemOffsets.filterKeys { !it.isPortable }
                maint.migrateItemKeys(seeded.associate { pi ->
                    "${pi.placementId}:${pi.item.name}" to "placed:${pi.id}"
                })
            }
            // v5: the seeded kitchen was re-arranged into one flush left-wall run (fridge,
            // counter, stove with the oven under its cooktop, counter, sink, dishwasher —
            // see kitchenRunSlots). Snap each kitchen's pieces onto the run's slots,
            // keeping their placed ids (and thus their maintenance records); duplicates,
            // extra counters, and out-of-kitchen copies stay put.
            if (savedVersion < 5) {
                placedItems = floorLayout.relocateKitchenAppliancesToLeftWall(placedItems)
            }
            // v5 (part 2): the bathroom fixtures (toilet, bathtub, vanity) joined the
            // portables — convert them to placed items exactly as v4 did for the kitchen,
            // re-keying their records. Only for saves at exactly v4: older saves already
            // received them through the v4 block above (fixtures are portable now), and
            // the retired utility sink simply stops rendering.
            if (savedVersion == 4) {
                val fixtures = floorLayout.defaultFurniturePlacedItems(
                    removedInstances, itemOffsets,
                    includeItem = { it.room == RoomType.BATHROOM && it.isPortable })
                placedItems = placedItems + fixtures
                itemOffsets = itemOffsets.filterKeys { it.room != RoomType.BATHROOM }
                maint.migrateItemKeys(fixtures.associate { pi ->
                    "${pi.placementId}:${pi.item.name}" to "placed:${pi.id}"
                })
            }
            // v5 (part 3): the water heater's home room moved from storage to the
            // laundry. Re-anchor unmoved storage-room water heaters onto a laundry
            // placement (same placed id, records intact); user-moved ones — and homes
            // with no laundry room — keep theirs exactly where it stands.
            if (savedVersion < 5) {
                val laundryRepId = (0..floorLayout.maxFloor()).firstNotNullOfOrNull { f ->
                    floorLayout.toMergedZones(f).firstOrNull { it.type == RoomType.LAUNDRY }
                        ?.let { z -> floorLayout.placementIdsInZone(z, f).minOrNull() }
                }
                val storageIds = floorLayout.rooms
                    .filter { it.type == RoomType.STORAGE }.map { it.id }.toSet()
                if (laundryRepId != null) placedItems = placedItems.map { pi ->
                    if (pi.item == RoomItem.WATER_HEATER && pi.placementId in storageIds &&
                        pi.dx == 0f && pi.dz == 0f) pi.copy(placementId = laundryRepId)
                    else pi
                }
            }
            // v6: gym equipment (treadmill/bike/weights) joined the portables — the last
            // category still sharing the flat itemOffsets map, so a second gym's equipment
            // moved in lockstep with the first's. Convert exactly as v4 did.
            if (savedVersion < 6) {
                val seeded = floorLayout.defaultFurniturePlacedItems(
                    removedInstances, itemOffsets, includeItem = { it.room == RoomType.GYM })
                placedItems = placedItems + seeded
                itemOffsets = itemOffsets.filterKeys { it.room != RoomType.GYM }
                maint.migrateItemKeys(seeded.associate { pi ->
                    "${pi.placementId}:${pi.item.name}" to "placed:${pi.id}"
                })
            }
            // One-time migration: TOWNHOUSE used to share HOUSE's exterior garage/driveway;
            // it now parks vehicles in an interior GARAGE room instead. Seed that room if
            // this save predates it, and drop the stale exterior placement so it stops
            // rendering an unreachable garage structure.
            if (homeStyle == HomeStyle.TOWNHOUSE) {
                floorLayout = floorLayout.withGarageRoom()
                if (HomeFeature.GARAGE in featurePlacements) {
                    featurePlacements = featurePlacements - HomeFeature.GARAGE
                    featureOffsets = featureOffsets - HomeFeature.GARAGE
                }
            }
            // De-overlap stacked vehicles: before the single-slot parking rule, every drop
            // near the garage snapped to the same (0,0) anchor, hiding cars inside each
            // other ("first car vanished"). Keep the first anchored car parked and fan the
            // rest onto free driveway slots; the next autosave persists the cleanup. Also
            // re-settles a TOWNHOUSE's fleet onto its (possibly just-migrated) garage room.
            val vehicles = placedItems.filter { it.item.isVehicle }
            if (vehicles.isNotEmpty()) {
                val lot = if (homeStyle == HomeStyle.TOWNHOUSE) {
                    CarLotGeometry.townhouseGarageSceneLot(floorLayout)
                        ?: CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, null)
                } else {
                    CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE])
                }
                val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, lot)
                if (settled.zip(vehicles).any { (a, b) -> a.dx != b.dx || a.dz != b.dz }) {
                    val byId = settled.associateBy { it.id }
                    placedItems = placedItems.map { byId[it.id] ?: it }
                }
            }
            viewState         = ViewState.EXTERIOR
        }
    }

    // Keeps parked vehicles valid (no overlap, no clipping into a wall) reactively, rather
    // than relying on every individual mutation site to remember a manual resettle call —
    // that's exactly the gap that let dragging the garage (onFeatureMoved) skip it while
    // adding/removing the garage and claiming a new model didn't. A vehicle's dx/dz is an
    // anchor-relative offset that's only valid against the lot it was computed against, and
    // the lot's shape changes whenever the garage moves/is added/removed, the floor plan is
    // resized (totalW/totalD feed the "no garage" apron lot too), or the home style changes.
    // settleVehiclesOntoLot is a no-op for anything already valid, so this fires harmlessly
    // often; the resulting placedItems change flows into the autosave effect below normally.
    LaunchedEffect(floorLayout, featurePlacements[HomeFeature.GARAGE], featureOffsets[HomeFeature.GARAGE], homeStyle) {
        val vehicles = placedItems.filter { it.item.isVehicle }
        if (vehicles.isEmpty()) return@LaunchedEffect
        val lot = if (homeStyle == HomeStyle.TOWNHOUSE) {
            CarLotGeometry.townhouseCarLot(floorLayout) ?: return@LaunchedEffect
        } else {
            val side  = featurePlacements[HomeFeature.GARAGE]
            val along = featureOffsets[HomeFeature.GARAGE]?.first ?: 0f
            val (gdx, gdz) = if (side == FeatureSide.FRONT) along to 0f else 0f to along
            CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, side, gdx, gdz)
        }
        val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, lot)
        if (settled.zip(vehicles).any { (a, b) -> a.dx != b.dx || a.dz != b.dz }) {
            val byId = settled.associateBy { it.id }
            placedItems = placedItems.map { byId[it.id] ?: it }
        }
    }

    // Autosave — the ONE persistence path for every home edit made after the initial claim:
    // floor-plan changes (room type/add/remove, walls, floors), features/systems, furniture
    // drags, added copies, and "not in my home" removals. All of those just mutate Compose
    // state; this effect debounces and writes the complete row. Keeping every persisted
    // field in a single write avoids the previous split-path bug where a floor-plan save
    // rebuilt the DB row without the furniture columns and silently wiped them.
    LaunchedEffect(floorLayout, featurePlacements, featureOffsets, placedDecks, homeSystems, homeStyle,
                   itemOffsets, placedItems, removedInstances) {
        delay(400)
        // Read the row AFTER the debounce: claiming a model both mutates this effect's
        // keys and rewrites the row's identity fields (label/dates/neighborKey). Capturing
        // the row before the delay raced that write and clobbered the fresh identity with
        // the previous claim's — the "title shows the last model" bug.
        val saved = savedHome
        if (saved != null) {
            maint.saveHome(
                style             = homeStyle,
                featurePlacements = featurePlacements,
                featureOffsets    = featureOffsets,
                placedDecks       = placedDecks,
                homeSystems       = homeSystems,
                floorLayout       = floorLayout,
                itemOffsets       = itemOffsets,
                placedItems       = placedItems,
                removedInstances  = removedInstances,
                buildYear         = saved.buildYear,
                purchaseYear      = saved.purchaseYear,
                label             = saved.label,
                neighborKey       = saved.neighborKey,
            )
        }
    }

    // Every physical instance of every TRACKED RoomItem in the house — one entry per
    // auto-populated default (one per room of that item's nominal type, unless individually
    // removed) plus one per user-added extra copy — numbered "Fridge 1"/"Fridge 2"/etc.
    // across the WHOLE house when there's more than one, so the tool box can track and open
    // a maintenance card for each specific appliance rather than one shared record per item
    // type. Appliances/fixtures/vehicles are always tracked; furniture (and treasures) only
    // when the user opted in via the track button on its rotate/flip bar (PlacedItem.tracked).
    val instancesByItem = remember(floorLayout, placedItems, removedInstances) {
        fun floorLabelFor(placementId: String): String? =
            floorLayout.rooms.firstOrNull { it.id == placementId }?.let { "F${it.floor + 1}" }

        RoomItem.entries.associateWith { ri ->
            // Vehicles, furniture, and portables have no auto-populated per-room
            // defaults — each is a placed item (an "extra"), so fabricating zone-keyed
            // defaults would invent phantom ones. The retired utility sink no longer
            // renders anywhere, so it gets no fabricated entries either.
            val defaultEntries = if (ri.isVehicle || ri.isFurniture || ri.isPortable ||
                    ri == RoomItem.UTILITY_SINK) emptyList()
                else floorLayout.defaultInstanceKeys(ri.room)
                    .map { repId -> repId to "$repId:${ri.name}" }
                    .filterNot { (_, key) -> key in removedInstances }
            val extras = placedItems.filter { it.item == ri && (!ri.isFurniture || it.tracked) }
            val total = defaultEntries.size + extras.size
            val defaultTargets = defaultEntries.mapIndexed { i, (repId, key) ->
                MaintenanceTarget.Item(
                    ri, key,
                    if (total > 1) "${ri.label} ${i + 1}" else ri.label,
                    floorLabel = floorLabelFor(repId),
                )
            }
            val extraTargets = extras.mapIndexed { i, pi ->
                MaintenanceTarget.Placed(
                    pi.id, ri,
                    if (total > 1) "${ri.label} ${defaultEntries.size + i + 1}" else ri.label,
                    floorLabel = floorLabelFor(pi.placementId),
                )
            }
            defaultTargets + extraTargets
        }.filterValues { it.isNotEmpty() }
    }

    // Document file picker
    val addDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val displayName = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "Document"
            maint.addDocument(pendingDocItemKey, uri.toString(), displayName)
        }
    }

    // Backup ZIP destination picker
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) maint.backup(uri)
    }

    // Restore ZIP source picker — goes through a confirmation dialog before actually
    // restoring, since it overwrites all current app data.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    val homeType = HomeType(homeStyle, HomeSize.MEDIUM, FloorCount.ONE, LayoutType.OPEN_PLAN)

    val engine         = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val mainLightNode  = rememberMainLightNode(engine)
    val fillLightNode  = rememberFillLightNode(engine)
    // Hoisted (instead of SceneView's internal default) so furniture drops can unproject
    // a screen point through the live camera into a floor-plane position.
    val sceneCameraNode = rememberCameraNode(engine)
    // Scaled to the footprint like every other camera below — the old fixed Float3(0f, 8f, 26f)
    // only looked right for whatever size it happened to be tuned against (a default-ish
    // ~14x13m home); anything larger left the camera effectively inside the roof, since
    // distance never grew with the house.
    val exteriorCamera = key(floorLayout.totalW, floorLayout.totalD) {
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val reach   = maxOf(w, d) * 1.6f + 10f
        val elevDeg = 18f
        val elevRad = elevDeg * (PI / 180.0)
        val eY = (reach * sin(elevRad)).toFloat() + 2f
        val eZ = (reach * cos(elevRad)).toFloat()
        rememberConfigurableCameraManipulator(
            settings          = exteriorCamSettings,
            orbitHomePosition = Float3(0f, eY, eZ),
            targetPosition    = Float3(0f, 2f, 0f),
            mapExtent         = reach + 10f,
        )
    }
    // Garage scene — corner-style framing looking at the open door + driveway, scaled to
    // the garage footprint (which follows the house dims).
    val garageCamera = key(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE], homeStyle, floorLayout.rooms) {
        val gLot = if (homeStyle == HomeStyle.TOWNHOUSE)
            CarLotGeometry.townhouseGarageSceneLot(floorLayout)
                ?: CarLotGeometry.garageSceneLot(floorLayout.totalW, floorLayout.totalD, null)
        else
            CarLotGeometry.garageSceneLot(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE])
        val gD   = gLot.doorFaceZ * 2f
        rememberConfigurableCameraManipulator(
            settings          = garageCamSettings,
            orbitHomePosition = Float3(gLot.halfW * 1.6f, 4.5f + gD * 0.3f, gLot.doorFaceZ + 9f),
            targetPosition    = Float3(0f, 0.5f, gLot.doorFaceZ),
            mapExtent         = gLot.farZ + 6f,
        )
    }
    val floorPlanCamera = key(floorLayout.gridCols, floorLayout.gridRows, currentFloor) {
        // Reach tuned to fit the whole footprint in frame; tilt trades straight-down
        // (90°) for a lower, more oblique vantage while keeping that same reach.
        val reach   = maxOf(floorLayout.totalW, floorLayout.totalD) * 1.4f + 2f
        val tiltDeg = 55f
        val tiltRad = tiltDeg * (PI / 180.0)
        val fpY = (reach * sin(tiltRad)).toFloat()
        val fpZ = (reach * cos(tiltRad)).toFloat()
        rememberConfigurableCameraManipulator(
            settings          = floorPlanCamSettings,
            orbitHomePosition = Float3(0f, fpY, fpZ),
            targetPosition    = Float3(0f, 0f, 0f),
            mapExtent         = maxOf(floorLayout.totalW, floorLayout.totalD) + 6f,
        )
    }
    val roomCamera = key(activeZone, currentFloor, roomCamAngle) {
        val az   = activeZone
        val w    = az?.w ?: 3f
        val d    = az?.d ?: 3f
        val hw   = w / 2f
        val hd   = d / 2f
        val door = if (az != null) doorSide(az, floorLayout, currentFloor) else Float3(0f, 0f, 1f)
        val (home, target) = when (roomCamAngle) {
            // Stand at eye level in the doorway (centred on the door wall), look across the room.
            RoomCamAngle.DOORWAY ->
                Float3(door.x * hw * 0.82f, 1.6f, door.z * hd * 0.82f) to
                Float3(-door.x * hw * 0.35f, 1.05f, -door.z * hd * 0.35f)
            // Above the door-side corner, high enough to see over the walls (no ceiling) —
            // pulled out past the room edge and raised with the room size so the whole
            // room stays in frame regardless of its dimensions.
            RoomCamAngle.CORNER -> {
                val cornerX = if (door.x != 0f) door.x * (hw + 1.2f) else hw * 0.9f
                val cornerZ = if (door.z != 0f) door.z * (hd + 1.2f) else hd * 0.9f
                Float3(cornerX, FLOOR_HEIGHT_M + maxOf(w, d) * 0.75f, cornerZ) to
                Float3(0f, 0.3f, 0f)
            }
            // Straight down, one-room floor plan. The slight lateral offset toward the door
            // keeps the view vector off vertical so the orbit up-vector stays well-defined.
            RoomCamAngle.OVERHEAD ->
                Float3(door.x * 0.5f + 0.01f, maxOf(w, d) * 1.5f + 1.5f, door.z * 0.5f + 0.01f) to
                Float3(0f, 0f, 0f)
        }
        rememberConfigurableCameraManipulator(
            settings          = roomCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = maxOf(w, d) + 2f,
        )
    }
    val neighborhoodCamera = rememberConfigurableCameraManipulator(
        settings          = neighborhoodCamSettings,
        orbitHomePosition = Float3(0f, 85f, 65f),
        targetPosition    = Float3(0f, 5f, -28f),
        mapExtent         = 120f,
    )
    val cameraManipulator = when (viewState) {
        ViewState.EXTERIOR     -> exteriorCamera
        ViewState.FLOOR_PLAN   -> floorPlanCamera
        ViewState.ROOM         -> roomCamera
        ViewState.GARAGE       -> garageCamera
        ViewState.NEIGHBORHOOD -> neighborhoodCamera
    }

    if (showInfo) InfoDialog(scene = viewState, onDismiss = { showInfo = false })

    if (showWelcomeDialog) {
        AlertDialog(
            onDismissRequest = { showWelcomeDialog = false; sharedPrefs.edit().putBoolean("welcome_shown", true).apply() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Build, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Welcome to Casa Health")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Casa Health helps you stay on top of your home's upkeep through an interactive 3D model.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "1. Browse the neighborhood and claim the home closest to yours.\n" +
                        "2. Make it yours — rooms, features, systems, and vehicles all stay editable.\n" +
                        "3. Tap the Wrench icon: your 3D model builds your To-Do list and Score tracking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Let's find your home!",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showWelcomeDialog = false
                    sharedPrefs.edit().putBoolean("welcome_shown", true).apply()
                }) { Text("Get Started") }
            }
        )
    }

    // ── Claiming a neighbor model ─────────────────────────────────────────────
    // Re-claiming a different model is a try-it-on activity, not a reset: everything keyed
    // to the household rather than the model — label, dates, vehicles (with their
    // per-instance maintenance records), appliance records, documents, task history,
    // contacts — carries over. Only the model-specific state (furniture seeding, item
    // offsets, removed instances) restarts with the new layout.
    val claimNeighbor: (NeighborHome, String, Int?, Int?) -> Unit = { neighbor, label, buildYear, purchaseYear ->
        val isFirstClaim = savedHome == null
        // TOWNHOUSE parks vehicles in an interior garage room instead of an exterior
        // driveway — ensure that room exists before computing where the fleet re-settles.
        val layout = buildPreviewLayout(neighbor).let {
            if (neighbor.homeType.style == HomeStyle.TOWNHOUSE) it.withGarageRoom() else it
        }
        // Vehicle offsets are anchor-relative, and each model's lot has its own
        // anchor and driveway bounds (the Penthouse's condo apron vs the Classic's
        // right-side garage) — so re-park the carried fleet against the NEW lot:
        // first anchored car keeps the garage slot, the rest fan onto free
        // driveway spots, same settle rule the restore path uses.
        val newLot = if (neighbor.homeType.style == HomeStyle.TOWNHOUSE) {
            CarLotGeometry.townhouseGarageSceneLot(layout)
                ?: CarLotGeometry.carLot(layout.totalW, layout.totalD, null)
        } else {
            CarLotGeometry.carLot(layout.totalW, layout.totalD, neighbor.featurePlacements[HomeFeature.GARAGE])
        }
        val settledVehicles = CarLotGeometry.settleVehiclesOntoLot(placedItems.filter { it.item.isVehicle }, newLot)
        val seededFurniture = layout.defaultFurniturePlacedItems()
        // The user's appliance install-years and documents describe THEIR fridge/washer/
        // furnace, not the model's — re-key them onto the new model's seeded twins (first
        // fridge → first fridge, and so on, in seeding order). Vehicles keep their ids so
        // their records are intact by construction; records with no twin in the new model
        // stay in the DB untouched until the user deletes them.
        val recordMapping = buildMap {
            placedItems.filter { !it.item.isVehicle }.groupBy { it.item }.forEach { (item, olds) ->
                val news = seededFurniture.filter { it.item == item }
                olds.zip(news).forEach { (o, n) -> put("placed:${o.id}", "placed:${n.id}") }
            }
        }
        if (recordMapping.isNotEmpty()) maint.migrateItemKeys(recordMapping)
        val seededItems = seededFurniture + settledVehicles
        homeStyle         = neighbor.homeType.style
        floorLayout       = layout
        featurePlacements = neighbor.featurePlacements
        // Anchor-relative offsets computed against the OLD home's lot/yard are meaningless
        // against the new one's — reset both to the new model's defaults, same reasoning as
        // vehicles' own re-settle just above.
        featureOffsets    = emptyMap()
        placedDecks       = neighbor.placedDecks
        homeSystems       = neighbor.homeSystems
        removedInstances  = emptySet()
        itemOffsets       = emptyMap()
        placedItems       = seededItems
        currentFloor      = 0
        previewedNeighbor = null
        pendingClaimNeighbor = null
        viewState = ViewState.EXTERIOR
        maint.saveHome(
            style             = neighbor.homeType.style,
            featurePlacements = neighbor.featurePlacements,
            featureOffsets    = emptyMap(),
            placedDecks       = neighbor.placedDecks,
            homeSystems       = neighbor.homeSystems,
            floorLayout       = layout,
            itemOffsets       = emptyMap(),
            placedItems       = seededItems,
            removedInstances  = emptySet(),
            buildYear         = buildYear,
            purchaseYear      = purchaseYear,
            label             = label,
            neighborKey       = neighbor.label,
        )
        if (isFirstClaim) showFirstClaimHint = true
    }

    // The claim FORM only ever shows for the first claim — the home info it collects is
    // saved and thereafter lives behind the Home icon (HomeDatesDialog), so later claims
    // reuse it directly and skip the dialog (see the ClaimHomeDialog onClaim call sites).
    pendingClaimNeighbor?.let { neighbor ->
        ClaimDialog(
            neighborLabel = neighbor.label,
            onConfirm     = { label, buildYear, purchaseYear ->
                claimNeighbor(neighbor, label, buildYear, purchaseYear)
            },
            onDismiss     = { pendingClaimNeighbor = null },
        )
    }

    // ── First-claim nudge — connect the claim to the app's core loop ──────────
    if (showFirstClaimHint) {
        AlertDialog(
            onDismissRequest = { showFirstClaimHint = false },
            icon  = { Icon(Icons.Outlined.Build, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Home claimed!") },
            text  = { Text("Your home's maintenance to-do list is ready — seasonal tasks, appliance tracking, and repair guides tailored to this home.") },
            confirmButton = {
                Button(onClick = { showFirstClaimHint = false; showMaintenanceHub = true }) {
                    Text("Open Maintenance")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFirstClaimHint = false }) { Text("Later") }
            },
        )
    }

    // ── Home dates dialog ─────────────────────────────────────────────────────
    if (showHomeDatesDialog) {
        savedHome?.let { entity ->
            // Model summary from the LIVE state, not the entity row — reflects the current
            // claim and any floor-plan/feature/system edits ahead of the debounced autosave.
            val styleLabel = homeStyle.name.lowercase().replaceFirstChar { it.uppercase() }
            val floors     = floorLayout.maxFloor() + 1
            val beds       = floorLayout.rooms.count { it.type == RoomType.BEDROOM }
            val baths      = floorLayout.rooms.count { it.type == RoomType.BATHROOM }
            HomeDatesDialog(
                entity    = entity,
                isPremium = isAdFree,
                specs     = "$styleLabel · $floors fl · $beds bd · $baths ba",
                equipment = (featurePlacements.keys.map { it.label } +
                    (if (placedDecks.isNotEmpty()) listOf(HomeFeature.DECK.label) else emptyList()) +
                    homeSystems.map { it.label })
                    .joinToString(" · "),
                premiumPrice = premiumPrice,
                onSave    = { label, buildYear, purchaseYear ->
                    maint.saveHome(
                        style             = homeStyle,
                        featurePlacements = featurePlacements,
                        featureOffsets    = featureOffsets,
                        placedDecks       = placedDecks,
                        homeSystems       = homeSystems,
                        floorLayout       = floorLayout,
                        itemOffsets       = itemOffsets,
                        placedItems       = placedItems,
                        removedInstances  = removedInstances,
                        buildYear         = buildYear,
                        purchaseYear      = purchaseYear,
                        label             = label,
                        neighborKey       = entity.neighborKey,
                    )
                    showHomeDatesDialog = false
                },
                onBackup  = {
                    if (isAdFree) {
                        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                        backupLauncher.launch("homehealth_backup_$date.zip")
                        showHomeDatesDialog = false
                    } else {
                        val activity = context as? Activity
                        activity?.let { BillingManager.getInstance(it).purchaseRemoveAds(it) }
                    }
                },
                onRestore = {
                    if (isAdFree) {
                        restoreLauncher.launch(arrayOf("application/zip"))
                        showHomeDatesDialog = false
                    } else {
                        val activity = context as? Activity
                        activity?.let { BillingManager.getInstance(it).purchaseRemoveAds(it) }
                    }
                },
                onDismiss = { showHomeDatesDialog = false },
            )
        }
    }

    // ── Backup result dialog ──────────────────────────────────────────────────
    backupResult?.let { result ->
        AlertDialog(
            onDismissRequest = { maint.clearBackupResult() },
            title = {
                when (result) {
                    is BackupResult.Success -> Text("Backup complete")
                    is BackupResult.Failure -> Text("Backup failed")
                }
            },
            text = {
                when (result) {
                    is BackupResult.Success -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (result.copied > 0)
                                Text("${result.copied} attachment(s) saved to device storage.")
                            if (result.skipped.isNotEmpty())
                                Text("Could not read: ${result.skipped.joinToString()}")
                            if (result.copied == 0 && result.skipped.isEmpty())
                                Text("Database backed up successfully.")
                        }
                    }
                    is BackupResult.Failure -> Text(result.message)
                }
            },
            confirmButton = {
                TextButton(onClick = { maint.clearBackupResult() }) { Text("OK") }
            },
        )
    }

    // ── Restore confirmation — restoring overwrites ALL current app data ────────
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Restore app data?") },
            text = {
                Text("This replaces your current home, floor plan, maintenance records, and documents with the contents of this backup. This can't be undone, and the app will restart.")
            },
            confirmButton = {
                Button(onClick = {
                    maint.restore(uri)
                    pendingRestoreUri = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            },
        )
    }

    // ── Restore result dialog ────────────────────────────────────────────────
    restoreResult?.let { result ->
        AlertDialog(
            onDismissRequest = { if (!result.success) maint.clearRestoreResult() },
            title = { Text(if (result.success) "Restore complete" else "Restore failed") },
            text = {
                Text(
                    if (result.success)
                        "Restored${if (result.attachments > 0) " ${result.attachments} attachment(s) and" else ""} your app data. Restart now to see it."
                    else
                        result.message ?: "Something went wrong."
                )
            },
            confirmButton = {
                if (result.success) {
                    Button(onClick = {
                        val ctx = context
                        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        ctx.startActivity(intent)
                        Runtime.getRuntime().exit(0)
                    }) { Text("Restart Now") }
                } else {
                    TextButton(onClick = { maint.clearRestoreResult() }) { Text("OK") }
                }
            },
        )
    }

    // ── Maintenance hub (To-Do + guides) ─────────────────────────────────
    if (showMaintenanceHub) {
        val roomTypes = remember(floorLayout) {
            floorLayout.rooms.map { it.type }.toSet()
        }
        val hasCar        = placedItems.any { it.item == RoomItem.CAR }
        val hasBoat       = placedItems.any { it.item == RoomItem.BOAT }
        val hasMotorcycle = placedItems.any { it.item == RoomItem.MOTORCYCLE }
        val hasGasCar        = placedItems.any { it.item == RoomItem.CAR && !it.electric }
        val hasGasMotorcycle = placedItems.any { it.item == RoomItem.MOTORCYCLE && !it.electric }
        val tasks = remember(homeStyle, featurePlacements, placedDecks, homeSystems, roomTypes, removedInstances,
                             hasCar, hasBoat, hasMotorcycle, hasGasCar, hasGasMotorcycle) {
            com.homehealth.data.HomeTaskList.forHome(
                style      = homeStyle,
                features   = featurePlacements.keys,
                systems    = homeSystems,
                roomTypes  = roomTypes,
                removedInstances = removedInstances,
                hasCar     = hasCar,
                hasBoat    = hasBoat,
                hasMotorcycle = hasMotorcycle,
                hasGasCar        = hasGasCar,
                hasGasMotorcycle = hasGasMotorcycle,
                hasDeck          = placedDecks.isNotEmpty(),
            )
        }
        val recordMap = remember(taskRecords) { taskRecords.associateBy { it.taskKey } }
        MaintenanceHubDialog(
            tasks           = tasks,
            taskRecords     = recordMap,
            isClaimed       = savedHome != null,
            onMarkDone      = { key -> maint.markTaskDone(key) },
            roomTypes       = roomTypes.toList(),
            instancesByItem = instancesByItem,
            applianceRecords = applianceRecs,
            homeYear        = savedHome?.buildYear,
            homeStyle       = homeStyle,
            featurePlacements = featurePlacements,
            hasDeck         = placedDecks.isNotEmpty(),
            homeSystems     = homeSystems,
            removedInstances  = removedInstances,
            onTapInstance   = { target -> maintenanceTarget = target },
            onTapHidden     = { asset -> maintenanceTarget = MaintenanceTarget.Hidden(asset) },
            contacts        = proContacts,
            onSaveContact   = { maint.saveContact(it) },
            onDeleteContact = { maint.deleteContact(it) },
            onDismiss       = { showMaintenanceHub = false },
        )
    }

    // ── Appliance / system maintenance dialog ─────────────────────────────────
    maintenanceTarget?.let { target ->
        val record  = applianceRecs.firstOrNull { it.itemKey == target.key }
        val itemDocs = allDocuments.filter { it.itemKey == target.key }
        ApplianceMaintenanceDialog(
            target          = target,
            record          = record,
            homeYear        = savedHome?.buildYear,
            documents       = itemDocs,
            onSave          = { installYear ->
                maint.upsertAppliance(target.key, installYear)
                maintenanceTarget = null
            },
            onAddDocument   = {
                pendingDocItemKey = target.key
                addDocLauncher.launch(arrayOf("*/*"))
            },
            onDeleteDocument = { id -> maint.deleteDocument(id) },
            onNotInHome     = when (target) {
                is MaintenanceTarget.Item -> {
                    {
                        removedInstances = if (target.instanceKey in removedInstances)
                            removedInstances - target.instanceKey else removedInstances + target.instanceKey
                        maintenanceTarget = null
                    }
                }
                is MaintenanceTarget.Placed -> {
                    {
                        placedItems = placedItems.filterNot { it.id == target.id }
                        maint.removeItemRecords("placed:${target.id}")
                        maintenanceTarget = null
                    }
                }
                is MaintenanceTarget.Hidden -> {
                    {
                        val key = target.asset.name
                        removedInstances = if (key in removedInstances) removedInstances - key else removedInstances + key
                        maintenanceTarget = null
                    }
                }
                else -> null
            },
            isRemoved       = when (target) {
                is MaintenanceTarget.Item   -> target.instanceKey in removedInstances
                is MaintenanceTarget.Hidden -> target.asset.name in removedInstances
                else -> false
            },
            onDismiss       = { maintenanceTarget = null },
        )
    }

    // ── Screen-point → ground-plane unprojection shared by the drop handlers. Anchored on
    // the camera's world position and a screenToWorld point (culling projection, finite far
    // plane). NOT the deprecated screenPointToRay: that unprojects its far endpoint against
    // the RENDERING projection, whose infinite far plane collapses the endpoint to the world
    // origin (w ≈ 0) — every drop ray then bent through the room centre no matter where the
    // finger let go.
    val screenPointOnPlane: (Offset, Rect, Float) -> Float3? = hit@{ rootPos, bounds, planeY ->
        val onRay = sceneCameraNode.view
            ?.screenToWorld(rootPos.x - bounds.left, rootPos.y - bounds.top) ?: return@hit null
        val eye = sceneCameraNode.worldPosition
        val dir = onRay - eye
        if (abs(dir.y) < 1e-5f) return@hit null
        val t = (planeY - eye.y) / dir.y
        if (t <= 0f) return@hit null
        eye + dir * t
    }

    // ── Furniture drop — convert a tray-icon release at [rootPos] (root/window px) into a
    // placed piece on the active room's floor: cast a camera ray through the drop pixel,
    // intersect it with the floor plane (room-local space IS world space in the room view),
    // clamp inside the walls, and store the offset from the item's nominal anchor.
    val dropFurnitureAt: (RoomItem, Offset) -> Unit = drop@{ item, rootPos ->
        val zone   = activeZone ?: return@drop
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.ROOM || !bounds.contains(rootPos)) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, WALL_T / 2f) ?: return@drop
        val margin = 0.4f
        val wx = hit.x.coerceIn(-zone.w / 2f + margin, zone.w / 2f - margin)
        val wz = hit.z.coerceIn(-zone.d / 2f + margin, zone.d / 2f - margin)
        val repId = floorLayout.placementIdsInZone(zone, currentFloor).minOrNull() ?: return@drop
        placedItems = placedItems + PlacedItem(
            placementId = repId,
            item        = item,
            dx          = wx - item.xFrac * zone.w * 0.38f,
            dz          = wz - item.zFrac * zone.d * 0.38f,
        )
    }

    // ── Vehicle drop — same ray-cast, but onto the exterior lot. The offset is stored
    // relative to the garage parked slot (carLot mirrors HouseScene's rendering math). A
    // car dropped on or near the garage parks INSIDE it — the slot holds exactly one
    // vehicle — and everything else lands on the nearest free driveway slot, so vehicles
    // can never stack invisibly on top of each other.
    val dropVehicleAt: (RoomItem, Offset) -> Unit = drop@{ item, rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState !in setOf(ViewState.EXTERIOR, ViewState.GARAGE) ||
            previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        @Suppress("DEPRECATION")
        val ray = sceneCameraNode.screenPointToRay(rootPos.x - bounds.left, rootPos.y - bounds.top)
        val o   = ray.getOrigin()
        val dir = ray.getDirection()
        if (abs(dir.y) < 1e-5f) return@drop
        val t = (0.15f - o.y) / dir.y
        if (t <= 0f) return@drop
        val lot = if (homeStyle == HomeStyle.TOWNHOUSE) {
            (if (viewState == ViewState.GARAGE) CarLotGeometry.townhouseGarageSceneLot(floorLayout)
             else CarLotGeometry.townhouseCarLot(floorLayout)) ?: return@drop
        } else if (viewState == ViewState.GARAGE) {
            CarLotGeometry.garageSceneLot(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE])
        } else CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE])
        var dx = (o.x + dir.x * t) - lot.anchorX
        var dz = (o.z + dir.z * t) - lot.anchorZ
        val vehicles = placedItems.filter { it.item.isVehicle }
        if (item == RoomItem.CAR && abs(dx) < 1.6f && abs(dz) < 1.6f && !CarLotGeometry.anchorOccupied(vehicles)) {
            // Dropped onto the free garage/parked slot — park it inside.
            dx = 0f; dz = 0f
        } else {
            // Otherwise take the free driveway spot nearest the drop. The garage scene's
            // overhead door rolls open on entry, so an anchor-parked car blocks the
            // driveway at its pulled-out position there (exterior doors stay closed).
            val (nx, nz) = CarLotGeometry.freeDrivewaySlot(lot, item, dx, dz,
                CarLotGeometry.occupiedSlots(lot, vehicles,
                    doorFraction  = if (viewState == ViewState.GARAGE) 1f else 0f,
                    garagePresent = viewState == ViewState.GARAGE || homeStyle == HomeStyle.TOWNHOUSE ||
                                    featurePlacements[HomeFeature.GARAGE] != null))
            dx = nx; dz = nz
        }
        // Each new vehicle defaults to a different body color from ones already parked —
        // still user-overridable afterward from its actions bar.
        val colorIndex = placedItems.count { it.item == item } % VEHICLE_BODY_COLORS.size
        placedItems = placedItems + PlacedItem(
            placementId = CAR_PLACEMENT_ID, item = item, dx = dx, dz = dz, colorIndex = colorIndex,
        )
    }

    // ── Room-vehicle drop — same tear-drag as the exterior vehicle tray, but dropped from a
    // GARAGE room's own furniture tray onto its roomGarageLot instead of the driveway. Uses
    // screenPointOnPlane (room-local space) rather than dropVehicleAt's screenPointToRay,
    // matching dropFurnitureAt's approach to the same room view.
    val dropRoomVehicleAt: (RoomItem, Offset) -> Unit = drop@{ item, rootPos ->
        val zone = activeZone ?: return@drop
        if (zone.type != RoomType.GARAGE) return@drop
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.ROOM || !bounds.contains(rootPos)) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, WALL_T / 2f) ?: return@drop
        val lot = CarLotGeometry.roomGarageLot(zone.w, zone.d)
        var dx = hit.x - lot.anchorX
        var dz = hit.z - lot.anchorZ
        val vehicles = placedItems.filter { it.item.isVehicle }
        if (item == RoomItem.CAR && abs(dx) < 1.6f && abs(dz) < 1.6f && !CarLotGeometry.anchorOccupied(vehicles)) {
            dx = 0f; dz = 0f
        } else {
            val (nx, nz) = CarLotGeometry.freeDrivewaySlot(lot, item, dx, dz,
                CarLotGeometry.occupiedSlots(lot, vehicles, doorFraction = 0f, garagePresent = true))
            dx = nx; dz = nz
        }
        val colorIndex = placedItems.count { it.item == item } % VEHICLE_BODY_COLORS.size
        placedItems = placedItems + PlacedItem(
            placementId = CAR_PLACEMENT_ID, item = item, dx = dx, dz = dz, colorIndex = colorIndex,
        )
    }

    // ── Deck drop — tear-drag from the sidebar tray like a vehicle, but onto the yard.
    // Snaps to whichever house wall it lands nearest (HouseSceneGeometry.nearestHouseSide),
    // same wall-snap the garage uses, so it always lands flush against the house.
    val dropDeckAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val side = HouseSceneGeometry.nearestHouseSide(hit.x, hit.z, w, d)
        val raw = HouseSceneGeometry.alongCoordinate(side, hit.x, hit.z)
        val along = HouseSceneGeometry.clampAlongWall(side, raw, w, d, w / 3f)
        placedDecks = placedDecks + PlacedDeck(side = side, along = along)
    }

    // ── Placed-item actions bar — shown for the tapped piece in Room/Exterior/Garage ────────
    if (viewState == ViewState.ROOM || viewState == ViewState.EXTERIOR || viewState == ViewState.GARAGE) {
        placedItems.firstOrNull { it.id == selectedFurnitureId }?.let { sel ->
            fun update(f: (PlacedItem) -> PlacedItem) {
                placedItems = placedItems.map { if (it.id == sel.id) f(it) else it }
            }
            val onRemove: () -> Unit = {
                placedItems = placedItems.filterNot { it.id == sel.id }
                maint.removeItemRecords("placed:${sel.id}")
                selectedFurnitureId = null
            }
            // Same lookup instancesByItem already builds for the hub's Score tab — opening it
            // here (from the Track icon) shows the exact same maintenance card that tapping
            // this instance's row in the hub would.
            val openMaintenanceCard: () -> Unit = {
                maintenanceTarget = instancesByItem[sel.item]
                    ?.firstOrNull { it is MaintenanceTarget.Placed && it.id == sel.id }
            }
            if (sel.item.isVehicle) {
                VehicleActionsDialog(
                    item              = sel.item,
                    colorIndex        = sel.colorIndex,
                    onColorChange     = { i -> update { it.copy(colorIndex = i) } },
                    electric          = sel.electric,
                    onToggleElectric  = { update { it.copy(electric = !it.electric) } },
                    onOpenMaintenance = openMaintenanceCard,
                    onRemove          = onRemove,
                    onDismiss         = { selectedFurnitureId = null },
                )
            } else {
                FurnitureActionsDialog(
                    item      = sel.item,
                    // Appliance copies are always lifespan-tracked — shown lit, and (like
                    // vehicles) the icon opens this instance's maintenance card. Furniture/
                    // treasures opt in via the toggle instead of a maintenance card.
                    tracked   = if (sel.item.isFurniture) sel.tracked else true,
                    onTrack   = if (sel.item.isFurniture) { { update { it.copy(tracked = !it.tracked) } } }
                                else openMaintenanceCard,
                    onRotate  = { update { it.copy(rotDeg = (it.rotDeg + 90) % 360) } },
                    onFlipX   = { update { it.copy(flipX = !it.flipX) } },
                    onFlipZ   = { update { it.copy(flipZ = !it.flipZ) } },
                    onRemove  = onRemove,
                    onDismiss = { selectedFurnitureId = null },
                )
            }
        }
    }

    // ── Feature actions bar — the tapped garage/deck/pool panel (Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR) {
        selectedFeature?.let { f ->
            FeatureActionsDialog(
                feature = f,
                onRemove = {
                    featurePlacements = featurePlacements - f
                    featureOffsets = featureOffsets - f
                    if (f == HomeFeature.GARAGE) {
                        val vehicles = placedItems.filter { it.item.isVehicle }
                        if (vehicles.isNotEmpty()) {
                            val newLot = CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, null)
                            val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, newLot)
                            val byId = settled.associateBy { it.id }
                            placedItems = placedItems.map { byId[it.id] ?: it }
                        }
                    }
                    selectedFeature = null
                },
                onDismiss = { selectedFeature = null },
            )
        }
    }

    // ── Deck actions bar — the tapped deck instance (Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR) {
        selectedDeckId?.let { id ->
            if (placedDecks.any { it.id == id }) {
                FeatureActionsDialog(
                    feature = HomeFeature.DECK,
                    onRemove = {
                        placedDecks = placedDecks.filterNot { it.id == id }
                        selectedDeckId = null
                    },
                    onDismiss = { selectedDeckId = null },
                )
            }
        }
    }

    // Room picker: shown once the user has selected the tiles for the new room's footprint
    if (showRoomTypePicker && selectedCells.isNotEmpty()) {
        val minCol = selectedCells.minOf { it.first }
        val minRow = selectedCells.minOf { it.second }
        val colSpan = selectedCells.maxOf { it.first } - minCol + 1
        val rowSpan = selectedCells.maxOf { it.second } - minRow + 1
        RoomPickerDialog(
            types     = availableRoomTypes(floorLayout, currentFloor, colSpan = colSpan, rowSpan = rowSpan),
            onSelect  = { type ->
                floorLayout = floorLayout.addRoom(
                    RoomPlacement(type = type, col = minCol, row = minRow, colSpan = colSpan, rowSpan = rowSpan, floor = currentFloor)
                )
                selectedCells = emptySet()
                showRoomTypePicker = false
            },
            onDismiss = { showRoomTypePicker = false },
        )
    }

    // Wall options: shown when user taps a wall or door in floor-plan view
    tappedWall?.let { (key, currentMode) ->
        val isOuter = key.boundary == 0 ||
            (key.vertical && key.boundary == floorLayout.gridCols) ||
            (!key.vertical && key.boundary == floorLayout.gridRows)
        WallOptionsDialog(
            currentMode = currentMode,
            isOuter   = isOuter,
            onSolid   = { floorLayout = floorLayout.withWall(key, WallMode.SOLID);  tappedWall = null },
            onOpen    = { floorLayout = floorLayout.withWall(key, WallMode.OPEN);   tappedWall = null },
            onDoor    = { floorLayout = floorLayout.withWall(key, WallMode.DOOR);   tappedWall = null },
            onWindow  = { floorLayout = floorLayout.withWall(key, WallMode.WINDOW); tappedWall = null },
            onDismiss = { tappedWall = null },
        )
    }

    // Placement options: shown when user taps an existing room in floor-plan view
    editingPlacement?.let { p ->
        PlacementOptionsDialog(
            placement      = p,
            availableTypes = availableRoomTypes(floorLayout, p.floor, excludeId = p.id, colSpan = p.colSpan, rowSpan = p.rowSpan),
            onEnter = {
                activeZone      = floorLayout.mergedZoneFor(p, p.floor)
                activePlacement = p
                viewState       = ViewState.ROOM
                editingPlacement = null
            },
            onRemove = {
                floorLayout = floorLayout.removeRoom(p.id)
                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                if (currentFloor > floorLayout.maxFloor()) currentFloor = floorLayout.maxFloor()
                editingPlacement = null
            },
            onChangeType = { type ->
                floorLayout = floorLayout.changeType(p.id, type)
                editingPlacement = null
            },
            onDismiss = { editingPlacement = null },
        )
    }

    // Lambdas shared between both pane orientations. GARAGE/DECK/POOL are freely dragged in
    // the 3D view now, not cycled through fixed sides — the icon just adds at the default
    // anchor when absent; tapping again while present does nothing (reposition by dragging
    // the feature itself, remove via its actions bar — see FeatureActionsDialog/selectedFeature
    // above). YARD (no side, on/off only) is unaffected — see also Stage 1's later removal of
    // its toggle icon entirely once presence becomes purely style-driven.
    val onFeatureClick: (HomeFeature) -> Unit = { f ->
        if (!f.hasSide) {
            featurePlacements = if (f in featurePlacements) featurePlacements - f
                                 else featurePlacements + (f to FeatureSide.FRONT)
        } else if (f !in featurePlacements) {
            featurePlacements = featurePlacements + (f to f.defaultSide)
            featureOffsets = featureOffsets - f
            // Adding the garage reshapes the driveway (carLot's anchor/orientation depend on
            // garageSide) — vehicles' dx/dz are anchor-relative offsets computed against the
            // OLD shape (none, here), so they must be re-settled onto the new one or they can
            // end up rendered clipped into a wall that used to be open driveway.
            if (f == HomeFeature.GARAGE) {
                val vehicles = placedItems.filter { it.item.isVehicle }
                if (vehicles.isNotEmpty()) {
                    val newLot = CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, f.defaultSide)
                    val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, newLot)
                    val byId = settled.associateBy { it.id }
                    placedItems = placedItems.map { byId[it.id] ?: it }
                }
            }
        }
    }
    val onSystemToggle: (HomeSystem) -> Unit = { s ->
        homeSystems = if (s in homeSystems) homeSystems - s else homeSystems + s
    }
    // Scene view, reused in both layout branches
    val scene: @Composable (Modifier) -> Unit = { mod ->
        Box(mod
            .background(Color(0xFF3A5070))
            .onGloballyPositioned { sceneBounds = it.boundsInRoot() }
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                mainLightNode = mainLightNode,
                fillLightNode = fillLightNode,
                cameraNode = sceneCameraNode,
                cameraManipulator = cameraManipulator,
                isOpaque = false,
            ) {
                when (viewState) {
                    ViewState.EXTERIOR -> {
                        val activeType     = previewedNeighbor?.homeType ?: homeType
                        val activeLayout   = if (previewedNeighbor != null) previewLayout else floorLayout
                        val activeFeatures = previewedNeighbor?.featurePlacements ?: featurePlacements
                        // Previewed neighbors are static presets with no drag history of their
                        // own — render at each feature's default anchor for that side.
                        val activeFeatureOffsets = if (previewedNeighbor != null) emptyMap() else featureOffsets
                        val activeDecks    = previewedNeighbor?.placedDecks ?: placedDecks
                        val activeSystems  = previewedNeighbor?.homeSystems ?: homeSystems
                        HouseScene(
                            homeType          = activeType,
                            floorLayout       = activeLayout,
                            featurePlacements = activeFeatures,
                            featureOffsets    = activeFeatureOffsets,
                            placedDecks       = activeDecks,
                            homeSystems       = activeSystems,
                            removedInstances  = if (previewedNeighbor != null) emptySet() else removedInstances,
                            itemOffsets       = if (previewedNeighbor != null) emptyMap() else itemOffsets,
                            placedItems       = if (previewedNeighbor != null) previewFurniture else placedItems,
                            cars              = if (previewedNeighbor != null) emptyList()
                                                else placedItems.filter { it.item.isVehicle },
                            onEnterGarage     = {
                                if (previewedNeighbor == null) {
                                    garageReturn = ViewState.EXTERIOR
                                    viewState    = ViewState.GARAGE
                                }
                            },
                            // Opens the vehicle's actions bar (color, fuel type, track-to-
                            // maintenance-card, remove) — see the placed-item actions bar below.
                            onCarTap          = { car -> selectedFurnitureId = car.id },
                            onCarMoved        = { pi, off ->
                                placedItems = placedItems.map { if (it.id == pi.id) it.copy(dx = off.first, dz = off.second) else it }
                            },
                            onCarRemoved      = { pi ->
                                placedItems = placedItems.filterNot { it.id == pi.id }
                                maint.removeItemRecords("placed:${pi.id}")
                            },
                            onFeatureMoved    = { f, side, off ->
                                featurePlacements = featurePlacements + (f to side)
                                featureOffsets = featureOffsets + (f to off)
                            },
                            onFeatureTap      = { f -> selectedFeature = f },
                            onDeckMoved       = { id, side, along ->
                                placedDecks = placedDecks.map { if (it.id == id) it.copy(side = side, along = along) else it }
                            },
                            onDeckTap         = { id -> selectedDeckId = id },
                            onEnterHome       = { if (previewedNeighbor == null) { viewState = ViewState.FLOOR_PLAN } },
                            // Claiming is never triggered by tapping the model itself — same as a
                            // previewed neighbor — it's always the floating ClaimHomeDialog button.
                            onHomeTap         = {
                                if (previewedNeighbor == null && savedHome != null) showHomeDatesDialog = true
                            },
                            // Maintenance cards live in the hub's Systems section now.
                            onTapSystem       = { },
                            isModelPreview    = previewedNeighbor != null,
                        )
                    }
                    ViewState.NEIGHBORHOOD ->
                        NeighborhoodScene(
                            homeType              = homeType,
                            floorLayout           = floorLayout,
                            featurePlacements     = featurePlacements,
                            featureOffsets        = featureOffsets,
                            placedDecks           = placedDecks,
                            homeSystems           = homeSystems,
                            removedInstances      = removedInstances,
                            vehicles              = placedItems.filter { it.item.isVehicle },
                            claimedNeighborLabel  = savedHome?.neighborKey ?: "",
                            onHomeClick           = { neighbor ->
                                previewedNeighbor = neighbor
                                viewState = ViewState.EXTERIOR
                            },
                            // Same as tapping a neighbor's lot: always walk into the exterior
                            // view first. If it's still unclaimed, ClaimHomeDialog's floating
                            // "Mine" button is what actually claims it from there.
                            onOwnHomeTap          = { viewState = ViewState.EXTERIOR },
                        )
                    ViewState.FLOOR_PLAN -> {
                        // Tap a room (its floor OR its furniture — items don't hit-test) to
                        // walk straight into it, like tapping the front door outside. All
                        // editing lives behind the pencil toggle; long-press always offers
                        // the room options regardless of mode.
                        val enterRoom: (RoomPlacement) -> Unit = { p ->
                            // TOWNHOUSE's interior garage room is entered like a House's
                            // exterior garage (door animates open, car rolls onto the
                            // driveway) rather than a plain walkable room — see GarageScene's
                            // gwOverride/gdOverride/lotOverride wiring below.
                            if (homeStyle == HomeStyle.TOWNHOUSE && p.type == RoomType.GARAGE) {
                                garageReturn = ViewState.FLOOR_PLAN
                                viewState    = ViewState.GARAGE
                            } else {
                                activeZone      = floorLayout.mergedZoneFor(p, p.floor)
                                activePlacement = p
                                viewState       = ViewState.ROOM
                            }
                        }
                        FloorPlanScene(
                            layout            = floorLayout,
                            currentFloor      = currentFloor,
                            homeStyle         = homeStyle,
                            featurePlacements = featurePlacements,
                            placedDecks       = placedDecks,
                            removedInstances  = removedInstances,
                            itemOffsets       = itemOffsets,
                            placedItems       = placedItems,
                            selectedCells     = selectedCells,
                            onTapPlacement    = { p ->
                                if (p.type != RoomType.HALLWAY) {
                                    if (floorPlanEditMode) editingPlacement = p else enterRoom(p)
                                }
                            },
                            onLongPressPlacement = { p -> if (p.type != RoomType.HALLWAY) editingPlacement = p },
                            onTapEmptyCell    = { col, row ->
                                if (floorPlanEditMode) selectedCells = when {
                                    (col to row) in selectedCells -> emptySet()
                                    selectedCells.isEmpty()       -> setOf(col to row)
                                    else -> {
                                        val minCol = minOf(selectedCells.minOf { it.first }, col)
                                        val maxCol = maxOf(selectedCells.maxOf { it.first }, col)
                                        val minRow = minOf(selectedCells.minOf { it.second }, row)
                                        val maxRow = maxOf(selectedCells.maxOf { it.second }, row)
                                        val candidate = (minCol..maxCol).flatMap { c ->
                                            (minRow..maxRow).map { r -> c to r }
                                        }.toSet()
                                        val allEmpty = candidate.all { (c, r) ->
                                            floorLayout.placementAt(c, r, currentFloor) == null
                                        }
                                        if (allEmpty) candidate else selectedCells
                                    }
                                }
                            },
                            onWallTap         = { key, mode ->
                                if (floorPlanEditMode) tappedWall = key to mode
                            },
                            onGarageTap       = {
                                garageReturn = ViewState.FLOOR_PLAN
                                viewState    = ViewState.GARAGE
                            },
                        )
                    }
                    ViewState.GARAGE -> {
                        val townhouseGarageZone = if (homeStyle == HomeStyle.TOWNHOUSE)
                            floorLayout.toMergedZones(0).firstOrNull { it.type == RoomType.GARAGE } else null
                        GarageScene(
                            homeW            = floorLayout.totalW,
                            homeD            = floorLayout.totalD,
                            garageSide       = featurePlacements[HomeFeature.GARAGE],
                            vehicles         = placedItems.filter { it.item.isVehicle },
                            // Opens the vehicle's actions bar (color, fuel type, track-to-
                            // maintenance-card, remove) — see the placed-item actions bar below.
                            onVehicleTap     = { car -> selectedFurnitureId = car.id },
                            onVehicleMoved   = { pi, off ->
                                placedItems = placedItems.map { if (it.id == pi.id) it.copy(dx = off.first, dz = off.second) else it }
                            },
                            onVehicleRemoved = { pi ->
                                placedItems = placedItems.filterNot { it.id == pi.id }
                                maint.removeItemRecords("placed:${pi.id}")
                            },
                            // TOWNHOUSE: shell sized to the real interior garage room, driveway
                            // anchored to its actual door in the front wall.
                            gwOverride       = townhouseGarageZone?.let { it.xMax - it.xMin },
                            gdOverride       = townhouseGarageZone?.let { it.zMax - it.zMin },
                            lotOverride      = if (homeStyle == HomeStyle.TOWNHOUSE)
                                CarLotGeometry.townhouseGarageSceneLot(floorLayout) else null,
                        )
                    }
                    ViewState.ROOM ->
                        activeZone?.let { zone ->
                            val wallModes = activePlacement
                                ?.let { floorLayout.wallModesFor(it) }
                                ?: emptyMap()
                            val exteriorSlider = exteriorSliderFor(zone, floorLayout, placedDecks, currentFloor)
                            val roomPlacementIds = floorLayout.placementIdsInZone(zone, currentFloor)
                            val repId = roomPlacementIds.minOrNull() ?: ""
                            RoomScene(
                                zone            = zone,
                                wallModes       = wallModes,
                                exteriorSlider  = exteriorSlider,
                                removedInstances = removedInstances,
                                defaultInstancePrefix = repId,
                                itemOffsets     = itemOffsets,
                                placedItems     = placedItems.filter { it.placementId in roomPlacementIds },
                                // Maintenance cards open from the hub's inventory only —
                                // in-room taps never open them. Default (wall/slot-anchored)
                                // items have no per-instance orientation state, so their tap
                                // is a no-op; the room pane still toggles "not in my home".
                                onTapItem       = { },
                                onItemMoved     = { item, offset ->
                                    itemOffsets = itemOffsets + (item to offset)
                                },
                                // Every placed item gets the rotate/flip/remove bar on tap
                                // (rotation only changes geometry for furniture/treasures —
                                // appliance copies stay wall-anchored but can be removed).
                                onPlacedItemTap = { pi -> selectedFurnitureId = pi.id },
                                onPlacedItemMoved = { pi, offset ->
                                    placedItems = placedItems.map {
                                        when {
                                            it.id == pi.id -> it.copy(dx = offset.first, dz = offset.second)
                                            // The range hood vents the stove and the oven door fills
                                            // the bay under its cooktop, so both ride along when the
                                            // stove is moved — shifted by the stove's drag delta (their
                                            // frac bases differ, so copying the offset would tear the
                                            // range apart). Moving either alone still moves just it.
                                            pi.item == RoomItem.STOVE && it.placementId == pi.placementId &&
                                                (it.item == RoomItem.RANGE_HOOD || it.item == RoomItem.OVEN) ->
                                                it.copy(dx = it.dx + offset.first - pi.dx,
                                                        dz = it.dz + offset.second - pi.dz)
                                            else -> it
                                        }
                                    }
                                },
                                // Tear-out: furniture dragged out through a wall is removed.
                                onPlacedItemRemoved = { pi ->
                                    placedItems = placedItems.filterNot { it.id == pi.id }
                                    maint.removeItemRecords("placed:${pi.id}")
                                    if (selectedFurnitureId == pi.id) selectedFurnitureId = null
                                },
                                // Vehicles parked in an interior garage room (TOWNHOUSE) — same
                                // actions-bar/remove wiring as GarageScene's exterior equivalent.
                                vehicles         = if (zone.type == RoomType.GARAGE)
                                    placedItems.filter { it.item.isVehicle } else emptyList(),
                                onVehicleTap     = { car -> selectedFurnitureId = car.id },
                                onVehicleMoved   = { pi, off ->
                                    placedItems = placedItems.map { if (it.id == pi.id) it.copy(dx = off.first, dz = off.second) else it }
                                },
                                onVehicleRemoved = { pi ->
                                    placedItems = placedItems.filterNot { it.id == pi.id }
                                    maint.removeItemRecords("placed:${pi.id}")
                                },
                            )
                        }
                }
            }
            // Tile-selection toolbar — shown while building a room footprint in the floor plan.
            // Rendered as a real Dialog (see TileSelectionDialog), not a Composable positioned
            // inside this Box — a Compose zIndex only orders content within Compose's own layer,
            // it can't win against SceneView's SurfaceView, which paints through its own hardware
            // layer regardless of camera angle. A Dialog gets its own OS window, which Android
            // always composites above the host Activity's entire view tree — the same reason
            // FurnitureActionsDialog (the furniture rotate/flip bar) is never hidden by the scene.
            if (viewState == ViewState.FLOOR_PLAN && selectedCells.isNotEmpty()) {
                val canCreateRoom = run {
                    val minC = selectedCells.minOf { it.first }
                    val maxC = selectedCells.maxOf { it.first }
                    val minR = selectedCells.minOf { it.second }
                    val maxR = selectedCells.maxOf { it.second }
                    availableRoomTypes(
                        floorLayout, currentFloor, colSpan = maxC - minC + 1, rowSpan = maxR - minR + 1,
                    ).isNotEmpty()
                }
                TileSelectionDialog(
                    tileCount     = selectedCells.size,
                    canCreateRoom = canCreateRoom,
                    onClear       = { selectedCells = emptySet() },
                    onCreateRoom  = { showRoomTypePicker = true },
                )
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = panePosition,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "pane-position"
    ) { pos ->
        when (pos) {
            PanePosition.RIGHT -> {
                if (viewState == ViewState.FLOOR_PLAN) {
                    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            scene(Modifier.weight(1f).fillMaxWidth())
                        }
                        VerticalDivider()
                        FloorSelectorBar(
                            modifier      = Modifier.fillMaxHeight(),
                            vertical      = true,
                            floorLayout   = floorLayout,
                            currentFloor  = currentFloor,
                            editMode         = floorPlanEditMode,
                            onEditModeChange = { on -> floorPlanEditMode = on; if (!on) selectedCells = emptySet() },
                            onFloorSelect = { currentFloor = it; selectedCells = emptySet() },
                            onAddFloor    = { floorLayout = floorLayout.addFloor(); currentFloor = floorLayout.maxFloor(); selectedCells = emptySet() },
                            onExpandCols  = { floorLayout = floorLayout.expandCols() },
                            onExpandRows  = { floorLayout = floorLayout.expandRows() },
                            onShrinkCols  = {
                                floorLayout = floorLayout.shrinkCols()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                selectedCells = emptySet()
                            },
                            onShrinkRows  = {
                                floorLayout = floorLayout.shrinkRows()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                selectedCells = emptySet()
                            },
                            onRemoveFloor = {
                                val top = floorLayout.maxFloor()
                                floorLayout = floorLayout.removeTopFloor()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                if (currentFloor >= top) currentFloor = floorLayout.maxFloor()
                                selectedCells = emptySet()
                            },
                            onExit        = { viewState = ViewState.EXTERIOR; selectedCells = emptySet(); floorPlanEditMode = false },
                            onInfo        = { showInfo = true },
                            homeClaimed      = savedHome != null,
                            onHomeInfo       = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                            onMaintenanceHub = { showMaintenanceHub = true },
                            cameraSettings         = floorPlanCamSettings,
                            onCameraSettingsChange = { floorPlanCamSettings = it },
                        )
                    }
                } else if (viewState == ViewState.EXTERIOR) {
                    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            scene(Modifier.weight(1f).fillMaxWidth())
                        }
                        VerticalDivider()
                        if (previewedNeighbor != null) {
                            NeighborPreviewPane(
                                vertical               = true,
                                onBack                 = { previewedNeighbor = null; viewState = ViewState.NEIGHBORHOOD },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                            )
                        } else {
                            RightPane(
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                homeSystems            = homeSystems,
                                onFeatureClick         = onFeatureClick,
                                onSystemToggle         = onSystemToggle,
                                onNeighborhoodToggle   = { viewState = ViewState.NEIGHBORHOOD },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                                onTear                 = { panePosition = PanePosition.BOTTOM },
                                onCarDragStart         = { item, pos -> trayDrag = item to pos },
                                onCarDrag              = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onCarDrop              = {
                                    trayDrag?.let { (item, pos) -> dropVehicleAt(item, pos) }
                                    trayDrag = null
                                },
                                onCarCancel            = { trayDrag = null },
                                onDeckDragStart        = { pos -> deckTrayDrag = pos },
                                onDeckDrag             = { pos -> deckTrayDrag = pos },
                                onDeckDrop             = {
                                    deckTrayDrag?.let { dropDeckAt(it) }
                                    deckTrayDrag = null
                                },
                                onDeckCancel           = { deckTrayDrag = null },
                            )
                        }
                    }
                    // Model homes on their lots are ALWAYS claimable — even the one your
                    // current home was claimed from, since re-claiming is a harmless
                    // "start fresh on this model" (records/dates/vehicles carry over).
                    // The unclaimed starter home is a sandbox: it points to the
                    // neighborhood instead, where all claiming lives.
                    if (previewedNeighbor != null) {
                        ClaimHomeDialog(
                            label          = previewedNeighbor!!.label,
                            floors         = previewedNeighbor!!.floors,
                            styleIcon      = previewedNeighbor!!.homeType.style.icon,
                            styleIconColor = previewedNeighbor!!.homeType.style.iconColor,
                            onClaim        = {
                                val n = previewedNeighbor!!
                                val saved = savedHome
                                if (saved != null) claimNeighbor(n, saved.label, saved.buildYear, saved.purchaseYear)
                                else pendingClaimNeighbor = n
                            },
                            onDismiss      = { previewedNeighbor = null; viewState = ViewState.NEIGHBORHOOD },
                        )
                    } else if (savedHome == null) {
                        FindHomeDialog(onBrowse = { viewState = ViewState.NEIGHBORHOOD })
                    }
                } else {
                    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            scene(Modifier.weight(1f).fillMaxWidth())
                        }
                        VerticalDivider()
                        when (viewState) {
                            ViewState.NEIGHBORHOOD -> RightPane(
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                homeSystems            = homeSystems,
                                onFeatureClick         = onFeatureClick,
                                onSystemToggle         = onSystemToggle,
                                onNeighborhoodToggle   = { viewState = ViewState.EXTERIOR },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = neighborhoodCamSettings,
                                onCameraSettingsChange = { neighborhoodCamSettings = it },
                                onTear                 = { panePosition = PanePosition.BOTTOM },
                            )
                            ViewState.ROOM -> RoomRightPane(
                                room                   = activeZone?.type ?: RoomType.LIVING_ROOM,
                                removedInstances        = removedInstances,
                                defaultInstancePrefix  = activeZone?.let { floorLayout.placementIdsInZone(it, currentFloor).minOrNull() } ?: "",
                                onItemToggle           = { key ->
                                    removedInstances = if (key in removedInstances) removedInstances - key else removedInstances + key
                                },
                                onFurnitureDragStart   = { item, pos -> trayDrag = item to pos },
                                onFurnitureDrag        = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onFurnitureDrop        = {
                                    trayDrag?.let { (item, pos) ->
                                        if (item.isVehicle) dropRoomVehicleAt(item, pos) else dropFurnitureAt(item, pos)
                                    }
                                    trayDrag = null
                                },
                                onFurnitureCancel      = { trayDrag = null },
                                onBack                 = { viewState = ViewState.FLOOR_PLAN; activeZone = null; activePlacement = null; selectedFurnitureId = null },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                camAngle               = roomCamAngle,
                                onCamAngleChange       = { roomCamAngle = it },
                                cameraSettings         = roomCamSettings,
                                onCameraSettingsChange = { roomCamSettings = it },
                            )
                            ViewState.GARAGE -> GaragePane(
                                vertical               = true,
                                onBack                 = { viewState = garageReturn },
                                onInfo                 = { showInfo = true },
                                cameraSettings         = garageCamSettings,
                                onCameraSettingsChange = { garageCamSettings = it },
                                onVehicleDragStart     = { item, pos -> trayDrag = item to pos },
                                onVehicleDrag          = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onVehicleDrop          = {
                                    trayDrag?.let { (item, pos) -> dropVehicleAt(item, pos) }
                                    trayDrag = null
                                },
                                onVehicleCancel        = { trayDrag = null },
                            )
                            else -> {}
                        }
                    }
                }
            }
            PanePosition.BOTTOM -> {
                if (viewState == ViewState.FLOOR_PLAN) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        scene(Modifier.weight(1f).fillMaxWidth())
                        FloorSelectorBar(
                            modifier      = Modifier.fillMaxWidth(),
                            vertical      = false,
                            floorLayout   = floorLayout,
                            currentFloor  = currentFloor,
                            editMode         = floorPlanEditMode,
                            onEditModeChange = { on -> floorPlanEditMode = on; if (!on) selectedCells = emptySet() },
                            onFloorSelect = { currentFloor = it; selectedCells = emptySet() },
                            onAddFloor    = { floorLayout = floorLayout.addFloor(); currentFloor = floorLayout.maxFloor(); selectedCells = emptySet() },
                            onExpandCols  = { floorLayout = floorLayout.expandCols() },
                            onExpandRows  = { floorLayout = floorLayout.expandRows() },
                            onShrinkCols  = {
                                floorLayout = floorLayout.shrinkCols()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                selectedCells = emptySet()
                            },
                            onShrinkRows  = {
                                floorLayout = floorLayout.shrinkRows()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                selectedCells = emptySet()
                            },
                            onRemoveFloor = {
                                val top = floorLayout.maxFloor()
                                floorLayout = floorLayout.removeTopFloor()
                                placedItems = floorLayout.pruneOrphanedPlacedItems(placedItems)
                                if (currentFloor >= top) currentFloor = floorLayout.maxFloor()
                                selectedCells = emptySet()
                            },
                            onExit        = { viewState = ViewState.EXTERIOR; selectedCells = emptySet(); floorPlanEditMode = false },
                            onInfo        = { showInfo = true },
                            homeClaimed      = savedHome != null,
                            onHomeInfo       = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                            onMaintenanceHub = { showMaintenanceHub = true },
                            cameraSettings         = floorPlanCamSettings,
                            onCameraSettingsChange = { floorPlanCamSettings = it },
                        )
                    }
                } else if (viewState == ViewState.EXTERIOR) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        scene(Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        if (previewedNeighbor != null) {
                            NeighborPreviewPane(
                                vertical               = false,
                                onBack                 = { previewedNeighbor = null; viewState = ViewState.NEIGHBORHOOD },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                            )
                        } else {
                            BottomPane(
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                homeSystems            = homeSystems,
                                onFeatureClick         = onFeatureClick,
                                onSystemToggle         = onSystemToggle,
                                onNeighborhoodToggle   = { viewState = ViewState.NEIGHBORHOOD },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                                onTear                 = { panePosition = PanePosition.RIGHT },
                                onCarDragStart         = { item, pos -> trayDrag = item to pos },
                                onCarDrag              = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onCarDrop              = {
                                    trayDrag?.let { (item, pos) -> dropVehicleAt(item, pos) }
                                    trayDrag = null
                                },
                                onCarCancel            = { trayDrag = null },
                                onDeckDragStart        = { pos -> deckTrayDrag = pos },
                                onDeckDrag             = { pos -> deckTrayDrag = pos },
                                onDeckDrop             = {
                                    deckTrayDrag?.let { dropDeckAt(it) }
                                    deckTrayDrag = null
                                },
                                onDeckCancel           = { deckTrayDrag = null },
                            )
                        }
                    }
                    // Model homes on their lots are ALWAYS claimable — even the one your
                    // current home was claimed from, since re-claiming is a harmless
                    // "start fresh on this model" (records/dates/vehicles carry over).
                    // The unclaimed starter home is a sandbox: it points to the
                    // neighborhood instead, where all claiming lives.
                    if (previewedNeighbor != null) {
                        ClaimHomeDialog(
                            label          = previewedNeighbor!!.label,
                            floors         = previewedNeighbor!!.floors,
                            styleIcon      = previewedNeighbor!!.homeType.style.icon,
                            styleIconColor = previewedNeighbor!!.homeType.style.iconColor,
                            onClaim        = {
                                val n = previewedNeighbor!!
                                val saved = savedHome
                                if (saved != null) claimNeighbor(n, saved.label, saved.buildYear, saved.purchaseYear)
                                else pendingClaimNeighbor = n
                            },
                            onDismiss      = { previewedNeighbor = null; viewState = ViewState.NEIGHBORHOOD },
                        )
                    } else if (savedHome == null) {
                        FindHomeDialog(onBrowse = { viewState = ViewState.NEIGHBORHOOD })
                    }
                } else {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        scene(Modifier.weight(1f).fillMaxWidth())
                        HorizontalDivider()
                        when (viewState) {
                            ViewState.NEIGHBORHOOD -> BottomPane(
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                homeSystems            = homeSystems,
                                onFeatureClick         = onFeatureClick,
                                onSystemToggle         = onSystemToggle,
                                onNeighborhoodToggle   = { viewState = ViewState.EXTERIOR },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = neighborhoodCamSettings,
                                onCameraSettingsChange = { neighborhoodCamSettings = it },
                                onTear                 = { panePosition = PanePosition.RIGHT },
                            )
                            ViewState.ROOM -> RoomBottomPane(
                                room                   = activeZone?.type ?: RoomType.LIVING_ROOM,
                                removedInstances        = removedInstances,
                                defaultInstancePrefix  = activeZone?.let { floorLayout.placementIdsInZone(it, currentFloor).minOrNull() } ?: "",
                                onItemToggle           = { key ->
                                    removedInstances = if (key in removedInstances) removedInstances - key else removedInstances + key
                                },
                                onFurnitureDragStart   = { item, pos -> trayDrag = item to pos },
                                onFurnitureDrag        = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onFurnitureDrop        = {
                                    trayDrag?.let { (item, pos) ->
                                        if (item.isVehicle) dropRoomVehicleAt(item, pos) else dropFurnitureAt(item, pos)
                                    }
                                    trayDrag = null
                                },
                                onFurnitureCancel      = { trayDrag = null },
                                onBack                 = { viewState = ViewState.FLOOR_PLAN; activeZone = null; activePlacement = null; selectedFurnitureId = null },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                camAngle               = roomCamAngle,
                                onCamAngleChange       = { roomCamAngle = it },
                                cameraSettings         = roomCamSettings,
                                onCameraSettingsChange = { roomCamSettings = it },
                            )
                            ViewState.GARAGE -> GaragePane(
                                vertical               = false,
                                onBack                 = { viewState = garageReturn },
                                onInfo                 = { showInfo = true },
                                cameraSettings         = garageCamSettings,
                                onCameraSettingsChange = { garageCamSettings = it },
                                onVehicleDragStart     = { item, pos -> trayDrag = item to pos },
                                onVehicleDrag          = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onVehicleDrop          = {
                                    trayDrag?.let { (item, pos) -> dropVehicleAt(item, pos) }
                                    trayDrag = null
                                },
                                onVehicleCancel        = { trayDrag = null },
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    // Ghost of the furniture icon being dragged from the tray — follows the finger, floats
    // just above it, and needs explicit zIndex to stay over the SceneView surface layer.
    trayDrag?.let { (item, pos) ->
        Icon(
            item.icon,
            contentDescription = null,
            tint = item.room.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    deckTrayDrag?.let { pos ->
        Icon(
            HomeFeature.DECK.icon,
            contentDescription = null,
            tint = HomeFeature.DECK.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    } // outer Box
}

// ── Right pane (vertical strip) ───────────────────────────────────────────────

@Composable
private fun RightPane(
    homeStyle: HomeStyle,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    homeSystems: Set<HomeSystem>,
    onFeatureClick: (HomeFeature) -> Unit,
    onSystemToggle: (HomeSystem) -> Unit,
    onNeighborhoodToggle: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    onTear: (() -> Unit)? = null,
    // Vehicle tray (exterior view of your own home only) — long-press a car/boat/motorcycle
    // icon and drag it onto the lot to park a new one there. Null hides the tray
    // (neighborhood view).
    onCarDragStart: ((RoomItem, Offset) -> Unit)? = null,
    onCarDrag: (Offset) -> Unit = {},
    onCarDrop: () -> Unit = {},
    onCarCancel: () -> Unit = {},
    // Deck tray — same tear-drag as the vehicle tray above, but drops onto the yard and
    // snaps to whichever wall it lands nearest. Null hides it too (neighborhood view).
    onDeckDragStart: ((Offset) -> Unit)? = null,
    onDeckDrag: (Offset) -> Unit = {},
    onDeckDrop: () -> Unit = {},
    onDeckCancel: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onTear != null) {
            DragHandle(
                modifier = Modifier
                    .size(48.dp)
                    .tearGesture(dragDown = true, onTear = onTear),
                rotated = false,
            )
            PanelDivider()
        }
        // Consistent pane prefix: back · help · home · maintenance · camera gear, then
        // scene-specific controls.
        PanelIcon(Icons.Outlined.ArrowBack, "Neighborhood", false, onClick = onNeighborhoodToggle)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        PanelIcon(
            if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
            if (homeClaimed) "Home info" else "Claim this home",
            false, onClick = onHomeInfo,
        )
        PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp))
        PanelDivider()
        availableFeatures(homeStyle).forEach { f ->
            FeatureIcon(f, featurePlacements[f]) { onFeatureClick(f) }
        }
        PanelDivider()
        availableSystems(homeStyle).forEach { s ->
            PanelIcon(s.icon, s.label, s in homeSystems, s.iconColor) { onSystemToggle(s) }
        }
        if (onCarDragStart != null) {
            PanelDivider()
            listOf(RoomItem.CAR, RoomItem.BOAT, RoomItem.MOTORCYCLE).forEach { vehicle ->
                FurnitureTrayIcon(
                    item        = vehicle,
                    onDragStart = onCarDragStart,
                    onDrag      = onCarDrag,
                    onDrop      = onCarDrop,
                    onCancel    = onCarCancel,
                )
            }
            DeckTrayIcon(
                onDragStart = onDeckDragStart ?: {},
                onDrag      = onDeckDrag,
                onDrop      = onDeckDrop,
                onCancel    = onDeckCancel,
            )
        }
    }
}

// ── Bottom pane (horizontal strip) ───────────────────────────────────────────

@Composable
private fun BottomPane(
    homeStyle: HomeStyle,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    homeSystems: Set<HomeSystem>,
    onFeatureClick: (HomeFeature) -> Unit,
    onSystemToggle: (HomeSystem) -> Unit,
    onNeighborhoodToggle: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    onTear: (() -> Unit)? = null,
    // Vehicle tray (exterior view of your own home only) — long-press a car/boat/motorcycle
    // icon and drag it onto the lot to park a new one there. Null hides the tray
    // (neighborhood view).
    onCarDragStart: ((RoomItem, Offset) -> Unit)? = null,
    onCarDrag: (Offset) -> Unit = {},
    onCarDrop: () -> Unit = {},
    onCarCancel: () -> Unit = {},
    // Deck tray — same tear-drag as the vehicle tray above, but drops onto the yard and
    // snaps to whichever wall it lands nearest. Null hides it too (neighborhood view).
    onDeckDragStart: ((Offset) -> Unit)? = null,
    onDeckDrag: (Offset) -> Unit = {},
    onDeckDrop: () -> Unit = {},
    onDeckCancel: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (onTear != null) {
            DragHandle(
                modifier = Modifier
                    .size(48.dp)
                    .tearGesture(dragDown = false, onTear = onTear),
                rotated = true,
            )
            VerticalDivider(modifier = Modifier.height(40.dp))
        }
        // Consistent pane prefix: back · help · home · maintenance · camera gear, then
        // scene-specific controls.
        PanelIcon(Icons.Outlined.ArrowBack, "Neighborhood", false, onClick = onNeighborhoodToggle)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        PanelIcon(
            if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
            if (homeClaimed) "Home info" else "Claim this home",
            false, onClick = onHomeInfo,
        )
        PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp))
        VerticalDivider(modifier = Modifier.height(40.dp))
        availableFeatures(homeStyle).forEach { f ->
            FeatureIcon(f, featurePlacements[f]) { onFeatureClick(f) }
        }
        VerticalDivider(modifier = Modifier.height(40.dp))
        availableSystems(homeStyle).forEach { s ->
            PanelIcon(s.icon, s.label, s in homeSystems, s.iconColor) { onSystemToggle(s) }
        }
        if (onCarDragStart != null) {
            listOf(RoomItem.CAR, RoomItem.BOAT, RoomItem.MOTORCYCLE).forEach { vehicle ->
                FurnitureTrayIcon(
                    item        = vehicle,
                    onDragStart = onCarDragStart,
                    onDrag      = onCarDrag,
                    onDrop      = onCarDrop,
                    onCancel    = onCarCancel,
                )
            }
            DeckTrayIcon(
                onDragStart = onDeckDragStart ?: {},
                onDrag      = onDeckDrag,
                onDrop      = onDeckDrop,
                onCancel    = onDeckCancel,
            )
        }
    }
}

// ── Garage pane — back · help · camera gear · vehicle tray, in either orientation ──────
@Composable
private fun GaragePane(
    vertical: Boolean,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    onVehicleDragStart: (RoomItem, Offset) -> Unit,
    onVehicleDrag: (Offset) -> Unit,
    onVehicleDrop: () -> Unit,
    onVehicleCancel: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        PanelIcon(Icons.Outlined.ArrowBack, "Back", false, onClick = onBack)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp))
        if (vertical) HorizontalDivider(modifier = Modifier.width(40.dp))
        else VerticalDivider(modifier = Modifier.height(40.dp))
        listOf(RoomItem.CAR, RoomItem.BOAT, RoomItem.MOTORCYCLE).forEach { vehicle ->
            FurnitureTrayIcon(
                item        = vehicle,
                onDragStart = onVehicleDragStart,
                onDrag      = onVehicleDrag,
                onDrop      = onVehicleDrop,
                onCancel    = onVehicleCancel,
            )
        }
    }
    if (vertical) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

// ── Neighbor preview pane ─────────────────────────────────────────────────────

@Composable
private fun NeighborPreviewPane(
    vertical: Boolean,
    onBack: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
) {
    // Consistent pane prefix: back · camera gear. The style icon and the home's name/floor
    // count both live in the floating ClaimHomeDialog instead — the "which home is this"
    // context is shared with the own-home claim flow, so it doesn't belong here too.
    val prefix: @Composable () -> Unit = {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
        }
        CameraGearButton(cameraSettings, onCameraSettingsChange)
    }
    if (vertical) {
        Column(
            modifier = Modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            prefix()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            prefix()
        }
    }
}

// Floating claim trigger — same real-Dialog-window mechanism as the furniture/vehicle
// action bars above, so it always composites above the SceneView regardless of z-order.
// Shown while previewing a model home. Carries the home's name/floor count so that context
// travels with the claim action. The close icon and the system back button both route to
// onDismiss, so browsing without claiming is always an out.
@Composable
private fun ClaimHomeDialog(
    label: String,
    floors: Int,
    styleIcon: ImageVector,
    styleIconColor: Color,
    onClaim: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx  = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(styleIcon, contentDescription = null, tint = styleIconColor,
                    modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    Text("$floors fl",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = onClaim) {
                    Icon(Icons.Outlined.AddHome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mine")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Not now")
                }
            }
        }
    }
}

// The unclaimed starter home's pointer: claiming happens in the NEIGHBORHOOD (on the model
// lots), never from this sandbox — this floating bar sends the user there. Same window
// shell as ClaimHomeDialog so the two read as one family.
@Composable
private fun FindHomeDialog(
    onBrowse: () -> Unit,
) {
    Dialog(
        onDismissRequest = onBrowse,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx  = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Find your home in the neighborhood",
                    style = MaterialTheme.typography.labelMedium)
                FilledTonalButton(onClick = onBrowse) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse")
                }
            }
        }
    }
}

// ── Build layout matching a neighbor's floor count ────────────────────────────

private fun buildPreviewLayout(neighbor: NeighborHome): FloorLayout {
    neighbor.customLayout?.let { return it }
    var layout = FloorLayout()
    repeat(neighbor.floors - 1) { layout = layout.addFloor() }
    return layout
}

// ── Drag-to-tear gesture ──────────────────────────────────────────────────────

// dragDown=true  → triggers when user swipes downward   (right pane → bottom)
// dragDown=false → triggers when user swipes up or right (bottom pane → right)
private fun Modifier.tearGesture(dragDown: Boolean, onTear: () -> Unit): Modifier =
    pointerInput(dragDown) {
        var dx = 0f; var dy = 0f
        detectDragGestures(
            onDragStart = { dx = 0f; dy = 0f },
            onDrag = { change, delta -> change.consume(); dx += delta.x; dy += delta.y },
            onDragEnd = {
                val threshold = 200f
                if (dragDown) {
                    if (dy > threshold && abs(dy) > abs(dx)) onTear()
                } else {
                    if (dy < -threshold || (dx > threshold && abs(dx) > abs(dy))) onTear()
                }
            }
        )
    }

// ── Shared primitives ─────────────────────────────────────────────────────────

@Composable
private fun DragHandle(modifier: Modifier, rotated: Boolean) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = "Move pane",
            modifier = if (rotated) Modifier.rotate(90f) else Modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun PanelIcon(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = if (selected) iconColor.copy(alpha = 0.15f) else Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) iconColor else iconColor.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
internal fun PanelTextToggle(
    label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    // Selected = filled primary, unselected = outlined — the old secondaryContainer tint was
    // nearly indistinguishable from a dialog's surface, so toggles looked like they ignored taps.
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null
                 else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PanelDivider() = HorizontalDivider(Modifier.padding(vertical = 4.dp))

// Feature icon: tap to enable (FRONT), tap again to cycle side, last tap removes.
// Yard has no directional side — it just toggles on/off.
@Composable
private fun FeatureIcon(
    feature: HomeFeature,
    side: FeatureSide?,
    onClick: () -> Unit,
) {
    val enabled = side != null
    val color   = feature.iconColor
    Surface(
        onClick  = onClick,
        modifier = Modifier.size(48.dp),
        shape    = CircleShape,
        color    = if (enabled) color.copy(alpha = 0.15f) else Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector     = feature.icon,
                    contentDescription = feature.label,
                    modifier        = Modifier.size(if (enabled && feature.hasSide) 18.dp else 22.dp),
                    tint            = if (enabled) color else color.copy(alpha = 0.45f),
                )
                if (enabled && feature.hasSide && side != null) {
                    Text(
                        text  = side.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
            }
        }
    }
}

// ── Floor selector bar — back, gear, and floor tabs, bottom-docked or right-docked ────

private enum class GridRemoval { FLOOR, COLUMN, ROW }

// Single icon button for a grid dimension (row/column/floor), badged with +/- to say
// whether it grows or shrinks that dimension — keeps the edit bar to one compact row.
@Composable
private fun GridStepButton(
    icon: ImageVector,
    isAdd: Boolean,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        BadgedBox(badge = {
            Badge(
                containerColor = if (isAdd) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.error,
            ) { Text(if (isAdd) "+" else "−") }
        }) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun FloorSelectorBar(
    modifier: Modifier,
    vertical: Boolean,
    floorLayout: FloorLayout,
    currentFloor: Int,
    editMode: Boolean,
    onEditModeChange: (Boolean) -> Unit,
    onFloorSelect: (Int) -> Unit,
    onAddFloor: () -> Unit,
    onExpandCols: () -> Unit,
    onExpandRows: () -> Unit,
    onShrinkCols: () -> Unit,
    onShrinkRows: () -> Unit,
    onRemoveFloor: () -> Unit,
    onExit: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
) {
    var pendingRemoval by remember { mutableStateOf<GridRemoval?>(null) }

    pendingRemoval?.let { removal ->
        val title: String
        val message: String
        val onConfirm: () -> Unit
        when (removal) {
            GridRemoval.FLOOR -> {
                val topFloor = floorLayout.maxFloor()
                val affected = floorLayout.roomsOnTopFloor()
                title = "Remove floor ${topFloor + 1}?"
                message = if (affected.isNotEmpty())
                    "This deletes floor ${topFloor + 1} and its ${affected.size} room(s): " +
                        "${affected.joinToString { it.type.label }}. This can't be undone."
                else "This deletes floor ${topFloor + 1}. This can't be undone."
                onConfirm = onRemoveFloor
            }
            GridRemoval.COLUMN -> {
                val affected = floorLayout.roomsInLastCol()
                title = "Remove last column?"
                message = if (affected.isNotEmpty())
                    "This deletes the last column and ${affected.size} room(s): " +
                        "${affected.joinToString { it.type.label }}. This can't be undone."
                else "This deletes the last (empty) column."
                onConfirm = onShrinkCols
            }
            GridRemoval.ROW -> {
                val affected = floorLayout.roomsInLastRow()
                title = "Remove last row?"
                message = if (affected.isNotEmpty())
                    "This deletes the last row and ${affected.size} room(s): " +
                        "${affected.joinToString { it.type.label }}. This can't be undone."
                else "This deletes the last (empty) row."
                onConfirm = onShrinkRows
            }
        }
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(title) },
            text  = { Text(message) },
            confirmButton = {
                Button(
                    onClick = { onConfirm(); pendingRemoval = null },
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }

    // Row/col/floor grid-editing controls — shown only while the pencil toggle is ON. Every
    // structural floor-plan edit lives here; MainActivity simultaneously switches scene taps to
    // edit actions (room options, wall modes, tile selection) while this mode is active.
    val editControls: @Composable () -> Unit = {
        GridStepButton(Icons.Outlined.TableRows, isAdd = false, "Remove row",
            enabled = floorLayout.gridRows > 1) { pendingRemoval = GridRemoval.ROW }
        GridStepButton(Icons.Outlined.TableRows, isAdd = true, "Add row", onClick = onExpandRows)
        if (vertical) HorizontalDivider(modifier = Modifier.width(28.dp).padding(vertical = 2.dp))
        else VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 2.dp))
        GridStepButton(Icons.Outlined.TableChart, isAdd = false, "Remove column",
            enabled = floorLayout.gridCols > 1) { pendingRemoval = GridRemoval.COLUMN }
        GridStepButton(Icons.Outlined.TableChart, isAdd = true, "Add column", onClick = onExpandCols)
        if (vertical) HorizontalDivider(modifier = Modifier.width(28.dp).padding(vertical = 2.dp))
        else VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 2.dp))
        GridStepButton(Icons.Outlined.Layers, isAdd = false, "Remove floor",
            enabled = floorLayout.maxFloor() > 0) { pendingRemoval = GridRemoval.FLOOR }
        GridStepButton(Icons.Outlined.Layers, isAdd = true, "Add floor", onClick = onAddFloor)
        if (vertical) HorizontalDivider(modifier = Modifier.width(28.dp).padding(vertical = 2.dp))
        else VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 2.dp))
        IconButton(onClick = { onEditModeChange(false) }) {
            Icon(Icons.Outlined.Close, "Done")
        }
    }
    // Consistent pane prefix: back · help · home · maintenance · gear · pencil (edit mode),
    // then the floor tabs. All structural editing lives behind the pencil.
    val prefix: @Composable () -> Unit = {
        IconButton(onClick = onExit) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Outside")
        }
        IconButton(onClick = onInfo) {
            Icon(Icons.Outlined.Info, contentDescription = "Help")
        }
        IconButton(onClick = onHomeInfo) {
            Icon(
                if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
                contentDescription = if (homeClaimed) "Home info" else "Claim this home",
            )
        }
        IconButton(onClick = onMaintenanceHub) {
            Icon(Icons.Outlined.Build, contentDescription = "Maintenance & Guides")
        }
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp))
        IconButton(onClick = { onEditModeChange(!editMode) }) {
            Icon(
                imageVector        = Icons.Outlined.Edit,
                contentDescription = if (editMode) "Done editing" else "Edit floor plan",
                tint               = if (editMode) MaterialTheme.colorScheme.primary
                                     else LocalContentColor.current,
            )
        }
    }
    val floorTabs: @Composable () -> Unit = {
        (0..floorLayout.maxFloor()).forEach { f ->
            PanelTextToggle(
                label    = "F${f + 1}",
                selected = currentFloor == f,
                modifier = Modifier.size(48.dp),
            ) { onFloorSelect(f) }
        }
    }

    if (vertical) {
        Column(modifier = modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedVisibility(
                visible  = editMode,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { editControls() }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                prefix()
                HorizontalDivider(modifier = Modifier.width(40.dp).padding(vertical = 4.dp))
                floorTabs()
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            // Compact EDIT row — rises above the bar while the pencil toggle is ON.
            AnimatedVisibility(
                visible  = editMode,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { editControls() }
                }
            }

            // Main bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                prefix()
                VerticalDivider(modifier = Modifier.height(40.dp).padding(horizontal = 4.dp))
                Row(
                    modifier              = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically,
                ) { floorTabs() }
            }
        }
    }
}

// ── Room panes ────────────────────────────────────────────────────────────────

@Composable
private fun RoomBottomPane(
    room: RoomType,
    removedInstances: Set<String>,
    defaultInstancePrefix: String,
    onItemToggle: (String) -> Unit,
    onFurnitureDragStart: (RoomItem, Offset) -> Unit,
    onFurnitureDrag: (Offset) -> Unit,
    onFurnitureDrop: () -> Unit,
    onFurnitureCancel: () -> Unit,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    camAngle: RoomCamAngle,
    onCamAngleChange: (RoomCamAngle) -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Consistent pane prefix: back · help · home · maintenance · camera gear, then
            // scene-specific controls.
            PanelIcon(Icons.Outlined.ArrowBack, "Floor plan", false, onClick = onBack)
            PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
            PanelIcon(
                if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
                if (homeClaimed) "Home info" else "Claim this home",
                false, onClick = onHomeInfo,
            )
            PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
            CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(44.dp),
                camAngle = camAngle, onCamAngleChange = onCamAngleChange)
            VerticalDivider(modifier = Modifier.height(36.dp))
            Icon(room.icon, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = room.iconColor)
            Text(room.label, style = MaterialTheme.typography.labelMedium,
                color = room.iconColor)
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Appliances/fixtures the room owns — tap to toggle "not in my home".
            val items = room.defaultItems()
            items.forEach { item ->
                val key = "$defaultInstancePrefix:${item.name}"
                InventoryItemIcon(item, key !in removedInstances) { onItemToggle(key) }
            }
            if (items.isNotEmpty()) VerticalDivider(modifier = Modifier.height(40.dp))
            // Furniture tray — long-press an icon and drag it into the room to place it.
            // Portable appliances (fridge, washer, dryer, water heater) ride along so a
            // copy can live outside its nominal room (combined laundry/storage, gym fridge…).
            // A GARAGE room's tray also offers vehicles — dropped via dropRoomVehicleAt
            // (see the onFurnitureDrop wiring), not dropFurnitureAt, since a vehicle's
            // dx/dz is CarLot-anchor-relative rather than the room-fraction convention
            // ordinary furniture uses.
            RoomItem.entries.filter { it.isFurniture || it.isPortable ||
                (room == RoomType.GARAGE && it.isVehicle) }.forEach { item ->
                FurnitureTrayIcon(
                    item        = item,
                    onDragStart = onFurnitureDragStart,
                    onDrag      = onFurnitureDrag,
                    onDrop      = onFurnitureDrop,
                    onCancel    = onFurnitureCancel,
                )
            }
        }
    }
}

@Composable
private fun RoomRightPane(
    room: RoomType,
    removedInstances: Set<String>,
    defaultInstancePrefix: String,
    onItemToggle: (String) -> Unit,
    onFurnitureDragStart: (RoomItem, Offset) -> Unit,
    onFurnitureDrag: (Offset) -> Unit,
    onFurnitureDrop: () -> Unit,
    onFurnitureCancel: () -> Unit,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    camAngle: RoomCamAngle,
    onCamAngleChange: (RoomCamAngle) -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Consistent pane prefix: back · help · home · maintenance · camera gear, then
        // scene-specific controls.
        PanelIcon(Icons.Outlined.ArrowBack, "Floor plan", false, onClick = onBack)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        PanelIcon(
            if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
            if (homeClaimed) "Home info" else "Claim this home",
            false, onClick = onHomeInfo,
        )
        PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp),
            camAngle = camAngle, onCamAngleChange = onCamAngleChange)
        PanelDivider()
        Icon(room.icon, contentDescription = room.label,
            modifier = Modifier.size(20.dp), tint = room.iconColor)
        PanelDivider()
        room.defaultItems().forEach { item ->
            val key = "$defaultInstancePrefix:${item.name}"
            InventoryItemIcon(item, key !in removedInstances) { onItemToggle(key) }
        }
        PanelDivider()
        // Furniture tray — long-press an icon and drag it into the room to place it.
        // Portable appliances ride along (see RoomBottomPane's tray note).
        RoomItem.entries.filter { it.isFurniture || it.isPortable }.forEach { item ->
            FurnitureTrayIcon(
                item        = item,
                onDragStart = onFurnitureDragStart,
                onDrag      = onFurnitureDrag,
                onDrop      = onFurnitureDrop,
                onCancel    = onFurnitureCancel,
            )
        }
    }
}

@Composable
private fun InventoryItemIcon(item: RoomItem, placed: Boolean, onClick: () -> Unit) {
    val color = item.room.iconColor
    Surface(
        onClick  = onClick,
        modifier = Modifier.size(52.dp),
        shape    = CircleShape,
        color    = if (placed) color.copy(alpha = 0.15f) else Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector        = item.icon,
                    contentDescription = item.label,
                    modifier           = Modifier.size(if (placed) 16.dp else 18.dp),
                    tint               = if (placed) color else color.copy(alpha = 0.45f),
                )
                Text(
                    text     = item.label,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = if (placed) color else color.copy(alpha = 0.45f),
                    maxLines = 1,
                )
            }
        }
    }
}

// A furniture piece in the room pane's tray. Not a toggle: long-press the icon and drag it
// into the 3D room to drop a new copy there. The drag is reported in root coordinates so
// MainActivity can float the ghost icon across panes and unproject the final drop point.
@Composable
private fun FurnitureTrayIcon(
    item: RoomItem,
    onDragStart: (RoomItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val color = item.room.iconColor
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .size(52.dp)
            .onGloballyPositioned { coords = it }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart  = { off -> coords?.let { onDragStart(item, it.localToRoot(off)) } },
                    onDrag       = { change, _ ->
                        change.consume()
                        coords?.let { onDrag(it.localToRoot(change.position)) }
                    },
                    onDragEnd    = { onDrop() },
                    onDragCancel = { onCancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector        = item.icon,
                contentDescription = "Add ${item.label}",
                modifier           = Modifier.size(18.dp),
                tint               = color,
            )
            Text(
                text     = item.label,
                style    = MaterialTheme.typography.labelSmall,
                color    = color,
                maxLines = 1,
            )
        }
    }
}

// A deck in the exterior pane's tray. Same tear-drag pattern as FurnitureTrayIcon, but for
// HomeFeature.DECK (not a RoomItem) — long-press and drag onto the yard, snaps to whichever
// wall it lands nearest. Supports multiple placed decks, unlike garage/pool's single slot.
@Composable
private fun DeckTrayIcon(
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val color = HomeFeature.DECK.iconColor
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .size(52.dp)
            .onGloballyPositioned { coords = it }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart  = { off -> coords?.let { onDragStart(it.localToRoot(off)) } },
                    onDrag       = { change, _ ->
                        change.consume()
                        coords?.let { onDrag(it.localToRoot(change.position)) }
                    },
                    onDragEnd    = { onDrop() },
                    onDragCancel = { onCancel() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector        = HomeFeature.DECK.icon,
                contentDescription = "Add Deck",
                modifier           = Modifier.size(18.dp),
                tint               = color,
            )
            Text(
                text     = HomeFeature.DECK.label,
                style    = MaterialTheme.typography.labelSmall,
                color    = color,
                maxLines = 1,
            )
        }
    }
}

// ── Furniture rotate/flip bar ─────────────────────────────────────────────────
// A real Dialog window (same mechanism as the camera gear dialog), so it always
// composites above the SceneView's SurfaceView layer — in-scene Compose overlays can
// lose that fight depending on how the surface is composited. The window is undimmed
// and pinned to the top edge so the room, and the piece being rotated, stay visible
// while the user taps; tapping anywhere in the scene dismisses it.
@Composable
private fun FurnitureActionsDialog(
    item: RoomItem,
    // Appliances are always tracked — shown lit, and the icon opens this instance's
    // maintenance card. Furniture/treasures opt in via the toggle (onTrack non-null) instead;
    // the icon reflects their current opt-in state rather than opening a card.
    tracked: Boolean,
    onTrack: (() -> Unit)?,
    onRotate: () -> Unit,
    onFlipX: () -> Unit,
    onFlipZ: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window  = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx   = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, Modifier.size(18.dp), tint = item.room.iconColor)
                Spacer(Modifier.width(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = onRotate) { Icon(Icons.Outlined.RotateRight, "Rotate 90°") }
                IconButton(onClick = onFlipX)  { Icon(Icons.Outlined.SwapHoriz, "Flip left–right") }
                IconButton(onClick = onFlipZ)  { Icon(Icons.Outlined.SwapVert, "Flip front–back") }
                IconButton(onClick = { onTrack?.invoke() }, enabled = onTrack != null) {
                    Icon(
                        Icons.Outlined.TrackChanges,
                        if (tracked) "Stop tracking lifespan" else "Track lifespan",
                        tint = if (tracked) MaterialTheme.colorScheme.primary
                               else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Done") }
            }
        }
    }
}

// ── Vehicle actions bar ────────────────────────────────────────────────────────
// Same shell/window mechanism as FurnitureActionsDialog, but vehicles have no rotation
// state at all — CarNode/BoatNode/MotorcycleNode always face +Z on the driveway — so
// Rotate/FlipX/FlipZ are replaced with vehicle-specific controls: a body-color radio and a
// gas/electric toggle. The Track icon (always lit — vehicles are always tracked) opens this
// instance's maintenance card, same as a Fridge's now does.
@Composable
private fun VehicleActionsDialog(
    item: RoomItem,
    colorIndex: Int,
    onColorChange: (Int) -> Unit,
    electric: Boolean,
    onToggleElectric: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window  = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx   = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, Modifier.size(18.dp), tint = item.room.iconColor)
                Spacer(Modifier.width(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                // A single swatch that cycles to the next palette entry on tap, rather than a
                // row of radio dots — keeps the bar's width fixed as the palette grows.
                val (r, g, b) = VEHICLE_BODY_COLORS[colorIndex]
                Surface(
                    onClick  = { onColorChange((colorIndex + 1) % VEHICLE_BODY_COLORS.size) },
                    modifier = Modifier
                        .size(22.dp)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                        .padding(2.dp),
                    shape = CircleShape,
                    color = Color(r, g, b),
                ) {}
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onToggleElectric) {
                    Icon(
                        if (electric) Icons.Outlined.EvStation else Icons.Outlined.LocalGasStation,
                        if (electric) "Electric — tap for gas" else "Gas — tap for electric",
                        tint = if (electric) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onOpenMaintenance) {
                    Icon(
                        Icons.Outlined.TrackChanges,
                        "Open maintenance card",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Done") }
            }
        }
    }
}

// A freely-placed garage/deck/pool's actions bar — same docked-dialog pattern as
// VehicleActionsDialog/FurnitureActionsDialog above, just Remove (repositioning is drag,
// not a button here).
@Composable
private fun FeatureActionsDialog(
    feature: HomeFeature,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window  = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx   = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(feature.icon, null, Modifier.size(18.dp), tint = feature.iconColor)
                Spacer(Modifier.width(4.dp))
                Text(feature.label, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "Remove") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Done") }
            }
        }
    }
}

// Docked top-of-screen, same as FurnitureActionsDialog — a real Dialog window so it stays
// above the 3D floor-plan scene at every camera angle, instead of a Composable racing
// SceneView's SurfaceView for z-order within the same window.
@Composable
private fun TileSelectionDialog(
    tileCount: Int,
    canCreateRoom: Boolean,
    onClear: () -> Unit,
    onCreateRoom: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClear,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Unlike the furniture bar (whose whole interaction lives inside the popup), the
            // floor plan must stay interactive while this floats: an outside tap is the user
            // selecting MORE tiles, not a dismissal.
            dismissOnClickOutside = false,
        ),
    ) {
        val window  = (LocalView.current.parent as? DialogWindowProvider)?.window
        val topPx   = with(LocalDensity.current) { 12.dp.roundToPx() }
        SideEffect {
            window?.apply {
                setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                // Dialog windows are touch-modal by default — they swallow every tap outside
                // their bounds, so the scene beneath would never see further tile taps. This
                // flag routes outside touches through to the activity window instead.
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                attributes = attributes.apply { y = topPx }
            }
        }
        Surface(
            // Bounded (not wrap-content) so the "select a bigger area" hint wraps instead of
            // requesting a window wider than the screen — Clear/Create Room stay reachable.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$tileCount tile${if (tileCount == 1) "" else "s"} selected" +
                        if (!canCreateRoom) " — select a bigger area" else "",
                    style    = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear") }
                Button(onClick = onCreateRoom, enabled = canCreateRoom) { Text("Create Room") }
            }
        }
    }
}

// ── Wall options dialog ───────────────────────────────────────────────────────

@Composable
private fun WallOptionsDialog(
    currentMode: WallMode,
    isOuter: Boolean,
    onSolid: () -> Unit,
    onOpen: () -> Unit,
    onDoor: () -> Unit,
    onWindow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isOuter) "Outer Wall" else "Wall") },
        text = {
            Text(
                text = when (currentMode) {
                    WallMode.DOOR   -> "This wall has a door."
                    WallMode.SOLID  -> "This is a solid wall."
                    WallMode.OPEN   -> "This opening has no wall."
                    WallMode.WINDOW -> "This wall has a window."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isOuter) {
                    when (currentMode) {
                        WallMode.WINDOW -> TextButton(onClick = onSolid) { Text("Remove Window") }
                        else            -> TextButton(onClick = onWindow) { Text("Add Window") }
                    }
                } else {
                    when (currentMode) {
                        WallMode.DOOR -> {
                            TextButton(onClick = onSolid) { Text("Make Solid") }
                            TextButton(onClick = onOpen) {
                                Text("Remove Wall", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        WallMode.SOLID, WallMode.WINDOW -> {
                            TextButton(onClick = onDoor) { Text("Add Door") }
                            TextButton(onClick = onOpen) {
                                Text("Remove Wall", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        WallMode.OPEN -> {
                            TextButton(onClick = onDoor) { Text("Add Door") }
                            TextButton(onClick = onSolid) { Text("Add Wall") }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Info dialog ───────────────────────────────────────────────────────────────

@Composable
private fun InfoDialog(scene: ViewState, onDismiss: () -> Unit) {
    val label = MaterialTheme.typography.labelMedium
    val body  = MaterialTheme.typography.bodySmall

    @Composable
    fun Section(title: String) {
        Spacer(Modifier.height(8.dp))
        Text(title, style = label, color = MaterialTheme.colorScheme.primary)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton    = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = {
            Text(when (scene) {
                ViewState.FLOOR_PLAN   -> "Building your Floor Plan"
                ViewState.ROOM         -> "Designing your Interior"
                ViewState.GARAGE       -> "Managing your Vehicles"
                ViewState.NEIGHBORHOOD -> "Exploring the Neighborhood"
                else                   -> "Home Maintenance & 3D"
            })
        },
        text  = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (scene) {
                    ViewState.FLOOR_PLAN -> {
                        Text("This view is where you define the structural layout of your home. What you build here determines which maintenance tasks appear in your To-Do list.", style = body)

                        Section("Structural Editing")
                        Text("• Tap the Pencil to enter edit mode.\n" +
                             "• Tap walls to cycle Solid / Open / Door / Window.\n" +
                             "• Select empty tiles and tap 'Create Room' to expand.\n" +
                             "• Long-press an existing room to change its type or remove it.", style = body)

                        Section("Storeys")
                        Text("Use the F1/F2 tabs in the pane to build and navigate multiple floors. Tap a room or the garage slab to step inside.", style = body)
                    }
                    ViewState.ROOM -> {
                        Text("Arrange your furniture and track the appliances that power your home.", style = body)

                        Section("Score (Inventory)")
                        Text("The icons in the side/bottom pane represent the appliances and fixtures for this room. Tap them to toggle ownership. Tracked items appear in the Maintenance Hub for lifespan (Score) tracking.", style = body)

                        Section("Furnishing")
                        Text("Long-press an icon in the furniture tray and drag it into the 3D room to place it. Tap a placed item to rotate, flip, or remove it. Drag an item out through a wall to delete it.", style = body)

                        Section("Camera Presets")
                        Text("Gear → Angle: Eye (walkthrough), Corner (birds-eye), Top (blueprints).", style = body)
                    }
                    ViewState.GARAGE -> {
                        Text("Your garage is more than storage—it's where vehicle maintenance is tracked.", style = body)

                        Section("Vehicle Management")
                        Text("Long-press the car, boat, or motorcycle icon and drag it onto the lot. Tapping a vehicle opens its actions bar — paint color, gas/electric, and a Track icon that opens its maintenance card.", style = body)

                        Section("Interactive Elements")
                        Text("Tap the overhead door to open or close it. A parked car will roll in and out automatically with the door.", style = body)
                    }
                    ViewState.NEIGHBORHOOD -> {
                        Text("Discover model homes and claim your own to start tracking maintenance.", style = body)

                        Section("Claiming a Home")
                        Text("Tap any house to preview it. When one matches yours, tap 'Mine' to claim it — the first claim asks for your home's name and dates once; after that they live behind the Home icon.", style = body)

                        Section("Switching Homes")
                        Text("Claim a different model any time — your name, dates, vehicles, appliance records, and documents follow you to the new home. Only the furniture layout resets to the new model.", style = body)

                        Section("Persistence")
                        Text("Your claimed home and all its customizations (rooms, furniture, vehicles) are saved locally on your device and will restore automatically.", style = body)
                    }
                    else -> {
                        Text("The Home View is your command center for exterior maintenance and systems.", style = body)

                        Section("Features & Systems")
                        Text("• Features (Garage, Pool, Deck, Yard) define your lot's footprint.\n" +
                             "• Systems (HVAC, Solar) represent your home's infrastructure.", style = body)

                        Section("Dynamic To-Do List")
                        Text("Adding a Pool adds water chemistry tasks. Marking HVAC as installed enables filter and service reminders. Your 3D model drives your real-world maintenance schedule.", style = body)

                        Section("The Wrench (Hub)")
                        Text("Tap the wrench icon in the pane to open the Maintenance Hub. Here you'll find your seasonal To-Do list, your condition Score, and DIY repair guides.", style = body)
                    }
                }
            }
        }
    )
}

// ── Room picker dialog ────────────────────────────────────────────────────────

@Composable
private fun RoomPickerDialog(
    types: List<RoomType> = RoomType.entries,
    onSelect: (RoomType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton    = {},
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add Room") },
        text  = {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
            ) {
                types.forEach { type ->
                    val color = type.iconColor
                    Surface(
                        onClick  = { onSelect(type) },
                        modifier = Modifier.width(84.dp),
                        shape    = MaterialTheme.shapes.small,
                        color    = color.copy(alpha = 0.08f),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(type.icon, contentDescription = null,
                                modifier = Modifier.size(20.dp), tint = color)
                            Text(
                                type.label,
                                style     = MaterialTheme.typography.labelSmall,
                                color     = color,
                                textAlign = TextAlign.Center,
                                maxLines  = 2,
                            )
                        }
                    }
                }
            }
        }
    )
}

// ── Placement options dialog ──────────────────────────────────────────────────

@Composable
private fun PlacementOptionsDialog(
    placement: RoomPlacement,
    availableTypes: List<RoomType> = RoomType.entries,
    onEnter: () -> Unit,
    onRemove: () -> Unit,
    onChangeType: (RoomType) -> Unit,
    onDismiss: () -> Unit,
) {
    var showTypePicker by remember { mutableStateOf(false) }

    if (showTypePicker) {
        RoomPickerDialog(
            types     = availableTypes,
            onSelect  = { type -> onChangeType(type) },
            onDismiss = { showTypePicker = false },
        )
        return
    }

    val color = placement.type.iconColor
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(placement.type.icon, null, Modifier.size(20.dp), tint = color)
                Text(placement.type.label)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Floor ${placement.floor + 1}  ·  col ${placement.col + 1}, row ${placement.row + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showTypePicker = true }) { Text("Change") }
                    OutlinedButton(onClick = onRemove) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onEnter) { Text("Enter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
