package com.homehealth.data

import com.homehealth.model.HiddenAsset
import com.homehealth.model.HomeFeature
import com.homehealth.model.HomeStyle
import com.homehealth.model.HomeSystem
import com.homehealth.model.RoomItem
import com.homehealth.model.RoomType

data class MaintenanceTask(
    val key: String,
    val title: String,
    val description: String,
    val frequency: TaskFrequency,
    val priority: TaskPriority,
    val estimatedCostDollars: Float = 0f,
    val repairGuideId: String? = null,
    // Which calendar month (0-indexed like java.util.Calendar: MARCH=2, SEPTEMBER=8) an
    // ANNUAL task should be anchored to — null for MONTHLY/QUARTERLY/BIANNUAL, which anchor
    // to a fixed set of months instead of one specific task-chosen month (see
    // MaintenanceHubDialog's nextDueMillis).
    val seasonMonth: Int? = null,
    // At most one of these three is set — the specific inventory item/asset/system this
    // task's completion status should be reflected against in the Inventory tab. Tasks
    // with no natural single-item fit (smoke detectors, foundation, pest control, etc.)
    // leave all three null and simply have no upkeep badge.
    val relatedRoomItem: RoomItem? = null,
    val relatedHiddenAsset: HiddenAsset? = null,
    val relatedHomeSystem: HomeSystem? = null,
)

enum class TaskFrequency(val months: Int, val displayName: String) {
    MONTHLY(1, "Monthly"),
    QUARTERLY(3, "Quarterly"),
    BIANNUAL(6, "Biannual"),
    ANNUAL(12, "Annual"),
}

enum class TaskPriority(val displayName: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High"),
}

object HomeTaskList {

