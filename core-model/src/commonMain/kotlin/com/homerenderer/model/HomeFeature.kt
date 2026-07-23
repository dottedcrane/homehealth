package com.homerenderer.model

enum class HomeFeature { GARAGE, YARD, POOL, DECK }
enum class HomeSystem   { SOLAR, HVAC }

// Structural/building assets and other home-wide (not room-specific) items whose upkeep this
// app tracks — unlike HomeSystem (an opt-in "mark as installed" toggle rendered in the exterior
// panel), these live in the tool box's Inventory tab. ROOF/SIDING/PLUMBING/ELECTRICAL_PANEL are
// unconditional (every home has one); the rest are only applicable to homes with the matching
// HomeFeature/HomeStyle (see `isApplicable`) and — like any inventory item — removable via "not
// in my home," which also drops their associated To-Do task (HomeTaskList.forHome).
enum class HiddenAsset {
    ROOF, SIDING, PLUMBING, ELECTRICAL_PANEL,
    GARAGE_DOOR, SMOKE_CO_DETECTORS, WINDOWS_DOORS, FIRE_EXTINGUISHER,
    DECK, LAWN_EQUIPMENT, POOL_EQUIPMENT, BALCONY,
}

// Whether [this] asset is relevant to a home of the given style/features at all — irrespective
// of whether the user has since marked it "not in my home." Assets with no applicable signal
// (e.g. a Deck when there's no HomeFeature.DECK) are hidden entirely rather than offered as
// removable/addable clutter.
// [hasDeck] is passed separately (not read off [features]) since decks now support multiple
// placed instances tracked in their own list (placedDecks), not a single featurePlacements slot.
// [hasGarage] is likewise passed separately: TOWNHOUSE homes have their garage as an interior
// RoomType.GARAGE room, never a features entry, so featurePlacements alone would wrongly hide
// their garage door upkeep.
fun HiddenAsset.isApplicable(
    style: HomeStyle,
    features: Set<HomeFeature>,
    hasDeck: Boolean = false,
    hasGarage: Boolean = HomeFeature.GARAGE in features,
): Boolean = when (this) {
    HiddenAsset.ROOF, HiddenAsset.SIDING, HiddenAsset.PLUMBING, HiddenAsset.ELECTRICAL_PANEL,
    HiddenAsset.SMOKE_CO_DETECTORS, HiddenAsset.WINDOWS_DOORS, HiddenAsset.FIRE_EXTINGUISHER -> true
    HiddenAsset.GARAGE_DOOR    -> hasGarage
    HiddenAsset.DECK           -> hasDeck
    HiddenAsset.POOL_EQUIPMENT -> HomeFeature.POOL in features
    // Yard presence is now purely style-derived (see HouseSceneGeometry.yardBounds) rather
    // than a HomeFeature the user toggles — CONDO is the only style with no yard.
    HiddenAsset.LAWN_EQUIPMENT -> style != HomeStyle.CONDO
    HiddenAsset.BALCONY        -> style == HomeStyle.CONDO
}

val HiddenAsset.label: String get() = when (this) {
    HiddenAsset.ROOF              -> "Roof"
    HiddenAsset.SIDING            -> "Siding"
    HiddenAsset.PLUMBING          -> "Plumbing"
    HiddenAsset.ELECTRICAL_PANEL  -> "Electrical Panel"
    HiddenAsset.GARAGE_DOOR       -> "Garage Door"
    HiddenAsset.SMOKE_CO_DETECTORS -> "Smoke & CO Detectors"
    HiddenAsset.WINDOWS_DOORS     -> "Windows & Doors"
    HiddenAsset.FIRE_EXTINGUISHER -> "Fire Extinguisher"
    HiddenAsset.DECK              -> "Deck"
    HiddenAsset.LAWN_EQUIPMENT    -> "Lawn Equipment"
    HiddenAsset.POOL_EQUIPMENT    -> "Pool Equipment"
    HiddenAsset.BALCONY           -> "Balcony"
}
