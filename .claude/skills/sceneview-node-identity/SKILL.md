---
name: sceneview-node-identity
description: Checklist for writing or reviewing SceneView CubeNode (or similar) calls inside a loop, where the `apply` block wires a tap/drag handler. Use when adding, editing, or reviewing interactive 3D nodes in RoomScene.kt, HouseScene.kt, FloorPlanScene.kt, NeighborhoodScene.kt, or their render-spec wiring.
---

# SceneView interactive node identity

## The gotcha

`SceneScope.CubeNode`'s `apply` lambda (where `onSingleTapConfirmed` / `onDrag` /
etc. get wired) runs **once**, only when Compose creates a fresh underlying node.
Position/material update every recomposition via a separate mechanism, but the
tap/drag handler does not.

Compose's slot table assigns node identity by **call order**, not content. So
inside a `forEach`/`for` loop with no `key(...)` wrapper, if the *shape* of what's
emitted for a given loop position stays the same across recompositions (same
branch taken, same number of nodes emitted) but the *data* changed — a different
floor, a different room, a different item — the OLD closure stays bound. The tap
silently fires against stale data. This is invisible in a screenshot; it only
shows up as "I tapped X but Y happened," which is easy to miss in manual testing
and easy to catch by reading the diff.

## The rule

Every `CubeNode` (or other interactive SceneView node) emitted from inside a loop,
whose `apply` sets a gesture handler, must be wrapped in `key(...)` with **every
value that determines what a tap on it should do** — not just a loop index if the
underlying identity can outlive that index's meaning (e.g. `col`/`row` alone isn't
enough if which floor is being rendered can change; use `key(currentFloor, col, row)`
or, better, a stable id like `key(placement.id)` when one exists).

Two already-correct examples to pattern-match against, both in
`app/src/main/java/com/homehealth/ui/FloorPlanScene.kt`:
- `key(currentFloor, col, row) { CubeNode(...) }` for empty grid cells
- `key(placement.id) { CubeNode(...) }` for room floor tiles

Both carry an explanatory comment — read it once, it's the canonical explanation
of this bug class in this codebase.

## Known violation (found 2026-07-19, not yet fixed)

The four exterior wall loops (`FloorPlanScene.kt` ~186–286) and two interior wall
loops (~383–515) emit `CubeNode`s with `apply = { onSingleTapConfirmed = { onWallTap(key, mode); true } }`
but **no `key(...)` wrapper**. Confirmed two concrete failure modes:
1. Toggling a wall's mode (e.g. SOLID → WINDOW) without changing which `if`/`else`
   branch it renders through reuses the same node — a second tap can show the
   stale mode in the dialog.
2. `FloorPlanScene(...)` is called from `MainActivity.kt` without `key(currentFloor)`,
   and walls whose branch shape matches between floors keep their original
   closure — tapping a floor-1 wall can silently edit floor 0's `WallKey` instead.

This is exactly the kind of thing this checklist exists to catch before it ships
again — e.g. when `FloorPlanScene.kt` gets extracted into a portable render-spec
(the deferred KMP Phase 4b), the new wiring must add `key(...)` per wall, not just
carry the bug forward.

## Gotcha: no `return@key` / non-local jumps inside a `key(...)` block

Once a loop body is wrapped in `key(key) { ... }`, a bare `continue` (targeting the
enclosing `for`) won't compile — Kotlin doesn't support non-local `break`/`continue`
across a lambda boundary. The obvious fix, `return@key` (a labeled return from just
that lambda), *does* compile and passes `compileDebugKotlin` — but fails at
`:app:dexBuilderDebug` with `Method name '<anonymous>' in class
'$$$$$NON_LOCAL_RETURN$$$$$' cannot be represented in dex format`. This is a
real Kotlin/D8 interaction issue (the compiler's non-local-return desugaring
generates a synthetic marker class whose name D8 rejects), not a mistake in the
`key()` usage itself — and it only surfaces at the dex step, well after a clean
`compileDebugKotlin`, so it's easy to think the fix is done when it isn't.

Fix: restructure with plain nested `if`/`else` (no early exit) instead of
`return@key`/`continue`. This is what `FloorPlanScene.kt`'s wall loops do — see
the `if (lp == null || rp == null) { ... } else { ... }` shape wrapping each
segment's full branch logic.

## Related but distinct: the "one geometry call per target, one forEach per call" invariant

Established in `NeighborhoodSceneGeometry`/`NeighborhoodScene.kt` (KMP Phase 4a):
when a portable render-spec function returns a flat `List<XBoxNode>` for one
logical target (one lot, one item) and the composable wraps it in one `forEach`
bound to one `onTap` closure, every tappable node in that list correctly shares
that closure — **provided** the composable never flattens multiple targets' node
lists into one batch before mapping. That pattern doesn't need per-node `key()`
because the shared closure is rebound correctly at each call site on every
recomposition. Don't add per-node keys there; do check that the "one call per
target" discipline hasn't been violated instead.
