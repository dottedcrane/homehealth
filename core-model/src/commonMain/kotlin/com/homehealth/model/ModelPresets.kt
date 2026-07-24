package com.homerenderer.model

object ModelPresets {
    val house = HomeType(
        style   = HomeStyle.HOUSE,
        size    = HomeSize.MEDIUM,
        floors  = FloorCount.ONE,
        layout  = LayoutType.MIXED
    )
    val condo = HomeType(
        style   = HomeStyle.CONDO,
        size    = HomeSize.MEDIUM,
        floors  = FloorCount.ONE,
        layout  = LayoutType.MIXED
    )
    val townhouse = HomeType(
        style   = HomeStyle.TOWNHOUSE,
        size    = HomeSize.MEDIUM,
        floors  = FloorCount.TWO,
        layout  = LayoutType.COMPARTMENTALIZED
    )

    val all = listOf(house, condo, townhouse)
}