    fun forHome(
        style: HomeStyle,
        features: Set<HomeFeature>,
        systems: Set<HomeSystem>,
        roomTypes: Set<RoomType>,
        // Keys the user has marked "not in my home" (RoomItem instance keys and/or bare
        // HiddenAsset names) — any task whose relatedHiddenAsset was removed this way drops out
        // of the schedule entirely, since there's nothing left to maintain.
        removedInstances: Set<String> = emptySet(),
        // True when at least one of that vehicle is parked on the lot (a matching
        // PlacedItem exists) — gates the vehicle upkeep tasks the same way roomTypes
        // gates appliance tasks.
        hasCar: Boolean = false,
        hasBoat: Boolean = false,
        hasMotorcycle: Boolean = false,
        // True when at least one car/motorcycle on the lot still runs on gas — gates the
        // oil-change task for that vehicle type off once every one of them is electric.
        hasGasCar: Boolean = false,
        hasGasMotorcycle: Boolean = false,
        // True when at least one deck is placed — decks support multiple instances now,
        // tracked in their own list (placedDecks), not a single features slot.
        hasDeck: Boolean = false,
    ): List<MaintenanceTask> {
        val all = buildList {

        // ── Always ────────────────────────────────────────────────────────────
        add(task(
            key = "smoke_co_test",
            title = "Test Smoke & CO Detectors",
            description = "Press the test button on every smoke and CO detector. Replace batteries if chirping. Replace the unit after 10 years (smoke) or 5–7 years (CO).",
            frequency = TaskFrequency.MONTHLY,
            priority = TaskPriority.HIGH,
            guideId = "elec_smoke",
            asset = HiddenAsset.SMOKE_CO_DETECTORS,
        ))
        add(task(
            key = "gutter_clean",
            title = "Clean Gutters",
            description = "Remove debris and flush downspouts with a hose. Check for sagging sections and loose hangers. Extend downspouts 6 ft from the foundation.",
            frequency = TaskFrequency.BIANNUAL,
            priority = TaskPriority.HIGH,
            cost = 150f,
            guideId = "roof_gutters",
        ))
        add(task(
            key = "roof_inspect",
            title = "Inspect Roof",
            description = "Check for missing or damaged shingles, flashing condition, and ridge cap. Inspect from the ground with binoculars. Look in the attic for daylight or water stains.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.HIGH,
            cost = 200f,
            guideId = "roof_shingles",
            seasonMonth = 8, // September
            asset = HiddenAsset.ROOF,
        ))
        add(task(
            key = "fire_extinguisher",
            title = "Inspect Fire Extinguisher",
            description = "Confirm pressure needle is in the green zone. Check the pin and tamper seal are intact. Replace or recharge if pressure is low.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.HIGH,
            seasonMonth = 8, // September
            asset = HiddenAsset.FIRE_EXTINGUISHER,
        ))
        add(task(
            key = "caulk_windows",
            title = "Inspect & Recaulk Windows and Doors",
            description = "Check for cracked or missing caulk around all window and door frames, both interior and exterior. Recaulk any gaps with paintable exterior caulk.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.MEDIUM,
            cost = 30f,
            guideId = "int_window_draft",
            seasonMonth = 8, // September
            asset = HiddenAsset.WINDOWS_DOORS,
        ))
        add(task(
            key = "pest_inspect",
            title = "Pest Inspection",
            description = "Walk the perimeter and check foundation, garage entry, and crawl space for signs of termites, rodent droppings, or ant trails. Seal entry points.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.MEDIUM,
            cost = 100f,
            seasonMonth = 2, // March
        ))
        add(task(
            key = "water_heater_flush",
            title = "Flush Water Heater",
            description = "Attach a hose to the drain valve and flush until clear to remove sediment. Prevents rumbling sounds and restores heating efficiency.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.MEDIUM,
            guideId = "plumb_no_hot_water",
            seasonMonth = 8, // September
            item = RoomItem.WATER_HEATER,
        ))
        add(task(
            key = "main_shutoff_test",
            title = "Locate & Test Main Water Shutoff",
            description = "Find the main water shutoff valve and confirm it turns freely. Knowing its location is critical before any plumbing emergency.",
            frequency = TaskFrequency.ANNUAL,
            priority = TaskPriority.MEDIUM,
            seasonMonth = 8, // September
            asset = HiddenAsset.PLUMBING,
        ))

        // ── HVAC ──────────────────────────────────────────────────────────────
        if (HomeSystem.HVAC in systems) {
            add(task(
                key = "hvac_filter",
                title = "Replace HVAC Filter",
                description = "Replace with a MERV-8 or higher filter. Note the airflow direction arrow on the filter frame. Monthly replacement extends equipment life and improves air quality.",
                frequency = TaskFrequency.QUARTERLY,
                priority = TaskPriority.HIGH,
                cost = 15f,
                guideId = "hvac_no_cool",
                system = HomeSystem.HVAC,
            ))
            add(task(
                key = "hvac_tune_up",
                title = "HVAC Annual Professional Tune-Up",
                description = "Schedule a licensed technician to inspect coils, check refrigerant level, test electrical connections, and clean the blower. Do this before each heating and cooling season.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                cost = 150f,
                guideId = "hvac_no_cool",
                seasonMonth = 2, // March
                system = HomeSystem.HVAC,
            ))
            add(task(
                key = "hvac_condenser",
                title = "Clean AC Condenser Coils & Unit",
                description = "Remove leaves and debris from around the outdoor unit. Gently rinse coils with a garden hose from inside out. Maintain 2 ft clearance on all sides.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                guideId = "hvac_no_cool",
                seasonMonth = 2, // March
                system = HomeSystem.HVAC,
            ))
            add(task(
                key = "hvac_drain",
                title = "Clear HVAC Condensate Drain Line",
                description = "Pour 1 cup of diluted white vinegar into the condensate drain access port to prevent algae clogs. A clogged drain can cause water damage and system shutdown.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.MEDIUM,
                system = HomeSystem.HVAC,
            ))
        }

        // ── SOLAR ─────────────────────────────────────────────────────────────
        if (HomeSystem.SOLAR in systems) {
            add(task(
                key = "solar_clean",
                title = "Clean Solar Panels",
                description = "Rinse panels with water to remove dust, bird droppings, and debris. Check for shading from new tree growth. Dirty panels can lose 20–30% of output.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 100f,
                system = HomeSystem.SOLAR,
            ))
            add(task(
                key = "solar_inspect",
                title = "Inspect Solar Mounting & Inverter",
                description = "Check all panel mounting brackets for corrosion or loosening. Inspect inverter indicator lights and review the monitoring dashboard for underperforming panels.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 150f,
                seasonMonth = 2, // March
                system = HomeSystem.SOLAR,
            ))
        }

        // ── GARAGE ────────────────────────────────────────────────────────────
        // TOWNHOUSE homes have their garage as an interior RoomType.GARAGE room rather than a
        // features entry, so both signals gate this (see HiddenAsset.isApplicable's hasGarage).
        if (HomeFeature.GARAGE in features || RoomType.GARAGE in roomTypes) {
            add(task(
                key = "garage_reverse_test",
                title = "Test Garage Door Auto-Reverse",
                description = "Place a 2×4 board flat on the ground in the door's path. The door must automatically reverse when it contacts the board. If it doesn't, the safety mechanism needs adjustment.",
                frequency = TaskFrequency.QUARTERLY,
                priority = TaskPriority.HIGH,
                asset = HiddenAsset.GARAGE_DOOR,
            ))
            add(task(
                key = "garage_lube",
                title = "Lubricate Garage Door Hardware",
                description = "Apply silicone-based lubricant to rollers, hinges, springs, and the inside of the tracks. Do NOT use WD-40 — it attracts dirt. Reduces wear and noise.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.LOW,
                cost = 10f,
                seasonMonth = 2, // March
                asset = HiddenAsset.GARAGE_DOOR,
            ))
        }

        // ── POOL ──────────────────────────────────────────────────────────────
        if (HomeFeature.POOL in features) {
            add(task(
                key = "pool_chemistry",
                title = "Test Pool Water Chemistry",
                description = "Test pH (target 7.2–7.6), free chlorine (1–3 ppm), and alkalinity (80–120 ppm). Adjust with chemicals as needed. Imbalanced water damages equipment and is unsafe.",
                frequency = TaskFrequency.MONTHLY,
                priority = TaskPriority.HIGH,
                cost = 20f,
                asset = HiddenAsset.POOL_EQUIPMENT,
            ))
            add(task(
                key = "pool_filter",
                title = "Clean Pool Filter",
                description = "Backwash a sand or DE filter, or rinse a cartridge filter, when pressure rises 8–10 psi above the clean baseline. Clean equipment runs more efficiently.",
                frequency = TaskFrequency.MONTHLY,
                priority = TaskPriority.MEDIUM,
                asset = HiddenAsset.POOL_EQUIPMENT,
            ))
            add(task(
                key = "pool_pump_inspect",
                title = "Inspect Pool Pump & Equipment",
                description = "Check the pump basket for debris. Listen for unusual motor noise. Inspect all seals and fittings for drips. Confirm the auto-timer is functioning correctly.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 200f,
                seasonMonth = 2, // March
                asset = HiddenAsset.POOL_EQUIPMENT,
            ))
        }

        // ── DECK ──────────────────────────────────────────────────────────────
        if (hasDeck) {
            add(task(
                key = "deck_inspect",
                title = "Inspect & Reseal Deck",
                description = "Probe boards with a screwdriver tip — replace immediately if it sinks in. Restain or reseal when water no longer beads on the surface. Inspect ledger board and post bases.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                cost = 150f,
                guideId = "ext_deck",
                seasonMonth = 2, // March
                asset = HiddenAsset.DECK,
            ))
            add(task(
                key = "deck_fasteners",
                title = "Tighten Deck Fasteners & Railings",
                description = "Push firmly on all railing posts — they must not move. Tighten loose screws and replace with exterior-rated deck screws if any are failing.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                guideId = "ext_deck",
                seasonMonth = 2, // March
                asset = HiddenAsset.DECK,
            ))
        }

        // ── YARD ──────────────────────────────────────────────────────────────
        // Yard presence is now purely style-derived (see HouseSceneGeometry.yardBounds)
        // rather than a HomeFeature the user toggles — CONDO is the only style with none.
        if (style != HomeStyle.CONDO) {
            add(task(
                key = "lawn_equipment",
                title = "Service Lawn Equipment",
                description = "Before the mowing season: change oil, replace spark plug, sharpen blade, and replace air filter. A sharp blade produces cleaner cuts and healthier grass.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.LOW,
                cost = 60f,
                seasonMonth = 2, // March
                asset = HiddenAsset.LAWN_EQUIPMENT,
            ))
        }

        // ── Style-specific ────────────────────────────────────────────────────
        if (style != HomeStyle.CONDO) {
            add(task(
                key = "foundation_inspect",
                title = "Inspect Foundation",
                description = "Walk the full perimeter and check for new or widening cracks. Photograph and measure any cracks. Ensure soil grades away from the foundation and gutters discharge well clear.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                guideId = "found_crack",
                seasonMonth = 2, // March
            ))
        }
        if (style == HomeStyle.CONDO) {
            add(task(
                key = "condo_balcony",
                title = "Inspect Balcony Drainage & Railing",
                description = "Ensure the balcony floor drain is clear. Push firmly on the railing — it must not flex or wobble. Check door threshold caulk and waterproofing membrane at the perimeter.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                seasonMonth = 2, // March
                asset = HiddenAsset.BALCONY,
            ))
        }

        // ── Kitchen appliances ────────────────────────────────────────────────
        if (RoomType.KITCHEN in roomTypes) {
            add(task(
                key = "fridge_coils",
                title = "Clean Refrigerator Condenser Coils",
                description = "Pull the fridge away from the wall and vacuum the coils on the back or underneath. Dirty coils force the compressor to work harder, shortening its life.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.MEDIUM,
                guideId = "appl_fridge",
                item = RoomItem.REFRIGERATOR,
            ))
            add(task(
                key = "dishwasher_filter",
                title = "Clean Dishwasher Filter",
                description = "Twist out the filter assembly from the bottom of the tub and rinse under running water. A clogged filter is the most common cause of poor cleaning and odors.",
                frequency = TaskFrequency.MONTHLY,
                priority = TaskPriority.LOW,
                guideId = "appl_dishwasher",
                item = RoomItem.DISHWASHER,
            ))
        }

        // ── Laundry appliances ────────────────────────────────────────────────
        if (RoomType.LAUNDRY in roomTypes) {
            add(task(
                key = "dryer_vent",
                title = "Clean Dryer Exhaust Duct",
                description = "Disconnect the dryer and clean the entire exhaust duct run with a long-reach vent brush kit. Check that the exterior vent cap flapper opens freely. A blocked duct is a leading cause of house fires.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                cost = 30f,
                guideId = "appl_dryer",
                seasonMonth = 8, // September
                item = RoomItem.DRYER,
            ))
            add(task(
                key = "washer_drum",
                title = "Clean Washing Machine Drum",
                description = "Run a hot empty cycle with a washing machine cleaning tablet or 2 cups of white vinegar. Wipe the door gasket dry after every wash to prevent mold.",
                frequency = TaskFrequency.MONTHLY,
                priority = TaskPriority.LOW,
                item = RoomItem.WASHER,
            ))
            add(task(
                key = "washer_hoses",
                title = "Inspect Washer Supply Hoses",
                description = "Check rubber hoses for bulges, cracks, or stiffening. Replace with braided stainless steel hoses every 5–7 years — a burst hose causes significant water damage.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 25f,
                guideId = "plumb_leaking_pipe",
                seasonMonth = 2, // March
                item = RoomItem.WASHER,
            ))
        }

        // ── Car ───────────────────────────────────────────────────────────────
        if (hasCar) {
            if (hasGasCar) add(task(
                key = "car_oil",
                title = "Car: Oil & Filter Change",
                description = "Change the engine oil and filter every 5,000–7,500 miles (full synthetic can stretch longer — check the owner's manual). Reset the oil-life monitor and jot the mileage on the filter with a marker.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.HIGH,
                cost = 70f,
                item = RoomItem.CAR,
            ))
            add(task(
                key = "car_tires",
                title = "Car: Rotate Tires & Check Pressure",
                description = "Rotate tires front-to-back to even out wear and set pressures to the door-jamb sticker (not the number on the tire sidewall). Check tread depth with the penny test — if you can see all of Lincoln's head, replace.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 40f,
                item = RoomItem.CAR,
            ))
            add(task(
                key = "car_fluids",
                title = "Car: Check Battery & Fluids",
                description = "Top up washer fluid; verify coolant and brake fluid sit between the reservoir marks (never open a hot coolant cap). Look for white/green corrosion on the battery terminals and brush it off — batteries last 3–5 years.",
                frequency = TaskFrequency.QUARTERLY,
                priority = TaskPriority.MEDIUM,
                item = RoomItem.CAR,
            ))
            add(task(
                key = "car_service",
                title = "Car: Annual Service & Inspection",
                description = "Book the yearly service: brake pads and rotors, alignment, engine and cabin air filters, wiper blades, and any state inspection or registration renewal that's due.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                cost = 250f,
                seasonMonth = 2, // March
                item = RoomItem.CAR,
            ))
        }

        // ── Boat ──────────────────────────────────────────────────────────────
        if (hasBoat) {
            add(task(
                key = "boat_winterize",
                title = "Boat: Winterize & Cover",
                description = "Before the first freeze: drain the water systems and lower unit, stabilize the fuel and run it through, fog the engine cylinders, pull the battery onto a tender, and strap the cover down tight enough to shed snow.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.HIGH,
                cost = 150f,
                seasonMonth = 9, // October
                item = RoomItem.BOAT,
            ))
            add(task(
                key = "boat_hull",
                title = "Boat: Hull, Trailer & Safety Check",
                description = "Spring launch prep: inspect the hull for blisters and gelcoat cracks, check the prop for dings, grease the trailer wheel bearings and test its lights, and confirm flares, extinguisher, and life jackets haven't expired.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 80f,
                seasonMonth = 3, // April
                item = RoomItem.BOAT,
            ))
        }

        // ── Motorcycle ────────────────────────────────────────────────────────
        if (hasMotorcycle) {
            if (hasGasMotorcycle) add(task(
                key = "moto_oil_chain",
                title = "Motorcycle: Oil & Chain Service",
                description = "Change the oil and filter every 3,000–5,000 miles, then clean, lube, and tension the chain (check slack with the bike on its stand — about 1–1.5 inches of play). A dry or sagging chain wears sprockets fast.",
                frequency = TaskFrequency.BIANNUAL,
                priority = TaskPriority.HIGH,
                cost = 60f,
                item = RoomItem.MOTORCYCLE,
            ))
            add(task(
                key = "moto_season",
                title = "Motorcycle: Season Prep & Inspection",
                description = "Riding-season checkup: charge or replace the battery after winter storage (a tender prevents this), check tire pressure and date codes (rubber ages out around 5–6 years), bleed brakes if the fluid is dark, and renew inspection/registration.",
                frequency = TaskFrequency.ANNUAL,
                priority = TaskPriority.MEDIUM,
                cost = 100f,
                seasonMonth = 2, // March
                item = RoomItem.MOTORCYCLE,
            ))
        }
        }
        return all.filterNot { it.relatedHiddenAsset?.name in removedInstances }
    }

    private fun task(
        key: String, title: String, description: String,
        frequency: TaskFrequency, priority: TaskPriority,
        cost: Float = 0f, guideId: String? = null,
        seasonMonth: Int? = null,
        item: RoomItem? = null, asset: HiddenAsset? = null, system: HomeSystem? = null,
    ) = MaintenanceTask(key, title, description, frequency, priority, cost, guideId,
        seasonMonth, item, asset, system)
}
