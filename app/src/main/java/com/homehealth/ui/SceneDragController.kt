package com.homehealth.ui

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.collision.HitResult
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.node.CameraNode
import io.github.sceneview.utils.screenToWorld
import kotlin.math.hypot

/**
 * Owns the entire touch gesture for the 3D scene, wired to SceneView's `onTouchEvent`. Returning
 * true from there short-circuits BOTH the per-node gesture detector and the camera gesture
 * detector, so a gesture belongs to exactly one thing for its whole life.
 *
 * ### Why this exists instead of SceneView's own node-drag support
 *
 * The library resolves ownership per MotionEvent: it re-runs a hit test on every ACTION_MOVE and
 * only skips the camera detector while the frontmost touchable node under the finger *right now*
 * reports `isEditable`. All the backdrop geometry in this app (walls, floor slabs, the yard
 * plane, the driveway, the roof) is touchable and not editable, so the instant a drag drifted off
 * the item onto whatever was behind it, the events went to the camera — which starts an orbit
 * after three move events with no distance threshold at all. Worse, drifting back onto the item
 * before lifting meant the camera detector never saw ACTION_UP, so `grabEnd()` never ran and its
 * `currentGesture` stayed ORBIT permanently, making the *next* touch anywhere lurch the camera.
 *
 * On top of that the library's move path required 31.6 px of travel before its first callback,
 * and resolved the drag position by hit-testing the dragged node's PARENT — so a drag froze in
 * place whenever the finger left that parent slab's on-screen projection (pushing an appliance
 * against a wall, a tree past the yard edge). [DragTarget.pick] replaces that with a plane, which
 * cannot be missed.
 *
 * ### Ownership rules
 *
 * A touch that lands on a draggable item is ours from ACTION_DOWN. That is not optional: if we
 * let the pre-hold events through, the camera detector would latch an orbit within three move
 * events (a resting finger emits plenty), and swallowing the later UP would strand it. Because
 * `CameraGestureDetector` mutates state only in MOVE/UP/CANCEL and ignores DOWN entirely, owning
 * from DOWN leaves it cleanly at rest.
 *
 * That means we also drive the manipulator ourselves for the one case where an owned gesture
 * turns out to be a camera move — a finger that starts on an item but swipes before the hold
 * elapses. It goes through the app's own GatedCameraManipulator, so the user's "Rotate" toggle
 * keeps working. As a bonus this is new behaviour: previously a swipe starting on furniture was
 * absorbed and did nothing at all, which made a furnished room hard to look around.
 */
class SceneDragController(private val registry: SceneDragRegistry) {

    // Refreshed from a SideEffect every recomposition. They cannot be constructor params:
    // SceneView captures its `onTouchEvent` lambda ONCE, in the AndroidView factory (its `update`
    // block is empty), so a controller recreated per composition would simply stop receiving
    // events after the first frame.
    var cameraNode: CameraNode? = null
    var manipulator: CameraGestureDetector.CameraManipulator? = null
    var touchSlopPx: Float = 24f
    var onArmHaptic: () -> Unit = {}

    private enum class Phase {
        /** Not ours — the library handles taps and camera gestures as usual. */
        IDLE,
        /** On a draggable item, hold not yet elapsed. Could still become a tap or an orbit. */
        PENDING,
        /** Held long enough: the item follows the finger. */
        DRAGGING,
        /** Swiped before the hold elapsed — we drive the camera for the rest of the gesture. */
        ORBIT,
        /** Handed back mid-gesture (a second finger arrived); ignore until the next DOWN. */
        RELEASED,
    }

    private var phase = Phase.IDLE
    private var target: DragTarget? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedSinceArm = false

    private val handler = Handler(Looper.getMainLooper())
    private val armRunnable = Runnable {
        if (phase == Phase.PENDING) {
            phase = Phase.DRAGGING
            movedSinceArm = false
            onArmHaptic()
        }
    }

