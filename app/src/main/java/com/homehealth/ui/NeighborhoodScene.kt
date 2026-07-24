package com.homerenderer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.homerenderer.model.*
import com.homerenderer.renderspec.CarLotGeometry
import com.homerenderer.renderspec.NeighborhoodBoxNode
import com.homerenderer.renderspec.NeighborhoodMaterialSlot
import com.homerenderer.renderspec.NeighborhoodSceneGeometry
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import kotlin.math.abs

@Composable
fun SceneScope.NeighborhoodScene(
    homeType: HomeType,
    floorLayout: FloorLayout,
    featurePlacements: Map<HomeFeature, FeatureSide>,
    featureOffsets: Map<HomeFeature, Pair<Float, Float>> = emptyMap(),
    placedDecks: List<PlacedDeck> = emptyList(),
    homeSystems: Set<HomeSystem>,
    removedInstances: Set<String> = emptySet(),
    // The user's parked vehicles — rendered at their home (view-only: HouseScene disables
    // vehicle drags whenever onShellTap is set).
    vehicles: List<PlacedItem> = emptyList(),
    claimedNeighborLabel: String = "",
    onHomeClick: (NeighborHome) -> Unit = {},
    onOwnHomeTap: () -> Unit = {},
) {
    val groundMat      = remember { materialLoader.createColorInstance(colorOf(0.32f, 0.58f, 0.22f)) }
    val roadMat        = remember { materialLoader.createColorInstance(colorOf(0.26f, 0.26f, 0.28f)) }
    val nbrWallMat     = remember { materialLoader.createColorInstance(colorOf(0.90f, 0.87f, 0.78f)) }
    val nbrRoofMat     = remember { materialLoader.createColorInstance(colorOf(0.60f, 0.18f, 0.10f)) }
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
    DisposableEffect(Unit) {
        onDispose {
            (listOf(groundMat, roadMat, nbrWallMat, nbrRoofMat, nbrFlatMat,
                    nbrSlabMat, condoBaseMat, nbrDoorMat, nbrWindowMat,
                    trunkMat, leafMat, leafMat2, decorDarkMat, decorWheelMat,
                    lotMat, stripeMat) + decorBodyMats)
                .forEach { engine.destroyMaterialInstance(it) }
        }
    }

    // Maps a portable NeighborhoodSceneGeometry node list to actual CubeNode calls, reattaching
    // [onTap] to every tappable node. Must be called once per lot/instance (never with multiple
    // instances' nodes flattened together first) since every tappable node in one call resolves
    // to the same [onTap] closure — see NeighborhoodBoxNode's kdoc.
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
                NeighborhoodMaterialSlot.NBR_WALL -> nbrWallMat
                NeighborhoodMaterialSlot.NBR_DOOR -> nbrDoorMat
                NeighborhoodMaterialSlot.NBR_WINDOW -> nbrWindowMat
                NeighborhoodMaterialSlot.NBR_ROOF -> nbrRoofMat
                NeighborhoodMaterialSlot.NBR_FLAT_ROOF -> nbrFlatMat
                NeighborhoodMaterialSlot.TREE_TRUNK -> trunkMat
                NeighborhoodMaterialSlot.TREE_LEAF -> if (n.materialVariant == 0) leafMat else leafMat2
                NeighborhoodMaterialSlot.CAR_BODY -> decorBodyMats[n.materialVariant % decorBodyMats.size]
                NeighborhoodMaterialSlot.CAR_CABIN -> decorDarkMat
                NeighborhoodMaterialSlot.CAR_WHEEL -> decorWheelMat
            }
            CubeNode(
                size = Size(n.size.x, n.size.y, n.size.z),
                position = Position(n.position.x, n.position.y, n.position.z),
                rotation = Rotation(n.rotationDeg.x, n.rotationDeg.y, n.rotationDeg.z),
                materialInstance = mat,
                apply = if (n.tappable) {
                    { onSingleTapConfirmed = { onTap(); true } }
                } else {
                    {}
                },
            ) {}
        }
    }

    emitNeighborhoodNodes(NeighborhoodSceneGeometry.groundAndRoads())

    // Claiming a model home gives the user a COPY at the origin — the model itself stays
    // staged on its lot, so the neighborhood never loses a house.

    // User's home at origin — full condo building block when penthouse is claimed
    if (claimedNeighborLabel == "The Penthouse") {
        val ph = PLACED_NEIGHBORS.find { it.home.label == "The Penthouse" }!!
        emitNeighborhoodNodes(
            NeighborhoodSceneGeometry.blockHome(
                wx = 0f, wz = 0f, yRotDeg = 0f,
                homeW = ph.home.totalW, homeD = ph.home.totalD,
                floors = ph.home.floors,
                isPitched = false,
                isCondoTop = true,
            ),
            onOwnHomeTap,
        )
    } else {
        HouseScene(homeType, floorLayout, featurePlacements, featureOffsets, placedDecks, homeSystems,
            removedInstances = removedInstances, cars = vehicles, onShellTap = onOwnHomeTap)
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

    // The condo tower gets a surface parking lot beside it (condos have no garage). It sits
    // at the tower's flank — next to the entry path for the neighbor tower at the end of
    // the cul-de-sac, or off the approach lane when the penthouse is claimed and the tower
    // stands at the origin. Scenery is filtered against the pad too.
    val parkingPads = buildList {
        if (claimedNeighborLabel == "The Penthouse") add(10.5f to 2f)
        add(10f to -51f)   // the model tower's lot at the end of the cul-de-sac
    }
    parkingPads.forEach { (px, pz) ->
        emitNeighborhoodNodes(NeighborhoodSceneGeometry.condoParkingLot(px, pz))
    }

    emitNeighborhoodNodes(NeighborhoodSceneGeometry.scatterTrees(ownHalfX, ownZMax, ownZMin, parkingPads))
    emitNeighborhoodNodes(NeighborhoodSceneGeometry.scatterDecorCars(ownHalfX, ownZMax, ownZMin, parkingPads))

    // Model homes — all five always staged on their lots
    PLACED_NEIGHBORS.forEach { placed ->
        val style = placed.home.homeType.style
        emitNeighborhoodNodes(
            NeighborhoodSceneGeometry.blockHome(
                wx = placed.wx, wz = placed.wz, yRotDeg = placed.yRotDeg,
                homeW = placed.home.totalW, homeD = placed.home.totalD,
                floors = placed.home.floors,
                isPitched = style == HomeStyle.HOUSE || style == HomeStyle.TOWNHOUSE,
                isCondoTop = style == HomeStyle.CONDO,
            ),
            { onHomeClick(placed.home) },
        )
    }
}
