package com.homehealth.db

import androidx.room.TypeConverter
import com.homehealth.model.FloorCount
import com.homehealth.model.HomeSize
import com.homehealth.model.HomeStyle
import com.homehealth.model.LayoutType

class Converters {
    @TypeConverter fun fromHomeStyle(v: HomeStyle) = v.name
    @TypeConverter fun toHomeStyle(v: String) = HomeStyle.valueOf(v)

    @TypeConverter fun fromHomeSize(v: HomeSize) = v.name
    @TypeConverter fun toHomeSize(v: String) = HomeSize.valueOf(v)

    @TypeConverter fun fromFloorCount(v: FloorCount) = v.name
    @TypeConverter fun toFloorCount(v: String) = FloorCount.valueOf(v)

    @TypeConverter fun fromLayoutType(v: LayoutType) = v.name
    @TypeConverter fun toLayoutType(v: String) = LayoutType.valueOf(v)
}
