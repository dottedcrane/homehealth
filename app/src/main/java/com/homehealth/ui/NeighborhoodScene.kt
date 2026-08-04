package com.homehealth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.homehealth.model.*
import com.homehealth.renderspec.CarLotGeometry
import com.homehealth.renderspec.HomeFacade
import com.homehealth.renderspec.HouseSceneGeometry
import com.homehealth.renderspec.NeighborhoodBoxNode
import com.homehealth.renderspec.NeighborhoodMaterialSlot
import com.homehealth.renderspec.NeighborhoodSceneGeometry
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SceneScope.NeighborhoodScene(
    homeType: HomeType,
    floorLayout: FloorLayout,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    featureOffsets: Map<HomeFeature, Pair<Float, Float>> = emptyMap(),
    placedDecks: List<PlacedDeck> = emptyList(),
    hvacPlacement: Pair<FeatureSide, Float>? = null,
    solarArrayPlacement: Pair<Boolean, Float>? = null,
    evBatteryPlacement: Pair<FeatureSide, Float>? = null,
    placedYardDecor: List<PlacedYardDecor> = emptyList(),
    removedInstances: Set<String> = emptySet(),
    // The user's parked vehicles — rendered at their home (view-only: HouseScene disables
    // vehicle drags whenever onShellTap is set).
    vehicles: List<PlacedItem> = emptyList(),
    claimedNeighborLabel: String = "",
    onHomeClick: (NeighborHome) -> Unit = {},
    onOwnHomeTap: () -> Unit = {},
) {
    // Everything except the user's own home — see NeighborhoodBackdrop, which every other scene
    // now draws too. Here it's the interactive version: each lot taps through to its preview.
    NeighborhoodBackdrop(
        floorLayout          = floorLayout,
        featurePlacements    = featurePlacements,
        claimedNeighborLabel = claimedNeighborLabel,
        onHomeClick          = onHomeClick,
        onOwnPenthouseTap    = onOwnHomeTap,
    )

    // Claiming a model home gives the user a COPY at the origin — the model itself stays
    // staged on its lot, so the neighborhood never loses a house. A claimed penthouse is drawn
    // by the backdrop above (a condo unit has no HouseScene of its own); everything else is a
    // real home, rendered by the same composable the exterior view uses.
    if (claimedNeighborLabel != "The Penthouse") {
        HouseScene(
            homeType          = homeType,
            floorLayout       = floorLayout,
            featurePlacements = featurePlacements,
            featureOffsets    = featureOffsets,
            placedDecks       = placedDecks,
            hvacPlacement     = hvacPlacement,
            solarArrayPlacement = solarArrayPlacement,
            evBatteryPlacement = evBatteryPlacement,
            placedYardDecor   = placedYardDecor,
            removedInstances  = removedInstances,
            cars              = vehicles,
            onShellTap        = onOwnHomeTap,
        )
    }
}

/**
 * The world: the ground, the roads, the park, the scenery and the five model homes on their lots
 * — everything the neighborhood is made of EXCEPT the user's own home, which each scene draws
 * its own way (the exterior's full house, the floor plan's storey, a room's cutaway).
 *
 * Every scene shares one coordinate frame — the user's home at the origin — so placing this
 * behind any of them costs only an [offset]: the room view shifts by minus its zone's centre
 * (a room scene renders itself at the origin), and scenes showing an upper storey drop the world
 * by that storey's height so you look down on the street.
 *
 * [onHomeClick] null is the scenery mode: no tap wiring, and every node opts out of hit-testing,
 * so the world can neither be acted on nor swallow a tap meant for the scene in front of it (the
 * same rule HomeBackdrop and GarageScene's shell panels follow — SceneView dispatches only to the
 * frontmost touchable node and never falls through).
 */
