package com.homehealth.model

import com.homehealth.data.TaskFrequency
import java.util.UUID

// A user-defined recurring reminder against any trackable item — [targetKey] is a
// MaintenanceTarget.key (app module), so this can attach to a RoomItem instance, a HomeSystem,
// a freely-placed instance, a HiddenAsset, or a CustomAppliance alike. The user-authored
// counterpart to HomeTaskList.forHome's built-in tasks. Reuses TaskFrequency rather than
// inventing a separate schedule vocabulary; see CustomTask.toMaintenanceTask() (HomeTaskList.kt)
// for how this merges into the live Tasks tab.
data class CustomTask(
    val id: String = UUID.randomUUID().toString(),
    val targetKey: String,
    val title: String,
    val frequency: TaskFrequency,
)
