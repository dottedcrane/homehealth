package com.homerenderer.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_home")
data class UserHomeEntity(
    @PrimaryKey val id: Int = 1,
    val homeStyle: String,
    val featurePlacementsJson: String = "",
    val featureOffsetsJson: String = "",
    val placedDecksJson: String = "",
    val homeSystemsJson: String = "",
    val floorLayoutJson: String = "",
    val itemOffsetsJson: String = "",
    val placedItemsJson: String = "",
    val removedInstancesJson: String = "",
    val buildYear: Int? = null,
    val purchaseYear: Int? = null,
    val label: String = "My Home",
    val neighborKey: String = "",
    val savedAt: Long = System.currentTimeMillis(),
)
