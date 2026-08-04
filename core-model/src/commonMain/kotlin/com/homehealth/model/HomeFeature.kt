package com.homehealth.model

enum class HomeFeature { GARAGE, YARD, POOL, DECK }
enum class HomeSystem   { SOLAR, HVAC, EV_BATTERY }

// What the home has above its top storey. Until now this was DERIVED from the roof — a pitched
// style got a full framed attic, a CONDO got a small rooftop equipment room, with no say from the
// owner. The roof decides whether the space is PITCHED (see HouseSceneGeometry.atticVolume); this
// decides whether it's a walk-in attic or a mechanical closet, which plenty of real houses and
// top-floor condos disagree with their roofs about.
//
// Held as a NULLABLE AtticType everywhere it's stored or passed: null means "never chosen", which
// resolves to HouseSceneGeometry.defaultAtticType(style) — so every save that predates the choice
// keeps exactly the space it had.
enum class AtticType(val label: String) {
    FULL("Attic"),
    CLOSET("Utility Closet"),
}

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
    DUCTWORK, ATTIC_INSULATION,
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
// [atticType] is the home's RESOLVED attic (never the raw nullable — callers resolve the style
// default first), and it can only ever widen what applies: see the DUCTWORK/ATTIC_INSULATION
// branch below.
fun HiddenAsset.isApplicable(
    style: HomeStyle,
    features: Set<HomeFeature>,
    hasDeck: Boolean = false,
    hasGarage: Boolean = HomeFeature.GARAGE in features,
    atticType: AtticType = AtticType.FULL,
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
    // Both live in the attic. A pitched-roof style has one by definition; a CONDO's flat roof has
    // no void, and its ductless mini-split has no ducts at all — the same reason its HVAC filter
    // task differs (see HomeTaskList) — so it starts without them.
    //
    // [atticType] only ever WIDENS this. A condo that chooses a full attic gains both, which it
    // must: the scene would otherwise render batts and duct runs the To-Do list denies exist. A
    // house that chooses a closet keeps both, because a mechanical closet is a statement about the
    // SPACE, not about the home's ducts — whether this particular house has any is answered the
    // way every other such question is, by removedInstances (the attic pane's own inventory row
    // and the hub's Inventory tab, which toggle these by bare name).
    HiddenAsset.DUCTWORK, HiddenAsset.ATTIC_INSULATION ->
        style != HomeStyle.CONDO || atticType == AtticType.FULL
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
    HiddenAsset.DUCTWORK          -> "Ductwork"
    HiddenAsset.ATTIC_INSULATION  -> "Attic Insulation"
}