    /** Wired to `SceneView(onTouchEvent = ...)`. All calls arrive on the main thread. */
    fun onTouch(e: MotionEvent, hit: HitResult?): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val t = registry.targetFor(hit?.node)
                if (t == null || !t.draggable) {
                    phase = Phase.IDLE
                    target = null
                    return false
                }
                target = t
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                movedSinceArm = false
                phase = Phase.PENDING
                handler.postDelayed(armRunnable, DRAG_HOLD_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = e.x; lastY = e.y
                when (phase) {
                    Phase.PENDING -> {
                        // Moved before the hold elapsed: this was never a pick-up, it's a look.
                        // Note the hold itself is NOT cancelled by small movement — only by
                        // crossing touch slop — which is exactly what the old long-press got
                        // wrong (any movement at all killed it and the drag silently never began).
                        if (hypot(e.x - downX, e.y - downY) > touchSlopPx) {
                            handler.removeCallbacks(armRunnable)
                            phase = Phase.ORBIT
                            manipulator?.grabBegin(e.x.toInt(), flipY(e.y), false)
                        }
                        return true
                    }
                    Phase.DRAGGING -> {
                        pointOnTarget(e.x, e.y)?.let { p ->
                            movedSinceArm = true
                            target?.onDrag?.invoke(p.x, p.z)
                        }
                        return true
                    }
                    Phase.ORBIT -> {
                        manipulator?.grabUpdate(e.x.toInt(), flipY(e.y))
                        return true
                    }
                    else -> return false
                }
            }

            // A second finger means pinch-zoom or two-finger pan — hand the gesture back so the
            // library's own detectors can run it. AOSP's GestureDetector treats an orphan
            // POINTER_DOWN as a tap cancellation, so no stray tap fires on the eventual UP.
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (phase == Phase.IDLE || phase == Phase.RELEASED) return false
                finishOwnedGesture(commitDrag = true)
                phase = Phase.RELEASED
                return false
            }

            MotionEvent.ACTION_UP -> {
                when (phase) {
                    // Lifted without ever crossing slop: a tap, whether or not the hold elapsed.
                    Phase.PENDING -> {
                        handler.removeCallbacks(armRunnable)
                        target?.onTap?.invoke()
                    }
                    Phase.DRAGGING -> if (movedSinceArm) target?.onDragEnd?.invoke()
                                      else target?.onTap?.invoke()
                    Phase.ORBIT -> manipulator?.grabEnd()
                    else -> {}
                }
                val owned = phase != Phase.IDLE && phase != Phase.RELEASED
                reset()
                return owned
            }

            MotionEvent.ACTION_CANCEL -> {
                val owned = phase != Phase.IDLE && phase != Phase.RELEASED
                // Commit rather than abandon: every consumer holds a live override that only
                // clears in onDragEnd, so dropping it here would leave the item rendering at a
                // stale position until the whole scene was rebuilt.
                finishOwnedGesture(commitDrag = true)
                reset()
                return owned
            }
        }
        return false
    }

    private fun finishOwnedGesture(commitDrag: Boolean) {
        handler.removeCallbacks(armRunnable)
        when (phase) {
            Phase.DRAGGING -> if (commitDrag && movedSinceArm) target?.onDragEnd?.invoke()
            Phase.ORBIT -> manipulator?.grabEnd()
            else -> {}
        }
    }

    private fun reset() {
        handler.removeCallbacks(armRunnable)
        phase = Phase.IDLE
        target = null
        movedSinceArm = false
    }

    /** Filament's manipulator takes Y measured up from the bottom; MotionEvent measures it down. */
    private fun flipY(y: Float): Int {
        val h = cameraNode?.viewport?.height ?: 0
        return (h - y).toInt()
    }

    /** Casts a ray through the touch point and asks the target where that lands on its surface. */
    private fun pointOnTarget(x: Float, y: Float): Float3? {
        val cam = cameraNode ?: return null
        val onRay = cam.view?.screenToWorld(x, y) ?: return null
        val eye = cam.worldPosition
        return target?.pick?.invoke(eye, onRay - eye)
    }
}
