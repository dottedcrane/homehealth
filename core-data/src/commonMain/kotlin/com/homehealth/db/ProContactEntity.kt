package com.homerenderer.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// A professional/service contact the user keeps for home maintenance — plumber,
// electrician, HVAC tech, etc. Shown in the Maintenance Hub's Pros tab.
@Entity(tableName = "pro_contacts")
data class ProContactEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val homeId: Int = 1,
    val name: String,
    val trade: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
)
