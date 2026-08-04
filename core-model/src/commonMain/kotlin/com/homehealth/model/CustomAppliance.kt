package com.homehealth.model

import java.util.UUID

// A user-named appliance not covered by the app's fixed taxonomy (RoomItem/HomeSystem/
// HiddenAsset) — e.g. a water softener, a whole-home generator, a specific piece of equipment
// unique to one building. Whole-home, never placed in the 3D scene (see the
// tracked-vs-placed-items skill) — tracked purely for its install year/documents and any
// CustomTask reminders the user adds against it (see CustomTask.applianceId). [id] is the
// stable key its ApplianceRecordEntity/DocumentEntity rows are keyed against — never the
// (renamable) name.
data class CustomAppliance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
)
