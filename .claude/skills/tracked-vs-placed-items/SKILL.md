---
name: tracked-vs-placed-items
description: Checklist for identifying whether an item type is "tracked" (has a Maintenance Hub / Score-tab card + DB history) vs. "placed" (rendered in the 3D scene) and for auditing that the two stay correctly coupled or decoupled — including the rule that a placed item carrying the user's own history must never be silently deleted when its room disappears. Use when adding a new trackable/placeable item type, wiring a new "Not In My Home" toggle, touching removedInstances/homeSystems-shaped state, changing pruneOrphanedPlacedItems / claimNeighbor / any path that can delete a room, or reviewing changes to MaintenanceDialogs.kt, MaintenanceHubDialog.kt, HomeTaskList.kt, or the ApplianceMaintenanceDialog call site in MainActivity.kt.
---

# Tracked items vs. placed items

## The distinction

**Tracked** = has a card in the Maintenance Hub's Score tab (`MaintenanceHubDialog.kt`'s
`InventoryTab`), an `ApplianceRecordEntity` row (install year) and `DocumentEntity` rows
(attachments) keyed by a stable string, and shows up in seasonal to-dos
(`HomeTaskList.forHome`'s output).

**Placed** = actually rendered somewhere in the 3D scene (`HouseScene.kt`/`RoomScene.kt`/
`GarageScene.kt`), with a tap target and (usually) drag-to-move.

These are two **separate, deliberately decoupled** axes. An item can be tracked without being
placed (a HomeSystem you removed from the yard, a HiddenAsset like the roof that's never placed
at all) or placed without being independently tracked-by-instance (a default RoomItem instance is
tracked as a type-in-a-room, not as "this exact sofa"). Conflating them — making trackability
depend on live placement state — is exactly the bug class this skill exists to catch: see
"Known precedent" below, where `HomeSystem` cards used to vanish from the Score tab whenever the
physical unit was removed or a different neighbor model was claimed, silently orphaning its
install-year/documents in the DB.

## The four `MaintenanceTarget` kinds

`MaintenanceTarget` (sealed class in `MaintenanceDialogs.kt`) is the single vocabulary for "a
trackable thing." Every kind has a `.key: String` used to look up its `ApplianceRecordEntity`/
`DocumentEntity` rows — **that key must never change once assigned**, or history silently
detaches from the item (this is why `PlacedItem.id` is a stable UUID, and why `HomeSystem`/
`HiddenAsset` use the bare enum `.name`).

| Kind | `.key` | Presence source | Placed in 3D? | "Not in my home" |
|---|---|---|---|---|
| `Item` | `"$repId:${item.name}"` | Default items for rooms actually in the current floor plan (`RoomType.defaultItems()`) | Yes, auto-rendered per room | Toggles `removedInstances` — hides, doesn't delete |
| `Placed` | `"placed:$id"` | `placedItems: List<PlacedItem>` (user drag-and-drop) | Yes, one instance = one placement | **Deletes** (`placedItems.filterNot{...}` + `maint.removeItemRecords(...)`) — the one kind where "remove" is destructive, because there's no other identity for a dragged-in instance to persist against |
| `System` | `sys.name` (`HVAC`/`SOLAR`/`EV_BATTERY`) | Always — `HomeSystem.entries`, unconditional | Only if `hvacPlacement`/`solarArrayPlacement`/`evBatteryPlacement` is non-null | Toggles `removedInstances` — hides, doesn't delete — **and** clears the matching placement, so the unit leaves the 3D scene and its tray icon comes back |
| `Hidden` | `asset.name` | `HiddenAsset.entries.filter { it.isApplicable(style, features, hasDeck, hasGarage) OR hasHistory(it) }` | **Never** — purely a tracking abstraction, no 3D geometry at all | Toggles `removedInstances` — hides, doesn't delete (but only reachable at all if applicable or historied — see below) |

`Item`/`System`/`Hidden` all reuse the exact same non-destructive pattern: a bare `.filter`/
`.filterNot` off `removedInstances`, with an entry point back in via a dedicated "Available to
Add" section. `Placed` is the deliberate exception — don't accidentally give a new item type
`Placed`-style destructive removal when it should behave like the other three, and don't give
`Placed` items the soft-hide treatment (their whole identity is "I dragged this specific one in";
there's nothing meaningful left to track once it's gone).

## The `removedInstances: Set<String>` mechanism

One flat `Set<String>` (persisted as `removedInstancesJson` on `UserHomeEntity`, no per-kind
sharding) is the entire "not in my home" implementation for `Item`/`System`/`Hidden`. A key's
presence in the set means "hidden, not deleted." Every consumer must apply the same filter
consistently:

- **`MaintenanceHubDialog.kt`'s `InventoryTab`**: for each kind, `present = candidates.filterNot
  { key(it) in removedInstances }`, `addable = candidates.filter { key(it) in removedInstances }`
  — present renders in its own named section ("Systems", "Structural", …), addable renders
  (dimmed) in "Available to Add", `onClick` re-opens the same maintenance dialog either way.
- **`HomeTaskList.forHome`** (core-model): the final `return all.filterNot { ... }` must check
  `removedInstances` for **every** kind that has recurring tasks (`relatedRoomItem`,
  `relatedHiddenAsset`, `relatedHomeSystem`) — a kind left out of this filter keeps nagging the
  Tasks tab after being marked "not in my home." Grep this function for `relatedHomeSystem`/
  `relatedHiddenAsset` before assuming a new task-bearing kind is wired correctly.
- **`ApplianceMaintenanceDialog`'s call site** (`MainActivity.kt`, the `onNotInHome`/`isRemoved`
  `when (target)` blocks right after `maintenanceTarget?.let { ... }`): every non-`Placed`
  `MaintenanceTarget` subtype needs an explicit branch in **both** `when`s, toggling
  `target.<kind-specific-key>` in `removedInstances`. A subtype that falls through to `else ->
  null` / `else -> false` silently has no "Not In My Home" button at all — this was the exact gap
  `System` had until the fix described below.

## Checklist: adding or touching a trackable/placeable item type

1. **Does this kind's Score-tab presence depend on live placement/instance state, a fixed
   always-applicable set (`HomeSystem.entries`), or a *conditionally* applicable set
   (`HiddenAsset.entries.filter { isApplicable(...) }`, gated on current style/features)?** Don't
   gate the first kind behind a mutable `Set<X>` state var that placement code grows/shrinks —
   that's the orphaning bug. For the third kind, don't just drop the applicability check either
   (see point 1a) — and don't assume "conditionally applicable" automatically means "mirror
   `HomeSystem` and make it unconditional": some conditions are real structural facts (a CONDO can
   never have a lawn), not just "not currently placed."
   1a. **For a conditionally-applicable kind, does losing applicability (e.g. claiming a model
       without that feature/style) hide items that still have tracked history?** If a kind can
       have real per-item history (an install year or a document) while also being conditionally
       inapplicable to *some* homes, add a `hasHistory(key) = applianceRecords.any{...} ||
       documents.any{...}` fallback: present when applicable-and-not-hidden, addable when
       (applicable-and-hidden) OR (inapplicable-but-historied) — an inapplicable, never-touched
       item still stays fully absent, avoiding clutter. This is `HiddenAsset`'s `InventoryTab`
       logic (`MaintenanceHubDialog.kt`) — a narrower fix than `HomeSystem`'s, because
       `HiddenAsset`'s applicability is sometimes a genuine structural fact, not just "unplaced."
2. **Does removing the physical placement also (accidentally) touch `removedInstances` or delete
   DB records?** For `System`-shaped kinds it must not — that direction is the orphaning bug (the
   actions bar's Remove is physical-only). For `Placed`-shaped kinds, deleting on removal is
   correct (there's no stable identity left to track).
   2a. **The converse is wired for `System`, and stays a soft hide: hiding clears the placement.**
       `MainActivity.kt`'s `toggleRemovedKey` — the one toggle behind *every* "not in my home"
       write path (the maintenance card's button and the attic pane's mechanical row, which can
       both write the bare key `"HVAC"`) — nulls `hvacPlacement`/`solarArrayPlacement`/
       `evBatteryPlacement` when hiding, so the unit leaves the yard and its tray icon returns.
       The 3D scene models *this* home; it can't keep a condenser on a wall whose owner just said
       there's no HVAC. **Nothing is deleted:** the card moves to "Available to Add" (still
       `HomeSystem.entries`, unconditional) and the `ApplianceRecordEntity`/`DocumentEntity` rows
       survive untouched — `toggleRemovedKey` makes no `maint.*` call. Only the chosen wall+offset
       is discarded, exactly as the actions bar's Remove already discards it. Route any new
       soft-hide write path through `toggleRemovedKey` rather than open-coding the set arithmetic,
       or the paths will disagree. HVAC's indoor half follows the same key: `AtticItem.homeSystem`
       (`HouseSceneGeometry.kt`) maps `AIR_HANDLER -> HomeSystem.HVAC` so `atticContents` drops it
       too.
3. **Does placing/re-placing an item clear it from `removedInstances` if present?** (See
   `dropHvacAt`/`dropSolarAt`/`dropEvBatteryAt` in `MainActivity.kt` — each does
   `removedInstances = removedInstances - HomeSystem.X.name` on drop.) Without this, a user could
   place a unit back and still see its card sitting in "Available to Add."
4. **Does claiming a different neighbor model (`claimNeighbor`) reset `removedInstances` to
   `emptySet()`?** It already does, deliberately — a newly claimed home starts every
   always-applicable kind fully present, not carrying over the old home's hidden set.
5. **Is the new kind's `.key` stable across every code path that can regenerate its identity**
   (migrations, re-seeding, model claims)? A key that changes silently detaches
   `ApplianceRecordEntity`/`DocumentEntity` history — this is what `MaintenanceViewModel
   .migrateItemKeys(mapping)` exists to handle for `Item`/`Placed` re-keying during claims; a new
   always-present kind (`System`/`Hidden`-shaped) typically doesn't need this since its key is a
   fixed enum name, never regenerated.
6. **Does `HomeTaskList.forHome`'s final filter check this kind's `relatedX` field?**
7. **For a `Placed`-shaped kind: what happens to it when its room stops existing?** If it can carry
   the user's own history, it must be re-homed rather than dropped — see "Orphaned `Placed` items"
   below, and route the deleting path through `rescueOrphans` rather than a bare filter.

## Orphaned `Placed` items: re-home them, never silently delete

`Placed` is the one kind whose identity **is** its placement (`placementId` + `PlacedItem.id`), so
when the room it names stops existing there is nothing left to hang it off. Rooms stop existing
constantly and mostly not on purpose: `removeRoom`, `shrinkCols`, `shrinkRows`, `removeTopFloor`,
`claimNeighbor` replacing the layout wholesale, and a save written by an older version whose ids no
longer resolve. The original `pruneOrphanedPlacedItems` simply dropped anything orphaned.

That is correct for an anonymous sofa and **wrong for anything the user put something of their own
into**. Deleting the `PlacedItem` strands its `ApplianceRecordEntity` (install year) and
`DocumentEntity` (warranty PDFs, receipts) rows in the DB — not deleted, but unreachable through
any UI, which is the same orphaning failure the two precedents below describe, arriving from the
placement side instead of the tracking side.

**The rule: sweep, don't prune.** `FloorLayout.sweepOrphanedPlacedItems` returns both halves
(`kept` / `orphaned`) rather than filtering, because deciding which orphans are worth keeping needs
the maintenance DB and `:core-model` has none. The policy lives at the call site — `rescueOrphans`
in `MainActivity.kt`, which every room-deleting path routes through so the answer can't differ
between deleting a room and shrinking a column:

- **Rescue if** the item is a `RoomItem.TREASURE` (a keepsake is *only* ever the user's own) **or**
  it has tracked history — `applianceRecords.any { it.itemKey == "placed:$id" } || documents.any {
  … }`. Everything else still prunes; a garage full of rescued dining chairs helps nobody.
- **Re-home it somewhere real**, not into a hidden bucket: the **garage** when the home has one and
  `RoomType.GARAGE in item.trayRooms` (a displaced water heater belongs where anyone would actually
  put one), the **attic** otherwise — for what a garage can't hold, and for homes with no garage.
  Both are places the user can walk into and find the thing, which is the point: a rescue the user
  can't see is indistinguishable from the deletion it replaced.
- **Don't capacity-gate the rescue.** `RoomZone.canFit` governs deliberate drops; a crowded attic
  is strictly better than a deleted keepsake.
- **`claimNeighbor` needs the same treatment twice over.** Items in non-room spaces
  (`isNonRoomPlacement` — the lot, the attic, an attached garage) are the owner's belongings rather
  than the model's furniture, so they're *carried* to the new home and re-clamped, like the fleet.
  Room furniture that the new model has no twin for — so it got no `recordMapping` entry — is
  *rescued* on the rule above. And carried items **must be excluded from `recordMapping`**: they
  keep their own ids, so pairing them with a seeded twin would re-key them and detach exactly the
  history the carry exists to preserve (this is why vehicles were already excluded).
- **Keep a non-destructive safety net** for orphans nothing in the session created (an old save, a
  since-fixed bug). `MainActivity.kt` runs one as a `LaunchedEffect`. It only ever *re-homes* and
  never deletes, because `applianceRecords`/`documents` load asynchronously — a net that also
  pruned could race them and bin an item whose documents simply hadn't arrived yet.

When adding a new sentinel placement id for a space outside the floor plan, add it to
`NON_ROOM_PLACEMENT_IDS` in `FloorLayout.kt` — that one set feeds the orphan sweep *and*
`isNonRoomPlacement`, so a new space is survivable by both paths at once.

## Known precedent: the `HomeSystem` migration

`HomeSystem` (HVAC/Solar/EV Battery) used to be tracked via a live `homeSystems: Set<HomeSystem>`
state var, grown on physical placement and shrunk on physical removal, and wholesale replaced by
`claimNeighbor` from whatever the newly-claimed `NeighborPresets.kt` model happened to define
(most models didn't define `EV_BATTERY` at all). Claiming a different model, or just removing a
placed unit, silently dropped its Score-tab card — the underlying DB rows survived, untouched, but
became permanently unreachable through the UI. Fixed by deleting `homeSystems` entirely and
re-deriving `System` presence from `HomeSystem.entries` filtered by `removedInstances`, exactly
mirroring `HiddenAsset`'s pre-existing pattern — see `MaintenanceHubDialog.kt`'s `InventoryTab`,
`MainActivity.kt`'s `ApplianceMaintenanceDialog` call site, and `HomeTaskList.kt`'s final filter
for the concrete shape. Use this as the template for "should this be trackable even when not
currently placed" — but see the follow-up below before assuming "unconditional" is always right.

## Known precedent: the `HiddenAsset` history fallback

Immediately after the `HomeSystem` fix, the same orphaning showed up one layer over: a user
tracked documents against `HiddenAsset.POOL_EQUIPMENT` while on a model with a pool, then claimed
the Penthouse (`CONDO`, no yard — a pool can never exist there). `POOL_EQUIPMENT` disappeared from
*both* "Structural" and "Available to Add," because `InventoryTab` computed `applicableAssets =
HiddenAsset.entries.filter { isApplicable(...) }` **before** the present/addable split — so
anything inapplicable to the *currently claimed* home was filtered out before it ever got a chance
to land in "Available to Add." Unlike `HomeSystem`, the fix here was **not** "drop the
applicability check and make everything unconditional" — `HiddenAsset.isApplicable` encodes real
structural facts (`LAWN_EQUIPMENT` requires `style != CONDO`; a condo cannot grow a lawn, ever) as
well as "just not currently placed" facts (`POOL_EQUIPMENT` requires `HomeFeature.POOL in
features`), and conflating the two would clutter every home's Score tab with entries that could
never apply to it. The actual fix: add a `hasHistory(key) = applianceRecords.any{...} ||
documents.any{...}` check (new `documents: List<DocumentEntity>` param threaded into
`InventoryTab`, reusing `MainActivity.kt`'s already-collected `allDocuments`), and let
"Available to Add" include an asset if it's *either* applicable-but-hidden *or*
inapplicable-but-historied. `HomeTaskList.forHome`'s recurring-task gating was deliberately left
untouched — a pool-cleaning reminder correctly stops firing when there's no pool, that's not an
orphaning bug, only the Score-tab card's reachability was. **Takeaway: when a kind's applicability
can be a genuine structural fact and not just "unplaced," reach for the history-fallback pattern,
not `HomeSystem`'s blanket-unconditional one.**
