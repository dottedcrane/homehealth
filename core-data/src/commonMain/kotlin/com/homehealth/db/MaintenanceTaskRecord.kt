package com.homerenderer.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_records")
data class MaintenanceTaskRecord(
    @PrimaryKey val taskKey: String,
    val homeId: Int = 1,
    val lastCompleted: Long = 0L,
)
