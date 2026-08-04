---
name: user-data-durability
description: Checklist for protecting user-authored data (install years, notes, attached documents, task completion history, custom appliances/tasks) whenever an item's identity key changes, an enum case is removed or renamed, a model is re-claimed, or a backup is restored. Use when editing HiddenAsset/HomeSystem/RoomItem enums, task keys in HomeTaskList.kt, MaintenanceTarget.key, claimNeighbor, the restore LaunchedEffect in MainActivity.kt, BackupManager.kt, or anything under core-data/db.
---

# Durable user data

## The invariant

**The model's look and feel is disposable. The user's records are not.**

Re-claiming a model, switching styles, restoring a backup, or shipping a taxonomy change may
freely reset layout, furniture seeding, feature placements and offsets. It must never destroy or
strand:

- `ApplianceRecordEntity` — install years and notes
- `DocumentEntity` — receipts, warranties, manuals (these also have **files on disk** under
  `filesDir/attachments`, which a DB-only reasoning pass will miss)
- `MaintenanceTaskRecord` — task completion history
- `customAppliancesJson` / `customTasksJson` — user-authored items and reminders

"Stranded" counts as data loss. A row whose key nothing reads any more is invisible to the user
forever, even though `SELECT *` still shows it. Treat orphaning as seriously as `DELETE`.

## Why key changes are the dangerous edit

Every one of those tables is keyed by a **plain string**, not a foreign key:

| Table | Key | Comes from |
|---|---|---|
| `appliance_records` | `(homeId, itemKey)` | `MaintenanceTarget.key` |
| `documents` | `itemKey` (no uniqueness) | `MaintenanceTarget.key` |
| `task_records` | `taskKey` (PK) | `MaintenanceTask.key` |

`MaintenanceTarget.key` (`MaintenanceDialogs.kt`) resolves to `instanceKey`, `system.name`,
`"placed:$id"`, `asset.name`, or `"custom:$id"`. So **renaming or deleting a `HiddenAsset` /
`HomeSystem` enum case silently orphans every record filed under its old `.name`** — the
compiler cannot see it, because the link is a string written to disk months ago.

The same applies to task keys: delete `foo_task` from `HomeTaskList.forHome` and every user who
ever completed it loses that history.

## Before removing or renaming any key

1. **Search the whole repo for the literal string**, not just the symbol — including
   `HomeStateSerialization.kt`, which deliberately keeps some on-disk JSON names frozen while the
   Kotlin property is renamed (e.g. `CustomTask.targetKey` still persists as `"applianceId"`).
2. **Decide where the data should land.** Merging two concepts? Re-key old → new. Genuinely
   retiring one? Say so explicitly in the migration comment so the next reader knows the
   orphaning was a decision, not an oversight.
3. **Write a Room data migration** (see below). Do not do it in Kotlin app code keyed off some
   "have I run this yet" flag.

## Put key migrations in a Room `Migration`, not app code

`DatabaseFactory.kt` (`core-data`). Bump `HomeDatabase.version`, add the migration to
`.addMigrations(...)`, and let the exported schema JSON regenerate under `core-data/schemas/`.

A data-only migration with no `ALTER TABLE` is still legitimate and still needs a version bump —
that's what makes it run exactly once.

**This is why it must be a Room migration:** `BackupManager.restoreZip` swaps the entire SQLite
file, including its `user_version`. A restored backup from an older build re-runs the whole
migration chain on next open. App-side one-shot logic guarded by a preference or a state flag
gets skipped on that path; a Room migration does not. Restores are precisely the case where
these bugs surface.

### Write collision-safe SQL

`appliance_records` PK is `(homeId, itemKey)` and `task_records` PK is `taskKey`, so a naive
re-key throws when both keys already exist. Guard on the destination instead of using
`OR REPLACE` / `OR IGNORE` — those resolve a collision by *destroying a row*, which is the thing
this skill exists to prevent:

```sql
UPDATE appliance_records SET itemKey = 'NEW'
WHERE itemKey = 'OLD'
  AND NOT EXISTS (
    SELECT 1 FROM appliance_records r
    WHERE r.itemKey = 'NEW' AND r.homeId = appliance_records.homeId)
```

Worst case a rare row stays unreferenced and recoverable — strictly better than dropping the
user's receipts. `documents` has no uniqueness constraint on `itemKey`, so it always re-keys and
merges cleanly.

**Verify the SQL before shipping.** A broken migration crashes every upgrading user on launch.
`sqlite3` is available on this machine — build a scratch DB with the real `CREATE TABLE`
statements, seed both the normal case and the both-keys collision case, run the migration, and
assert nothing was lost. Room does not validate `execSQL` strings at compile time.

## Deletion is only ever explicit

`MaintenanceViewModel.removeItemRecords(itemKey)` is genuinely destructive — it does
`deleteAppliance` + `deleteDocumentsForItem`. Every current call site is a direct user action on
one specific item (removing a placed item, deleting a custom appliance, tearing a vehicle out).

**It must never be called from `claimNeighbor`, the restore effect, or any bulk/style-change
path.** If a change makes a claim or restore delete records, that's a bug regardless of how
reasonable the cleanup looks.

Note the asymmetry with `removedInstances` ("Not In My Home"): that is a soft hide, holds only
strings, and deliberately does **not** touch the DB — so re-adding an item brings its history
back. Don't "helpfully" make it delete records. Stale entries in it are harmless by design
(it stores raw strings and is never `valueOf`-parsed, so a removed enum case can't crash it).

## `claimNeighbor` is the model-vs-records boundary

`claimNeighbor` (`MainActivity.kt`) is the canonical example of the invariant in action:

- **Reset** (model state): `floorLayout`, `featurePlacements`, `featureOffsets`, `itemOffsets`,
  `removedInstances`, seeded furniture.
- **Preserved** (user state): `customAppliances`, `customTasks`, vehicles (they keep their ids,
  so records follow by construction), and all DB records.
- **Re-keyed**: `recordMapping` pairs each old seeded item with its twin in the new model in
  seeding order and calls `migrateItemKeys`, so "my fridge's install year" follows to the new
  model's fridge. Records with no twin stay in the DB untouched.

When adding anything new to this function, classify it into one of those three buckets
deliberately. A new field silently landing in "reset" is how user data gets lost.

## Review checklist

- [ ] Did an enum case or task key get removed/renamed? Is there a matching data migration?
- [ ] Is the migration a Room `Migration` with a version bump (so restored backups get it)?
- [ ] Is the re-key SQL guarded against PK collision without destroying a row?
- [ ] Was the SQL actually executed against a scratch SQLite DB, including the collision case?
- [ ] Does any new code path call `removeItemRecords` outside an explicit single-item user action?
- [ ] For new state in `claimNeighbor`: reset, preserved, or re-keyed — chosen on purpose?
- [ ] Do attached document **files** still resolve (`filesDir/attachments`), not just DB rows?

## Known precedent (2026-07-29)

Merging Mini-Split AC into HVAC removed `HiddenAsset.MINI_SPLIT_AC` and the
`condo_minisplit_filter` task. Both were pure Kotlin edits that compiled cleanly and looked
complete — while stranding every condo owner's mini-split install year, notes, receipts and
completion history under keys nothing read any more. Fixed by `MIGRATION_14_15`, a data-only
migration re-keying `MINI_SPLIT_AC` → `HVAC` and `condo_minisplit_filter` → `hvac_filter`.

The lesson worth carrying: **the compiler is no help here.** Nothing about deleting an enum case
signals that it is also a storage key. Only this checklist catches it.
