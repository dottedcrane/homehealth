package com.homerenderer.model

import com.homerenderer.util.randomUUIDString

// A user-placed deck instance — unlike garage/pool (one slot each, in featurePlacements),
// decks support multiple copies, added via tear-drag from the sidebar tray just like
// vehicles. Wall-snapped like the garage: [side] is which house wall it's flush against,
// [along] is its position along that wall (world X for FRONT/BACK, world Z for LEFT/RIGHT),
// relative to the wall's own centre — see HouseSceneGeometry.deckBox.
data class PlacedDeck(
    val id: String = randomUUIDString(),
    val side: FeatureSide,
    val along: Float = 0f,
)
