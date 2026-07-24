package com.homerenderer.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val homeId: Int = 1,
    val itemKey: String,    // RoomItem.name, HomeSystem.name, or "HOME"
    val uri: String,
    val label: String,
    val addedAt: Long = System.currentTimeMillis(),
)
