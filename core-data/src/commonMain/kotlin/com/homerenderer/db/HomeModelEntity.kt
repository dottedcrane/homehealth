package com.homerenderer.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.homerenderer.model.FloorCount
import com.homerenderer.model.HomeSize
import com.homerenderer.model.HomeStyle
import com.homerenderer.model.LayoutType

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
