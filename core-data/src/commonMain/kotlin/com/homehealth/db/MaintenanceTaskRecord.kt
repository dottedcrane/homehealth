package com.homehealth.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_records")
data class MaintenanceTaskRecord(
    @PrimaryKey val taskKey: String,
    val homeId: Int = 1,
    // A rolling timestamp, not a "completed" flag: marking a task done stamps this to now, the
    // task leaves the due set, and it comes back at its next scheduled anchor (see the Tasks
    // tab's nextDueMillis). 0L means never completed, which reads as "due now".
    val lastCompleted: Long = 0L,
    // Per-task deferral, set from the task detail popup. [snoozedUntil] pushes the effective due
    // date out without pretending the work was done (unlike lastCompleted, which would skip a
    // whole cycle); [muted] stops a task competing for attention at all — it sorts last and stays
    // visible so it can be un-muted, rather than being deleted.
    //
    // A row can now exist for a task that was never completed (lastCompleted = 0L) — snoozing or
    // muting creates one. Anything counting "up to date" must therefore key off the due date, not
    // the mere existence of a record.
    val snoozedUntil: Long = 0L,
    val muted: Boolean = false,
)
