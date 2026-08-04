package com.homehealth.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import android.app.Activity
import android.content.Intent
import android.provider.OpenableColumns
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.homehealth.data.toMaintenanceTask
import com.homehealth.db.HomeStateSerialization
import com.homehealth.model.*
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.renderspec.NeighborhoodSceneGeometry
import com.homehealth.renderspec.hiddenAsset
import com.homehealth.renderspec.Vec3
import com.homehealth.renderspec.slopeOffset
import com.homehealth.scene.FLOOR_HEIGHT_M
import com.homehealth.scene.WALL_T
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
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
enum class ViewState    { EXTERIOR, FLOOR_PLAN, ROOM, NEIGHBORHOOD }

// The three kinds of place ViewState.ROOM can be showing. All three share one scene (RoomScene),
// one camera slot, one pane and one Back path — the whole point of folding the garage and attic
// scenes back in — but they differ in what the scene draws and what the pane offers, and that is
// exactly a three-way question rather than two booleans (which would spell a fourth, nonsense,
// state):
//   INDOOR   — a real room from the floor plan: walls, floor, its own furniture and tray.
//   DRIVEWAY — the lot you stand on: no walls, the garage shell and the fleet (see DrivewayLot).
//   ATTIC    — the space over the top storey: its own shell, the mechanicals, keepsakes.
enum class RoomPlace { INDOOR, DRIVEWAY, ATTIC }

// Camera angle presets, shared by every scene so one name always means one thing:
//   TOP  — straight down, floor-plan style, unoccluded by perspective. Best for placing things.
//   SIDE — the oblique 3D overview; each scene scales its own standoff to its own footprint.
//   EYE  — standing at human eye height inside the space, looking across it.
// Which subset a scene offers is [ViewState.camAngles]; the framing math itself stays per-scene
// (see the camera blocks in HomeApp) since a room, a driveway and a neighbourhood have nothing
// dimensionally in common. Replaces the old RoomCamAngle/ExteriorCamAngle/GarageCamAngle trio,
// which spelled the same two concepts three ways ("Corner" vs "Side") and forced every consumer
// — CameraGearButton, the settings dialog, all eight panes — to carry three parallel params.
enum class SceneCamAngle(val label: String) { EYE("Eye"), SIDE("Side"), TOP("Top") }

// EYE needs somewhere a person could actually stand. A room has an interior and the driveway is
// a place you stand on; the neighbourhood has a street — you stand in the middle of the cul-de-sac
// and turn to look at the homes around you. The floor plan has neither, so it keeps Side/Top.
val ViewState.camAngles: List<SceneCamAngle> get() = when (this) {
    ViewState.ROOM, ViewState.NEIGHBORHOOD ->
        listOf(SceneCamAngle.EYE, SceneCamAngle.SIDE, SceneCamAngle.TOP)
    else -> listOf(SceneCamAngle.SIDE, SceneCamAngle.TOP)
}

// ...with one exception inside ROOM: the attic is a crawl space you look INTO, not one you stand
// up in, so it drops EYE. Places are what differ here, not scenes, which is why this hangs off
// RoomPlace rather than adding a fourth ViewState back.
fun ViewState.camAngles(place: RoomPlace): List<SceneCamAngle> =
    if (this == ViewState.ROOM && place == RoomPlace.ATTIC)
        listOf(SceneCamAngle.SIDE, SceneCamAngle.TOP)
    else camAngles

// Every scene opens on its oblique overview. GARAGE and EXTERIOR are additionally forced to TOP
// at specific moments (entering the garage; cycling a yard feature onto another wall) — see the
// setCamAngle call sites.
val ViewState.defaultCamAngle: SceneCamAngle get() = SceneCamAngle.SIDE

/**
 * Returns a unit vector pointing FROM the room centre TOWARD the wall the room is entered
 * through. When the door is on the +X side, for example, this returns Float3(1,0,0) and the
 * EYE camera stands just inside that wall looking toward the back (–X) wall.
 *
 * Resolved in preference order: a wall actually rendered as a DOOR, then an OPEN threshold,
 * then the direction of the NEAREST hallway, then the front (+Z) wall. The wall modes are what
 * RoomScene itself renders (see wallModesForZone), so "the doorway" now names the opening the
 * user can actually see rather than, as before, whichever hallway happened to come first in the
 * zone list — which on the default layout (a vertical spine AND a horizontal bridge) picked an
 * arbitrary one and regularly put the camera facing a solid wall.
 */
private fun doorSide(zone: RoomZone, layout: FloorLayout, floor: Int): Float3 {
    fun vec(side: FeatureSide) = when (side) {
        FeatureSide.LEFT  -> Float3(-1f, 0f, 0f)
        FeatureSide.RIGHT -> Float3(1f, 0f, 0f)
        FeatureSide.BACK  -> Float3(0f, 0f, -1f)
        FeatureSide.FRONT -> Float3(0f, 0f, 1f)
    }
    val modes = layout.wallModesForZone(zone, floor)
    modes.entries.firstOrNull { it.value == WallMode.DOOR }?.let { return vec(it.key) }
    modes.entries.firstOrNull { it.value == WallMode.OPEN }?.let { return vec(it.key) }
    val hallway = layout.toZones(floor).filter { it.type == RoomType.HALLWAY }
        .minByOrNull { (it.cx - zone.cx) * (it.cx - zone.cx) + (it.cz - zone.cz) * (it.cz - zone.cz) }
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
    HiddenAsset.DUCTWORK          -> Icons.Outlined.Air
    HiddenAsset.ATTIC_INSULATION  -> Icons.Outlined.Layers
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
    HiddenAsset.DUCTWORK          -> Color(0xFF78909C)   // galvanized steel
    HiddenAsset.ATTIC_INSULATION  -> Color(0xFFEC407A)   // pink batt
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
    HiddenAsset.DUCTWORK          -> 25
    HiddenAsset.ATTIC_INSULATION  -> 30
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
    HomeSystem.SOLAR      -> "Solar"
    HomeSystem.HVAC       -> "HVAC"
    HomeSystem.EV_BATTERY -> "EV Battery"
}

private val HomeSystem.icon: ImageVector get() = when (this) {
    HomeSystem.SOLAR      -> Icons.Outlined.WbSunny
    HomeSystem.HVAC       -> Icons.Outlined.AcUnit
    HomeSystem.EV_BATTERY -> Icons.Outlined.BatteryChargingFull
}

private val HomeSystem.iconColor: Color get() = when (this) {
    HomeSystem.SOLAR      -> Color(0xFFF9A825)   // amber — sun
    HomeSystem.HVAC       -> Color(0xFF0288D1)   // blue  — cooling
    HomeSystem.EV_BATTERY -> Color(0xFF00897B)   // teal  — battery
}

private val YardDecorKind.label: String get() = when (this) {
    YardDecorKind.TREE   -> "Tree"
    YardDecorKind.GAZEBO -> "Gazebo"
}

// Which house wall a cycled feature (pool/garage/HVAC/EV battery) currently sits on — shown
// on FeatureActionsDialog next to the rotate button so a cycle click never leaves the user
// hunting for where the item went (see nextCycleSide/yardCycleSides above).
private val FeatureSide.label: String get() = when (this) {
    FeatureSide.FRONT -> "Front"
    FeatureSide.RIGHT -> "Right"
    FeatureSide.BACK  -> "Back"
    FeatureSide.LEFT  -> "Left"
}

private val YardDecorKind.icon: ImageVector get() = when (this) {
    YardDecorKind.TREE   -> Icons.Outlined.Park
    YardDecorKind.GAZEBO -> Icons.Outlined.Cabin
}

