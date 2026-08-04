package com.homehealth.model

// Which decorative yard item a PlacedYardDecor instance is — both share one free-2D-placement
// model since their mechanics (unlimited instances, drop-anywhere, no wall coupling) are
// identical, distinguished only at render/tray/icon time.
enum class YardDecorKind { TREE, GAZEBO }
