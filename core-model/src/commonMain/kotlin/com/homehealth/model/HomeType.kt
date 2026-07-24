package com.homerenderer.model

enum class HomeStyle {
    HOUSE, CONDO, TOWNHOUSE
}

enum class HomeSize(val sqftMin: Int, val sqftMax: Int) {
    SMALL(0, 800),
    MEDIUM(800, 1500),
    LARGE(1500, 2500),
    XLARGE(2500, Int.MAX_VALUE)
}

enum class FloorCount(val count: Int) {
    ONE(1), TWO(2), THREE_PLUS(3)
}

enum class LayoutType {
    OPEN_PLAN, COMPARTMENTALIZED, MIXED
}

data class HomeType(
    val style: HomeStyle,
    val size: HomeSize,
    val floors: FloorCount,
    val layout: LayoutType
)
