package com.homehealth.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.homehealth.model.FloorCount
import com.homehealth.model.HomeSize
import com.homehealth.model.HomeStyle
import com.homehealth.model.LayoutType

@Entity(tableName = "home_models")
data class HomeModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val style: HomeStyle,
    val size: HomeSize,
    val floors: FloorCount,
    val layout: LayoutType,
    val createdAt: Long = System.currentTimeMillis()
)
