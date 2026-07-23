package com.homerenderer.db

import androidx.room.Entity

@Entity(tableName = "appliance_records", primaryKeys = ["homeId", "itemKey"])
data class ApplianceRecordEntity(
    val homeId: Int = 1,
    val itemKey: String,        // RoomItem.name or HomeSystem.name
    val installYear: Int? = null,
    val notes: String = "",
)