private val YardDecorKind.iconColor: Color get() = when (this) {
    YardDecorKind.TREE   -> Color(0xFF2E7D32)   // green — foliage
    YardDecorKind.GAZEBO -> Color(0xFF6D4C41)   // brown — timber
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
    RoomItem.COFFEE_TABLE   -> Icons.Outlined.TableBar
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

// Kitchen: only one per home — but reassigning it now retypes as a SWAP rather than being
// blocked outright (see performSingletonSwap/requestRoomTypeChange), so it stays offered here;
// instead it's gated by whether the candidate room can actually fit a kitchen appliance run
// (canFitKitchenRun), which minCellArea alone can't guarantee (a 2x2 clears the area check but
// has no room for fridge+stove+sink+dishwasher). Hallway: still only one per floor, no swap
// semantics. Garage: locked to wherever the TOWNHOUSE preset's exterior garage door/driveway
// geometry expects it (isValidGaragePosition) — reassigning it elsewhere would silently break
// that geometry, so it's excluded rather than offered as a swap target. Every type also needs
// to clear its own minCellArea (fitsFootprint) — colSpan/rowSpan default to "unbounded" so
// callers checking general room-creation eligibility (not a specific selection) still see
// every type (Garage excluded in that case too, since no real position is being checked).
private fun availableRoomTypes(
    layout: FloorLayout,
    floor: Int,
    excludeId: String? = null,
    col: Int = 0,
    row: Int = 0,
    colSpan: Int = Int.MAX_VALUE,
    rowSpan: Int = Int.MAX_VALUE,
): List<RoomType> {
    val others = if (excludeId != null) layout.rooms.filter { it.id != excludeId } else layout.rooms
    val depthM = if (rowSpan == Int.MAX_VALUE) Float.MAX_VALUE else rowSpan * layout.cellD
    return RoomType.entries.filter { type ->
        type.fitsFootprint(colSpan, rowSpan) && when (type) {
            RoomType.KITCHEN -> canFitKitchenRun(depthM)
            RoomType.HALLWAY -> others.none { it.floor == floor && it.type == RoomType.HALLWAY }
            RoomType.GARAGE  -> layout.isValidGaragePosition(RoomPlacement(
                type = RoomType.GARAGE, col = col, row = row, colSpan = colSpan, rowSpan = rowSpan, floor = floor,
            ))
            else             -> true
        }
    }
}

private val HomeStyle.isAttached get() = this == HomeStyle.CONDO

// A room-type change onto an already-furnished room, awaiting a Replace/Keep choice.
private data class PendingReplaceOrKeep(val placement: RoomPlacement, val targetType: RoomType)

// A room-type change whose target type (Kitchen or Garage) already exists elsewhere in the
// home — handled as a swap (see performSingletonSwap) rather than blocked outright, awaiting
// the user's confirmation since two rooms change at once.
private data class PendingTypeSwap(
    val target: RoomPlacement,
    val targetType: RoomType,
    val existing: RoomPlacement,
)


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
    // HVAC — single slot like garage/pool, but no separate offsets map: side+along live
    // together since (unlike garage/pool) nothing else ever reads just the side.
    var hvacPlacement     by remember { mutableStateOf<Pair<FeatureSide, Float>?>(null) }
    // Solar array — single slot like HVAC, a grid of small tiles on one roof slope
    // (HouseScene.kt's FeaturesOnYard).
    var solarArrayPlacement by remember { mutableStateOf<Pair<Boolean, Float>?>(null) }
    // EV Battery — single slot like HVAC, wall-mounted on any of the 4 sides.
    var evBatteryPlacement by remember { mutableStateOf<Pair<FeatureSide, Float>?>(null) }
    // Trees/gazebo — any number of instances of either kind, free-2D-placed anywhere in the
    // yard (no wall-snap, no house-adjacency preference), same tear-drag pattern as placedDecks.
    var placedYardDecor   by remember { mutableStateOf(emptyList<PlacedYardDecor>()) }
    // User-named appliances not covered by the app's fixed taxonomy, and their user-defined
    // recurring tasks — independent of any claimed model (never reset by claimNeighbor).
    var customAppliances  by remember { mutableStateOf(emptyList<CustomAppliance>()) }
    var customTasks       by remember { mutableStateOf(emptyList<CustomTask>()) }
    var showAddCustomAppliance by remember { mutableStateOf(false) }
    var showAddCustomTask      by remember { mutableStateOf(false) }
    var previewedNeighbor by remember { mutableStateOf<NeighborHome?>(null) }
    val previewLayout     = remember(previewedNeighbor) {
        val n = previewedNeighbor
        if (n != null) buildPreviewLayout(n) else FloorLayout()
    }
    // Neighbor previews render furnished too — same seeding a claim would apply.
    val previewFurniture  = remember(previewLayout) { previewLayout.defaultFurniturePlacedItems() }
    var showInfo              by remember { mutableStateOf(false) }
    // Bottom by default: on a phone it leaves the scene the full width of the screen, which
    // matters far more than the extra vertical room a side pane would give. Tear it to the
    // right at any time (every pane's onTear flips this).
    var panePosition          by remember { mutableStateOf(PanePosition.BOTTOM) }
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
    // Whether the room tray's "All items" overflow is expanded. Hoisted (rather than remembered
    // inside a pane) so tearing the pane between bottom and right doesn't collapse it mid-use;
    // reset on leaving a room, so each one opens showing just its own relevant items.
    var trayShowAll           by remember { mutableStateOf(false) }
    // Same tear-drag pattern as trayDrag, for the deck/garage/pool tray icons — separate
    // states since none of them are a RoomItem (trayDrag's type), and each feature is its
    // own single/multi-slot concern.
    var deckTrayDrag          by remember { mutableStateOf<Offset?>(null) }
    var garageTrayDrag        by remember { mutableStateOf<Offset?>(null) }
    var poolTrayDrag          by remember { mutableStateOf<Offset?>(null) }
    var hvacTrayDrag          by remember { mutableStateOf<Offset?>(null) }
    var solarTrayDrag         by remember { mutableStateOf<Offset?>(null) }
    var evBatteryTrayDrag     by remember { mutableStateOf<Offset?>(null) }
    var treeTrayDrag          by remember { mutableStateOf<Offset?>(null) }
    var gazeboTrayDrag        by remember { mutableStateOf<Offset?>(null) }
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
    // Tapped the placed HVAC unit — same actions-bar (Remove) pattern, single slot.
    var selectedHvac by remember { mutableStateOf(false) }
    LaunchedEffect(viewState) { selectedHvac = false }
    // Tapped the placed solar array — same actions-bar (Remove) pattern, single slot.
    var selectedSolar by remember { mutableStateOf(false) }
    LaunchedEffect(viewState) { selectedSolar = false }
    // Tapped the placed EV Battery — same actions-bar (Remove) pattern, single slot.
    var selectedEvBattery by remember { mutableStateOf(false) }
    LaunchedEffect(viewState) { selectedEvBattery = false }
    // Selected placed tree/gazebo instance — same actions-bar (Remove) pattern, per-instance.
    var selectedYardDecorId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewState) { selectedYardDecorId = null }
    // ── "Not in my home" for a room-item / HiddenAsset / HomeSystem key ───────────────────
    // One toggle behind every entry point (the maintenance card's button, the attic pane's
    // mechanical row) so the two can't disagree. Hiding a HomeSystem also takes its unit out of
    // the 3D scene and hands the icon back to the tray: the yard is a model of *this* home, so a
    // condenser can't keep standing on a wall whose owner just said there's no HVAC.
    // Deliberately one-directional — the actions bar's Remove stays physical-only, and
    // dropHvacAt/dropSolarAt/dropEvBatteryAt already clear the key on placement, so there's no
    // toggle loop. Never touches DB records: this is a hide, not a delete, so an install year or
    // an attached document survives the round trip (see MaintenanceTarget.Placed/Custom for the
    // two kinds that really do delete).
    val toggleRemovedKey: (String) -> Unit = { key ->
        val hiding = key !in removedInstances
        removedInstances = if (hiding) removedInstances + key else removedInstances - key
        // Clearing the selection closes an actions bar left open on a unit that no longer exists.
        if (hiding) when (key) {
            HomeSystem.HVAC.name       -> { hvacPlacement       = null; selectedHvac      = false }
            HomeSystem.SOLAR.name      -> { solarArrayPlacement = null; selectedSolar     = false }
            HomeSystem.EV_BATTERY.name -> { evBatteryPlacement  = null; selectedEvBattery = false }
        }
    }
    // Per-scene camera settings — independent so tuning one scene's camera never affects another.
    var exteriorCamSettings     by remember { mutableStateOf(CameraSettings()) }
    var floorPlanCamSettings    by remember { mutableStateOf(CameraSettings(zoomSpeed = 0.15f)) }
    var roomCamSettings         by remember { mutableStateOf(CameraSettings()) }
    var neighborhoodCamSettings by remember { mutableStateOf(CameraSettings()) }
    // Per-scene camera angle, one map instead of a var per scene. Every scene offers an angle
    // control now, and the panes are shared across scenes (HomePane serves both
    // EXTERIOR and NEIGHBORHOOD), so keying on ViewState lets both read the right value with no
    // branching. A scene absent from the map reads its default, so adding a scene needs no new
    // state. Written by the gear dialog and by the few places that force an angle: entering the
    // garage (always TOP — you're there to arrange vehicles) and cycling a yard feature onto
    // another wall (TOP so the moved item stays in frame).
    var camAngles by remember { mutableStateOf(mapOf<ViewState, SceneCamAngle>()) }
    fun camAngleOf(vs: ViewState) = camAngles[vs] ?: vs.defaultCamAngle
    fun setCamAngle(vs: ViewState, angle: SceneCamAngle) { camAngles = camAngles + (vs to angle) }
    // Where the room view was entered from — Back returns there. Rooms are reached from the
    // floor plan, the driveway from the exterior too, so this can't be a constant.
    var roomReturn              by remember { mutableStateOf(ViewState.FLOOR_PLAN) }
    // Which kind of place the room view is showing (see RoomPlace). Both synthetic ones — the
    // driveway and the attic — have DERIVED zones rather than stored ones (the driveway's extent
    // grows with the fleet parked on it; the attic's follows the home's footprint and the owner's
    // AtticType), so what's remembered is only that this is where the user is.
    var roomPlace               by remember { mutableStateOf(RoomPlace.INDOOR) }
    val atDriveway              = roomPlace == RoomPlace.DRIVEWAY
    val atAttic                 = roomPlace == RoomPlace.ATTIC
    // Mirrors the overhead door's animation (0 = closed, 1 = fully open) so
    // dropVehicleAt can tell whether an anchor-parked car is still mid pull-out instead of
    // assuming the door (and any car rolling out with it) is already all the way open.
    var garageDoorFraction      by remember { mutableStateOf(0f) }
    // What the home has above its top storey — a walk-in attic or a mechanical closet. NULL means
    // the owner has never chosen, which resolves to the roof's own default (see AtticType and
    // HouseSceneGeometry.defaultAtticType), so a save from before the choice existed keeps exactly
    // the space it had. Set from inside the attic (tap the floor) or the home-info dialog.
    var atticType             by remember { mutableStateOf<AtticType?>(null) }
    var showAtticTypeDialog   by remember { mutableStateOf(false) }
    // Maintenance dialogs
    var pendingClaimNeighbor  by remember { mutableStateOf<NeighborHome?>(null) }
    var showHomeDatesDialog   by remember { mutableStateOf(false) }
    var showMaintenanceHub    by remember { mutableStateOf(false) }
    // First-claim celebration: points the brand-new owner at the Maintenance hub, the
    // whole reason to claim. Fires on the savedHome null→non-null transition only.
    var showFirstClaimHint    by remember { mutableStateOf(false) }
    // Every LATER claim (a genuine model switch, or "Reset to default" on the same model) —
    // explains that this is a reset of the model-specific state (layout/features/furniture
    // seeding), not a wipe: appliance/document history, vehicles, and custom items carry over.
    var showClaimResetHint    by remember { mutableStateOf(false) }
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
    // A room-type change targeting a currently-furnished room, awaiting the user's choice of
    // whether to clear its furniture for the new type's defaults or leave it as-is.
    var pendingReplaceOrKeep by remember { mutableStateOf<PendingReplaceOrKeep?>(null) }
    // A room-type change targeting Kitchen/Garage when one already exists elsewhere — a swap
    // awaiting the user's confirmation (see performSingletonSwap).
    var pendingTypeSwap by remember { mutableStateOf<PendingTypeSwap?>(null) }

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
            // Blank means never chosen — left null so it keeps resolving to the roof's default.
            atticType         = AtticType.entries.find { it.name == saved.atticType }
            featurePlacements = HomeStateSerialization.deserializeFeaturePlacements(saved.featurePlacementsJson)
            featureOffsets    = HomeStateSerialization.deserializeFeatureOffsets(saved.featureOffsetsJson)
            placedDecks       = HomeStateSerialization.deserializePlacedDecks(saved.placedDecksJson)
            hvacPlacement     = HomeStateSerialization.deserializeHvacPlacement(saved.hvacPlacementJson)
            solarArrayPlacement = HomeStateSerialization.deserializeSolarArrayPlacement(saved.solarArrayPlacementJson)
            evBatteryPlacement = HomeStateSerialization.deserializeEvBatteryPlacement(saved.evBatteryPlacementJson)
            placedYardDecor   = HomeStateSerialization.deserializePlacedYardDecor(saved.placedYardDecorJson)
            customAppliances  = HomeStateSerialization.deserializeCustomAppliances(saved.customAppliancesJson)
            customTasks       = HomeStateSerialization.deserializeCustomTasks(saved.customTasksJson)
            floorLayout       = HomeStateSerialization.deserializeFloorLayout(saved.floorLayoutJson)
            itemOffsets       = HomeStateSerialization.deserializeItemOffsets(saved.itemOffsetsJson)
            placedItems       = HomeStateSerialization.deserializePlacedItems(saved.placedItemsJson)
            removedInstances  = HomeStateSerialization.deserializeRemovedInstances(saved.removedInstancesJson)
            // Settle the fleet against the lot this save actually restored into. The reactive
            // effect below covers later changes, but it keys off floorLayout/garage/style — a
            // restore that lands on values equal to the ones already in state wouldn't retrigger
            // it, leaving vehicles stacked on the same anchor ("first car vanished").
            val vehicles = placedItems.filter { it.item.isVehicle }
            if (vehicles.isNotEmpty()) {
                val vehicleItems = vehicles.map { it.item }
                val lot = CarLotGeometry.homeLot(
                    floorLayout, featurePlacements, featureOffsets, vehicleItems)
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
        val lot = CarLotGeometry.homeLot(
            floorLayout, featurePlacements, featureOffsets, vehicles.map { it.item })
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
    LaunchedEffect(floorLayout, featurePlacements, featureOffsets, placedDecks, hvacPlacement,
                   solarArrayPlacement, evBatteryPlacement, placedYardDecor, homeStyle, atticType,
                   itemOffsets, placedItems, removedInstances, customAppliances, customTasks) {
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
                hvacPlacement     = hvacPlacement,
                solarArrayPlacement = solarArrayPlacement,
                evBatteryPlacement = evBatteryPlacement,
                placedYardDecor   = placedYardDecor,
                customAppliances  = customAppliances,
                customTasks       = customTasks,
                floorLayout       = floorLayout,
                itemOffsets       = itemOffsets,
                placedItems       = placedItems,
                removedInstances  = removedInstances,
                atticType         = atticType,
                buildYear         = saved.buildYear,
                purchaseYear      = saved.purchaseYear,
                label             = saved.label,
                neighborKey       = saved.neighborKey,
            )
        }
    }

    // Every physical instance of every RoomItem in the house — one entry per auto-populated
    // default (one per room of that item's nominal type, unless individually removed) plus
    // one per user-added extra copy — numbered "Fridge 1"/"Fridge 2"/etc. across the WHOLE
    // house when there's more than one, so the tool box can track and open a maintenance
    // card for each specific instance rather than one shared record per item type. Every
    // placed extra is tracked, furniture included — no more opt-in toggle.
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
            val extras = placedItems.filter { it.item == ri }
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

    // ── Live task list ────────────────────────────────────────────────────────────────────
    // Hoisted above the hub dialog that used to own it: three consumers now need it — the hub's
    // To-Do tab, the appliance card's due-task list, and the room pane's maintenance strip —
    // and the latter two render with the hub closed. All pure remembered derivations, so
    // computing them unconditionally costs nothing beyond the memo.
    val roomTypes = remember(floorLayout) {
        floorLayout.rooms.map { it.type }.toSet()
    }
    // Every vehicle, not just a "do we own one" flag: each generates its own task set from its
    // own electric flag, so toggling one car's fuel type re-derives only that car's tasks.
    val vehicles = remember(placedItems) { placedItems.filter { it.item.isVehicle } }
    // The attic, resolved. [atticType] is what the OWNER chose (null = never chosen), so every
    // read goes through this instead — the roof supplies the default. Declared here because the
    // task schedule below is the first thing to ask.
    val resolvedAtticType = atticType ?: HouseSceneGeometry.defaultAtticType(homeStyle)
    val tasks = remember(homeStyle, featurePlacements, placedDecks, roomTypes, removedInstances,
                         vehicles, customTasks, resolvedAtticType) {
        com.homehealth.data.HomeTaskList.forHome(
            style      = homeStyle,
            features   = featurePlacements.keys,
            // Every HomeSystem is always applicable (available in every home) — removedInstances
            // (the same "not in my home" set HiddenAsset uses) is what actually filters tasks
            // out below, not this set.
            systems    = HomeSystem.entries.toSet(),
            roomTypes  = roomTypes,
            removedInstances = removedInstances,
            vehicles         = vehicles,
            hasDeck          = placedDecks.isNotEmpty(),
            hasTrees         = placedYardDecor.any { it.kind == YardDecorKind.TREE },
            hasGazebo        = placedYardDecor.any { it.kind == YardDecorKind.GAZEBO },
            // Only ever widens the schedule (see HiddenAsset.isApplicable): a condo that declares
            // a full attic gains the duct/insulation tasks; nothing ever loses one by choosing.
            atticType        = resolvedAtticType,
        // User-defined tasks (see CustomTask.toMaintenanceTask) merged in alongside the
        // app's built-in ones — HomeTaskList.forHome itself has no DB access and knows
        // nothing about user-created content.
        ) + customTasks.map { it.toMaintenanceTask() }
    }
    val recordMap = remember(taskRecords) { taskRecords.associateBy { it.taskKey } }

    // Everything a room view draws around itself — the storey, the home's items, and the vehicle
    // lot derived from them (see RoomSurroundings). One value, built once, so the scene, the
    // driveway zone, the vehicle drop and the camera can't disagree about where this home parks.
    val surroundings = RoomSurroundings(
        layout            = floorLayout,
        floor             = currentFloor,
        placedItems       = placedItems,
        featurePlacements = featurePlacements,
        featureOffsets    = featureOffsets,
    )
    // The attic's shape. The two questions stay separate: the type decides the size, the roof
    // style decides the pitch (see atticVolume).
    val atticVolume = remember(floorLayout, homeStyle, resolvedAtticType) {
        HouseSceneGeometry.atticVolume(
            floorLayout.totalW, floorLayout.totalD,
            wallTopY = (floorLayout.maxFloor() + 1) * FLOOR_HEIGHT_M,
            style = homeStyle, type = resolvedAtticType,
        )
    }
    // What's up there to see and tap. An item switched off in the attic pane's inventory row
    // stops being drawn, the same way a room drops a default marked "not in my home".
    val atticContents = remember(resolvedAtticType, removedInstances) {
        HouseSceneGeometry.atticContents(resolvedAtticType, removedInstances)
    }

    val atticZone = HouseSceneGeometry.atticZone(atticVolume)

    // ── Orphan rescue ────────────────────────────────────────────────────────────────────────
    // Deleting a room, shrinking the grid, claiming a different model, or restoring a save written
    // by an older version can all leave a PlacedItem whose placementId names a room that no longer
    // exists. Those used to be deleted outright, which is fine for a sofa and not fine for a
    // keepsake or for the water heater whose warranty PDF the user attached last year: a
    // MaintenanceTarget.Placed's identity IS its placement, so dropping it strands its
    // ApplianceRecordEntity/DocumentEntity rows in the DB, unreachable through any UI.
    //
    // So anything the user put something OF THEIR OWN into gets re-homed into a real place they can
    // walk into and find it — the garage if it's the sort of thing a garage holds, the attic
    // otherwise — while genuinely anonymous furniture still prunes as before.
    fun hasTrackedHistory(pi: PlacedItem): Boolean {
        val key = "placed:${pi.id}"
        return applianceRecs.any { it.itemKey == key } || allDocuments.any { it.itemKey == key }
    }
    fun worthRescuing(pi: PlacedItem) = pi.item == RoomItem.TREASURE || hasTrackedHistory(pi)

    // Where a rescued thing lands. The garage first, whenever this home has one and the item is
    // something a garage can hold — RoomItem.trayRooms already answers exactly that question, and
    // it's where anyone would actually put a displaced water heater. The attic is the fallback:
    // for what a garage can't take (a toilet, a bed), and for homes that have no garage at all.
    fun rescueHome(pi: PlacedItem): Pair<RoomZone, String> {
        val garageId = surroundings.garagePlacementId
        return if (garageId != null && RoomType.GARAGE in pi.item.trayRooms)
            surroundings.garageZone to garageId
        else atticZone to ATTIC_PLACEMENT_ID
    }

    // Clamped inside the destination and stepped along its floor, so rescuing several doesn't pile
    // them all on one spot. Offsets are zone-relative, the convention every placed item uses.
    // Deliberately not capacity-gated: a crowded garage beats a deleted keepsake.
    fun intoSpace(
        items: List<PlacedItem>, zone: RoomZone, placementId: String, startIndex: Int,
    ): List<PlacedItem> {
        val margin = 0.4f
        val halfW = (zone.w / 2f - margin).coerceAtLeast(0f)
        val halfD = (zone.d / 2f - margin).coerceAtLeast(0f)
        val step = 0.8f
        val perRow = ((halfW * 2f) / step).toInt().coerceAtLeast(1)
        return items.mapIndexed { i, pi ->
            val n = startIndex + i
            val wx = (-halfW + (n % perRow) * step).coerceIn(-halfW, halfW)
            val wz = (-halfD + (n / perRow) * step).coerceIn(-halfD, halfD)
            pi.copy(
                placementId = placementId,
                dx = wx - pi.item.xFrac * zone.w * 0.38f,
                dz = wz - pi.item.zFrac * zone.d * 0.38f,
            )
        }
    }

    // Re-homes [rescued] into whichever space each one belongs in, continuing the step pattern
    // after whatever is already standing there so a rescue never lands on top of existing items.
    fun reHome(rescued: List<PlacedItem>, alongside: List<PlacedItem>): List<PlacedItem> =
        rescued.groupBy { rescueHome(it) }.flatMap { (dest, group) ->
            val (zone, placementId) = dest
            intoSpace(group, zone, placementId, alongside.count { it.placementId == placementId })
        }

    // The one call every "a room may have just disappeared" path goes through, so the policy can't
    // differ between deleting a room and shrinking a column.
    fun rescueOrphans(layout: FloorLayout, items: List<PlacedItem>): List<PlacedItem> {
        val sweep = layout.sweepOrphanedPlacedItems(items)
        if (sweep.orphaned.isEmpty()) return sweep.kept
        val rescued = sweep.orphaned.filter { worthRescuing(it) }
        if (rescued.isEmpty()) return sweep.kept
        return sweep.kept + reHome(rescued, sweep.kept)
    }

    // The safety net, for orphans nothing in this session created: a save written by an older
    // version, a migration that renamed a room id, a bug since fixed. The explicit rescueOrphans
    // calls above keep each individual edit atomic; this catches whatever arrives already broken.
    //
    // Deliberately NON-DESTRUCTIVE, unlike rescueOrphans: it only re-homes what it's sure is worth
    // keeping and leaves everything else exactly as it found it. The maintenance tables load
    // asynchronously, so a sweep that also deleted could race them and bin an item whose documents
    // simply hadn't arrived yet — this way the worst case is that a rescue happens a moment later.
    LaunchedEffect(floorLayout, placedItems, applianceRecs, allDocuments, homeRestoredFromDb) {
        if (!homeRestoredFromDb) return@LaunchedEffect
        val orphaned = floorLayout.sweepOrphanedPlacedItems(placedItems).orphaned
        val rescued = orphaned.filter { worthRescuing(it) }
        if (rescued.isEmpty()) return@LaunchedEffect
        val rescuedIds = rescued.map { it.id }.toSet()
        val survivors = placedItems.filterNot { it.id in rescuedIds }
        placedItems = survivors + reHome(rescued, survivors)
    }

    // The zone the room view is showing: a real room, the driveway, or the attic. Both synthetic
    // ones are derived rather than stored — the driveway's extent grows with the fleet parked on
    // it (a car added while standing there widens the place you're standing on) and the attic's
    // follows the footprint and the chosen type.
    val activeRoomZone: RoomZone? = when (roomPlace) {
        RoomPlace.DRIVEWAY -> surroundings.drivewayZone
        RoomPlace.ATTIC    -> HouseSceneGeometry.atticZone(atticVolume)
        RoomPlace.INDOOR   -> activeZone
    }

    // Turns a place's candidate targets into its maintenance strip: drops anything with no
    // recurring tasks (upkeepStatusFor returns null), then orders worst-first so what needs
    // attention leads. Shared by the room, garage and attic strips so all three agree on both
    // status and ordering.
    val maintenanceEntries: (List<MaintenanceTarget>) -> List<Pair<MaintenanceTarget, UpkeepStatus>> =
        { targets ->
            targets.mapNotNull { target ->
                upkeepStatusFor(tasksForTarget(target, tasks), recordMap)?.let { target to it }
            }.sortedBy { (_, status) -> status.ordinal }
        }

    // The things in the room the user is standing in that carry recurring tasks — the room
    // pane's maintenance strip, which turns opening a card from "wrench, Score tab, scroll the
    // whole house" into one tap on a red/amber/green icon.
    //
    // Room membership follows ACTUAL PLACEMENT, never RoomItem.room, matching how taskScope
    // already scopes the hub's To-Do list: a fridge dragged into the garage is maintained from
    // the garage. Built from instancesByItem rather than re-deriving instances, so the strip's
    // labels ("Fridge 1"/"Fridge 2") and identity match the Score tab exactly.
    val activeRoomMaintenance = remember(activeRoomZone, roomPlace, currentFloor, floorLayout,
                                         placedItems, instancesByItem, tasks, recordMap) {
        // Both synthetic places have their own strip (garageMaintenance / atticMaintenance) —
        // neither is made of room-placed items, so there's nothing here to gather for them.
        val zone = if (roomPlace == RoomPlace.INDOOR) activeZone else null
        if (zone == null) emptyList() else {
            val ids = floorLayout.placementIdsInZone(zone, currentFloor)
            val placedById = placedItems.associateBy { it.id }
            maintenanceEntries(
                instancesByItem.values.flatten().filter { target ->
                    when (target) {
                        // Vehicles hang off CAR_PLACEMENT_ID rather than a room, but RoomScene
                        // parks them inside a GARAGE room, so the strip matches what's visible.
                        is MaintenanceTarget.Placed ->
                            placedById[target.id]?.let { pi ->
                                pi.placementId in ids ||
                                    (pi.item.isVehicle && zone.type == RoomType.GARAGE)
                            } ?: false
                        is MaintenanceTarget.Item -> target.instanceKey.substringBefore(':') in ids
                        else -> false
                    }
                }
            )
        }
    }

    // The garage scene's own strip: every parked vehicle plus the door itself. Mirrors the
    // ATTIC/GARAGE branches of taskScope below, so the strip and the hub's scoped To-Do list
    // always describe the same place.
    val garageMaintenance = remember(instancesByItem, tasks, recordMap, placedItems,
                                     surroundings.garagePlacementId) {
        // Everything maintained from out here: the fleet, the door itself, and whatever is kept
        // INSIDE the garage — a water heater in there should open its card from the place it
        // physically stands, not only from the hub.
        val garageIds = setOfNotNull(surroundings.garagePlacementId)
        val insideIds = placedItems.filter { it.placementId in garageIds }.map { it.id }.toSet()
        maintenanceEntries(
            instancesByItem.values.flatten().filter {
                it is MaintenanceTarget.Placed && (it.item.isVehicle || it.id in insideIds)
            } + MaintenanceTarget.Hidden(HiddenAsset.GARAGE_DOOR)
        )
    }

    // The attic's strip — the home's mechanical systems. HVAC's air handler and the ductwork/
    // insulation around it are physically in here; solar and the EV battery are roof/wall mounted
    // rather than inside the void, but this is the home's one mechanical place and burying their
    // cards in the hub is the friction this whole strip exists to remove. Anything that doesn't
    // apply drops out for free — an asset with no tasks resolves to no entry — which covers both a
    // CONDO with no ducts and anything switched off in the attic pane's own inventory row.
    val atticMaintenance = remember(tasks, recordMap) {
        maintenanceEntries(
            listOf(
                MaintenanceTarget.System(HomeSystem.HVAC),
                MaintenanceTarget.Hidden(HiddenAsset.DUCTWORK),
                MaintenanceTarget.Hidden(HiddenAsset.ATTIC_INSULATION),
                MaintenanceTarget.System(HomeSystem.SOLAR),
                MaintenanceTarget.System(HomeSystem.EV_BATTERY),
            )
        )
    }

    // What the room the user is standing in currently holds, and whether it can take one more
    // of a given item — the furniture tray badges every icon with the count and dims the ones
    // that are full. Computed once here rather than in each pane so the bottom and right panes
    // can't drift apart. dropFurnitureAt re-derives both at drop time: this is only the display
    // state, and a drag can outlive the state it started against.
    // Which bucket the place you're standing in fills, and the zone whose floor budgets it. For a
    // room the two are the same thing; for the attic and the garage they aren't, because neither is
    // a RoomPlacement and the DRIVEWAY you stand on is not the space you put things in.
    val activeItemPlacementIds: Set<String> = when (roomPlace) {
        RoomPlace.ATTIC    -> setOf(ATTIC_PLACEMENT_ID)
        // Things dropped on the driveway go INTO the garage (see dropFurnitureAt), so they belong
        // to whichever bucket that garage owns — its room's, or the attached wing's sentinel.
        RoomPlace.DRIVEWAY -> setOfNotNull(surroundings.garagePlacementId)
        RoomPlace.INDOOR   -> activeRoomZone?.let { floorLayout.placementIdsInZone(it, currentFloor) }
            ?: emptySet()
    }
    // The zone whose floor area bounds how much fits. Deliberately the GARAGE for the driveway, not
    // the driveway itself: the driveway's extent grows with the fleet, so budgeting against it
    // would let the garage hold more simply because another car turned up outside it.
    val activeItemZone: RoomZone? =
        if (roomPlace == RoomPlace.DRIVEWAY) surroundings.garageZone.takeIf { surroundings.garageEnclosed }
        else activeRoomZone
    val activeRoomItemCounts = remember(placedItems, activeItemPlacementIds) {
        // roomItemCounts takes the id set rather than a zone precisely so a caller can hand it one
        // — the tray badges and RoomZone.canFit's floor-area budget then behave in these synthetic
        // places exactly as they do in a room.
        roomItemCounts(placedItems, activeItemPlacementIds)
    }
    val activeRoomCanAdd: (RoomItem) -> Boolean =
        { item -> activeItemZone?.canFit(item, activeRoomItemCounts) ?: false }
    // Every room opens showing just its own relevant items — keyed on the zone rather than
    // added to the pane's Back handler so leaving by any route (back, a floor switch, walking
    // into a different room) collapses it.
    LaunchedEffect(activeZone) { trayShowAll = false }

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

    // Three places offer these now — the cloud pill floating over every scene, the Maintenance Hub
    // header, and the Home dialog — so the Premium gate and the launcher call live here once.
    // Callers add only their own "and close me" on top.
    val doBackup: () -> Unit = {
        if (isAdFree) {
            val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            backupLauncher.launch("homehealth_backup_$date.zip")
        } else {
            (context as? Activity)?.let { BillingManager.getInstance(it).purchaseRemoveAds(it) }
        }
    }
    val doRestore: () -> Unit = {
        if (isAdFree) restoreLauncher.launch(arrayOf("application/zip"))
        else (context as? Activity)?.let { BillingManager.getInstance(it).purchaseRemoveAds(it) }
    }

    val homeType = HomeType(homeStyle, HomeSize.MEDIUM, FloorCount.ONE, LayoutType.OPEN_PLAN)

    val engine         = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val mainLightNode  = rememberMainLightNode(engine)
    val fillLightNode  = rememberFillLightNode(engine)
    // Hoisted (instead of SceneView's internal default) so furniture drops can unproject
    // a screen point through the live camera into a floor-plane position.
    val sceneCameraNode = rememberCameraNode(engine)
    // Scene touch handling. The controller owns every gesture that starts on a draggable item —
    // see SceneDragController's kdoc for why partial ownership isn't possible. Both objects are
    // remembered and their inputs refreshed below, because SceneView captures its onTouchEvent
    // lambda once and never again.
    val dragRegistry   = remember { SceneDragRegistry() }
    val dragController = remember { SceneDragController(dragRegistry) }
    // ── Camera framings ───────────────────────────────────────────────────────────────────────
    // Every scene computes eye/target/extent as plain locals and hands them to
    // rememberConfigurableCameraManipulator, which keys on those numbers — so a framing rebuilds
    // exactly when it moves, and no outer key() wrapper is needed (the old wrappers had to
    // enumerate every input by hand and got it wrong; see that function's kdoc).
    //
    // All coordinates are true world space: SceneView's autoCenterContent is off (see the
    // SceneView call below), so a camera written against the geometry's own numbers points where
    // it says it does.
    //
    // Shared constants. EYE_HEIGHT_M is where a standing adult's eyes are above the floor
    // SURFACE; EYE_PITCH_TAN is a fixed gentle downward glance (10°) applied as a drop over the
    // horizontal run, so the pitch reads identically in a 2 m bathroom and a 6 m living room —
    // a constant target HEIGHT (what this used to do) makes the pitch swing from 9° to 25° with
    // room size. It can't be a level gaze: at the default 28 mm lens (46° vertical FOV) the floor
    // wouldn't enter frame until 3.5 m out, which hides the furniture you came to arrange.
    // TOP_FIT scales a straight-down eye height to fit an extent in that same FOV, with margin.
    val eyeHeightM   = 1.62f
    val eyePitchTan  = 0.1763f   // tan(10°)
    val topFit       = 1.35f
    // Scaled to the footprint — the old fixed Float3(0f, 8f, 26f) only looked right for whatever
    // size it happened to be tuned against (a default-ish ~14x13m home); anything larger left the
    // camera effectively inside the roof, since distance never grew with the house.
    val exteriorCamera = run {
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val wallTopY = (floorLayout.maxFloor() + 1) * FLOOR_HEIGHT_M
        val reach = maxOf(w, d) * 1.6f + 10f
        val (home, target, extent) = when (camAngleOf(ViewState.EXTERIOR)) {
            SceneCamAngle.SIDE, SceneCamAngle.EYE -> {
                val elevDeg = 18f
                val elevRad = elevDeg * (PI / 180.0)
                val eY = (reach * sin(elevRad)).toFloat() + 2f
                val eZ = (reach * cos(elevRad)).toFloat()
                // Aim at the house's own mid-height rather than a fixed 2 m: the old value was
                // tuned against the auto-centred frame, where content sat ~2.5 m lower.
                Triple(Float3(0f, eY, eZ), Float3(0f, wallTopY * 0.5f, 0f), reach + 10f)
            }
            // Near-vertical, whole yard/roof in frame — extra margin keeps garage/pool/deck/
            // solar wings from clipping at the frame edge. Tiny lateral epsilon on the eye
            // keeps the view vector off vertical so the orbit up-vector stays well-defined
            // (the same trick every TOP framing below uses).
            SceneCamAngle.TOP ->
                Triple(Float3(0.01f, reach + 6f, 0.01f), Float3(0f, 0f, 0f), reach + 16f)
        }
        rememberConfigurableCameraManipulator(
            settings          = exteriorCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = extent,
        )
    }
    // The attic — a room view like any other now, but it keeps its OWN framing rather than
    // roomCamera's. SIDE looks in through the open near face instead of down from a corner, so
    // neither the knee walls nor the rafters ever stand between the camera and the contents;
    // roomCamera's above-the-corner vantage would put you on the wrong side of both. TOP is the
    // straight-down layout. Coordinates are in the scene's own frame, where the attic floor is
    // y = 0 and the space is centred on the origin (see atticShell's kdoc) — a full attic is far
    // larger than a utility closet, so the standoff scales off the resolved volume.
    //
    // Shares roomCamSettings with the rooms and the driveway: one place, one pane, one set of
    // camera preferences.
    val atticCamera = run {
        val v = atticVolume
        val extent = maxOf(v.halfW * 2f, v.halfD * 2f)
        val (home, target) = when (camAngleOf(ViewState.ROOM)) {
            SceneCamAngle.TOP ->
                Float3(0.01f, topFit * extent + 1.5f, 0.01f) to Float3(0f, 0f, 0f)
            else -> {
                val standoff = maxOf(v.halfW, v.height) * 1.5f + 2.0f
                Float3(0.01f, v.height * 0.85f, v.halfD + standoff) to
                    Float3(0f, v.height * 0.30f, 0f)
            }
        }
        rememberConfigurableCameraManipulator(
            settings          = roomCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = extent + 4f,
        )
    }
    // Floor plan. SIDE keeps the established 55° oblique; TOP is the flat straight-down read.
    // Both aim at the slab's top surface (y = WALL_T/2) rather than y = 0, which is inside it.
    val floorPlanCamera = run {
        val extent = maxOf(floorLayout.totalW, floorLayout.totalD)
        val slabY  = WALL_T / 2f
        val (home, target) = when (camAngleOf(ViewState.FLOOR_PLAN)) {
            SceneCamAngle.TOP ->
                Float3(0.01f, slabY + topFit * extent + 3f, 0.01f) to Float3(0f, slabY, 0f)
            else -> {
                // Reach tuned to fit the whole footprint in frame; tilt trades straight-down
                // (90°) for a lower, more oblique vantage while keeping that same reach.
                val reach   = extent * 1.4f + 2f
                val tiltRad = 55f * (PI / 180.0)
                Float3(0f, (reach * sin(tiltRad)).toFloat(), (reach * cos(tiltRad)).toFloat()) to
                    Float3(0f, slabY, 0f)
            }
        }
        rememberConfigurableCameraManipulator(
            settings          = floorPlanCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = extent + 6f,
        )
    }
    val roomCamera = run {
        val az   = activeRoomZone
        val w    = az?.w ?: 3f
        val d    = az?.d ?: 3f
        val hw   = w / 2f
        val hd   = d / 2f
        // The floor SLAB is centred on y = 0 and WALL_T thick, so its walkable top surface —
        // what every height below is measured from — is at +WALL_T/2. Walls run 0 → 2.7.
        val floorY = WALL_T / 2f
        // The driveway has no doorway of its own: doorSide falls through to +Z, which is the
        // street end — exactly where you'd stand to look back at the garage.
        val door = if (az != null) doorSide(az, floorLayout, currentFloor) else Float3(0f, 0f, 1f)
        val (home, target) = when (camAngleOf(ViewState.ROOM)) {
            // Stand in the doorway at real eye height, looking across the room. The standoff is
            // an absolute 0.6 m inside the door wall's inner face (which sits at halfDim −
            // WALL_T/2), not a fraction of the room: the old 0.82 × halfDim left the eye 8 cm
            // from the plaster in a 2 m room. The floor clamp keeps a 1 m hallway cell sane.
            SceneCamAngle.EYE -> {
                val halfDim = if (door.x != 0f) hw else hd
                val stand   = (halfDim - WALL_T / 2f - 0.6f).coerceAtLeast(halfDim * 0.35f)
                val eyeY    = floorY + eyeHeightM
                Float3(door.x * stand, eyeY, door.z * stand) to
                    // Pivot on the room centre: Filament's ORBIT swings the EYE around the
                    // target, so a near pivot reads as circling the room's contents at eye
                    // height. A far-wall pivot would maximise the swing radius instead.
                    Float3(0f, eyeY - stand * eyePitchTan, 0f)
            }
            // Above the door-side corner, high enough to see over the walls (no ceiling is
            // rendered) — pulled out past the room edge and raised with the room size so the
            // whole room stays in frame regardless of its dimensions.
            SceneCamAngle.SIDE -> {
                val cornerX = if (door.x != 0f) door.x * (hw + 1.2f) else hw * 0.9f
                val cornerZ = if (door.z != 0f) door.z * (hd + 1.2f) else hd * 0.9f
                Float3(cornerX, FLOOR_HEIGHT_M + maxOf(w, d) * 0.75f, cornerZ) to
                    Float3(0f, floorY + 0.3f, 0f)
            }
            // Straight down, one-room floor plan. The slight lateral offset toward the door
            // keeps the view vector off vertical so the orbit up-vector stays well-defined.
            SceneCamAngle.TOP ->
                Float3(door.x * 0.5f + 0.01f, floorY + topFit * maxOf(w, d) + 1.5f, door.z * 0.5f + 0.01f) to
                    Float3(0f, floorY, 0f)
        }
        rememberConfigurableCameraManipulator(
            settings          = roomCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = maxOf(w, d) + 2f,
        )
    }
    // Neighborhood. Everything is laid out around the green in the middle (see
    // NeighborhoodSceneGeometry's plan constants), so every framing aims at it rather than at
    // the ground plane's own centre.
    val neighborhoodCamera = run {
        val centerZ = NeighborhoodSceneGeometry.PARK_CZ
        val (framing, extent) = when (camAngleOf(ViewState.NEIGHBORHOOD)) {
            SceneCamAngle.TOP ->
                (Float3(0.01f, 85f, centerZ + 0.01f) to Float3(0f, 0f, centerZ)) to 120f
            // Standing on the green itself, at the north end of its crossing path, looking
            // across it toward the tower. The lawn's top surface is y ~ 0.01 — near enough to
            // grade that eyeHeightM needs no floor offset. Same eye height and 10° downward
            // glance as the room and driveway EYE framings.
            //
            // The target is only 8 m ahead on purpose. Filament's ORBIT swings the EYE around
            // the TARGET, so a near pivot reads as turning on the spot in the middle of the
            // green — far enough round to face any of the five homes, your own, or the tower,
            // without the sweep carrying you out over the neighbourhood. Same trick RoomScene's
            // EYE uses by pivoting on the room centre rather than the far wall.
            SceneCamAngle.EYE -> {
                val standZ = NeighborhoodSceneGeometry.PARK_CZ + 9f
                val pivot  = 8f
                (Float3(0f, eyeHeightM, standZ) to
                    Float3(0f, eyeHeightM - pivot * eyePitchTan, standZ - pivot)) to 40f
            }
            SceneCamAngle.SIDE -> {
                val reach   = 100f
                val elevRad = 40f * (PI / 180.0)
                (Float3(0f, (reach * sin(elevRad)).toFloat() + 2f, centerZ + (reach * cos(elevRad)).toFloat()) to
                    Float3(0f, 4f, centerZ)) to 120f
            }
        }
        val (home, target) = framing
        rememberConfigurableCameraManipulator(
            settings          = neighborhoodCamSettings,
            orbitHomePosition = home,
            targetPosition    = target,
            mapExtent         = extent,
        )
    }
    val cameraManipulator = when (viewState) {
        ViewState.EXTERIOR     -> exteriorCamera
        ViewState.FLOOR_PLAN   -> floorPlanCamera
        // Rooms and the driveway share one framing; the attic keeps its own (see atticCamera).
        ViewState.ROOM         -> if (atAttic) atticCamera else roomCamera
        ViewState.NEIGHBORHOOD -> neighborhoodCamera
    }

    // Feed the drag controller everything that changes underneath it. Assigning into a remembered
    // object rather than rebuilding it keeps the identity SceneView latched onto at first frame.
    val dragHaptics = LocalHapticFeedback.current
    val dragSlopPx  = with(LocalDensity.current) { 8.dp.toPx() }
    SideEffect {
        dragController.cameraNode  = sceneCameraNode
        dragController.manipulator = cameraManipulator
        dragController.touchSlopPx = dragSlopPx
        // The only signal that an item has been picked up — without it a long-press that armed
        // and one that didn't look identical until you move.
        dragController.onArmHaptic = { dragHaptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }

    if (showInfo) InfoDialog(scene = viewState, place = roomPlace, onDismiss = { showInfo = false })

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
        val carriedVehicles = placedItems.filter { it.item.isVehicle }
        val carriedVehicleItems = carriedVehicles.map { it.item }
        val newLot = CarLotGeometry.homeLot(
            layout, neighbor.featurePlacements[HomeFeature.GARAGE], vehicles = carriedVehicleItems)
        val settledVehicles = CarLotGeometry.settleVehiclesOntoLot(carriedVehicles, newLot)
        val seededFurniture = layout.defaultFurniturePlacedItems()
        // The user's appliance install-years and documents describe THEIR fridge/washer/
        // furnace, not the model's — re-key them onto the new model's seeded twins (first
        // fridge → first fridge, and so on, in seeding order). Vehicles keep their ids so
        // their records are intact by construction; records with no twin in the new model
        // stay in the DB untouched until the user deletes them.
        // Your belongings in spaces the model doesn't furnish — the garage, the attic — are not part
        // of its room layout, so they come with you the way the fleet does rather than resetting.
        // (A garage ROOM's contents are room furniture and do reset; the new model has a different
        // garage room, and there's no meaningful spot in it to carry them to.)
        val carriedBelongings = placedItems.filter { isNonRoomPlacement(it.placementId) && !it.item.isVehicle }
        val carriedIds = (carriedBelongings + carriedVehicles).map { it.id }.toSet()
        val recordMapping = buildMap {
            // Carried items keep their own ids, so they must stay OUT of this pairing: re-keying a
            // carried water heater onto the new model's seeded laundry twin would detach the very
            // history that carrying it across exists to preserve. Vehicles are excluded already.
            placedItems.filter { it.id !in carriedIds }.groupBy { it.item }.forEach { (item, olds) ->
                val news = seededFurniture.filter { it.item == item }
                olds.zip(news).forEach { (o, n) -> put("placed:${o.id}", "placed:${n.id}") }
            }
            // GARAGE_DOOR is the one remaining auto-populated default instance (every other
            // appliance/fixture became a placed item) — same re-key reasoning as above, just
            // keyed by the garage room's own id instead of a PlacedItem's.
            floorLayout.defaultInstanceKeys(RoomType.GARAGE).firstOrNull()?.let { oldRepId ->
                layout.defaultInstanceKeys(RoomType.GARAGE).firstOrNull()?.let { newRepId ->
                    put("$oldRepId:${RoomItem.GARAGE_DOOR.name}", "$newRepId:${RoomItem.GARAGE_DOOR.name}")
                }
            }
        }
        if (recordMapping.isNotEmpty()) maint.migrateItemKeys(recordMapping)
        // Room furniture the new model has no twin for would otherwise be dropped along with its
        // install year and documents — the same silent loss deleting a room used to cause. Rescue
        // the same things on the same rule (see rescueOrphans), into the NEW home's spaces.
        val stranded = placedItems.filter {
            it.id !in carriedIds && "placed:${it.id}" !in recordMapping && worthRescuing(it)
        }
        // Anchor-relative offsets computed against the OLD home's lot/yard are meaningless
        // against the new one's — reset, same reasoning as vehicles' own re-settle just above.
        val initialFeatureOffsets =
            defaultGarageOffsets(neighbor.featurePlacements[HomeFeature.GARAGE], layout.totalW, layout.totalD)
        // The NEW home's spaces, asked the same way every scene asks — one derivation of "where
        // does this home park / keep things", so a rescue can't land somewhere the renderer
        // disagrees with.
        val newSurroundings = RoomSurroundings(
            layout            = layout,
            floor             = 0,
            placedItems       = settledVehicles,
            featurePlacements = neighbor.featurePlacements,
            featureOffsets    = initialFeatureOffsets,
        )
        val newAtticZone = HouseSceneGeometry.atticZone(HouseSceneGeometry.atticVolume(
            layout.totalW, layout.totalD,
            wallTopY = (layout.maxFloor() + 1) * FLOOR_HEIGHT_M,
            style = neighbor.homeType.style,
            type = HouseSceneGeometry.defaultAtticType(neighbor.homeType.style),
        ))
        val newGarageId = newSurroundings.garagePlacementId
        val rehomed = (carriedBelongings + stranded).groupBy { pi ->
            if (newGarageId != null && RoomType.GARAGE in pi.item.trayRooms)
                newSurroundings.garageZone to newGarageId
            else newAtticZone to ATTIC_PLACEMENT_ID
        }.flatMap { (dest, group) ->
            val (zone, placementId) = dest
            intoSpace(group, zone, placementId, 0)
        }
        val seededItems = seededFurniture + settledVehicles + rehomed
        homeStyle         = neighbor.homeType.style
        floorLayout       = layout
        featurePlacements = neighbor.featurePlacements
        featureOffsets    = initialFeatureOffsets
        placedDecks       = neighbor.placedDecks
        // Coerced for the same reason the restore path does it: a preset must never hand a
        // wall-mounted utility the FRONT elevation, since rotate can't cycle back off it.
        hvacPlacement     = neighbor.hvacPlacement?.let { (s, a) ->
            HouseSceneGeometry.coerceUtilitySide(s).let { if (it == s) s to a else it to 0f } }
        solarArrayPlacement = neighbor.solarArrayPlacement
        evBatteryPlacement = neighbor.evBatteryPlacement?.let { (s, a) ->
            HouseSceneGeometry.coerceUtilitySide(s).let { if (it == s) s to a else it to 0f } }
        placedYardDecor   = neighbor.placedYardDecor
        // Back to "never chosen", so the new model's own roof decides what's up top — carrying a
        // pitched home's full attic onto a claimed condo would contradict the roof you just picked.
        atticType         = null
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
            featureOffsets    = initialFeatureOffsets,
            placedDecks       = neighbor.placedDecks,
            // The coerced state set just above, not the raw preset — otherwise the row on disk
            // would disagree with what's rendering until the next autosave.
            hvacPlacement     = hvacPlacement,
            solarArrayPlacement = neighbor.solarArrayPlacement,
            evBatteryPlacement = evBatteryPlacement,
            placedYardDecor   = neighbor.placedYardDecor,
            // Custom appliances/tasks are user-authored, independent of any claimed model —
            // deliberately not reset here (see the tracked-vs-placed-items skill).
            customAppliances  = customAppliances,
            customTasks       = customTasks,
            floorLayout       = layout,
            itemOffsets       = emptyMap(),
            placedItems       = seededItems,
            removedInstances  = emptySet(),
            atticType         = null,
            buildYear         = buildYear,
            purchaseYear      = purchaseYear,
            label             = label,
            neighborKey       = neighbor.label,
        )
        if (isFirstClaim) showFirstClaimHint = true else showClaimResetHint = true
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

    // ── Claim-reset confirmation — every claim after the first ─────────────────
    if (showClaimResetHint) {
        AlertDialog(
            onDismissRequest = { showClaimResetHint = false },
            icon  = { Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Home Updated") },
            text  = { Text("This home's layout, features, and systems now match the new model. Your appliance records, documents, vehicles, and custom items all carried over — nothing was lost.") },
            confirmButton = {
                Button(onClick = { showClaimResetHint = false }) { Text("Got it") }
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
                    (if (placedYardDecor.any { it.kind == YardDecorKind.TREE }) listOf("Trees") else emptyList()) +
                    (if (placedYardDecor.any { it.kind == YardDecorKind.GAZEBO }) listOf("Gazebo") else emptyList()) +
                    // Physical presence, not Score-tab trackability — a system stays trackable
                    // (see removedInstances) even after its unit is removed from the yard, but
                    // this summary should only list what's actually standing in the home.
                    listOfNotNull(
                        HomeSystem.HVAC.label.takeIf { hvacPlacement != null },
                        HomeSystem.SOLAR.label.takeIf { solarArrayPlacement != null },
                        HomeSystem.EV_BATTERY.label.takeIf { evBatteryPlacement != null },
                    ))
                    .joinToString(" · "),
                premiumPrice = premiumPrice,
                // The home-wide half of the attic choice; the other is tapping its floor from
                // inside. Both write this same state, so they can't drift.
                atticType    = resolvedAtticType,
                onSave    = { label, buildYear, purchaseYear, chosenAttic ->
                    atticType = chosenAttic
                    maint.saveHome(
                        style             = homeStyle,
                        featurePlacements = featurePlacements,
                        featureOffsets    = featureOffsets,
                        placedDecks       = placedDecks,
                        hvacPlacement     = hvacPlacement,
                        solarArrayPlacement = solarArrayPlacement,
                        evBatteryPlacement = evBatteryPlacement,
                        placedYardDecor   = placedYardDecor,
                        customAppliances  = customAppliances,
                        customTasks       = customTasks,
                        floorLayout       = floorLayout,
                        itemOffsets       = itemOffsets,
                        placedItems       = placedItems,
                        removedInstances  = removedInstances,
                        atticType         = chosenAttic,
                        buildYear         = buildYear,
                        purchaseYear      = purchaseYear,
                        label             = label,
                        neighborKey       = entity.neighborKey,
                    )
                    showHomeDatesDialog = false
                },
                // Only step out of the way when something actually opens over us. On the locked
                // path the purchase sheet takes over instead, and staying put means the buttons
                // unlock in place when it returns rather than sending the user back through the pane.
                onBackup  = {
                    if (isAdFree) showHomeDatesDialog = false
                    doBackup()
                },
                onRestore = {
                    if (isAdFree) showHomeDatesDialog = false
                    doRestore()
                },
                onDismiss = { showHomeDatesDialog = false },
            )
        }
    }

    // ── Attic type ────────────────────────────────────────────────────────────
    // Opened by tapping the attic's bare boards, the same gesture that asks a room what it is
    // from inside it. The home-info dialog above offers the same choice; both write this state.
    if (showAtticTypeDialog) {
        AtticTypeDialog(
            current   = resolvedAtticType,
            onSelect  = { atticType = it; showAtticTypeDialog = false },
            onDismiss = { showAtticTypeDialog = false },
        )
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
        // Where the user is standing, so the To-Do list can narrow to "what needs doing here".
        // Resolved here because this is where the layout/placement state lives; the hub just
        // matches against it.
        //
        // A room's contents come from ACTUAL placement, never RoomItem.room: every appliance with
        // a room-attributable task (fridge, dishwasher, washer, dryer, water heater) is portable,
        // so its nominal room is only a taxonomy hint. A fridge dragged into the garage must take
        // its task with it, not stay listed under a kitchen that no longer has one.
        val taskScope = remember(viewState, activeRoomZone, roomPlace, currentFloor, floorLayout,
                                 placedItems, resolvedAtticType, activeItemPlacementIds) {
            when {
                // Vehicles aren't room-anchored (they live on the lot), so the driveway's scope is
                // defined by item type rather than placement. The door's tasks hang off the
                // HiddenAsset, not RoomItem.GARAGE_DOOR, which carries none — hence both buckets.
                atDriveway -> {
                    // Vehicles aren't room-anchored, so they're scoped by item TYPE; the garage's
                    // own contents are scoped by placement, exactly as a room's are below.
                    val inside = placedItems.filter { it.placementId in activeItemPlacementIds }
                    TaskScope.Place(
                        label      = "Garage",
                        items      = RoomItem.entries.filter { it.isVehicle }.toSet() +
                                     inside.map { it.item }.toSet(),
                        assets     = setOf(HiddenAsset.GARAGE_DOOR),
                        targetKeys = inside.map { "placed:${it.id}" }.toSet(),
                    )
                }
                // Same assets whichever kind of space it is: the scope answers "what is maintained
                // from HERE", and a mechanical closet is still where you go to deal with the home's
                // ducts. Anything the owner has switched off in the pane's inventory row has no
                // tasks left to scope, so it drops out on its own.
                atAttic -> TaskScope.Place(
                    label   = resolvedAtticType.label,
                    assets  = setOf(HiddenAsset.DUCTWORK, HiddenAsset.ATTIC_INSULATION),
                    systems = setOf(HomeSystem.HVAC),
                )
                viewState == ViewState.ROOM -> activeZone?.let { zone ->
                    val ids = floorLayout.placementIdsInZone(zone, currentFloor).toSet()
                    val here = placedItems.filter { it.placementId in ids }
                    TaskScope.Place(
                        label = zone.type.label,
                        items = here.map { it.item }.toSet(),
                        // Custom tasks attached to one of this room's appliances travel with it.
                        targetKeys = here.map { "placed:${it.id}" }.toSet(),
                    )
                } ?: TaskScope.All
                // Whole-home vantage points show everything.
                else -> TaskScope.All
            }
        }

        MaintenanceHubDialog(
            tasks           = tasks,
            taskRecords     = recordMap,
            isClaimed       = savedHome != null,
            onMarkDone      = { key -> maint.markTaskDone(key) },
            onSnoozeTask    = { key, until -> maint.snoozeTask(key, until) },
            onTaskMutedChange = { key, muted -> maint.setTaskMuted(key, muted) },
            // Same lambda ApplianceMaintenanceDialog's own custom-task delete uses below, so the
            // two entry points can't drift.
            onDeleteCustomTask = { id -> customTasks = customTasks.filterNot { it.id == id } },
            taskScope       = taskScope,
            roomTypes       = roomTypes.toList(),
            instancesByItem = instancesByItem,
            applianceRecords = applianceRecs,
            documents       = allDocuments,
            homeYear        = savedHome?.buildYear,
            homeStyle       = homeStyle,
            atticType       = resolvedAtticType,
            featurePlacements = featurePlacements,
            hasDeck         = placedDecks.isNotEmpty(),
            removedInstances  = removedInstances,
            customAppliances = customAppliances,
            onAddCustomAppliance = { showAddCustomAppliance = true },
            onTapInstance   = { target -> maintenanceTarget = target },
            onTapHidden     = { asset -> maintenanceTarget = MaintenanceTarget.Hidden(asset) },
            contacts        = proContacts,
            onSaveContact   = { maint.saveContact(it) },
            onDeleteContact = { maint.deleteContact(it) },
            isPremium       = isAdFree,
            // Step aside for the file picker so the result dialog isn't stacked behind a
            // full-screen panel — but stay put on the locked path, where the purchase sheet
            // takes over and the icons should unlock in place when it returns.
            onBackup        = {
                if (isAdFree) showMaintenanceHub = false
                doBackup()
            },
            onRestore       = {
                if (isAdFree) showMaintenanceHub = false
                doRestore()
            },
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
            customTasks     = customTasks.filter { it.targetKey == target.key },
            // Same resolution the room pane's status strip and the hub's Score dots use, so the
            // card always explains the exact colour that led the user here.
            dueTasks        = tasksForTarget(target, tasks).filterNot { it.isCustom },
            taskRecords     = recordMap,
            onMarkTaskDone  = { key -> maint.markTaskDone(key) },
            onAddCustomTask = { showAddCustomTask = true },
            onDeleteCustomTask = { id -> customTasks = customTasks.filterNot { it.id == id } },
            onNotInHome     = when (target) {
                // The three soft-hide kinds share one toggle (see toggleRemovedKey): it hides
                // without deleting, and for a HomeSystem it also unplaces the unit. Item and
                // Hidden keys can never collide with a HomeSystem name ("$placementId:${item.name}"
                // and HiddenAsset names respectively), so they get the plain hide.
                is MaintenanceTarget.Item -> {
                    {
                        toggleRemovedKey(target.instanceKey)
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
                        toggleRemovedKey(target.asset.name)
                        maintenanceTarget = null
                    }
                }
                is MaintenanceTarget.System -> {
                    {
                        toggleRemovedKey(target.system.name)
                        maintenanceTarget = null
                    }
                }
                is MaintenanceTarget.Custom -> {
                    {
                        customAppliances = customAppliances.filterNot { it.id == target.id }
                        customTasks = customTasks.filterNot { it.targetKey == target.key }
                        maint.removeItemRecords("custom:${target.id}")
                        maintenanceTarget = null
                    }
                }
            },
            isRemoved       = when (target) {
                is MaintenanceTarget.Item   -> target.instanceKey in removedInstances
                is MaintenanceTarget.Hidden -> target.asset.name in removedInstances
                is MaintenanceTarget.System -> target.system.name in removedInstances
                else -> false
            },
            onDismiss       = { maintenanceTarget = null },
        )
    }

    // ── Add custom appliance / add custom task dialogs ─────────────────────────
    if (showAddCustomAppliance) {
        AddCustomApplianceDialog(
            onAdd = { name ->
                val ca = CustomAppliance(name = name)
                customAppliances = customAppliances + ca
                maintenanceTarget = MaintenanceTarget.Custom(ca.id, ca.name)
                showAddCustomAppliance = false
            },
            onDismiss = { showAddCustomAppliance = false },
        )
    }
    if (showAddCustomTask) {
        maintenanceTarget?.let { current ->
            AddCustomTaskDialog(
                onAdd = { title, frequency ->
                    customTasks = customTasks + CustomTask(
                        targetKey = current.key, title = title, frequency = frequency)
                    showAddCustomTask = false
                },
                onDismiss = { showAddCustomTask = false },
            )
        }
    }

    // ── Camera ray through a tray-drop pixel. Anchored on the camera's world position and a
    // screenToWorld point (culling projection, finite far plane). NOT the deprecated
    // screenPointToRay: that unprojects its far endpoint against the RENDERING projection, whose
    // infinite far plane collapses the endpoint to the world origin (w ≈ 0) — every drop ray then
    // bent through the room centre no matter where the finger let go.
    //
    // Tray drops report ROOT/window coordinates, so the scene's own bounds come off first. The
    // in-scene drag controller receives surface-local coordinates already and casts its own ray;
    // both then hand the result to the SAME pick functions below, so dropping an item and
    // dragging it resolve to identical positions.
    val screenRay: (Offset, Rect) -> Pair<Float3, Float3>? = ray@{ rootPos, bounds ->
        val onRay = sceneCameraNode.view
            ?.screenToWorld(rootPos.x - bounds.left, rootPos.y - bounds.top) ?: return@ray null
        val eye = sceneCameraNode.worldPosition
        eye to (onRay - eye)
    }

    val screenPointOnPlane: (Offset, Rect, Float) -> Float3? = hit@{ rootPos, bounds, planeY ->
        val (eye, dir) = screenRay(rootPos, bounds) ?: return@hit null
        horizontalPick(planeY)(eye, dir)
    }

    // ── Same ray, against a plane at any orientation (point + normal) instead of a fixed
    // horizontal Y — used to place solar accurately on the pitched roof from any camera angle
    // (see HouseSceneGeometry.roofSlopePlane).
    val screenPointOnTiltedPlane: (Offset, Rect, Vec3, Vec3) -> Float3? = hit@{ rootPos, bounds, planePoint, planeNormal ->
        val (eye, dir) = screenRay(rootPos, bounds) ?: return@hit null
        tiltedPick(Float3(planePoint.x, planePoint.y, planePoint.z),
                   Float3(planeNormal.x, planeNormal.y, planeNormal.z))(eye, dir)
    }

    // ── Furniture drop — convert a tray-icon release at [rootPos] (root/window px) into a
    // placed piece on the active room's floor: cast a camera ray through the drop pixel,
    // intersect it with the floor plane (room-local space IS world space in the room view),
    // clamp inside the walls, and store the offset from the item's nominal anchor.
    // Three kinds of place can take a dropped item, and they differ in exactly three ways: which
    // zone bounds it, how high its floor is, and — because a scene renders the place you're
    // STANDING in at the origin — where that zone sits in scene coordinates.
    //
    //   a room     — its own zone, floor at +WALL_T/2 (the slab is centred on y = 0), at the origin
    //   the attic  — its own zone, floor top at y = 0 (see atticShell), at the origin
    //   the garage — the GARAGE zone while you stand on the DRIVEWAY, so it's offset by the
    //                difference between their centres; floor top at y = 0.15, the same plane the
    //                driveway slab and every vehicle drag already resolve against
    val dropFurnitureAt: (RoomItem, Offset) -> Unit = drop@{ item, rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.ROOM || !bounds.contains(rootPos)) return@drop
        val zone   = activeItemZone ?: return@drop
        val repId  = activeItemPlacementIds.minOrNull() ?: return@drop
        val planeY = when (roomPlace) {
            RoomPlace.ATTIC    -> 0f
            RoomPlace.DRIVEWAY -> DRIVEWAY_PLANE_Y
            RoomPlace.INDOOR   -> WALL_T / 2f
        }
        // Into the target zone's own frame before clamping. Zero everywhere except the driveway,
        // which stands outside the garage it's dropping into.
        val (offX, offZ) = if (roomPlace == RoomPlace.DRIVEWAY)
            (zone.cx - surroundings.drivewayZone.cx) to (zone.cz - surroundings.drivewayZone.cz)
        else 0f to 0f
        val hit = screenPointOnPlane(rootPos, bounds, planeY) ?: return@drop
        val margin = 0.4f
        val wx = (hit.x - offX).coerceIn(-zone.w / 2f + margin, zone.w / 2f - margin)
        val wz = (hit.z - offZ).coerceIn(-zone.d / 2f + margin, zone.d / 2f - margin)
        // The authoritative capacity gate — the tray only DIMS a full item's icon, which is
        // cosmetic, and a drag can be released against state that changed after it began.
        if (!zone.canFit(item, roomItemCounts(placedItems, activeItemPlacementIds))) return@drop
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
        // Vehicles are dropped where they live: the exterior yard, or the driveway zone itself.
        if ((viewState != ViewState.EXTERIOR && !atDriveway) ||
            previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        // Driveway slab top (see VehiclesOnLot's dwH) — the same plane a vehicle DRAG resolves
        // against, so a drop and a drag put the car in the same place. This used to go through
        // the deprecated screenPointToRay, which this file's own screenRay comment documents as
        // bending every ray through the world origin.
        val hit = screenPointOnPlane(rootPos, bounds, DRIVEWAY_PLANE_Y) ?: return@drop
        val vehicles = placedItems.filter { it.item.isVehicle }
        val hasInteriorGarage = HouseSceneGeometry.townhouseGarageBox(floorLayout) != null
        // The home's one lot — sized for the vehicle about to land here as well as the ones
        // already parked, and shifted into whichever frame the drop happened in: the exterior
        // draws it in world space, a room scene renders itself at the origin and shifts
        // everything around it by minus its own centre (see RoomScene / HomeBackdrop).
        val worldLot = surroundings.vehicleLotFor(extra = item)
        val lot = if (atDriveway)
            activeRoomZone?.let { CarLotGeometry.translate(worldLot, -it.cx, -it.cz) } ?: worldLot
        else worldLot
        var dx = hit.x - lot.anchorX
        var dz = hit.z - lot.anchorZ
        if (item == RoomItem.CAR && abs(dx) < 1.6f && abs(dz) < 1.6f && !CarLotGeometry.anchorOccupied(vehicles)) {
            // Dropped onto the free garage/parked slot — park it inside.
            dx = 0f; dz = 0f
        } else {
            // Otherwise take the free driveway spot nearest the drop. The garage scene's
            // overhead door rolls open on entry (exterior doors stay closed), so an anchor-
            // parked car blocks the driveway at its pulled-out position there — read the
            // door's LIVE fraction (garageDoorFraction, mirrored from GarageScene) rather
            // than assuming it's already fully open, otherwise a vehicle dropped during the
            // ~1.2s opening animation could land right where the first car is still sliding
            // out to.
            val (nx, nz) = CarLotGeometry.freeDrivewaySlot(lot, item, dx, dz,
                CarLotGeometry.occupiedSlots(lot, vehicles,
                    doorFraction  = if (atDriveway) garageDoorFraction else 0f,
                    // "A garage physically exists", not "we're looking at the garage scene" —
                    // an open lot (CONDO, or a HOUSE with no garage yet) renders no shell and
                    // no door, so its anchor is an ordinary parking space with nothing to be
                    // parked behind. Must agree with GarageScene's own `enclosed`.
                    garagePresent = hasInteriorGarage ||
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

    // ── Actions-bar "rotate" — cycles which house side a feature sits on without re-dragging
    // it. Pool/HVAC/EV Battery use yardCycleSides (FRONT excluded — those three reading fine
    // on any of the other 3 walls, but a pool/HVAC/EV-battery out front looks odd); garage
    // uses its own drag-time garageSides (BACK excluded — its door orientation math only
    // supports FRONT/RIGHT/LEFT). If a feature somehow already sits on a side outside its own
    // order, indexOf's -1 coerces to 0, landing on the next entry rather than crashing.
    val yardCycleSides = listOf(FeatureSide.RIGHT, FeatureSide.LEFT, FeatureSide.BACK)
    fun nextCycleSide(current: FeatureSide, order: List<FeatureSide> = yardCycleSides): FeatureSide {
        val i = order.indexOf(current).coerceAtLeast(0)
        return order[(i + 1) % order.size]
    }

    // ── Deck drop — tear-drag from the sidebar tray, single slot like garage/pool/HVAC:
    // always the full width of the back wall, so the drop position doesn't matter beyond
    // landing somewhere in the scene — no more per-wall snapping, cycling, or 1/3-width sizing.
    val dropDeckAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        if (homeStyle.isAttached || placedDecks.isNotEmpty()) return@drop
        placedDecks = placedDecks + PlacedDeck(side = FeatureSide.BACK, along = 0f)
    }

    // ── Garage drop — tear-drag from the sidebar tray, same shape as dropDeckAt. Snaps to
    // whichever of FRONT/RIGHT/LEFT it lands nearest (garage never attaches to BACK — see
    // HouseSceneGeometry.garageBaseAnchor). No-ops if a garage already exists (single slot;
    // reposition by dragging the placed garage, remove via its actions bar).
    val garageSides = listOf(FeatureSide.FRONT, FeatureSide.RIGHT, FeatureSide.LEFT)
    val dropGarageAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        // Same rule as the tray icon that starts this drag, enforced independently: a drag can be
        // released against state that changed after it began, and a home with a garage room in its
        // layout already has somewhere to park (see the pane's hasGarageRoom).
        if (homeStyle != HomeStyle.HOUSE || HomeFeature.GARAGE in featurePlacements ||
            HouseSceneGeometry.townhouseGarageBox(floorLayout) != null) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val side = HouseSceneGeometry.nearestHouseSide(hit.x, hit.z, w, d, garageSides)
        // A side garage snaps its front (door) face flush with the house's own front wall —
        // matching how an attached side garage commonly sits — rather than wherever along the
        // wall the drop happened to land. FRONT still follows the drop point: that wall's
        // "along" is an X position along the ONE front wall, still a meaningful choice.
        val along = if (side == FeatureSide.FRONT) {
            val box = HouseSceneGeometry.garageBox(w, d, side)
            val raw = HouseSceneGeometry.alongCoordinate(side, hit.x, hit.z)
            HouseSceneGeometry.clampAlongWall(side, raw, w, d, box.w)
        } else {
            HouseSceneGeometry.garageFrontAlignedAlong(side, w, d)
        }
        featurePlacements = featurePlacements + (HomeFeature.GARAGE to side)
        featureOffsets = featureOffsets + (HomeFeature.GARAGE to (along to 0f))
        // Same re-settle dropDeckAt's sibling onFeatureClick performed — the driveway's shape
        // depends on the garage's side, so vehicles' anchor-relative offsets (computed against
        // the OLD no-garage shape) must be re-resolved onto the new one.
        val vehicles = placedItems.filter { it.item.isVehicle }
        if (vehicles.isNotEmpty()) {
            val newLot = CarLotGeometry.carLot(w, d, side, vehicles = vehicles.map { it.item })
            val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, newLot)
            val byId = settled.associateBy { it.id }
            placedItems = placedItems.map { byId[it.id] ?: it }
        }
    }

    // ── Pool drop — tear-drag from the sidebar tray. Free 2D placement (no wall coupling),
    // clamped into the usable yard and pushed clear of the house's own footprint, same as the
    // pool's live-drag math. No-ops if a pool already exists (single slot).
    val poolSides = listOf(FeatureSide.BACK, FeatureSide.RIGHT, FeatureSide.LEFT)
    val dropPoolAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        if (homeStyle.isAttached || HomeFeature.POOL in featurePlacements) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val yard = HouseSceneGeometry.yardBounds(homeStyle, w, d) ?: return@drop
        val side = HouseSceneGeometry.nearestHouseSide(hit.x, hit.z, w, d, poolSides)
        val base = HouseSceneGeometry.poolBox(w, d, side)
        val cx = hit.x.coerceIn(yard.xMin, yard.xMax)
        val cz = hit.z.coerceIn(yard.zMin, yard.zMax)
        val (kx, kz) = HouseSceneGeometry.keepOutsideHouse(cx, cz, base.w, base.d, w, d)
        featurePlacements = featurePlacements + (HomeFeature.POOL to side)
        featureOffsets = featureOffsets + (HomeFeature.POOL to ((kx - base.cx) to (kz - base.cz)))
    }

    // ── HVAC drop — tear-drag from the sidebar tray, same shape as dropDeckAt. Valid on any
    // of the 4 walls (no door-orientation constraint the way garage has). No-ops if a unit
    // already exists (single slot; reposition by dragging it, remove via its actions bar).
    // Unlike the old auto-placed version, there's no automatic door-avoidance here — the user
    // drops it wherever they like, same as any other tray feature. Available for every style,
    // including CONDO (unlike Deck/Pool/Solar/EV Battery/yard decor, which need real yard
    // ground — see yardBounds): the unit itself is wall-mounted, not yard-placed, and for
    // CONDO it's the entry point into the Attic scene (see onHvacTap).
    val dropHvacAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        if (hvacPlacement != null) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        // Never the front elevation — see HouseSceneGeometry.UTILITY_SIDES.
        val side = HouseSceneGeometry.nearestHouseSide(hit.x, hit.z, w, d, HouseSceneGeometry.UTILITY_SIDES)
        val raw = HouseSceneGeometry.alongCoordinate(side, hit.x, hit.z)
        val along = HouseSceneGeometry.clampAlongWall(side, raw, w, d, HouseSceneGeometry.HVAC_ALONG_WIDTH)
        hvacPlacement = side to along
        // Placing a unit always makes its Score-tab card visible again, even if it had
        // previously been marked "not in my home".
        removedInstances = removedInstances - HomeSystem.HVAC.name
    }

    // ── Solar array drop — tear-drag from the sidebar tray, available from any exterior camera
    // angle (Top or Side). Raycasts against BOTH pitched roof slopes' real tilted planes
    // (HouseSceneGeometry.roofSlopePlane) rather than a flat plane at eave height, since a flat
    // approximation is only accurate from a near-vertical vantage — from Side view it would
    // land far from where the roof actually renders on screen. Keeps whichever slope's hit
    // lands on the finite slab (not the plane's infinite extension) and nearest the camera.
    // Single slot, like garage/pool/HVAC.
    val dropSolarAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        if (homeStyle.isAttached || solarArrayPlacement != null) return@drop
        val wallTopY = (floorLayout.maxFloor() + 1) * FLOOR_HEIGHT_M
        val w = floorLayout.totalW
        val eye = sceneCameraNode.worldPosition
        var best: Pair<Boolean, Float3>? = null
        var bestDist = Float.MAX_VALUE
        for (onLeftSlope in listOf(true, false)) {
            val plane = HouseSceneGeometry.roofSlopePlane(w, wallTopY, onLeftSlope)
            val hit = screenPointOnTiltedPlane(rootPos, bounds, plane.point, plane.normal) ?: continue
            val offset = plane.slopeOffset(Vec3(hit.x, hit.y, hit.z))
            if (abs(offset) > plane.slantLen / 2f + 0.5f) continue
            val dx = hit.x - eye.x; val dy = hit.y - eye.y; val dz = hit.z - eye.z
            val dist = dx * dx + dy * dy + dz * dz
            if (dist < bestDist) { bestDist = dist; best = onLeftSlope to hit }
        }
        val (onLeftSlope, hit) = best ?: return@drop
        val along = HouseSceneGeometry.clampSolarAlong(hit.z, floorLayout.totalD)
        solarArrayPlacement = onLeftSlope to along
        removedInstances = removedInstances - HomeSystem.SOLAR.name
    }

    // ── EV Battery drop — tear-drag from the sidebar tray, same shape as dropHvacAt. Valid
    // on any of the 4 walls, no gating on solar being present. No-ops if a unit already
    // exists (single slot; reposition by dragging it, remove via its actions bar).
    val dropEvBatteryAt: (Offset) -> Unit = drop@{ rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        if (homeStyle.isAttached || evBatteryPlacement != null) return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        // Never the front elevation — see HouseSceneGeometry.UTILITY_SIDES.
        val side = HouseSceneGeometry.nearestHouseSide(hit.x, hit.z, w, d, HouseSceneGeometry.UTILITY_SIDES)
        val raw = HouseSceneGeometry.alongCoordinate(side, hit.x, hit.z)
        val along = HouseSceneGeometry.clampAlongWall(side, raw, w, d, HouseSceneGeometry.EV_BATTERY_ALONG_WIDTH)
        evBatteryPlacement = side to along
        removedInstances = removedInstances - HomeSystem.EV_BATTERY.name
    }

    // ── Tree/gazebo drop — tear-drag from the sidebar tray, free 2D placement like the pool
    // (no wall coupling, no house-adjacency preference), kept clear of the house footprint.
    // Unlike garage/pool/HVAC/EV Battery's single slot, any number of either kind are allowed
    // (like decks/solar). Unavailable for CONDO (no yard at all).
    val dropYardDecorAt: (YardDecorKind, Offset) -> Unit = drop@{ kind, rootPos ->
        val bounds = sceneBounds ?: return@drop
        if (viewState != ViewState.EXTERIOR || previewedNeighbor != null || !bounds.contains(rootPos)) return@drop
        val w = floorLayout.totalW
        val d = floorLayout.totalD
        val yard = HouseSceneGeometry.yardBounds(homeStyle, w, d) ?: return@drop
        val hit = screenPointOnPlane(rootPos, bounds, 0.09f) ?: return@drop
        val (footW, footD) = HouseSceneGeometry.yardDecorFootprint(kind)
        val cx = hit.x.coerceIn(yard.xMin, yard.xMax)
        val cz = hit.z.coerceIn(yard.zMin, yard.zMax)
        val (kx, kz) = HouseSceneGeometry.keepOutsideHouse(cx, cz, footW, footD, w, d)
        placedYardDecor = placedYardDecor + PlacedYardDecor(kind = kind, dx = kx, dz = kz)
    }

    // ── Placed-item actions bar — shown for the tapped piece in Room/Exterior/Garage ────────
    if (viewState == ViewState.ROOM || viewState == ViewState.EXTERIOR) {
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
                    // Every placed instance is tracked now, furniture included — the icon
                    // always opens this instance's maintenance card, same as appliances and
                    // vehicles (see openMaintenanceCard above).
                    onTrack   = openMaintenanceCard,
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
                icon = f.icon, label = f.label, iconColor = f.iconColor,
                onCycleSide = when (f) {
                    HomeFeature.POOL -> {
                        {
                            val next = nextCycleSide(featurePlacements[HomeFeature.POOL]!!)
                            featurePlacements = featurePlacements + (HomeFeature.POOL to next)
                            featureOffsets = featureOffsets + (HomeFeature.POOL to (0f to 0f))
                            // The default Side camera looks dead-on at the front wall with no X
                            // offset — Right/Left are edge-on and Back is fully hidden behind
                            // the house, so a cycle that lands there is otherwise invisible
                            // until the user manually switches views. Top view shows the whole
                            // yard from above, so the new position is always immediately visible.
                            setCamAngle(ViewState.EXTERIOR, SceneCamAngle.TOP)
                        }
                    }
                    HomeFeature.GARAGE -> {
                        {
                            val next = nextCycleSide(featurePlacements[HomeFeature.GARAGE]!!, garageSides)
                            val w = floorLayout.totalW; val d = floorLayout.totalD
                            // Nudge clear of HVAC/EV Battery if either already sits on the wall
                            // we're rotating onto — otherwise both land re-centered at along=0
                            // and one renders behind/inside the other (see nonOverlappingAlong).
                            val occupants = buildList {
                                hvacPlacement?.let { (s, a) -> if (s == next) add(a to HouseSceneGeometry.HVAC_ALONG_WIDTH) }
                                evBatteryPlacement?.let { (s, a) -> if (s == next) add(a to HouseSceneGeometry.EV_BATTERY_ALONG_WIDTH) }
                            }
                            // A side garage's default is front-aligned (see dropGarageAt); FRONT
                            // itself has no such alignment concept, so it keeps centering at 0.
                            val desired = if (next == FeatureSide.FRONT) 0f
                                else HouseSceneGeometry.garageFrontAlignedAlong(next, w, d)
                            val along = HouseSceneGeometry.nonOverlappingAlong(
                                next, desired, HouseSceneGeometry.garageAlongWidth(next, w, d), occupants, w, d)
                            featurePlacements = featurePlacements + (HomeFeature.GARAGE to next)
                            featureOffsets = featureOffsets + (HomeFeature.GARAGE to (along to 0f))
                            // See the Pool case above — Right/Left are poorly framed by the
                            // default Side camera.
                            setCamAngle(ViewState.EXTERIOR, SceneCamAngle.TOP)
                            // The driveway's shape depends on the garage's side — re-settle
                            // vehicles onto the new lot, same as dropGarageAt/onRemove do,
                            // otherwise they'd render at stale offsets computed against the
                            // OLD side's driveway (the "disappearing" bug class this mirrors).
                            val vehicles = placedItems.filter { it.item.isVehicle }
                            if (vehicles.isNotEmpty()) {
                                val newLot = CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, next, vehicles = vehicles.map { it.item })
                                val settled = CarLotGeometry.settleVehiclesOntoLot(vehicles, newLot)
                                val byId = settled.associateBy { it.id }
                                placedItems = placedItems.map { byId[it.id] ?: it }
                            }
                        }
                    }
                    else -> null
                },
                currentSideLabel = featurePlacements[f]?.label,
                onTrack = {
                    val asset = when (f) {
                        HomeFeature.GARAGE -> HiddenAsset.GARAGE_DOOR
                        HomeFeature.POOL   -> HiddenAsset.POOL_EQUIPMENT
                        else               -> null
                    }
                    if (asset != null) maintenanceTarget = MaintenanceTarget.Hidden(asset)
                    else showMaintenanceHub = true
                },
                onRemove = {
                    featurePlacements = featurePlacements - f
                    featureOffsets = featureOffsets - f
                    if (f == HomeFeature.GARAGE) {
                        val vehicles = placedItems.filter { it.item.isVehicle }
                        if (vehicles.isNotEmpty()) {
                            val newLot = CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, null, vehicles = vehicles.map { it.item })
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

    // ── Deck actions bar — the tapped deck (Remove; Track) — single slot, no cycle icon
    // (always the back wall now, nothing to cycle between).
    if (viewState == ViewState.EXTERIOR) {
        selectedDeckId?.let { id ->
            if (placedDecks.any { it.id == id }) {
                FeatureActionsDialog(
                    icon = HomeFeature.DECK.icon, label = HomeFeature.DECK.label, iconColor = HomeFeature.DECK.iconColor,
                    onTrack = { maintenanceTarget = MaintenanceTarget.Hidden(HiddenAsset.DECK) },
                    onRemove = {
                        placedDecks = placedDecks.filterNot { it.id == id }
                        selectedDeckId = null
                    },
                    onDismiss = { selectedDeckId = null },
                )
            }
        }
    }

    // ── HVAC actions bar — the tapped HVAC unit (Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR && selectedHvac && hvacPlacement != null) {
        FeatureActionsDialog(
            icon = HomeSystem.HVAC.icon, label = HomeSystem.HVAC.label, iconColor = HomeSystem.HVAC.iconColor,
            onCycleSide = {
                val next = nextCycleSide(hvacPlacement!!.first, yardCycleSides)
                val w = floorLayout.totalW; val d = floorLayout.totalD
                // Nudge clear of Garage/EV Battery if either already sits on the wall we're
                // rotating onto (see nonOverlappingAlong).
                val occupants = buildList {
                    if (featurePlacements[HomeFeature.GARAGE] == next) {
                        add((featureOffsets[HomeFeature.GARAGE]?.first ?: 0f) to HouseSceneGeometry.garageAlongWidth(next, w, d))
                    }
                    evBatteryPlacement?.let { (s, a) -> if (s == next) add(a to HouseSceneGeometry.EV_BATTERY_ALONG_WIDTH) }
                }
                val along = HouseSceneGeometry.nonOverlappingAlong(next, 0f, HouseSceneGeometry.HVAC_ALONG_WIDTH, occupants, w, d)
                hvacPlacement = next to along
                // See the Pool actions-bar case above — Right/Left/Back are poorly framed or
                // fully hidden by the default Side camera.
                setCamAngle(ViewState.EXTERIOR, SceneCamAngle.TOP)
            },
            currentSideLabel = hvacPlacement?.first?.label,
            onTrack = { maintenanceTarget = MaintenanceTarget.System(HomeSystem.HVAC) },
            onRemove = {
                // Physical removal only — the Score-tab card stays tracked (see
                // ApplianceMaintenanceDialog's own "Not In My Home" for the real opt-out).
                // Deliberately not the mirror of toggleRemovedKey: taking the box off the wall
                // isn't a claim the home has no HVAC, whereas saying so does take the box off.
                hvacPlacement = null
                selectedHvac = false
            },
            onDismiss = { selectedHvac = false },
        )
    }

    // ── Solar array actions bar — single slot (flip slope; Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR && selectedSolar && solarArrayPlacement != null) {
        FeatureActionsDialog(
            icon = HomeSystem.SOLAR.icon, label = HomeSystem.SOLAR.label, iconColor = HomeSystem.SOLAR.iconColor,
            onCycleSide = {
                solarArrayPlacement = solarArrayPlacement?.let { (onLeftSlope, along) -> !onLeftSlope to along }
            },
            currentSideLabel = solarArrayPlacement?.first?.let { if (it) "Left slope" else "Right slope" },
            onTrack = { maintenanceTarget = MaintenanceTarget.System(HomeSystem.SOLAR) },
            onRemove = {
                solarArrayPlacement = null
                selectedSolar = false
            },
            onDismiss = { selectedSolar = false },
        )
    }

    // ── EV Battery actions bar — the tapped unit (Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR && selectedEvBattery && evBatteryPlacement != null) {
        FeatureActionsDialog(
            icon = HomeSystem.EV_BATTERY.icon, label = HomeSystem.EV_BATTERY.label, iconColor = HomeSystem.EV_BATTERY.iconColor,
            onCycleSide = {
                val next = nextCycleSide(evBatteryPlacement!!.first, yardCycleSides)
                val w = floorLayout.totalW; val d = floorLayout.totalD
                // Nudge clear of Garage/HVAC if either already sits on the wall we're rotating
                // onto (see nonOverlappingAlong).
                val occupants = buildList {
                    if (featurePlacements[HomeFeature.GARAGE] == next) {
                        add((featureOffsets[HomeFeature.GARAGE]?.first ?: 0f) to HouseSceneGeometry.garageAlongWidth(next, w, d))
                    }
                    hvacPlacement?.let { (s, a) -> if (s == next) add(a to HouseSceneGeometry.HVAC_ALONG_WIDTH) }
                }
                val along = HouseSceneGeometry.nonOverlappingAlong(next, 0f, HouseSceneGeometry.EV_BATTERY_ALONG_WIDTH, occupants, w, d)
                evBatteryPlacement = next to along
                // See the Pool actions-bar case above — Right/Left/Back are poorly framed or
                // fully hidden by the default Side camera.
                setCamAngle(ViewState.EXTERIOR, SceneCamAngle.TOP)
            },
            currentSideLabel = evBatteryPlacement?.first?.label,
            onTrack = { maintenanceTarget = MaintenanceTarget.System(HomeSystem.EV_BATTERY) },
            onRemove = {
                evBatteryPlacement = null
                selectedEvBattery = false
            },
            onDismiss = { selectedEvBattery = false },
        )
    }

    // ── Tree/gazebo actions bar — the tapped instance (Remove; drag to move) ────
    if (viewState == ViewState.EXTERIOR) {
        selectedYardDecorId?.let { id ->
            placedYardDecor.firstOrNull { it.id == id }?.let { decor ->
                FeatureActionsDialog(
                    icon = decor.kind.icon, label = decor.kind.label, iconColor = decor.kind.iconColor,
                    onTrack = { showMaintenanceHub = true },
                    onRemove = {
                        placedYardDecor = placedYardDecor.filterNot { it.id == id }
                        selectedYardDecorId = null
                    },
                    onDismiss = { selectedYardDecorId = null },
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
            types     = availableRoomTypes(floorLayout, currentFloor, col = minCol, row = minRow, colSpan = colSpan, rowSpan = rowSpan),
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

    // Walking onto the driveway — from the exterior's garage door or pane icon, or the floor
    // plan's garage slab. It's a room view like any other now (RoomPlace.DRIVEWAY), so this only
    // has to say where the user is and where Back goes. TOP because you're there to arrange
    // vehicles, same as the old garage scene opened.
    fun enterDriveway(from: ViewState) {
        roomPlace       = RoomPlace.DRIVEWAY
        activeZone      = null
        activePlacement = null
        roomReturn      = from
        viewState       = ViewState.ROOM
        setCamAngle(ViewState.ROOM, SceneCamAngle.TOP)
    }

    // Climbing into the attic — from the exterior, the floor plan, or a room, all of which offer
    // the pane's Roofing icon. Same shape as the driveway above. SIDE because the attic is a space
    // you look INTO over its knee walls, and because a persisted EYE (which the attic's own pane
    // doesn't offer — see ViewState.camAngles(place)) would otherwise strand the camera inside a
    // crawl space with no way to change it.
    fun enterAttic(from: ViewState) {
        roomPlace       = RoomPlace.ATTIC
        activeZone      = null
        activePlacement = null
        roomReturn      = from
        viewState       = ViewState.ROOM
        setCamAngle(ViewState.ROOM, SceneCamAngle.SIDE)
    }

    // Refreshes activePlacement/activeZone in place after a floor-plan mutation, when the room
    // mutated is also the one currently open in RoomScene — otherwise the 3D view would keep
    // rendering a room that no longer exists (or a stale type/shape). newLayout.rooms having no
    // match (removal) naturally falls into the "back out to the floor plan" branch. Shared by
    // every room-mutation path: plain remove, plain change-type, Replace/Keep, and Phase 3's
    // singleton swap.
    fun syncActiveRoomAfterEdit(newLayout: FloorLayout, placementId: String) {
        if (viewState != ViewState.ROOM || activePlacement?.id != placementId) return
        val p = newLayout.rooms.firstOrNull { it.id == placementId }
        activePlacement = p
        activeZone = p?.let { newLayout.mergedZoneFor(it, it.floor) }
        if (p == null) viewState = ViewState.FLOOR_PLAN
    }

    // Applies a room's new type and, if requested, clears its existing furniture and reseeds
    // the new type's defaults (scoped to just this room via defaultFurnitureForZone).
    fun applyRoomTypeChange(p: RoomPlacement, type: RoomType, replaceFurniture: Boolean) {
        val oldZone = floorLayout.mergedZoneFor(p, p.floor) ?: return
        val oldPlacementIds = floorLayout.placementIdsInZone(oldZone, p.floor)
        val newLayout = floorLayout.changeType(p.id, type)
        floorLayout = newLayout
        if (replaceFurniture) {
            val remaining = placedItems.filterNot { it.placementId in oldPlacementIds }
            val newRoom = newLayout.rooms.first { it.id == p.id }
            val newZone = newLayout.mergedZoneFor(newRoom, p.floor)
            placedItems = if (newZone != null)
                remaining + newLayout.defaultFurnitureForZone(newZone, p.floor, itemOffsets)
            else remaining
        }
        syncActiveRoomAfterEdit(newLayout, p.id)
    }

    // Which wall segments would change (open/solid/door state) if [swap] were applied — shown
    // in the confirmation dialog so the ripple into derived wall/door state (see
    // FloorLayout.effectiveWallModes) isn't a silent surprise. Only the two swapped rooms'
    // types change, so only segments touching them can differ.
    fun wallChangesForSwap(swap: PendingTypeSwap): List<String> {
        val after = floorLayout
            .changeType(swap.target.id, swap.targetType)
            .changeType(swap.existing.id, swap.target.type)
        val floors = setOf(swap.target.floor, swap.existing.floor)
        fun WallMode.label() = name.lowercase().replaceFirstChar { it.uppercase() }
        return floors.flatMap { floor ->
            val before = floorLayout.effectiveWallModes(floor)
            val afterModes = after.effectiveWallModes(floor)
            (before.keys + afterModes.keys).distinct().mapNotNull { key ->
                val b = before[key]; val a = afterModes[key]
                if (b == a) return@mapNotNull null
                val colA: Int; val rowA: Int; val colB: Int; val rowB: Int
                if (key.vertical) {
                    colA = key.boundary - 1; rowA = key.index
                    colB = key.boundary;     rowB = key.index
                } else {
                    colA = key.index; rowA = key.boundary - 1
                    colB = key.index; rowB = key.boundary
                }
                val swappedIds = setOf(swap.target.id, swap.existing.id)
                val other = listOfNotNull(
                    floorLayout.placementAt(colA, rowA, floor),
                    floorLayout.placementAt(colB, rowB, floor),
                ).firstOrNull { it.id !in swappedIds }
                val otherLabel = other?.type?.label ?: "the exterior"
                "Floor ${floor + 1}: the wall toward $otherLabel changes from " +
                    "${b?.label() ?: "no wall"} to ${a?.label() ?: "no wall"}"
            }
        }
    }

    // Swaps two rooms' types (Kitchen or Garage moving to a new location while the room that
    // held it takes on the target's old type) — the existing singleton's appliances move with
    // it, maintenance records (install dates/notes/documents/tasks) intact; the vacated room
    // gets fresh defaults for its new (target's old) type, same as any empty-room retype.
    fun performSingletonSwap(swap: PendingTypeSwap) {
        val target = swap.target
        val existing = swap.existing
        val targetType = swap.targetType
        // What the EXISTING room (currently targetType) becomes: the target room's type as it
        // was before this swap — i.e. the two rooms trade types. (existing.type is already
        // targetType at this point, so it must NOT be used here — that would leave existing
        // unchanged and silently turn target into a second room of targetType instead of a
        // real swap.)
        val targetOldType = target.type

        // Defensive re-checks (belt-and-suspenders — the picker's availableRoomTypes should
        // already have excluded any invalid combination before this was ever offered).
        val targetDepthM = target.rowSpan * floorLayout.cellD
        val existingDepthM = existing.rowSpan * floorLayout.cellD
        val targetOk = targetType.fitsFootprint(target.colSpan, target.rowSpan) &&
            (targetType != RoomType.KITCHEN || canFitKitchenRun(targetDepthM))
        val existingOk = targetOldType.fitsFootprint(existing.colSpan, existing.rowSpan) &&
            (targetOldType != RoomType.KITCHEN || canFitKitchenRun(existingDepthM))
        if (!targetOk || !existingOk) return

        val targetOldZone = floorLayout.mergedZoneFor(target, target.floor) ?: return
        val existingOldZone = floorLayout.mergedZoneFor(existing, existing.floor) ?: return
        val targetOldPlacementIds = floorLayout.placementIdsInZone(targetOldZone, target.floor)
        val existingOldPlacementIds = floorLayout.placementIdsInZone(existingOldZone, existing.floor)

        val newLayout = floorLayout
            .changeType(target.id, targetType)
            .changeType(existing.id, targetOldType)

        val newTargetZone = newLayout.rooms.firstOrNull { it.id == target.id }
            ?.let { newLayout.mergedZoneFor(it, target.floor) }
        val newExistingZone = newLayout.rooms.firstOrNull { it.id == existing.id }
            ?.let { newLayout.mergedZoneFor(it, existing.floor) }

        // The old singleton's own items move into the target room at fresh positions sized
        // for its shape (reusing offsets from a differently-shaped room wouldn't make sense).
        val movingItems = placedItems.filter { it.placementId in existingOldPlacementIds }
        val seededAtTarget = newTargetZone
            ?.let { newLayout.defaultFurnitureForZone(it, target.floor, itemOffsets) } ?: emptyList()
        val seededAtExisting = newExistingZone
            ?.let { newLayout.defaultFurnitureForZone(it, existing.floor, itemOffsets) } ?: emptyList()

        // Preserve maintenance records across the move by matching old items to new ones of
        // the same RoomItem type, in order — same pattern claimNeighbor uses re-seeding a home.
        val recordMapping = buildMap {
            movingItems.groupBy { it.item }.forEach { (item, olds) ->
                val news = seededAtTarget.filter { it.item == item }
                olds.zip(news).forEach { (o, n) -> put("placed:${o.id}", "placed:${n.id}") }
            }
            // GARAGE_DOOR is the one remaining auto-populated default instance.
            if (targetType == RoomType.GARAGE || targetOldType == RoomType.GARAGE) {
                floorLayout.defaultInstanceKeys(RoomType.GARAGE).firstOrNull()?.let { oldRepId ->
                    newLayout.defaultInstanceKeys(RoomType.GARAGE).firstOrNull()?.let { newRepId ->
                        put("$oldRepId:${RoomItem.GARAGE_DOOR.name}", "$newRepId:${RoomItem.GARAGE_DOOR.name}")
                    }
                }
            }
        }
        if (recordMapping.isNotEmpty()) maint.migrateItemKeys(recordMapping)

        var newPlacedItems = placedItems
            .filterNot { it.placementId in targetOldPlacementIds || it.placementId in existingOldPlacementIds }
            .plus(seededAtTarget)
            .plus(seededAtExisting)

        // Vehicles live on the home's lot, not in a room, so moving the garage doesn't move them
        // on its own — but their offsets were settled against the OLD garage's position, and the
        // lot is anchored on the garage, so re-settle onto where it is now.
        if (targetType == RoomType.GARAGE && newTargetZone != null) {
            val vehicles = newPlacedItems.filter { it.item.isVehicle }
            val resettled = CarLotGeometry.settleVehiclesOntoLot(
                vehicles,
                CarLotGeometry.homeLot(newLayout, featurePlacements[HomeFeature.GARAGE],
                    vehicles = vehicles.map { it.item }))
            newPlacedItems = newPlacedItems.filterNot { it.item.isVehicle } + resettled
        }

        placedItems = newPlacedItems
        floorLayout = newLayout
        syncActiveRoomAfterEdit(newLayout, target.id)
        syncActiveRoomAfterEdit(newLayout, existing.id)
    }

    // Room-type-change dispatcher: a singleton type (Kitchen/Garage) that already exists
    // elsewhere asks to confirm a swap; an empty room reseeds immediately (nothing to lose); a
    // furnished room asks Replace/Keep first.
    fun requestRoomTypeChange(p: RoomPlacement, type: RoomType) {
        val conflict = if (type == RoomType.KITCHEN || type == RoomType.GARAGE)
            floorLayout.rooms.firstOrNull { it.id != p.id && it.type == type } else null
        if (conflict != null) {
            pendingTypeSwap = PendingTypeSwap(target = p, targetType = type, existing = conflict)
            return
        }
        val zone = floorLayout.mergedZoneFor(p, p.floor) ?: return
        val roomPlacementIds = floorLayout.placementIdsInZone(zone, p.floor)
        val isEmpty = placedItems.none { it.placementId in roomPlacementIds }
        if (isEmpty) applyRoomTypeChange(p, type, replaceFurniture = true)
        else pendingReplaceOrKeep = PendingReplaceOrKeep(p, type)
    }

    // Placement options: shown when user taps an existing room in floor-plan view
    editingPlacement?.let { p ->
        PlacementOptionsDialog(
            placement      = p,
            availableTypes = availableRoomTypes(floorLayout, p.floor, excludeId = p.id, col = p.col, row = p.row, colSpan = p.colSpan, rowSpan = p.rowSpan),
            // Opened via RoomScene's floor tap (in-room) vs. the floor plan's long-press —
            // "Enter" only makes sense from the floor plan, you're already inside otherwise.
            showEnter      = viewState != ViewState.ROOM,
            onEnter = {
                roomPlace       = RoomPlace.INDOOR
                activeZone      = floorLayout.mergedZoneFor(p, p.floor)
                activePlacement = p
                roomReturn      = ViewState.FLOOR_PLAN
                viewState       = ViewState.ROOM
                editingPlacement = null
            },
            onRemove = {
                val newLayout = floorLayout.removeRoom(p.id)
                floorLayout = newLayout
                placedItems = rescueOrphans(newLayout, placedItems)
                if (currentFloor > newLayout.maxFloor()) currentFloor = newLayout.maxFloor()
                syncActiveRoomAfterEdit(newLayout, p.id)
                editingPlacement = null
            },
            onChangeType = { type ->
                requestRoomTypeChange(p, type)
                editingPlacement = null
            },
            onDismiss = { editingPlacement = null },
        )
    }

    // Replace/Keep: shown when a room-type change targets a room that already has furniture in
    // it and there's no singleton conflict to resolve instead (see performSingletonSwap).
    pendingReplaceOrKeep?.let { pending ->
        ReplaceOrKeepFurnitureDialog(
            roomType  = pending.targetType,
            onReplace = {
                applyRoomTypeChange(pending.placement, pending.targetType, replaceFurniture = true)
                pendingReplaceOrKeep = null
            },
            onKeep = {
                applyRoomTypeChange(pending.placement, pending.targetType, replaceFurniture = false)
                pendingReplaceOrKeep = null
            },
            onDismiss = { pendingReplaceOrKeep = null },
        )
    }

    // Singleton swap: shown when a room-type change targets Kitchen/Garage and one already
    // exists elsewhere in the home.
    pendingTypeSwap?.let { pending ->
        SwapConfirmDialog(
            targetType     = pending.targetType,
            existingLabel  = "Floor ${pending.existing.floor + 1} · col ${pending.existing.col + 1}, row ${pending.existing.row + 1}",
            wallChanges    = wallChangesForSwap(pending),
            onConfirm      = { performSingletonSwap(pending); pendingTypeSwap = null },
            onDismiss      = { pendingTypeSwap = null },
        )
    }

    // Scene view, reused in both layout branches
    val scene: @Composable (Modifier) -> Unit = { mod ->
      // Every scene below binds its draggable cubes into this registry; the controller resolves
      // the node under the finger through it.
      CompositionLocalProvider(LocalSceneDragRegistry provides dragRegistry) {
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
                // SceneView otherwise re-parents every DSL node under an internal contentRoot and
                // translates it by -bounds.center (Scene.kt's autoCenterContent, default true) —
                // while the camera and lights, being SceneView parameters rather than DSL children,
                // stay in true world space. That silent offset is what put the room's "Eye" camera
                // 2.8 m up (above the shifted wall tops) and shifted the garage scene ~4 m in Z, so
                // absolute-mode drags (vehicles, yard features) resolved a world hit position that
                // the scene-local drag math then clamped straight back. Off, world == scene-local
                // everywhere, which is what every camera framing, drag handler and drop handler in
                // this file already assumes. Also removes a hidden non-determinism: the offset
                // changed whenever the top-level node list did (entering a room, toggling a wall).
                autoCenterContent = false,
                // Takes over the whole gesture whenever it starts on a draggable item, so a drag
                // and the camera can never both act on the same touch. Method reference on a
                // remembered object — SceneView latches this lambda once, in its AndroidView
                // factory, and never re-reads it.
                onTouchEvent = dragController::onTouch,
            ) {
                when (viewState) {
                    ViewState.EXTERIOR -> {
                        val activeType     = previewedNeighbor?.homeType ?: homeType
                        val activeLayout   = if (previewedNeighbor != null) previewLayout else floorLayout
                        val activeFeatures = previewedNeighbor?.featurePlacements ?: featurePlacements
                        // Previewed neighbors are static presets with no drag history of their
                        // own — render at each feature's default anchor for that side.
                        val activeFeatureOffsets = previewedNeighbor?.let {
                            defaultGarageOffsets(it.featurePlacements[HomeFeature.GARAGE], activeLayout.totalW, activeLayout.totalD)
                        } ?: featureOffsets
                        val activeDecks    = previewedNeighbor?.placedDecks ?: placedDecks
                        val activeHvac     = previewedNeighbor?.hvacPlacement ?: hvacPlacement
                        val activeSolar    = previewedNeighbor?.solarArrayPlacement ?: solarArrayPlacement
                        val activeEvBattery = previewedNeighbor?.evBatteryPlacement ?: evBatteryPlacement
                        val activeYardDecor = previewedNeighbor?.placedYardDecor ?: placedYardDecor
                        HouseScene(
                            homeType          = activeType,
                            floorLayout       = activeLayout,
                            featurePlacements = activeFeatures,
                            featureOffsets    = activeFeatureOffsets,
                            placedDecks       = activeDecks,
                            hvacPlacement     = activeHvac,
                            solarArrayPlacement = activeSolar,
                            evBatteryPlacement = activeEvBattery,
                            placedYardDecor   = activeYardDecor,
                            removedInstances  = if (previewedNeighbor != null) emptySet() else removedInstances,
                            itemOffsets       = if (previewedNeighbor != null) emptyMap() else itemOffsets,
                            placedItems       = if (previewedNeighbor != null) previewFurniture else placedItems,
                            cars              = if (previewedNeighbor != null) emptyList()
                                                else placedItems.filter { it.item.isVehicle },
                            // Visible through the now see-through shell; managed from the driveway.
                            garageContents    = if (previewedNeighbor != null) emptyList() else
                                surroundings.garagePlacementId?.let { id ->
                                    placedItems.filter { it.placementId == id && !it.item.isVehicle }
                                } ?: emptyList(),
                            garageZone        = surroundings.garageZone.takeIf { surroundings.garageEnclosed },
                            onEnterGarage     = {
                                if (previewedNeighbor == null) {
                                    enterDriveway(from = ViewState.EXTERIOR)
                                }
                            },
                            // Opens the vehicle's actions bar (color, fuel type, track-to-
                            // maintenance-card, remove) — see the placed-item actions bar below.
                            // Every onXTap/onXMoved callback below is guarded by `previewedNeighbor
                            // == null`: a previewed neighbor is a read-only static preset, and every
                            // one of these actions bars mutates or force-unwraps the PLAYER's OWN
                            // state (featurePlacements, hvacPlacement, etc.), not the neighbor's —
                            // e.g. selecting the previewed home's garage and hitting "cycle side"
                            // used to crash with a NPE on `featurePlacements[HomeFeature.GARAGE]!!`
                            // whenever the player's own home had no garage placed yet. Dragging was
                            // already disabled during preview (HouseScene's `movable` flag); this
                            // closes the same gap for tapping.
                            onCarTap          = { car -> if (previewedNeighbor == null) selectedFurnitureId = car.id },
                            onCarMoved        = { pi, off ->
                                if (previewedNeighbor == null) placedItems = placedItems.map { if (it.id == pi.id) it.copy(dx = off.first, dz = off.second) else it }
                            },
                            onCarRemoved      = { pi ->
                                if (previewedNeighbor == null) {
                                    placedItems = placedItems.filterNot { it.id == pi.id }
                                    maint.removeItemRecords("placed:${pi.id}")
                                }
                            },
                            onFeatureMoved    = { f, side, off ->
                                if (previewedNeighbor == null) {
                                    featurePlacements = featurePlacements + (f to side)
                                    featureOffsets = featureOffsets + (f to off)
                                }
                            },
                            onFeatureTap      = { f -> if (previewedNeighbor == null) selectedFeature = f },
                            onDeckTap         = { id -> if (previewedNeighbor == null) selectedDeckId = id },
                            onHvacMoved       = { side, along -> if (previewedNeighbor == null) hvacPlacement = side to along },
                            // Same on every style: this is the outdoor condenser, so it opens the
                            // placement actions bar. The indoor half (air handler) lives in the
                            // attic, reached by the pane's attic button.
                            onHvacTap         = { if (previewedNeighbor == null) selectedHvac = true },
                            onSolarMoved      = { onLeftSlope, along -> if (previewedNeighbor == null) solarArrayPlacement = onLeftSlope to along },
                            onSolarTap        = { if (previewedNeighbor == null) selectedSolar = true },
                            onEvBatteryMoved  = { side, along -> if (previewedNeighbor == null) evBatteryPlacement = side to along },
                            onEvBatteryTap    = { if (previewedNeighbor == null) selectedEvBattery = true },
                            onYardDecorMoved  = { id, dx, dz ->
                                if (previewedNeighbor == null) placedYardDecor = placedYardDecor.map { if (it.id == id) it.copy(dx = dx, dz = dz) else it }
                            },
                            onYardDecorTap    = { id -> if (previewedNeighbor == null) selectedYardDecorId = id },
                            onEnterHome       = { if (previewedNeighbor == null) { viewState = ViewState.FLOOR_PLAN } },
                            // Claiming is never triggered by tapping the model itself — same as a
                            // previewed neighbor — it's always the floating ClaimHomeDialog button.
                            onHomeTap         = {
                                if (previewedNeighbor == null && savedHome != null) showHomeDatesDialog = true
                            },
                            // Maintenance cards live in the hub's Systems section now.
                            onTapSystem       = { },
                            isModelPreview    = previewedNeighbor != null,
                            // Your own home stands on your street; a previewed model is staged on
                            // its own, so it keeps the plain backdrop it has always had.
                            showNeighborhood  = previewedNeighbor == null,
                        )
                    }
                    ViewState.NEIGHBORHOOD ->
                        NeighborhoodScene(
                            homeType              = homeType,
                            floorLayout           = floorLayout,
                            featurePlacements     = featurePlacements,
                            featureOffsets        = featureOffsets,
                            placedDecks           = placedDecks,
                            hvacPlacement         = hvacPlacement,
                            solarArrayPlacement   = solarArrayPlacement,
                            evBatteryPlacement    = evBatteryPlacement,
                            placedYardDecor       = placedYardDecor,
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
                            // An interior garage room (TOWNHOUSE always has one; some HOUSE
                            // presets do too) is entered like an exterior garage — door
                            // animates open, car rolls onto the driveway — rather than a plain
                            // walkable room. See GarageScene's gwOverride/gdOverride/lotOverride
                            // wiring below.
                            // A garage ROOM is now entered like any other room; the driveway
                            // outside it is its own zone, reached from the slab or the exterior.
                            roomPlace       = RoomPlace.INDOOR
                            activeZone      = floorLayout.mergedZoneFor(p, p.floor)
                            activePlacement = p
                            roomReturn      = ViewState.FLOOR_PLAN
                            viewState       = ViewState.ROOM
                        }
                        FloorPlanScene(
                            layout            = floorLayout,
                            currentFloor      = currentFloor,
                            homeStyle         = homeStyle,
                            featurePlacements = featurePlacements,
                            featureOffsets    = featureOffsets,
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
                            onGarageTap       = { enterDriveway(from = ViewState.FLOOR_PLAN) },
                        )
                    }
                    ViewState.ROOM ->
                        activeRoomZone?.let { zone ->
                            val wallModes = activePlacement
                                ?.let { floorLayout.wallModesFor(it) }
                                ?: emptyMap()
                            val exteriorSlider = exteriorSliderFor(zone, floorLayout, placedDecks, currentFloor)
                            // Neither synthetic place is a RoomPlacement: the attic's contents hang
                            // off a sentinel id, and the driveway shows what's in the GARAGE behind
                            // it (a real room's bucket, or the attached wing's sentinel).
                            val roomPlacementIds = activeItemPlacementIds
                            val repId = if (atAttic || atDriveway) "" else roomPlacementIds.minOrNull() ?: ""
                            RoomScene(
                                zone            = zone,
                                // What the room draws around itself — the rest of the storey and
                                // the street beyond it, all untouchable (see HomeBackdrop).
                                // The attic looks DOWN on the street: its world sits a storey above
                                // the top floor, and it has no storey of its own to cut away (see
                                // HomeBackdrop's showStorey).
                                surroundings    = if (atAttic)
                                    surroundings.copy(floor = floorLayout.maxFloor() + 1)
                                else surroundings,
                                // The driveway and the attic are places you stand rather than rooms
                                // you're inside: no walls, no fittings, their own content instead.
                                place           = roomPlace,
                                atticVolume     = atticVolume,
                                atticContents   = atticContents,
                                // Every attic item opens its own maintenance card. Deliberately NOT
                                // the exterior actions bar: that bar's Rotate/Remove act on the
                                // wall-mounted outdoor unit, which isn't visible from in here —
                                // rotating something off-screen would be baffling. The air handler
                                // is the indoor half of the same HVAC system, so it shares its card.
                                onAtticItemTap  = { item ->
                                    maintenanceTarget = item.hiddenAsset
                                        ?.let { MaintenanceTarget.Hidden(it) }
                                        ?: MaintenanceTarget.System(HomeSystem.HVAC)
                                },
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
                                // The fleet is managed from the driveway and shown as scenery
                                // everywhere else (see HomeBackdrop), so only this zone gets the
                                // interactive copy — same actions-bar/remove wiring as before.
                                vehicles         = if (atDriveway) surroundings.vehicles else emptyList(),
                                onVehicleTap     = { car -> selectedFurnitureId = car.id },
                                onVehicleMoved   = { pi, off ->
                                    placedItems = placedItems.map { if (it.id == pi.id) it.copy(dx = off.first, dz = off.second) else it }
                                },
                                onVehicleRemoved = { pi ->
                                    placedItems = placedItems.filterNot { it.id == pi.id }
                                    maint.removeItemRecords("placed:${pi.id}")
                                },
                                // Tapping bare floor opens the same Change/Remove card the floor
                                // plan's long-press menu uses, for the room currently being viewed.
                                // In the attic it asks what kind of space this is instead — one
                                // gesture, "tell me about the place I'm standing in", in both.
                                // The driveway has neither a placement nor a type, so it's a no-op.
                                // Reads roomPlace (state) rather than the captured `atAttic`: a
                                // CubeNode's apply block runs once, so this closure outlives the
                                // composition that built it and must resolve where it is at TAP
                                // time, not at node-creation time.
                                onFloorTap = {
                                    if (roomPlace == RoomPlace.ATTIC) showAtticTypeDialog = true
                                    else editingPlacement = activePlacement
                                },
                                // Lets the vehicle drop see how far an anchor-parked car has
                                // rolled out — see DrivewayLot's onDoorFractionChanged.
                                onDoorFractionChanged = { garageDoorFraction = it },
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
                        floorLayout, currentFloor, col = minC, row = minR,
                        colSpan = maxC - minC + 1, rowSpan = maxR - minR + 1,
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
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                selectedCells = emptySet()
                            },
                            onShrinkRows  = {
                                floorLayout = floorLayout.shrinkRows()
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                selectedCells = emptySet()
                            },
                            onRemoveFloor = {
                                val top = floorLayout.maxFloor()
                                floorLayout = floorLayout.removeTopFloor()
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                if (currentFloor >= top) currentFloor = floorLayout.maxFloor()
                                selectedCells = emptySet()
                            },
                            onAttic       = { enterAttic(from = ViewState.FLOOR_PLAN) },
                            onExit        = { viewState = ViewState.EXTERIOR; selectedCells = emptySet(); floorPlanEditMode = false },
                            onInfo        = { showInfo = true },
                            homeClaimed      = savedHome != null,
                            onHomeInfo       = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                            onMaintenanceHub = { showMaintenanceHub = true },
                            cameraSettings         = floorPlanCamSettings,
                            onCameraSettingsChange = { floorPlanCamSettings = it },
                            camAngle         = camAngleOf(ViewState.FLOOR_PLAN),
                            camAngles        = ViewState.FLOOR_PLAN.camAngles,
                            onCamAngleChange = { setCamAngle(ViewState.FLOOR_PLAN, it) },
                            onTear           = { panePosition = PanePosition.BOTTOM },
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
                                camAngle               = camAngleOf(ViewState.EXTERIOR),
                                camAngles              = ViewState.EXTERIOR.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.EXTERIOR, it) },
                            )
                        } else {
                            HomePane(
                                vertical               = true,
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                onNeighborhoodToggle   = { viewState = ViewState.NEIGHBORHOOD },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                onAttic                = { enterAttic(from = ViewState.EXTERIOR) },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                                camAngle               = camAngleOf(ViewState.EXTERIOR),
                                camAngles              = ViewState.EXTERIOR.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.EXTERIOR, it) },
                                onTear                 = { panePosition = PanePosition.BOTTOM },
                                onGarage               = { enterDriveway(from = ViewState.EXTERIOR) },
                                onDeckDragStart        = { pos -> deckTrayDrag = pos },
                                onDeckDrag             = { pos -> deckTrayDrag = pos },
                                onDeckDrop             = {
                                    deckTrayDrag?.let { dropDeckAt(it) }
                                    deckTrayDrag = null
                                },
                                onDeckCancel           = { deckTrayDrag = null },
                                hasGarageRoom          = HouseSceneGeometry.townhouseGarageBox(floorLayout) != null,
                                onGarageDragStart      = { pos -> garageTrayDrag = pos },
                                onGarageDrag           = { pos -> garageTrayDrag = pos },
                                onGarageDrop           = {
                                    garageTrayDrag?.let { dropGarageAt(it) }
                                    garageTrayDrag = null
                                },
                                onGarageCancel         = { garageTrayDrag = null },
                                onPoolDragStart        = { pos -> poolTrayDrag = pos },
                                onPoolDrag             = { pos -> poolTrayDrag = pos },
                                onPoolDrop             = {
                                    poolTrayDrag?.let { dropPoolAt(it) }
                                    poolTrayDrag = null
                                },
                                onPoolCancel           = { poolTrayDrag = null },
                                deckPlaced             = placedDecks.isNotEmpty(),
                                hvacPlacement          = hvacPlacement,
                                onHvacDragStart        = { pos -> hvacTrayDrag = pos },
                                onHvacDrag             = { pos -> hvacTrayDrag = pos },
                                onHvacDrop             = {
                                    hvacTrayDrag?.let { dropHvacAt(it) }
                                    hvacTrayDrag = null
                                },
                                onHvacCancel           = { hvacTrayDrag = null },
                                solarArrayPlacement    = solarArrayPlacement,
                                onSolarDragStart       = { pos -> solarTrayDrag = pos },
                                onSolarDrag            = { pos -> solarTrayDrag = pos },
                                onSolarDrop            = {
                                    solarTrayDrag?.let { dropSolarAt(it) }
                                    solarTrayDrag = null
                                },
                                onSolarCancel          = { solarTrayDrag = null },
                                evBatteryPlacement     = evBatteryPlacement,
                                onEvBatteryDragStart   = { pos -> evBatteryTrayDrag = pos },
                                onEvBatteryDrag        = { pos -> evBatteryTrayDrag = pos },
                                onEvBatteryDrop        = {
                                    evBatteryTrayDrag?.let { dropEvBatteryAt(it) }
                                    evBatteryTrayDrag = null
                                },
                                onEvBatteryCancel      = { evBatteryTrayDrag = null },
                                onTreeDragStart        = { pos -> treeTrayDrag = pos },
                                onTreeDrag             = { pos -> treeTrayDrag = pos },
                                onTreeDrop             = {
                                    treeTrayDrag?.let { dropYardDecorAt(YardDecorKind.TREE, it) }
                                    treeTrayDrag = null
                                },
                                onTreeCancel           = { treeTrayDrag = null },
                                onGazeboDragStart      = { pos -> gazeboTrayDrag = pos },
                                onGazeboDrag           = { pos -> gazeboTrayDrag = pos },
                                onGazeboDrop           = {
                                    gazeboTrayDrag?.let { dropYardDecorAt(YardDecorKind.GAZEBO, it) }
                                    gazeboTrayDrag = null
                                },
                                onGazeboCancel         = { gazeboTrayDrag = null },
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
                            isCurrentClaim = savedHome?.neighborKey == previewedNeighbor!!.label,
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
                            ViewState.NEIGHBORHOOD -> HomePane(
                                vertical               = true,
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                onNeighborhoodToggle   = { viewState = ViewState.EXTERIOR },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = neighborhoodCamSettings,
                                onCameraSettingsChange = { neighborhoodCamSettings = it },
                                camAngle               = camAngleOf(ViewState.NEIGHBORHOOD),
                                camAngles              = ViewState.NEIGHBORHOOD.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.NEIGHBORHOOD, it) },
                                onTear                 = { panePosition = PanePosition.BOTTOM },
                            )
                            ViewState.ROOM -> RoomPane(
                                vertical               = true,
                                room                   = activeRoomZone?.type ?: RoomType.LIVING_ROOM,
                                removedInstances        = removedInstances,
                                // The attic has no default items to mark "not in my home", and
                                // no RoomPlacement to key them on.
                                defaultInstancePrefix  = if (atAttic) "" else
                                    activeRoomZone?.let { floorLayout.placementIdsInZone(it, currentFloor).minOrNull() } ?: "",
                                // Same shared toggle as everywhere else. A room item's key is
                                // "$placementId:${item.name}", so it can never match a HomeSystem
                                // name and nothing gets unplaced here — just the plain hide.
                                onItemToggle           = toggleRemovedKey,
                                itemCounts             = activeRoomItemCounts,
                                canAddItem             = activeRoomCanAdd,
                                showAllItems           = trayShowAll,
                                onToggleShowAllItems   = { trayShowAll = !trayShowAll },
                                place                  = roomPlace,
                                hasGarage              = surroundings.garageEnclosed,
                                // The attic's zone is typed STORAGE so it needs no new RoomType;
                                // the header says what it actually is.
                                placeLabel             = if (atAttic) resolvedAtticType.label else null,
                                placeIcon              = if (atAttic) Icons.Outlined.Roofing else null,
                                // Each place brings its own strip: a room's appliances, the
                                // driveway's vehicles and door, the attic's mechanicals.
                                maintenance            = when (roomPlace) {
                                    RoomPlace.DRIVEWAY -> garageMaintenance
                                    RoomPlace.ATTIC    -> atticMaintenance
                                    RoomPlace.INDOOR   -> activeRoomMaintenance
                                },
                                // The attic's mechanical row toggles HiddenAssets and HomeSystems
                                // by bare name — the same keys the hub's Inventory tab writes, and
                                // the same shared toggle, so the two are views of one fact and
                                // can't disagree. Switching HVAC off here takes the outdoor
                                // condenser off the wall too, not just the air handler overhead.
                                onAssetToggle          = toggleRemovedKey,
                                onOpenMaintenanceCard  = { maintenanceTarget = it },
                                onFurnitureDragStart   = { item, pos -> trayDrag = item to pos },
                                onFurnitureDrag        = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onFurnitureDrop        = {
                                    trayDrag?.let { (item, pos) ->
                                        if (item.isVehicle) dropVehicleAt(item, pos) else dropFurnitureAt(item, pos)
                                    }
                                    trayDrag = null
                                },
                                onFurnitureCancel      = { trayDrag = null },
                                onBack                 = { viewState = roomReturn; roomPlace = RoomPlace.INDOOR; activeZone = null; activePlacement = null; selectedFurnitureId = null },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                camAngle               = camAngleOf(ViewState.ROOM),
                                // The attic drops EYE — a crawl space you look into, not stand in.
                                camAngles              = ViewState.ROOM.camAngles(roomPlace),
                                onCamAngleChange       = { setCamAngle(ViewState.ROOM, it) },
                                // Already up here? Then there's nothing for the icon to do.
                                onAttic                = if (atAttic) null
                                                         else ({ enterAttic(from = ViewState.ROOM) }),
                                cameraSettings         = roomCamSettings,
                                onCameraSettingsChange = { roomCamSettings = it },
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
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                selectedCells = emptySet()
                            },
                            onShrinkRows  = {
                                floorLayout = floorLayout.shrinkRows()
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                selectedCells = emptySet()
                            },
                            onRemoveFloor = {
                                val top = floorLayout.maxFloor()
                                floorLayout = floorLayout.removeTopFloor()
                                placedItems = rescueOrphans(floorLayout, placedItems)
                                if (currentFloor >= top) currentFloor = floorLayout.maxFloor()
                                selectedCells = emptySet()
                            },
                            onAttic       = { enterAttic(from = ViewState.FLOOR_PLAN) },
                            onExit        = { viewState = ViewState.EXTERIOR; selectedCells = emptySet(); floorPlanEditMode = false },
                            onInfo        = { showInfo = true },
                            homeClaimed      = savedHome != null,
                            onHomeInfo       = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                            onMaintenanceHub = { showMaintenanceHub = true },
                            cameraSettings         = floorPlanCamSettings,
                            onCameraSettingsChange = { floorPlanCamSettings = it },
                            camAngle         = camAngleOf(ViewState.FLOOR_PLAN),
                            camAngles        = ViewState.FLOOR_PLAN.camAngles,
                            onCamAngleChange = { setCamAngle(ViewState.FLOOR_PLAN, it) },
                            onTear           = { panePosition = PanePosition.RIGHT },
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
                                camAngle               = camAngleOf(ViewState.EXTERIOR),
                                camAngles              = ViewState.EXTERIOR.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.EXTERIOR, it) },
                            )
                        } else {
                            HomePane(
                                vertical               = false,
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                onNeighborhoodToggle   = { viewState = ViewState.NEIGHBORHOOD },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                onAttic                = { enterAttic(from = ViewState.EXTERIOR) },
                                cameraSettings         = exteriorCamSettings,
                                onCameraSettingsChange = { exteriorCamSettings = it },
                                camAngle               = camAngleOf(ViewState.EXTERIOR),
                                camAngles              = ViewState.EXTERIOR.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.EXTERIOR, it) },
                                onTear                 = { panePosition = PanePosition.RIGHT },
                                onGarage               = { enterDriveway(from = ViewState.EXTERIOR) },
                                onDeckDragStart        = { pos -> deckTrayDrag = pos },
                                onDeckDrag             = { pos -> deckTrayDrag = pos },
                                onDeckDrop             = {
                                    deckTrayDrag?.let { dropDeckAt(it) }
                                    deckTrayDrag = null
                                },
                                onDeckCancel           = { deckTrayDrag = null },
                                hasGarageRoom          = HouseSceneGeometry.townhouseGarageBox(floorLayout) != null,
                                onGarageDragStart      = { pos -> garageTrayDrag = pos },
                                onGarageDrag           = { pos -> garageTrayDrag = pos },
                                onGarageDrop           = {
                                    garageTrayDrag?.let { dropGarageAt(it) }
                                    garageTrayDrag = null
                                },
                                onGarageCancel         = { garageTrayDrag = null },
                                onPoolDragStart        = { pos -> poolTrayDrag = pos },
                                onPoolDrag             = { pos -> poolTrayDrag = pos },
                                onPoolDrop             = {
                                    poolTrayDrag?.let { dropPoolAt(it) }
                                    poolTrayDrag = null
                                },
                                onPoolCancel           = { poolTrayDrag = null },
                                deckPlaced             = placedDecks.isNotEmpty(),
                                hvacPlacement          = hvacPlacement,
                                onHvacDragStart        = { pos -> hvacTrayDrag = pos },
                                onHvacDrag             = { pos -> hvacTrayDrag = pos },
                                onHvacDrop             = {
                                    hvacTrayDrag?.let { dropHvacAt(it) }
                                    hvacTrayDrag = null
                                },
                                onHvacCancel           = { hvacTrayDrag = null },
                                solarArrayPlacement    = solarArrayPlacement,
                                onSolarDragStart       = { pos -> solarTrayDrag = pos },
                                onSolarDrag            = { pos -> solarTrayDrag = pos },
                                onSolarDrop            = {
                                    solarTrayDrag?.let { dropSolarAt(it) }
                                    solarTrayDrag = null
                                },
                                onSolarCancel          = { solarTrayDrag = null },
                                evBatteryPlacement     = evBatteryPlacement,
                                onEvBatteryDragStart   = { pos -> evBatteryTrayDrag = pos },
                                onEvBatteryDrag        = { pos -> evBatteryTrayDrag = pos },
                                onEvBatteryDrop        = {
                                    evBatteryTrayDrag?.let { dropEvBatteryAt(it) }
                                    evBatteryTrayDrag = null
                                },
                                onEvBatteryCancel      = { evBatteryTrayDrag = null },
                                onTreeDragStart        = { pos -> treeTrayDrag = pos },
                                onTreeDrag             = { pos -> treeTrayDrag = pos },
                                onTreeDrop             = {
                                    treeTrayDrag?.let { dropYardDecorAt(YardDecorKind.TREE, it) }
                                    treeTrayDrag = null
                                },
                                onTreeCancel           = { treeTrayDrag = null },
                                onGazeboDragStart      = { pos -> gazeboTrayDrag = pos },
                                onGazeboDrag           = { pos -> gazeboTrayDrag = pos },
                                onGazeboDrop           = {
                                    gazeboTrayDrag?.let { dropYardDecorAt(YardDecorKind.GAZEBO, it) }
                                    gazeboTrayDrag = null
                                },
                                onGazeboCancel         = { gazeboTrayDrag = null },
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
                            isCurrentClaim = savedHome?.neighborKey == previewedNeighbor!!.label,
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
                            ViewState.NEIGHBORHOOD -> HomePane(
                                vertical               = false,
                                homeStyle              = homeStyle,
                                featurePlacements      = featurePlacements,
                                onNeighborhoodToggle   = { viewState = ViewState.EXTERIOR },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                cameraSettings         = neighborhoodCamSettings,
                                onCameraSettingsChange = { neighborhoodCamSettings = it },
                                camAngle               = camAngleOf(ViewState.NEIGHBORHOOD),
                                camAngles              = ViewState.NEIGHBORHOOD.camAngles,
                                onCamAngleChange       = { setCamAngle(ViewState.NEIGHBORHOOD, it) },
                                onTear                 = { panePosition = PanePosition.RIGHT },
                            )
                            ViewState.ROOM -> RoomPane(
                                vertical               = false,
                                room                   = activeRoomZone?.type ?: RoomType.LIVING_ROOM,
                                removedInstances        = removedInstances,
                                // The attic has no default items to mark "not in my home", and
                                // no RoomPlacement to key them on.
                                defaultInstancePrefix  = if (atAttic) "" else
                                    activeRoomZone?.let { floorLayout.placementIdsInZone(it, currentFloor).minOrNull() } ?: "",
                                // Same shared toggle as everywhere else. A room item's key is
                                // "$placementId:${item.name}", so it can never match a HomeSystem
                                // name and nothing gets unplaced here — just the plain hide.
                                onItemToggle           = toggleRemovedKey,
                                itemCounts             = activeRoomItemCounts,
                                canAddItem             = activeRoomCanAdd,
                                showAllItems           = trayShowAll,
                                onToggleShowAllItems   = { trayShowAll = !trayShowAll },
                                place                  = roomPlace,
                                hasGarage              = surroundings.garageEnclosed,
                                // The attic's zone is typed STORAGE so it needs no new RoomType;
                                // the header says what it actually is.
                                placeLabel             = if (atAttic) resolvedAtticType.label else null,
                                placeIcon              = if (atAttic) Icons.Outlined.Roofing else null,
                                // Each place brings its own strip: a room's appliances, the
                                // driveway's vehicles and door, the attic's mechanicals.
                                maintenance            = when (roomPlace) {
                                    RoomPlace.DRIVEWAY -> garageMaintenance
                                    RoomPlace.ATTIC    -> atticMaintenance
                                    RoomPlace.INDOOR   -> activeRoomMaintenance
                                },
                                // The attic's mechanical row toggles HiddenAssets and HomeSystems
                                // by bare name — the same keys the hub's Inventory tab writes, and
                                // the same shared toggle, so the two are views of one fact and
                                // can't disagree. Switching HVAC off here takes the outdoor
                                // condenser off the wall too, not just the air handler overhead.
                                onAssetToggle          = toggleRemovedKey,
                                onOpenMaintenanceCard  = { maintenanceTarget = it },
                                onFurnitureDragStart   = { item, pos -> trayDrag = item to pos },
                                onFurnitureDrag        = { pos -> trayDrag = trayDrag?.let { it.first to pos } },
                                onFurnitureDrop        = {
                                    trayDrag?.let { (item, pos) ->
                                        if (item.isVehicle) dropVehicleAt(item, pos) else dropFurnitureAt(item, pos)
                                    }
                                    trayDrag = null
                                },
                                onFurnitureCancel      = { trayDrag = null },
                                onBack                 = { viewState = roomReturn; roomPlace = RoomPlace.INDOOR; activeZone = null; activePlacement = null; selectedFurnitureId = null },
                                onInfo                 = { showInfo = true },
                                homeClaimed            = savedHome != null,
                                onHomeInfo             = { if (savedHome != null) showHomeDatesDialog = true else viewState = ViewState.NEIGHBORHOOD },
                                onMaintenanceHub       = { showMaintenanceHub = true },
                                camAngle               = camAngleOf(ViewState.ROOM),
                                // The attic drops EYE — a crawl space you look into, not stand in.
                                camAngles              = ViewState.ROOM.camAngles(roomPlace),
                                onCamAngleChange       = { setCamAngle(ViewState.ROOM, it) },
                                // Already up here? Then there's nothing for the icon to do.
                                onAttic                = if (atAttic) null
                                                         else ({ enterAttic(from = ViewState.ROOM) }),
                                cameraSettings         = roomCamSettings,
                                onCameraSettingsChange = { roomCamSettings = it },
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
    garageTrayDrag?.let { pos ->
        Icon(
            HomeFeature.GARAGE.icon,
            contentDescription = null,
            tint = HomeFeature.GARAGE.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    poolTrayDrag?.let { pos ->
        Icon(
            HomeFeature.POOL.icon,
            contentDescription = null,
            tint = HomeFeature.POOL.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    hvacTrayDrag?.let { pos ->
        Icon(
            HomeSystem.HVAC.icon,
            contentDescription = null,
            tint = HomeSystem.HVAC.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    solarTrayDrag?.let { pos ->
        Icon(
            HomeSystem.SOLAR.icon,
            contentDescription = null,
            tint = HomeSystem.SOLAR.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    evBatteryTrayDrag?.let { pos ->
        Icon(
            HomeSystem.EV_BATTERY.icon,
            contentDescription = null,
            tint = HomeSystem.EV_BATTERY.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    treeTrayDrag?.let { pos ->
        Icon(
            YardDecorKind.TREE.icon,
            contentDescription = null,
            tint = YardDecorKind.TREE.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }
    gazeboTrayDrag?.let { pos ->
        Icon(
            YardDecorKind.GAZEBO.icon,
            contentDescription = null,
            tint = YardDecorKind.GAZEBO.iconColor,
            modifier = Modifier
                .zIndex(200f)
                .offset { IntOffset((pos.x - 20.dp.toPx()).roundToInt(), (pos.y - 44.dp.toPx()).roundToInt()) }
                .size(40.dp),
        )
    }

    // Backup/restore, over every scene and both pane positions at once — the top-left is empty
    // either way, since all chrome docks right or bottom and the claim/find bars float top-CENTRE.
    // Declared LAST and at the highest zIndex in this Box on purpose: draw order is zIndex first,
    // then declaration order, so nothing in Compose — not even a drag ghost — can land on top of
    // it, and the SceneView's SurfaceView can't cover it at any camera angle.
    // safeDrawingPadding is on us: this root Box isn't inset, each AnimatedContent branch insets
    // itself.
    CloudBackupPill(
        isPremium = isAdFree,
        onBackup  = doBackup,
        onRestore = doRestore,
        modifier  = Modifier
            .align(Alignment.TopStart)
            .zIndex(300f)
            .safeDrawingPadding()
            .padding(start = 8.dp, top = 8.dp),
    )
    } // outer Box
}

// ── Exterior / neighborhood pane ──────────────────────────────────────────────

// One function for both orientations. It was two — RightPane and BottomPane, copy-pasted down to
// the comments — and they had already drifted apart once (each icon's own null check below is
// what caught it). [vertical] picks the lane geometry; everything else here is the CONTENT of
// those lanes, which was never orientation-specific to begin with. See ScenePane.
@Composable
private fun HomePane(
    vertical: Boolean,
    homeStyle: HomeStyle,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    // Single slot, like garage/pool but not IN featurePlacements — gates the HVAC tray icon.
    hvacPlacement: Pair<FeatureSide, Float>? = null,
    // Single slot now (always the back wall, full width) — gates the deck tray icon the same
    // way hvacPlacement gates HVAC's.
    deckPlaced: Boolean = false,
    onNeighborhoodToggle: () -> Unit,
    onInfo: () -> Unit,
    // Home info/claim + maintenance hub — sit between the help and camera-gear icons.
    homeClaimed: Boolean,
    onHomeInfo: () -> Unit,
    onMaintenanceHub: () -> Unit,
    // Opens the attic / rooftop mechanical scene. Null hides the button (neighborhood view,
    // and previewed neighbors — a preview is read-only and has no sub-scenes to walk into).
    onAttic: (() -> Unit)? = null,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    // Camera angle presets for whichever scene this pane is hosting — the gear dialog renders
    // exactly [camAngles], in that order (see ViewState.camAngles). This pane serves both the
    // exterior and the neighborhood, and both offer Side/Top, so no branching is needed.
    camAngle: SceneCamAngle,
    camAngles: List<SceneCamAngle>,
    onCamAngleChange: (SceneCamAngle) -> Unit,
    onTear: (() -> Unit)? = null,
    // Vehicle tray (exterior view of your own home only) — long-press a car/boat/motorcycle
    // icon and drag it onto the lot to park a new one there. Null hides the tray
    // (neighborhood view).
    // Opens the garage scene. Null hides the button (the neighborhood view, which has no
    // single home to walk into).
    onGarage: (() -> Unit)? = null,
    // Deck/garage/pool trays — same tear-drag as the vehicle tray above, but drop onto the
    // yard (each with its own snap/clamp rule — see dropDeckAt/dropGarageAt/dropPoolAt). Null
    // hides them too (neighborhood view). Garage/pool/deck/HVAC/solar/EV-battery are all
    // single-slot, so their icon only shows while unplaced — and, for garage, HOUSE-only; for
    // deck/pool/solar/EV battery/trees/gazebo, non-attached styles only (CONDO has no yard at
    // all — HouseSceneGeometry.yardBounds returns null — so a unit placed there would render
    // nowhere and become a permanently invisible, untappable orphan; see the
    // tracked-vs-placed-items skill). HVAC is the one exception: it's wall-mounted, not
    // yard-placed, and CONDO's unit is the entry point into the Attic scene (see onHvacTap).
    onDeckDragStart: ((Offset) -> Unit)? = null,
    onDeckDrag: (Offset) -> Unit = {},
    onDeckDrop: () -> Unit = {},
    onDeckCancel: () -> Unit = {},
    // True when the LAYOUT already contains a garage room (a TOWNHOUSE always does; the Classic
    // bakes one in, and any home can have one added in the floor plan). Such a home has somewhere
    // to park already, so the tray must not offer it a second, redundant attached wing.
    hasGarageRoom: Boolean = false,
    onGarageDragStart: ((Offset) -> Unit)? = null,
    onGarageDrag: (Offset) -> Unit = {},
    onGarageDrop: () -> Unit = {},
    onGarageCancel: () -> Unit = {},
    onPoolDragStart: ((Offset) -> Unit)? = null,
    onPoolDrag: (Offset) -> Unit = {},
    onPoolDrop: () -> Unit = {},
    onPoolCancel: () -> Unit = {},
    onHvacDragStart: ((Offset) -> Unit)? = null,
    onHvacDrag: (Offset) -> Unit = {},
    onHvacDrop: () -> Unit = {},
    onHvacCancel: () -> Unit = {},
    // Solar array tray — single slot like HVAC (gates the icon once placed). Available from any
    // exterior camera angle — dropSolarAt raycasts against the roof's real tilted slope planes,
    // not a flat approximation, so it no longer needs the near-vertical Top vantage.
    solarArrayPlacement: Pair<Boolean, Float>? = null,
    onSolarDragStart: ((Offset) -> Unit)? = null,
    onSolarDrag: (Offset) -> Unit = {},
    onSolarDrop: () -> Unit = {},
    onSolarCancel: () -> Unit = {},
    // EV Battery tray — same shape as HVAC's, single-slot, all 4 walls, no gating on solar.
    evBatteryPlacement: Pair<FeatureSide, Float>? = null,
    onEvBatteryDragStart: ((Offset) -> Unit)? = null,
    onEvBatteryDrag: (Offset) -> Unit = {},
    onEvBatteryDrop: () -> Unit = {},
    onEvBatteryCancel: () -> Unit = {},
    // Tree/gazebo trays — always re-offered (unlimited instances, like decks/solar), gated
    // only on the style having a yard at all (null hides both, same as neighborhood view).
    onTreeDragStart: ((Offset) -> Unit)? = null,
    onTreeDrag: (Offset) -> Unit = {},
    onTreeDrop: () -> Unit = {},
    onTreeCancel: () -> Unit = {},
    onGazeboDragStart: ((Offset) -> Unit)? = null,
    onGazeboDrag: (Offset) -> Unit = {},
    onGazeboDrop: () -> Unit = {},
    onGazeboCancel: () -> Unit = {},
) {
    // The command lane: back · help · home · maintenance · camera gear, pinned at the screen edge.
    val commandLane: @Composable () -> Unit = {
        PanelIcon(Icons.Outlined.ArrowBack, "Neighborhood", false, onClick = onNeighborhoodToggle)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        PanelIcon(
            if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
            if (homeClaimed) "Home info" else "Claim this home",
            false, onClick = onHomeInfo,
        )
        PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
        if (onAttic != null) PanelIcon(Icons.Outlined.Roofing, "Attic", false, onClick = onAttic)
        // Walks into the garage scene, where every vehicle is now added and tracked. Paired
        // with the attic icon as the home's two sub-scenes: tapping the 3D garage door still
        // works, but a CONDO has no garage body to tap, and that used to leave it with no way
        // into the scene — and so no way to own a vehicle at all.
        if (onGarage != null) PanelIcon(Icons.Outlined.Garage, "Garage", false, onClick = onGarage)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp),
            camAngle = camAngle, camAngles = camAngles, onCamAngleChange = onCamAngleChange)
    }
    // The feature tray — yard and whole-home systems only, and the one lane of this pane that
    // changes with the scene, so it takes the slot nearest the yard you drop onto. Vehicles moved
    // out to the garage scene entirely (every style can reach it, see the Garage panel icon
    // above). A caller with nothing to drop into passes none of these callbacks, and the lane
    // goes away with them. Asking all of them rather than electing one as the stand-in is what
    // keeps each icon's own null check load-bearing: elect one and that icon's check is dead
    // weight, which is how the two panes drifted apart in the first place.
    val offersFeatureTray = onDeckDragStart != null || onGarageDragStart != null ||
        onPoolDragStart != null || onHvacDragStart != null || onSolarDragStart != null ||
        onEvBatteryDragStart != null || onTreeDragStart != null || onGazeboDragStart != null
    val featureLane: @Composable () -> Unit = {
        if (onDeckDragStart != null && !homeStyle.isAttached && !deckPlaced) {
            FeatureTrayIcon(
                icon        = HomeFeature.DECK.icon,
                label       = HomeFeature.DECK.label,
                iconColor   = HomeFeature.DECK.iconColor,
                onDragStart = onDeckDragStart,
                onDrag      = onDeckDrag,
                onDrop      = onDeckDrop,
                onCancel    = onDeckCancel,
            )
        }
        // Offered only to a home with NOWHERE to park: a HOUSE with neither an attached wing
        // already placed nor an interior garage bay in its layout. The Classic is the model
        // that made the second clause necessary — its preset bakes a GARAGE room into the
        // floor plan and so carries no featurePlacements entry to suppress this icon, which
        // left it offering a second, redundant garage. Asking the LAYOUT rather than naming
        // the model also covers a home the user builds a garage room into by hand.
        if (onGarageDragStart != null && homeStyle == HomeStyle.HOUSE &&
            HomeFeature.GARAGE !in featurePlacements &&
            !hasGarageRoom) {
            FeatureTrayIcon(
                icon        = HomeFeature.GARAGE.icon,
                label       = HomeFeature.GARAGE.label,
                iconColor   = HomeFeature.GARAGE.iconColor,
                onDragStart = onGarageDragStart,
                onDrag      = onGarageDrag,
                onDrop      = onGarageDrop,
                onCancel    = onGarageCancel,
            )
        }
        if (onPoolDragStart != null && !homeStyle.isAttached && HomeFeature.POOL !in featurePlacements) {
            FeatureTrayIcon(
                icon        = HomeFeature.POOL.icon,
                label       = HomeFeature.POOL.label,
                iconColor   = HomeFeature.POOL.iconColor,
                onDragStart = onPoolDragStart,
                onDrag      = onPoolDrag,
                onDrop      = onPoolDrop,
                onCancel    = onPoolCancel,
            )
        }
        if (onHvacDragStart != null && hvacPlacement == null) {
            FeatureTrayIcon(
                icon        = HomeSystem.HVAC.icon,
                label       = HomeSystem.HVAC.label,
                iconColor   = HomeSystem.HVAC.iconColor,
                onDragStart = onHvacDragStart,
                onDrag      = onHvacDrag,
                onDrop      = onHvacDrop,
                onCancel    = onHvacCancel,
            )
        }
        if (onSolarDragStart != null && !homeStyle.isAttached && solarArrayPlacement == null) {
            FeatureTrayIcon(
                icon        = HomeSystem.SOLAR.icon,
                label       = HomeSystem.SOLAR.label,
                iconColor   = HomeSystem.SOLAR.iconColor,
                onDragStart = onSolarDragStart,
                onDrag      = onSolarDrag,
                onDrop      = onSolarDrop,
                onCancel    = onSolarCancel,
            )
        }
        if (onEvBatteryDragStart != null && !homeStyle.isAttached && evBatteryPlacement == null) {
            FeatureTrayIcon(
                icon        = HomeSystem.EV_BATTERY.icon,
                label       = HomeSystem.EV_BATTERY.label,
                iconColor   = HomeSystem.EV_BATTERY.iconColor,
                onDragStart = onEvBatteryDragStart,
                onDrag      = onEvBatteryDrag,
                onDrop      = onEvBatteryDrop,
                onCancel    = onEvBatteryCancel,
            )
        }
        // yardBounds(homeStyle, ...) is null only for CONDO, regardless of w/d — style
        // alone is enough to gate the tray icon here (this pane doesn't carry the live floor
        // dimensions).
        if (onTreeDragStart != null && homeStyle != HomeStyle.CONDO) {
            FeatureTrayIcon(
                icon        = YardDecorKind.TREE.icon,
                label       = YardDecorKind.TREE.label,
                iconColor   = YardDecorKind.TREE.iconColor,
                onDragStart = onTreeDragStart,
                onDrag      = onTreeDrag,
                onDrop      = onTreeDrop,
                onCancel    = onTreeCancel,
            )
        }
        if (onGazeboDragStart != null && homeStyle != HomeStyle.CONDO) {
            FeatureTrayIcon(
                icon        = YardDecorKind.GAZEBO.icon,
                label       = YardDecorKind.GAZEBO.label,
                iconColor   = YardDecorKind.GAZEBO.iconColor,
                onDragStart = onGazeboDragStart,
                onDrag      = onGazeboDrag,
                onDrop      = onGazeboDrop,
                onCancel    = onGazeboCancel,
            )
        }
    }
    ScenePane(
        vertical    = vertical,
        onTear      = onTear,
        sceneLanes  = if (offersFeatureTray) listOf(featureLane) else emptyList(),
        commandLane = commandLane,
    )
}

// ── Neighbor preview pane ─────────────────────────────────────────────────────

@Composable
private fun NeighborPreviewPane(
    vertical: Boolean,
    onBack: () -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    camAngle: SceneCamAngle,
    camAngles: List<SceneCamAngle>,
    onCamAngleChange: (SceneCamAngle) -> Unit,
) {
    // Command lane only — a preview is read-only, so there is no scene lane and the pane stays
    // the single strip it always was. Back · camera gear: the style icon and the home's name/floor
    // count both live in the floating ClaimHomeDialog instead — the "which home is this" context
    // is shared with the own-home claim flow, so it doesn't belong here too.
    ScenePane(vertical = vertical) {
        PanelIcon(Icons.Outlined.ArrowBack, "Back", false, onClick = onBack)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp),
            camAngle = camAngle, camAngles = camAngles, onCamAngleChange = onCamAngleChange)
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
    // True while previewing the exact model already claimed (see the neighborhood lot's own
    // "this is yours" marker) — onClaim still does the same thing (re-claims this model,
    // resetting it to defaults), just labeled honestly instead of reading like a first claim.
    isCurrentClaim: Boolean = false,
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
                    Icon(
                        if (isCurrentClaim) Icons.Outlined.RestartAlt else Icons.Outlined.AddHome,
                        null, Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isCurrentClaim) "Reset to default" else "Mine")
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
            // Unlike the icon-only docked banners (ClaimHomeDialog etc.), this one's content
            // is a full sentence — without a width cap the Row grows to fit it on one line,
            // which can overflow a narrow phone's screen. fillMaxWidth + the Text's weight(1f)
            // below let it wrap instead.
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Find your home in the neighborhood",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f))
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

// A LEFT/RIGHT garage's default anchor for a preset model — flush with the front wall, the
// same rule HouseSceneGeometry.garageFrontAlignedAlong already gives a freshly-dropped garage.
// Centered-on-the-wall (offset 0, i.e. no entry in the map) predates that rule and is now just
// what "no override yet" happened to mean, not an intentional default — used both when
// previewing a model and when actually claiming it, so the garage doesn't jump on claim.
private fun defaultGarageOffsets(side: FeatureSide?, w: Float, d: Float): Map<HomeFeature, Pair<Float, Float>> =
    side?.takeIf { it == FeatureSide.LEFT || it == FeatureSide.RIGHT }
        ?.let { mapOf(HomeFeature.GARAGE to (HouseSceneGeometry.garageFrontAlignedAlong(it, w, d) to 0f)) }
        ?: emptyMap()

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

// Backup and restore, floating at the top-left over every scene.
//
// Deliberately NOT a Dialog, though the other floating bars are. A permanent dialog window has to
// re-apply its gravity/flags/attributes from a SideEffect, which fires on every recomposition —
// and this screen recomposes every frame while the camera moves, so it relayouts a window over the
// SurfaceView forever and the app locks up. The transient bars survive it only because they're on
// screen for seconds. Layering is the drag ghosts' approach instead, which is proven over this
// scene: an explicit zIndex, since a SurfaceView paints through its own hardware layer and would
// otherwise cover Compose content regardless of camera angle.
//
// Not pane icons: the bottom pane already scrolls on a phone at six of them, and — the reason this
// exists — restore has to be reachable with NO home claimed. Its only other entry point is the Home
// dialog, which is guarded on savedHome, so a fresh install had a backup zip and nowhere to feed it in.
@Composable
private fun CloudBackupPill(
    isPremium: Boolean,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        // CircleShape on a wider-than-tall Row is what gives the pill its rounded ends.
        shape = CircleShape,
        // Opaque, not tinted-translucent: it sits over sky, grass and dark room walls by turns,
        // and the glyphs have to stay readable against all of them.
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The SAME two glyphs the Home dialog's buttons carry, so one shape means one action
            // wherever you meet it. Not a CloudUpload/CloudDownload pair: those are the same cloud
            // with one small arrow flipped, indistinguishable at this size — and restore overwrites
            // everything, so telling them apart at a glance is the whole job.
            CloudActionIcon(Icons.Outlined.Backup, "Backup app data", isPremium, onBackup)
            // Splits the pill into two obviously separate controls, not one wide target.
            VerticalDivider(
                modifier = Modifier.height(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
            )
            CloudActionIcon(Icons.Outlined.SettingsBackupRestore, "Restore app data", isPremium, onRestore)
        }
    }
}

// One action, with a padlock badge while the Premium gate is closed. HomeDatesDialog's buttons say
// the same thing by swapping their whole icon for a lock, but here the glyph is the only label there
// is — replacing it would leave nothing saying WHICH action this is, so the lock rides alongside
// instead. Tapping while locked opens the purchase sheet either way.
// Shared with the Maintenance Hub header, hence internal rather than private.
@Composable
internal fun CloudActionIcon(
    icon: ImageVector,
    label: String,
    isPremium: Boolean,
    onClick: () -> Unit,
) {
    // A clickable Surface rather than an IconButton, for the same reason PanelIcon is one: it sizes
    // to exactly what it's told, where IconButton silently floors its layout at the 48.dp minimum
    // touch target. 44.dp still clears the accessibility floor comfortably; the 22.dp glyph inside
    // is what makes these read lighter than the pane icons.
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Inner Box sizes to the glyph, not the 44.dp target, so the badge hangs off the
            // cloud's own corner instead of the far corner of the touch area.
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isPremium) {
                    // The surface-coloured backing is what keeps the padlock readable where it
                    // overlaps the cloud's own outline.
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 3.dp, y = 3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
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

// ── The pane skeleton every scene's pane is built on ──────────────────────────

// Two lanes with a fixed meaning, whichever edge the pane is torn to:
//
//   [sceneLanes]  what THIS scene lets you drag or switch between — put nearest the 3D view you
//                 drop into (the top rows of a bottom pane, the upper column of a right pane).
//   [commandLane] back · help · home · maintenance · gear, the icons every scene shares — pinned
//                 along the screen EDGE, farthest from the drop area.
//
// One strip that ran commands-first and items-last is what this replaces. It put ~336.dp of
// command icons in front of the first draggable icon, so on a phone the tray began off-screen in
// both orientations and a first-time user simply never found it — FloorSelectorBar had already
// written that failure down as a comment about its own floor tabs. Splitting the lanes gives the
// scene's own icons the start of their scroll, and gives the shared ones a constant home: the
// bottom edge in a bottom pane, the foot of the column in a right pane. Tear the pane across and
// Back stays where the thumb last left it.
//
// Lanes are slots rather than a list of items because each pane fills them differently — feature
// icons, a furniture tray plus a maintenance strip, floor tabs — but every one of those already
// emits BARE icons into whatever layout its caller provides, so the same body serves a Row and a
// Column. No [sceneLanes] means this scene offers nothing to drag (the neighborhood, a previewed
// neighbor) and the pane collapses to the single strip it always was.
@Composable
private fun ScenePane(
    vertical: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onTear: (() -> Unit)? = null,
    sceneLanes: List<@Composable () -> Unit> = emptyList(),
    commandLane: @Composable () -> Unit,
) {
    // The grip. Leads the command lane in both orientations — leftmost in a bottom pane's row,
    // just under the lane divider in a right pane — so it reads as the handle of the strip that
    // stays put rather than of the scene's own icons.
    val handle: @Composable () -> Unit = {
        if (onTear != null) {
            DragHandle(
                modifier = Modifier.size(48.dp).tearGesture(dragDown = vertical, onTear = onTear),
                rotated  = !vertical,
            )
            if (vertical) PanelDivider() else VerticalDivider(Modifier.height(36.dp))
        }
    }
    if (vertical) {
        BoxWithConstraints(
            modifier
                .width(64.dp)
                .fillMaxHeight()
                .background(containerColor),
        ) {
            // Seven 48.dp command icons want ~350.dp, and a landscape phone's pane is only ~380.dp
            // tall — pinned at its natural height the command lane would squeeze the tray out of
            // frame completely, which is the very failure this layout exists to fix. So it yields
            // whatever it must to keep two item cells visible and scrolls inside itself instead.
            val commandMax = (maxHeight - 116.dp).coerceAtLeast(144.dp)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sceneLanes.isNotEmpty()) {
                    // All the scene's lanes share ONE scroll region: a 64.dp column has no room to
                    // give each its own, and stacking them keeps the tray/maintenance split legible
                    // through the dividers alone.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        sceneLanes.forEachIndexed { i, lane ->
                            if (i > 0) PanelDivider()
                            lane()
                        }
                    }
                    HorizontalDivider()
                } else {
                    // Nothing to drag here, but the commands still hug the foot of the pane —
                    // walking from the exterior to the neighborhood must not slide Back up the
                    // screen. The whole point of the two lanes is that one of them never moves.
                    Spacer(Modifier.weight(1f))
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = commandMax)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    handle()
                    commandLane()
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth().background(containerColor)) {
            sceneLanes.forEach { lane ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) { lane() }
                HorizontalDivider()
            }
            // Scrolls too, though it rarely needs to: the handle plus seven command icons come to
            // ~384.dp against a phone's ~392.dp of width, so an eighth would clip silently.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                handle()
                commandLane()
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
    // Opens the attic / rooftop mechanical scene. Null hides the button (neighborhood view,
    // and previewed neighbors — a preview is read-only and has no sub-scenes to walk into).
    onAttic: (() -> Unit)? = null,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
    // Side / Top — the floor plan is an overview by definition, so there's no Eye.
    camAngle: SceneCamAngle,
    camAngles: List<SceneCamAngle>,
    onCamAngleChange: (SceneCamAngle) -> Unit,
    // Swipe the drag handle to move this pane to the other edge, same as every other scene's
    // pane. Null hides the handle.
    onTear: (() -> Unit)? = null,
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
    // The command lane: back · help · home · maintenance · gear · pencil (edit mode), pinned at
    // the screen edge below/beside the floor tabs. All structural editing lives behind the pencil.
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
        // The attic is the storey above the top floor, so reaching it from the plan is the one
        // route that reads as simply going up. Sits with the floor tabs' own navigation.
        if (onAttic != null) IconButton(onClick = onAttic) {
            Icon(Icons.Outlined.Roofing, contentDescription = "Attic")
        }
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp),
            camAngle = camAngle, camAngles = camAngles, onCamAngleChange = onCamAngleChange)
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

    // The compact EDIT strip floats clear of the pane, on whichever side the pane isn't — it
    // appears only while the pencil toggle is ON, so it must not push the bar around when it does.
    val editStrip: @Composable () -> Unit = {
        AnimatedVisibility(
            visible  = editMode,
            modifier = if (vertical) Modifier.padding(top = 8.dp)
                       else Modifier.padding(start = 8.dp, bottom = 4.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                if (vertical) {
                    Column(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { editControls() }
                } else {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { editControls() }
                }
            }
        }
    }
    // The floor tabs are this scene's own lane, so they get the start of their own scroll instead
    // of queueing behind the prefix. They used to share one strip with it, and the handle plus six
    // prefix icons ate 336.dp of a phone's ~411.dp width — three 48.dp tabs need another 144.dp,
    // so F3 sat off-screen with only a half-cut F2 hinting it was there.
    if (vertical) {
        Column(modifier = modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            editStrip()
            ScenePane(
                vertical       = true,
                modifier       = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                onTear         = onTear,
                sceneLanes     = listOf(floorTabs),
                commandLane    = prefix,
            )
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            editStrip()
            ScenePane(
                vertical       = false,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                onTear         = onTear,
                sceneLanes     = listOf(floorTabs),
                commandLane    = prefix,
            )
        }
    }
}

// ── Room pane ─────────────────────────────────────────────────────────────────

// One function for both orientations — see HomePane for why the two were merged. This one has
// TWO scene lanes: the tray you drag out of, nearest the room, and the maintenance strip below
// it. Which puts the strip right against the wrench in the command lane, so it still reads as
// that wrench expanded (it used to sit directly under it), while keeping its status-colored
// icons out of the room-colored tray — a tap there opens a card, a tap in the tray places a
// thing, and the two must never look alike.
@Composable
private fun RoomPane(
    vertical: Boolean,
    room: RoomType,
    removedInstances: Set<String>,
    defaultInstancePrefix: String,
    onItemToggle: (String) -> Unit,
    // Furniture-tray state for THIS room: how many of each item it holds (badged on each icon)
    // and whether it can take another (dims the icon and disables its drag). See RoomZone.canFit.
    itemCounts: Map<RoomItem, Int>,
    canAddItem: (RoomItem) -> Boolean,
    showAllItems: Boolean,
    onToggleShowAllItems: () -> Unit,
    // Which of the three kinds of place this pane is serving (see RoomPlace). It decides both
    // rows below: what's offered to toggle "in my home", and what the tray holds.
    place: RoomPlace = RoomPlace.INDOOR,
    // Toggles one of the attic's mechanical assets by BARE NAME — the same removedInstances keys
    // the hub's Inventory tab writes for HiddenAsset and HomeSystem, so the two can't disagree.
    onAssetToggle: (String) -> Unit = {},
    // See RoomFurnitureTray — false for a driveway with no garage behind it.
    hasGarage: Boolean = true,
    // Overrides the zone-derived header. The attic is typed STORAGE so it needs no new RoomType
    // (see HouseSceneGeometry.atticZone), but it must read as "Attic"/"Utility Closet".
    placeLabel: String? = null,
    placeIcon: ImageVector? = null,
    // This room's task-carrying items and their upkeep status — the maintenance strip.
    maintenance: List<Pair<MaintenanceTarget, UpkeepStatus>>,
    onOpenMaintenanceCard: (MaintenanceTarget) -> Unit,
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
    // Opens the attic / rooftop mechanical scene. Null hides the button (neighborhood view,
    // and previewed neighbors — a preview is read-only and has no sub-scenes to walk into).
    onAttic: (() -> Unit)? = null,
    // Eye / Side / Top — the room is one of the two scenes you can stand inside.
    camAngle: SceneCamAngle,
    camAngles: List<SceneCamAngle>,
    onCamAngleChange: (SceneCamAngle) -> Unit,
    cameraSettings: CameraSettings,
    onCameraSettingsChange: (CameraSettings) -> Unit,
) {
    val commandLane: @Composable () -> Unit = {
        PanelIcon(Icons.Outlined.ArrowBack, "Floor plan", false, onClick = onBack)
        PanelIcon(Icons.Outlined.Info, "Help", false, onClick = onInfo)
        PanelIcon(
            if (homeClaimed) Icons.Outlined.Home else Icons.Outlined.AddHome,
            if (homeClaimed) "Home info" else "Claim this home",
            false, onClick = onHomeInfo,
        )
        PanelIcon(Icons.Outlined.Build, "Maintenance & Guides", false, onClick = onMaintenanceHub)
        CameraGearButton(cameraSettings, onCameraSettingsChange, modifier = Modifier.size(48.dp),
            camAngle = camAngle, camAngles = camAngles, onCamAngleChange = onCamAngleChange)
        // Which place you're standing in, closing out the lane. The bottom pane has the width for
        // its name; the right pane is 64.dp wide and gets the icon alone.
        if (vertical) {
            PanelDivider()
            Icon(placeIcon ?: room.icon, contentDescription = placeLabel ?: room.label,
                modifier = Modifier.size(20.dp), tint = room.iconColor)
        } else {
            VerticalDivider(modifier = Modifier.height(36.dp))
            Icon(placeIcon ?: room.icon, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = room.iconColor)
            Text(placeLabel ?: room.label, style = MaterialTheme.typography.labelMedium,
                color = room.iconColor)
        }
    }
    // The tray lane — what this place holds and what you can drag into it, nearest the room.
    val trayLane: @Composable () -> Unit = {
        // What this place is responsible for — tap to toggle "not in my home". A room owns
        // appliances and fixtures; the attic owns the home's mechanicals (see atticPaneAssets),
        // which is what keeps a utility closet honest: the space holds only the air handler,
        // but the ducts and insulation your home does have are still declared and tracked here.
        // The driveway owns neither — its vehicles are tray items, not fixtures.
        val assets = if (place == RoomPlace.ATTIC) atticPaneAssets else emptyList()
        assets.forEach { a ->
            InventoryIcon(a.icon, a.label, a.color, a.key !in removedInstances) { onAssetToggle(a.key) }
        }
        val items = if (place == RoomPlace.INDOOR) room.defaultItems() else emptyList()
        items.forEach { item ->
            val key = "$defaultInstancePrefix:${item.name}"
            InventoryItemIcon(item, key !in removedInstances) { onItemToggle(key) }
        }
        if (items.isNotEmpty() || assets.isNotEmpty()) {
            if (vertical) PanelDivider() else VerticalDivider(modifier = Modifier.height(40.dp))
        }
        // A GARAGE zone's tray also offers vehicles — the driveway is one (see
        // CarLotGeometry.drivewayZone). They're dropped via dropVehicleAt (see the
        // onFurnitureDrop wiring), not dropFurnitureAt, since a vehicle's dx/dz is
        // CarLot-anchor-relative rather than the room-fraction convention furniture uses.
        RoomFurnitureTray(
            room            = room,
            itemCounts      = itemCounts,
            canAddItem      = canAddItem,
            showAll         = showAllItems,
            onToggleShowAll = onToggleShowAllItems,
            onDragStart     = onFurnitureDragStart,
            onDrag          = onFurnitureDrag,
            onDrop          = onFurnitureDrop,
            onCancel        = onFurnitureCancel,
            place           = place,
            hasGarage       = hasGarage,
        )
    }
    // No tear handle: a room pane has never carried one, and the position it would flip is global
    // state the user sets from the scenes that do.
    ScenePane(
        vertical    = vertical,
        sceneLanes  = if (maintenance.isEmpty()) listOf(trayLane)
                      else listOf(trayLane, { MaintenanceStrip(maintenance, onOpenMaintenanceCard) }),
        commandLane = commandLane,
    )
}

// A pane's maintenance strip — one icon per thing in THIS place that carries recurring tasks,
// tinted by its upkeep status, opening that thing's maintenance card in a single tap. This is
// the wrench expanded into the pane: reaching a card used to mean wrench → Score tab → scroll
// the whole house, and nothing in the scene ever signalled that something was due.
//
// One lane of the room pane, filled differently by each of the three places it serves (see the
// activeRoomMaintenance / garageMaintenance / atticMaintenance builders in HomeHealthScreen):
// a room's own appliances, the driveway's vehicles and door, the attic's mechanical systems.
//
// Emits bare icons into whichever layout the caller provides, the same shape RoomFurnitureTray
// uses so a bottom pane's Row and a right pane's Column share one implementation.
//
// Only task-carrying things appear — upkeepStatusFor returns null without tasks and the
// builders drop those — so a kitchen shows two icons and a bedroom shows none. Every icon here
// is meaningfully colored rather than a wall of neutral ones; cards for everything else still
// open from the hub's Score tab.
@Composable
private fun MaintenanceStrip(
    entries: List<Pair<MaintenanceTarget, UpkeepStatus>>,
    onOpenCard: (MaintenanceTarget) -> Unit,
) {
    entries.forEach { (target, status) ->
        val icon = when (target) {
            is MaintenanceTarget.Item   -> target.item.icon
            is MaintenanceTarget.Placed -> target.item.icon
            is MaintenanceTarget.System -> target.system.icon
            is MaintenanceTarget.Hidden -> target.asset.icon
            is MaintenanceTarget.Custom -> Icons.Outlined.Build
        }
        Surface(
            onClick  = { onOpenCard(target) },
            modifier = Modifier.size(52.dp),
            shape    = CircleShape,
            color    = status.color.copy(alpha = 0.15f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        // Spelled out because color alone carries the whole signal here.
                        contentDescription = "${target.displayLabel}, " + when (status) {
                            UpkeepStatus.RED   -> "maintenance overdue"
                            UpkeepStatus.AMBER -> "maintenance due soon"
                            UpkeepStatus.GREEN -> "maintenance up to date"
                        },
                        modifier = Modifier.size(18.dp),
                        tint     = status.color,
                    )
                    Text(
                        text     = target.displayLabel,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = status.color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// The room pane's furniture tray — long-press an icon and drag it into the room to place it.
// Emits bare icons into whichever layout the caller provides, so the bottom pane's Row and the
// right pane's Column share one implementation (they used to duplicate it, and had already
// drifted: only the bottom pane offered a GARAGE room its vehicles).
//
// The list is room-relevant rather than every piece in the game: RoomType.trayItems covers what
// plausibly belongs here — a garage fridge and a bathroom washer included — and the "All items"
// expander keeps everything else one tap away, so filtering never removes a capability.
@Composable
private fun RoomFurnitureTray(
    room: RoomType,
    itemCounts: Map<RoomItem, Int>,
    canAddItem: (RoomItem) -> Boolean,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onDragStart: (RoomItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    // What the place you're standing in can take. The attic takes the one item that belongs
    // anywhere: keepsakes (see RoomItem.TREASURE's trayItems, which already offers it in every
    // room; this extends that to the one place people actually stash things). Neither synthetic
    // place has default fittings to fill out the rest of a tray.
    place: RoomPlace = RoomPlace.INDOOR,
    // Whether the driveway you're standing on has a garage behind it to put things in. False for a
    // CONDO or a HOUSE before one is added: those park in the open, so the tray stays vehicles-only
    // rather than offering a water heater with nowhere to stand.
    hasGarage: Boolean = true,
) {
    @Composable
    fun trayIcon(item: RoomItem) = FurnitureTrayIcon(
        item        = item,
        onDragStart = onDragStart,
        onDrag      = onDrag,
        onDrop      = onDrop,
        onCancel    = onCancel,
        count       = itemCounts[item] ?: 0,
        atCap       = !canAddItem(item),
    )

    // Anything actually IN the room stays in the primary tray even when this room type doesn't
    // nominally offer it — an existing save may hold a hallway fridge, and its owner must still
    // see the count and be able to re-add one after tearing it out.
    when {
        // An open lot: vehicles and nothing else.
        place == RoomPlace.DRIVEWAY && !hasGarage -> {
            RoomItem.entries.filter { it.isVehicle }.forEach { trayIcon(it) }
            return
        }
        place == RoomPlace.ATTIC -> {
            trayIcon(RoomItem.TREASURE)
            return
        }
        // The driveway falls through: its zone is already typed RoomType.GARAGE (deliberately, see
        // CarLotGeometry.drivewayZone), so room.trayItems() resolves to exactly what a garage ROOM
        // offers — vehicles plus the fridge, water heater, washer, dryer, sink, gym gear and
        // keepsakes. That parity is the whole point: an attached garage should hold what an
        // interior one holds. Vehicles still drop via dropVehicleAt (see onFurnitureDrop), the
        // rest into the garage box via dropFurnitureAt.
        else -> Unit
    }
    // What the place is FOR leads — see RoomType.inTrayOrder. Applied AFTER the itemCounts merge
    // below so a stray hallway fridge is banded by the same rule as everything else.
    val offered = room.trayItems()
    room.inTrayOrder(RoomItem.entries.filter { it in offered || it in itemCounts })
        .forEach { trayIcon(it) }
    val overflow = room.inTrayOrder(room.overflowTrayItems().filterNot { it in itemCounts })
    if (overflow.isNotEmpty()) {
        PanelIcon(
            if (showAll) Icons.Outlined.ExpandLess else Icons.Outlined.MoreHoriz,
            "All items", showAll, onClick = onToggleShowAll,
        )
        if (showAll) overflow.forEach { trayIcon(it) }
    }
}

// One thing this place is responsible for, and whether the home has it. Tapping toggles it in or
// out (the caller writes removedInstances); dimmed means "not in my home".
@Composable
private fun InventoryIcon(
    icon: ImageVector,
    label: String,
    color: Color,
    placed: Boolean,
    onClick: () -> Unit,
) {
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
                    imageVector        = icon,
                    contentDescription = label,
                    modifier           = Modifier.size(if (placed) 16.dp else 18.dp),
                    tint               = if (placed) color else color.copy(alpha = 0.45f),
                )
                Text(
                    text     = label,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = if (placed) color else color.copy(alpha = 0.45f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun InventoryItemIcon(item: RoomItem, placed: Boolean, onClick: () -> Unit) =
    InventoryIcon(item.icon, item.label, item.room.iconColor, placed, onClick)

// One entry in the attic pane's mechanical row. [key] is the BARE enum name, which is exactly what
// removedInstances holds for a HiddenAsset or a HomeSystem (see MaintenanceHubDialog's Inventory
// tab) — so toggling here and toggling there write the same fact.
private data class PaneAsset(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val color: Color,
)

// What an attic is responsible for, in the order it reads: the equipment, what's routed through
// it, and what wraps it. Shown whichever kind of space the home has — a utility closet holds only
// the air handler, but the ducts and insulation the home does have are still declared and tracked
// from up here (see AtticType). Solar and the EV battery are deliberately absent: they're roof and
// wall mounted, so they belong to the maintenance strip beside this row, not to the attic itself.
private val atticPaneAssets: List<PaneAsset> get() = listOf(
    PaneAsset(HomeSystem.HVAC.name, HomeSystem.HVAC.icon, HomeSystem.HVAC.label, HomeSystem.HVAC.iconColor),
    PaneAsset(HiddenAsset.DUCTWORK.name, HiddenAsset.DUCTWORK.icon,
        HiddenAsset.DUCTWORK.label, HiddenAsset.DUCTWORK.iconColor),
    PaneAsset(HiddenAsset.ATTIC_INSULATION.name, HiddenAsset.ATTIC_INSULATION.icon,
        "Insulation", HiddenAsset.ATTIC_INSULATION.iconColor),
)

// A furniture piece in the room pane's tray. Not a toggle: long-press the icon and drag it
// into the 3D room to drop a new copy there. The drag is reported in root coordinates so
// MainActivity can float the ghost icon across panes and unproject the final drop point.
//
// [count] is how many of this item the room already holds — badged on the icon, so the tray
// doubles as an at-a-glance inventory of the room. [atCap] means it can't hold another: either
// the room's floor budget is spent or the item hit its per-type ceiling (see RoomZone.canFit).
// Both default, so a vehicles-only tray — where neither gate applies — needs neither.
@Composable
private fun FurnitureTrayIcon(
    item: RoomItem,
    onDragStart: (RoomItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    count: Int = 0,
    atCap: Boolean = false,
) {
    val color = item.room.iconColor
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // A long-press that goes nowhere reads as a bug, so a full item drops the gesture detector
    // entirely rather than arming a drag whose drop dropFurnitureAt would only refuse.
    val dragModifier = if (atCap) Modifier else Modifier.pointerInput(item) {
        detectDragGesturesAfterLongPress(
            onDragStart  = { off -> coords?.let { onDragStart(item, it.localToRoot(off)) } },
            onDrag       = { change, _ ->
                change.consume()
                coords?.let { onDrag(it.localToRoot(change.position)) }
            },
            onDragEnd    = { onDrop() },
            onDragCancel = { onCancel() },
        )
    }
    val tint = if (atCap) color.copy(alpha = 0.30f) else color
    Box(
        modifier = Modifier
            .size(52.dp)
            .onGloballyPositioned { coords = it }
            .then(dragModifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector        = item.icon,
                // Says which of the two caps bound, so the dimming isn't a mystery to a
                // screen-reader user (or to anyone reading it back in a bug report).
                contentDescription = when {
                    atCap && count >= item.maxPerRoom ->
                        "${item.label}, $count in this room, the most allowed"
                    atCap     -> "${item.label}, $count in this room, no space for more"
                    count > 0 -> "Add another ${item.label}, $count in this room"
                    else      -> "Add ${item.label}"
                },
                modifier           = Modifier.size(18.dp),
                tint               = tint,
            )
            Text(
                text     = item.label,
                style    = MaterialTheme.typography.labelSmall,
                color    = tint,
                maxLines = 1,
            )
        }
        if (count > 0) {
            Text(
                text     = "×$count",
                style    = MaterialTheme.typography.labelSmall,
                color    = tint,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
            )
        }
    }
}

// A yard/roof feature in the exterior pane's tray (deck, garage, pool, HVAC, solar). Same
// tear-drag pattern as FurnitureTrayIcon, but parameterized directly by icon/label/color
// instead of a RoomItem — long-press and drag into the scene, dropped by the caller's own
// drop handler (each feature snaps/clamps differently, see dropDeckAt/dropGarageAt/etc.).
@Composable
private fun FeatureTrayIcon(
    icon: ImageVector,
    label: String,
    iconColor: Color,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
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
                imageVector        = icon,
                contentDescription = "Add $label",
                modifier           = Modifier.size(18.dp),
                tint               = iconColor,
            )
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelSmall,
                color    = iconColor,
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
    // Every placed instance is tracked — the icon always opens this instance's maintenance
    // card, same as appliances/vehicles (no more furniture-only opt-in toggle).
    onTrack: () -> Unit,
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
                IconButton(onClick = onTrack) {
                    Icon(Icons.Outlined.TrackChanges, "Track maintenance", tint = MaterialTheme.colorScheme.primary)
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

// A freely-placed garage/deck/pool/HVAC/solar-panel's actions bar — same docked-dialog
// pattern as VehicleActionsDialog/FurnitureActionsDialog above, just Remove (repositioning
// is drag, not a button here). Takes icon/label/color directly rather than a HomeFeature so
// it also covers HVAC/solar (HomeSystem, not HomeFeature).
@Composable
private fun FeatureActionsDialog(
    icon: ImageVector,
    label: String,
    iconColor: Color,
    // null hides the button — only deck/pool offer a cycle-to-next-side shortcut.
    onCycleSide: (() -> Unit)? = null,
    // Current side/slope, shown next to the rotate button so cycling never leaves the user
    // hunting for where the item went — null hides the label (items with no onCycleSide).
    currentSideLabel: String? = null,
    onTrack: () -> Unit,
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
                Icon(icon, null, Modifier.size(18.dp), tint = iconColor)
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
                if (currentSideLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "· $currentSideLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (onCycleSide != null) {
                    IconButton(onClick = onCycleSide) { Icon(Icons.Outlined.RotateRight, "Move to next side") }
                }
                IconButton(onClick = onTrack) { Icon(Icons.Outlined.TrackChanges, "Track maintenance") }
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
//
// Onboarding, not documentation. One gesture per line, big enough to read at a glance, and nothing
// that isn't an action you can take right now in the scene you're looking at — this used to run to
// ten sections of paragraphs, which is a manual, and nobody reads a manual from a dialog. The
// long-form explanations live with the store listing instead.
//
// Keyed on RoomPlace as well as ViewState: ViewState.ROOM serves three places (a room, the
// driveway, the attic) whose gestures have almost nothing in common, and terse copy can't carry
// all three at once the way the old prose did.

@Composable
private fun InfoDialog(scene: ViewState, place: RoomPlace, onDismiss: () -> Unit) {
    val (title, steps) = when {
        scene == ViewState.NEIGHBORHOOD -> "Exploring the Neighborhood" to listOf(
            "Tap a house to look it over.",
            "Tap \"Mine\" to claim it as your home.",
            "Claim another model any time — your records follow you.",
        )
        scene == ViewState.EXTERIOR -> "Home Maintenance & 3D" to listOf(
            "Long-press a tray icon and drag it onto the yard.",
            "Tap a placed item to manage or remove it.",
            "The Garage and Attic icons walk you inside.",
            "Tap the wrench for your To-Do list, Guides, Score and Pros.",
            "Tap the Home icon for your home's details.",
        )
        scene == ViewState.FLOOR_PLAN -> "Building your Floor Plan" to listOf(
            "Tap the Pencil to edit.",
            "Tap a wall to cycle Solid / Open / Door / Window.",
            "Select empty tiles, then tap \"Create Room\".",
            "Long-press a room to change or remove it.",
            "Tap a room to step inside.",
            "F1 / F2 switch floors.",
        )
        // Everything below is ViewState.ROOM, split by which place it's showing.
        place == RoomPlace.DRIVEWAY -> "Your Driveway" to listOf(
            "Long-press a vehicle and drag it onto the lot.",
            "Tap a vehicle to paint, fuel, or track it.",
            "Drag it to the street to remove it.",
            "Tap the garage door to open or close it.",
        )
        place == RoomPlace.ATTIC -> "Your Attic" to listOf(
            "Tap any equipment for its maintenance card.",
            "Tap the bare floor to switch attic or utility closet.",
            "The pane's row sets which systems your home has.",
            "Long-press Treasure in the tray and drag it in.",
        )
        else -> "Designing your Interior" to listOf(
            "Long-press a tray item and drag it in.",
            "Tap a placed item to rotate, flip, or remove it.",
            "Drag it out through a wall to delete it.",
            "Tap a coloured icon at the top for its maintenance card.",
            "Tap \"All items\" for the rest of the tray.",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton    = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text  = {
            // Still scrollable: six lines at bodyLarge fit portrait comfortably, but landscape and
            // large font scales don't get to clip the tail.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                steps.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
            }
        },
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
    // False when opened from inside the room itself (RoomScene's floor tap) — "Enter" doesn't
    // apply when you're already standing in it.
    showEnter: Boolean = true,
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
        confirmButton = if (showEnter) { { Button(onClick = onEnter) { Text("Enter") } } } else { {} },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// Shown when a room-type change targets a room that already has furniture in it — lets the
// user choose whether the new type's defaults replace what's there or the room just relabels.
@Composable
private fun ReplaceOrKeepFurnitureDialog(
    roomType: RoomType,
    onReplace: () -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(roomType.icon, null, Modifier.size(20.dp), tint = roomType.iconColor)
                Text("Change to ${roomType.label}")
            }
        },
        text = {
            Text("This room already has furniture in it. Replace it with ${roomType.label}'s " +
                "usual furniture, or keep what's there and just relabel the room?")
        },
        confirmButton = { Button(onClick = onReplace) { Text("Replace furniture") } },
        dismissButton = {
            Row {
                TextButton(onClick = onKeep) { Text("Keep furniture") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// Shown when a room-type change targets Kitchen/Garage and one already exists elsewhere —
// explains the swap (appliances + maintenance history move here; the other room takes over
// this one's current type) and previews any wall/door changes before committing.
@Composable
private fun SwapConfirmDialog(
    targetType: RoomType,
    existingLabel: String,
    wallChanges: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(targetType.icon, null, Modifier.size(20.dp), tint = targetType.iconColor)
                Text("Move the ${targetType.label} here")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A home can only have one ${targetType.label}. Its appliances — with " +
                    "their install dates, notes, and documents — will move to this room. The " +
                    "room at $existingLabel (the current ${targetType.label}) will take over " +
                    "this room's type instead, with fresh furniture.")
                if (wallChanges.isNotEmpty()) {
                    Text("This also changes:", style = MaterialTheme.typography.labelLarge)
                    wallChanges.forEach { change ->
                        Text("• $change", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Swap") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