@Composable
internal fun SceneScope.NeighborhoodBackdrop(
    floorLayout: FloorLayout,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    claimedNeighborLabel: String = "",
    offset: Position = Position(0f, 0f, 0f),
    onHomeClick: ((NeighborHome) -> Unit)? = null,
    // The neighborhood view alone draws a claimed penthouse as a tower block at the origin —
    // a condo unit has no HouseScene to render. Every other caller is drawing the world behind
    // its own home and passes null, which also keeps that tower's parking lot off their yard.
    onOwnPenthouseTap: (() -> Unit)? = null,
) {
    val groundMat      = remember { materialLoader.createColorInstance(colorOf(0.32f, 0.58f, 0.22f)) }
    val roadMat        = remember { materialLoader.createColorInstance(colorOf(0.26f, 0.26f, 0.28f)) }
    // Body-colour palette, indexed by HomeFacade.wallVariant — five identical cream boxes was
    // most of why the model homes read as placeholder massing rather than as five houses.
    val nbrWallMats    = remember { listOf(
        materialLoader.createColorInstance(colorOf(0.90f, 0.87f, 0.78f)),   // cream
        materialLoader.createColorInstance(colorOf(0.72f, 0.76f, 0.72f)),   // sage
        materialLoader.createColorInstance(colorOf(0.82f, 0.72f, 0.62f)),   // sand
        materialLoader.createColorInstance(colorOf(0.66f, 0.70f, 0.78f)),   // slate blue
    ) }
    val nbrRoofMat     = remember { materialLoader.createColorInstance(colorOf(0.60f, 0.18f, 0.10f)) }
    val nbrTrimMat     = remember { materialLoader.createColorInstance(colorOf(0.97f, 0.97f, 0.95f)) }
    val nbrShutterMat  = remember { materialLoader.createColorInstance(colorOf(0.20f, 0.28f, 0.24f)) }
    val nbrGarageMat   = remember { materialLoader.createColorInstance(colorOf(0.80f, 0.80f, 0.79f)) }
    val nbrPavingMat   = remember { materialLoader.createColorInstance(colorOf(0.68f, 0.66f, 0.63f)) }
    val nbrChimneyMat  = remember { materialLoader.createColorInstance(colorOf(0.48f, 0.34f, 0.30f)) }
    val parkLawnMat    = remember { materialLoader.createColorInstance(colorOf(0.38f, 0.66f, 0.28f)) }
    val parkStructMat  = remember { materialLoader.createColorInstance(colorOf(0.55f, 0.40f, 0.25f)) }
    val parkAccentMat  = remember { materialLoader.createColorInstance(colorOf(0.30f, 0.48f, 0.62f)) }
    val nbrFlatMat     = remember { materialLoader.createColorInstance(colorOf(0.25f, 0.25f, 0.28f)) }
    val nbrSlabMat     = remember { materialLoader.createColorInstance(colorOf(0.52f, 0.48f, 0.43f)) }
    val condoBaseMat   = remember { materialLoader.createColorInstance(colorOf(0.34f, 0.34f, 0.37f)) }
    val nbrDoorMat     = remember { materialLoader.createColorInstance(colorOf(0.22f, 0.14f, 0.09f)) }
    val nbrWindowMat   = remember { materialLoader.createColorInstance(colorOf(0.58f, 0.78f, 0.92f)) }
    val trunkMat       = remember { materialLoader.createColorInstance(colorOf(0.38f, 0.26f, 0.13f)) }
    val leafMat        = remember { materialLoader.createColorInstance(colorOf(0.16f, 0.42f, 0.14f)) }
    val leafMat2       = remember { materialLoader.createColorInstance(colorOf(0.22f, 0.50f, 0.18f)) }
    val decorBodyMats  = remember { listOf(
        materialLoader.createColorInstance(colorOf(0.62f, 0.14f, 0.14f)),   // red
        materialLoader.createColorInstance(colorOf(0.18f, 0.32f, 0.55f)),   // blue
        materialLoader.createColorInstance(colorOf(0.76f, 0.76f, 0.78f)),   // silver
    ) }
    val decorDarkMat   = remember { materialLoader.createColorInstance(colorOf(0.15f, 0.15f, 0.17f)) }
    val decorWheelMat  = remember { materialLoader.createColorInstance(colorOf(0.07f, 0.07f, 0.08f)) }
    val lotMat         = remember { materialLoader.createColorInstance(colorOf(0.33f, 0.33f, 0.35f)) }
    val stripeMat      = remember { materialLoader.createColorInstance(colorOf(0.88f, 0.88f, 0.85f)) }
    // "This is yours" marker on whichever PLACED_NEIGHBORS lot matches the current claim.
    val markerPostMat  = remember { materialLoader.createColorInstance(colorOf(0.20f, 0.20f, 0.22f)) }
    val markerFlagMat  = remember { materialLoader.createColorInstance(colorOf(1.0f, 0.78f, 0.0f)) }  // gold
    DisposableEffect(Unit) {
        onDispose {
            (listOf(groundMat, roadMat, nbrRoofMat, nbrFlatMat,
                    nbrSlabMat, condoBaseMat, nbrDoorMat, nbrWindowMat,
                    nbrTrimMat, nbrShutterMat, nbrGarageMat, nbrPavingMat, nbrChimneyMat,
                    parkLawnMat, parkStructMat, parkAccentMat,
                    trunkMat, leafMat, leafMat2, decorDarkMat, decorWheelMat,
                    lotMat, stripeMat, markerPostMat, markerFlagMat) + decorBodyMats + nbrWallMats)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Maps a portable NeighborhoodSceneGeometry node list to actual CubeNode calls, reattaching
    // [onTap] to every tappable node. Must be called once per lot/instance (never with multiple
    // instances' nodes flattened together first) since every tappable node in one call resolves
    // to the same [onTap] closure — see NeighborhoodBoxNode's kdoc.
    //
    // [offset] is applied per node rather than by parenting the world under a transform node:
    // these are the same flat CubeNode emissions every other scene makes, and keeping them
    // unparented means world space stays scene space, which every camera framing and drag plane
    // in the app assumes (see SceneView's autoCenterContent note in MainActivity).
    val scenery = onHomeClick == null
    @Composable
    fun SceneScope.emitNeighborhoodNodes(nodes: List<NeighborhoodBoxNode>, onTap: () -> Unit = {}) {
        nodes.forEach { n ->
            val mat = when (n.material) {
                NeighborhoodMaterialSlot.GROUND -> groundMat
                NeighborhoodMaterialSlot.ROAD -> roadMat
                NeighborhoodMaterialSlot.LOT -> lotMat
                NeighborhoodMaterialSlot.LOT_STRIPE -> stripeMat
                NeighborhoodMaterialSlot.NBR_SLAB -> nbrSlabMat
                NeighborhoodMaterialSlot.NBR_CONDO_BASE -> condoBaseMat
                NeighborhoodMaterialSlot.NBR_WALL -> nbrWallMats[n.materialVariant % nbrWallMats.size]
                NeighborhoodMaterialSlot.NBR_DOOR -> nbrDoorMat
                NeighborhoodMaterialSlot.NBR_WINDOW -> nbrWindowMat
                NeighborhoodMaterialSlot.NBR_ROOF -> nbrRoofMat
                NeighborhoodMaterialSlot.NBR_FLAT_ROOF -> nbrFlatMat
                NeighborhoodMaterialSlot.NBR_TRIM -> nbrTrimMat
                NeighborhoodMaterialSlot.NBR_SHUTTER -> nbrShutterMat
                NeighborhoodMaterialSlot.NBR_GARAGE_DOOR -> nbrGarageMat
                NeighborhoodMaterialSlot.NBR_PAVING -> nbrPavingMat
                NeighborhoodMaterialSlot.NBR_CHIMNEY -> nbrChimneyMat
                NeighborhoodMaterialSlot.PARK_LAWN -> parkLawnMat
                NeighborhoodMaterialSlot.PARK_STRUCTURE -> parkStructMat
                NeighborhoodMaterialSlot.PARK_ACCENT -> parkAccentMat
                NeighborhoodMaterialSlot.TREE_TRUNK -> trunkMat
                NeighborhoodMaterialSlot.TREE_LEAF -> if (n.materialVariant == 0) leafMat else leafMat2
                NeighborhoodMaterialSlot.CAR_BODY -> decorBodyMats[n.materialVariant % decorBodyMats.size]
                NeighborhoodMaterialSlot.CAR_CABIN -> decorDarkMat
                NeighborhoodMaterialSlot.CAR_WHEEL -> decorWheelMat
                NeighborhoodMaterialSlot.MARKER_POST -> markerPostMat
                NeighborhoodMaterialSlot.MARKER_FLAG -> markerFlagMat
            }
            CubeNode(
                size = Size(n.size.x, n.size.y, n.size.z),
                position = Position(
                    n.position.x + offset.x,
                    n.position.y + offset.y,
                    n.position.z + offset.z,
                ),
                rotation = Rotation(n.rotationDeg.x, n.rotationDeg.y, n.rotationDeg.z),
                materialInstance = mat,
                apply = when {
                    scenery          -> { { isTouchable = false } }
                    n.tappable       -> { { onSingleTapConfirmed = { onTap(); true } } }
                    else             -> { {} }
                },
            ) {}
        }
    }

    emitNeighborhoodNodes(NeighborhoodSceneGeometry.groundAndRoads())

    // The user's claimed penthouse, as a tower block at the origin — the neighborhood view's job
    // only (a condo unit has no HouseScene). The model itself stays staged on its own lot below,
    // so the neighborhood never loses a house.
    val ownPenthouseTap = onOwnPenthouseTap?.takeIf { claimedNeighborLabel == "The Penthouse" }
    if (ownPenthouseTap != null) {
        val ph = PLACED_NEIGHBORS.find { it.home.label == "The Penthouse" }!!
        emitNeighborhoodNodes(
            NeighborhoodSceneGeometry.blockHome(
                wx = 0f, wz = 0f, yRotDeg = 0f,
                homeW = ph.home.totalW, homeD = ph.home.totalD,
                floors = ph.home.floors,
                isPitched = false,
                isCondoTop = true,
            ),
            ownPenthouseTap,
        )
    }

    // ── Scenery — trees along the lanes and yards, plus a few parked cars ─────────
    // The static spots steer clear of the roads, neighbor lots, and the condo; the user's
    // home footprint is DYNAMIC (layout size + garage wing + driveway reaching the street),
    // so every position is additionally filtered against its bounding rect instead of
    // hand-tuning — no tree ever grows through the house.
    val ownLot   = CarLotGeometry.carLot(floorLayout.totalW, floorLayout.totalD, featurePlacements[HomeFeature.GARAGE])
    val ownHalfX = maxOf(floorLayout.totalW / 2f, abs(ownLot.anchorX) + ownLot.halfW) + 1.5f
    val ownZMax  = ownLot.farZ + 1.5f
    val ownZMin  = -(floorLayout.totalD / 2f + 4.5f)

    // The condo tower gets a surface parking lot beside it (condos have no garage) — a real
    // two-row residents' lot. It tucks into the south-east corner INSIDE the loop, clear of the
    // tower, the home east of it and the road behind: parking belongs off the green, not on it.
    val towerLot = NeighborhoodSceneGeometry.condoLotRect(16f, -68f)
    val ownTowerLot = NeighborhoodSceneGeometry.condoLotRect(18f, 2f)
    val parkCenter = NeighborhoodSceneGeometry.PARK_CX to NeighborhoodSceneGeometry.PARK_CZ
    val park = NeighborhoodSceneGeometry.parkRect(parkCenter.first, parkCenter.second)

    val keepOut = buildList {
        add(towerLot)
        // Only when the penthouse is claimed and the tower stands at the origin instead. Gated on
        // the tower actually being drawn here: a scene drawing the world behind its OWN home must
        // not park a residents' lot on the user's front lawn.
        if (ownPenthouseTap != null) add(ownTowerLot)
        add(park)
    }
    keepOut.filter { it != park }.forEach { pad ->
        emitNeighborhoodNodes(NeighborhoodSceneGeometry.condoParkingLot(pad.cx, pad.cz))
    }
    // The green, in the middle of it all — what the homes face and the walkways lead to.
    emitNeighborhoodNodes(NeighborhoodSceneGeometry.park(parkCenter.first, parkCenter.second))

    // The user's own home is the ring's north side. Its front (and driveway) faces the loop road,
    // so its path to the green leaves from the BACK — where the deck is.
    emitNeighborhoodNodes(
        NeighborhoodSceneGeometry.walkway(fromX = 0f, fromZ = -floorLayout.totalD / 2f))

    emitNeighborhoodNodes(NeighborhoodSceneGeometry.scatterTrees(ownHalfX, ownZMax, ownZMin, keepOut))
    emitNeighborhoodNodes(NeighborhoodSceneGeometry.scatterDecorCars(ownHalfX, ownZMax, ownZMin, keepOut))

    // Model homes — all five always staged on their lots.
    // key(label) so each lot's tap closure stays bound to its own home: a CubeNode's apply block
    // runs once, and every tappable node in one blockHome() call resolves to the closure captured
    // by that iteration (see NeighborhoodBoxNode.tappable). PLACED_NEIGHBORS is a fixed val today,
    // but the facade detail below multiplies the tappable nodes per lot, so this is cheap
    // insurance against a future reorder silently cross-wiring them.
    PLACED_NEIGHBORS.forEach { placed ->
        key(placed.home.label) {
            val style = placed.home.homeType.style
            emitNeighborhoodNodes(
                NeighborhoodSceneGeometry.blockHome(
                    wx = placed.wx, wz = placed.wz, yRotDeg = placed.yRotDeg,
                    homeW = placed.home.totalW, homeD = placed.home.totalD,
                    floors = placed.home.floors,
                    isPitched = style == HomeStyle.HOUSE || style == HomeStyle.TOWNHOUSE,
                    isCondoTop = style == HomeStyle.CONDO,
                    facade = placed.home.facade(),
                ),
                { onHomeClick?.invoke(placed.home) },
            )
            // The path from this home's front door to the green. Emitted per lot inside the
            // same key() as the home, so it moves with it.
            emitNeighborhoodNodes(
                NeighborhoodSceneGeometry.walkway(
                    fromX = placed.wx + sin(placed.yRotDeg * PI / 180.0).toFloat() * (placed.home.totalD / 2f),
                    fromZ = placed.wz + cos(placed.yRotDeg * PI / 180.0).toFloat() * (placed.home.totalD / 2f),
                )
            )
            // "This is yours" marker — purely decorative, no onTap override (every box in
            // claimedMarker() is non-tappable), so it never competes with the lot's own tap.
            if (placed.home.label == claimedNeighborLabel) {
                emitNeighborhoodNodes(
                    NeighborhoodSceneGeometry.claimedMarker(placed.wx, placed.wz, placed.yRotDeg, placed.home.totalD))
            }
        }
    }
}

/**
 * A model home's neighborhood facade, resolved from the model itself rather than hardcoded per
 * lot — so the block on the lot always agrees with the home you get on claiming it.
 *
 * The garage door comes from whichever of the two garage forms this model actually uses:
 * Classic and Townhouse bake a GARAGE room into their floor plan (read by
 * [HouseSceneGeometry.townhouseGarageBox], the same helper the exterior scene and the driveway
 * use), while Estate and Ranch carry an exterior [HomeFeature.GARAGE] wing ([HouseSceneGeometry.
 * garageBox]). Both resolve to a centre offset in the home's own frame plus a width. The condo
 * has neither — it parks in the surface lot.
 *
 * The colour variant is keyed off the label so each model keeps one identity across launches.
 */
private fun NeighborHome.facade(): HomeFacade {
    val style = homeType.style
    if (style == HomeStyle.CONDO) return HomeFacade()
    val interior = customLayout?.let { HouseSceneGeometry.townhouseGarageBox(it) }
    val exterior = featurePlacements[HomeFeature.GARAGE]
        ?.let { HouseSceneGeometry.garageBox(totalW, totalD, it) }
    val box = interior ?: exterior
    return HomeFacade(
        wallVariant = PLACED_NEIGHBORS.indexOfFirst { it.home.label == label }.coerceAtLeast(0),
        // A side-mounted exterior garage sits beside the house, so its door still reads on the
        // front elevation — clamped inside the wall it's drawn on.
        garageDoorX = box?.let { it.cx.coerceIn(-totalW / 2f + 2.2f, totalW / 2f - 2.2f) },
        garageDoorW = box?.let { minOf(it.w * 0.7f, 4.5f) } ?: 4.0f,
        chimney = true,
    )
}
