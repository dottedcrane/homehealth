package com.homehealth.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_home")
data class UserHomeEntity(
    @PrimaryKey val id: Int = 1,
    val homeStyle: String,
    val featurePlacementsJson: String = "",
    val featureOffsetsJson: String = "",
    val placedDecksJson: String = "",
    val hvacPlacementJson: String = "",
    val evBatteryPlacementJson: String = "",
    // Single-slot solar array placement (onLeftSlope + along-ridge offset) — solar is one placed
    // item like HVAC/EV battery.
    val solarArrayPlacementJson: String = "",
    val placedYardDecorJson: String = "",
    // User-named appliances not covered by the app's fixed taxonomy, and their user-defined
    // recurring tasks — see CustomAppliance/CustomTask (core-model). Independent of any claimed
    // model (claimNeighbor never resets these).
    val customAppliancesJson: String = "",
    val customTasksJson: String = "",
    val floorLayoutJson: String = "",
    val itemOffsetsJson: String = "",
    val placedItemsJson: String = "",
    val removedInstancesJson: String = "",
    // What the home has above its top storey (see AtticType). Empty means NEVER CHOSEN, which
    // resolves to the style default — so every row written before this column existed keeps
    // exactly the attic it had, and nothing moves until the owner actually picks one.
    val atticType: String = "",
    val buildYear: Int? = null,
    val purchaseYear: Int? = null,
    val label: String = "My Home",
    val neighborKey: String = "",
    val savedAt: Long = System.currentTimeMillis(),
)
