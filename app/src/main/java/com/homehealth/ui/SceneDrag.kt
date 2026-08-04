package com.homehealth.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.node.Node
import java.util.WeakHashMap
import kotlin.math.abs

// ── Scene drag targets ────────────────────────────────────────────────────────────────────────
//
// What a draggable thing in a 3D scene exposes to [SceneDragController], which owns the whole
// touch gesture (see that file for why it has to).
//
// This is a MUTABLE holder rather than a data class of callbacks on purpose. SceneScope.CubeNode
// runs its `apply` block exactly once, at node creation (`remember(engine) { CubeNodeImpl(...)
// .apply(apply) }`) — it is never re-run on recomposition, so anything a handler captures there
// is frozen at the moment the node was born. Binding the holder once and refreshing its lambdas
// from a SideEffect on every recomposition means the gesture always calls today's closures over
// today's state, instead of the ones that happened to exist when the cube first appeared. That
// is the whole class of staleness the sceneview-node-identity skill exists to work around.

class DragTarget {
    /**
     * Turns a camera ray into the world point the item should follow. Almost always
     * [horizontalPick] at the height of whatever surface the item slides on; the solar array
     * overrides it to track the pitched roof it actually renders on.
     *
     * Projecting a ray onto a known plane replaces SceneView's own approach of hit-testing the
     * dragged node's PARENT every move event, which silently returned nothing — freezing the item
     * mid-drag while the finger kept going — the moment the finger left that parent slab's
     * on-screen projection. A plane is infinite, so it can't be missed.
     */
    var pick: ((eye: Float3, dir: Float3) -> Float3?)? = null
    var onTap: (() -> Unit)? = null
    /** World X/Z on the pick surface. Null means "tappable but pinned". */
    var onDrag: ((worldX: Float, worldZ: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    val draggable: Boolean get() = onDrag != null && pick != null
}

/** Intersects the ray with the horizontal plane at [planeY]. Null when it points away or parallel. */
fun horizontalPick(planeY: Float): (Float3, Float3) -> Float3? = pick@{ eye, dir ->
    if (abs(dir.y) < 1e-5f) return@pick null
    val t = (planeY - eye.y) / dir.y
    if (t <= 0f) return@pick null
    eye + dir * t
}

/**
 * Same solve against a plane at any orientation, given a point on it and its normal. Used for the
 * pitched roof slopes, so a solar array tracks the surface it visibly sits on rather than a flat
 * approximation underneath it.
 */
fun tiltedPick(planePoint: Float3, planeNormal: Float3): (Float3, Float3) -> Float3? = pick@{ eye, dir ->
    val denom = dir.x * planeNormal.x + dir.y * planeNormal.y + dir.z * planeNormal.z
    if (abs(denom) < 1e-5f) return@pick null
    val t = ((planePoint.x - eye.x) * planeNormal.x + (planePoint.y - eye.y) * planeNormal.y +
        (planePoint.z - eye.z) * planeNormal.z) / denom
    if (t <= 0f) return@pick null
    eye + dir * t
}

/** Picks the nearer of two candidate surfaces — the two roof slopes meeting at a ridge. */
fun nearestPick(
    a: (Float3, Float3) -> Float3?,
    b: (Float3, Float3) -> Float3?,
): (Float3, Float3) -> Float3? = pick@{ eye, dir ->
    val ha = a(eye, dir)
    val hb = b(eye, dir)
    when {
        ha == null -> hb
        hb == null -> ha
        else -> {
            fun d2(p: Float3) = (p.x - eye.x) * (p.x - eye.x) +
                (p.y - eye.y) * (p.y - eye.y) + (p.z - eye.z) * (p.z - eye.z)
            if (d2(ha) <= d2(hb)) ha else hb
        }
    }
}

/**
 * Maps a scene [Node] to the item it belongs to. Every cube of one item binds the SAME target, so
 * grabbing a table leg drags the whole table.
 *
 * Weakly keyed on node identity ([Node] overrides neither equals nor hashCode, so this is a pure
 * identity map): scenes create and destroy cubes constantly as rooms and floors change, and a
 * strong map would pin every node that ever existed for the life of the activity.
 */
class SceneDragRegistry {
    private val byNode = WeakHashMap<Node, DragTarget>()

    fun bind(node: Node, target: DragTarget) { byNode[node] = target }

    fun targetFor(node: Node?): DragTarget? = node?.let { byNode[it] }
}

val LocalSceneDragRegistry = staticCompositionLocalOf { SceneDragRegistry() }

/**
 * How long to hold before an item is picked up.
 *
 * A plain tap already opens an item's actions bar, so dragging stays gated behind a deliberate
 * hold. 300 ms rather than the system's 400-500 ms long-press because this hold is *reliable*:
 * the old implementation leaned on android.view.GestureDetector, whose long-press timer is
 * cancelled the instant the finger moves past touch slop — so pressing and sliding in one motion
 * armed nothing at all and the item simply never moved. A hold that always works at 300 ms feels
 * far quicker than one that needs 500 ms of perfect stillness and otherwise silently fails.
 */
const val DRAG_HOLD_MS = 300L
