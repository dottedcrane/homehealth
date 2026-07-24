package com.homerenderer.db

import com.homerenderer.model.FeatureSide
import com.homerenderer.model.FloorLayout
import com.homerenderer.model.HomeFeature
import com.homerenderer.model.HomeSystem
import com.homerenderer.model.PlacedDeck
import com.homerenderer.model.PlacedItem
import com.homerenderer.model.RoomItem
import com.homerenderer.model.RoomPlacement
import com.homerenderer.model.RoomType
import com.homerenderer.model.WallKey
import com.homerenderer.model.WallMode
import com.homerenderer.util.randomUUIDString
import org.json.JSONArray
import org.json.JSONObject

object HomeStateSerialization {

    fun serializeFeaturePlacements(map: Map<HomeFeature, FeatureSide>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k.name, v.name) }
        return obj.toString()
    }

    fun deserializeFeaturePlacements(json: String): Map<HomeFeature, FeatureSide> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associate { k ->
                HomeFeature.valueOf(k) to FeatureSide.valueOf(obj.getString(k))
            }
                // YARD's own toggle is gone — presence is purely style-derived now (see
                // HouseSceneGeometry.yardBounds). Drop any stale entry from an older save; the
                // next autosave stops writing it back.
                .filterKeys { it != HomeFeature.YARD }
        } catch (e: Exception) { emptyMap() }
    }

    // Live drag offset for a freely-placed exterior feature (garage/deck/pool), relative to
    // its default anchor for the current side — same shape and precedent as itemOffsets, kept
    // as its own map rather than folded into featurePlacements so the "which side" decision
    // and the "how far dragged from that side's default spot" decision stay independent.
    fun serializeFeatureOffsets(offsets: Map<HomeFeature, Pair<Float, Float>>): String {
        val obj = JSONObject()
        offsets.forEach { (feature, off) ->
            obj.put(feature.name, JSONObject().apply { put("dx", off.first); put("dz", off.second) })
        }
        return obj.toString()
    }

    fun deserializeFeatureOffsets(json: String): Map<HomeFeature, Pair<Float, Float>> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { k ->
                runCatching {
                    val o = obj.getJSONObject(k)
                    HomeFeature.valueOf(k) to (o.getDouble("dx").toFloat() to o.getDouble("dz").toFloat())
                }.getOrNull()
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun serializePlacedDecks(decks: List<PlacedDeck>): String {
        val arr = JSONArray()
        decks.forEach { deck ->
            arr.put(JSONObject().apply {
                put("id", deck.id)
                put("side", deck.side.name)
                put("along", deck.along)
            })
        }
        return arr.toString()
    }

    fun deserializePlacedDecks(json: String): List<PlacedDeck> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    PlacedDeck(
                        id    = o.getString("id"),
                        side  = FeatureSide.valueOf(o.getString("side")),
                        along = o.getDouble("along").toFloat(),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun serializeHomeSystems(set: Set<HomeSystem>): String {
        val arr = JSONArray()
        set.forEach { arr.put(it.name) }
        return arr.toString()
    }

    fun deserializeHomeSystems(json: String): Set<HomeSystem> {
        if (json.isBlank()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { runCatching { HomeSystem.valueOf(arr.getString(it)) }.getOrNull() }.toSet()
        } catch (e: Exception) { emptySet() }
    }

    fun serializeFloorLayout(layout: FloorLayout): String {
        val root = JSONObject()
        root.put("cols", layout.gridCols)
        root.put("rows", layout.gridRows)
        root.put("cellW", layout.cellW)
        root.put("cellD", layout.cellD)
        val roomsArr = JSONArray()
        layout.rooms.forEach { r ->
            roomsArr.put(JSONObject().apply {
                // id MUST be persisted: removedInstances keys and PlacedItem.placementId
                // both reference it. Regenerating ids on load orphans all of that state.
                put("id", r.id)
                put("type", r.type.name)
                put("col", r.col); put("row", r.row)
                put("cs", r.colSpan); put("rs", r.rowSpan)
                put("fl", r.floor)
            })
        }
        root.put("rooms", roomsArr)
        val wallArr = JSONArray()
        layout.wallOverrides.forEach { (k, v) ->
            wallArr.put(JSONObject().apply {
                put("v", k.vertical); put("b", k.boundary)
                put("i", k.index); put("fl", k.floor)
                put("m", v.name)
            })
        }
        root.put("walls", wallArr)
        return root.toString()
    }

    fun deserializeFloorLayout(json: String): FloorLayout {
        if (json.isBlank()) return FloorLayout()
        return try {
            val root = JSONObject(json)
            val cols  = root.optInt("cols", 7)
            val rows  = root.optInt("rows", 6)
            val cellW = root.optDouble("cellW", 1.5).toFloat()
            val cellD = root.optDouble("cellD", 1.5).toFloat()
            val roomsArr = root.optJSONArray("rooms") ?: JSONArray()
            val rooms = (0 until roomsArr.length()).mapNotNull { i ->
                try {
                    val obj = roomsArr.getJSONObject(i)
                    RoomPlacement(
                        // Layouts saved before ids were serialized fall back to a fresh
                        // UUID (one final id churn); the next save persists it for good.
                        id      = obj.optString("id").ifEmpty { randomUUIDString() },
                        type    = RoomType.valueOf(obj.getString("type")),
                        col     = obj.getInt("col"),
                        row     = obj.getInt("row"),
                        colSpan = obj.optInt("cs", 1),
                        rowSpan = obj.optInt("rs", 1),
                        floor   = obj.optInt("fl", 0),
                    )
                } catch (e: Exception) { null }
            }
            val wallsArr = root.optJSONArray("walls") ?: JSONArray()
            val wallOverrides = (0 until wallsArr.length()).mapNotNull { i ->
                try {
                    val obj = wallsArr.getJSONObject(i)
                    WallKey(
                        vertical = obj.getBoolean("v"),
                        boundary = obj.getInt("b"),
                        index    = obj.getInt("i"),
                        floor    = obj.optInt("fl", 0),
                    ) to WallMode.valueOf(obj.getString("m"))
                } catch (e: Exception) { null }
            }.toMap()
            FloorLayout(
                gridCols      = cols,
                gridRows      = rows,
                cellW         = cellW,
                cellD         = cellD,
                rooms         = rooms,
                wallOverrides = wallOverrides,
            )
        } catch (e: Exception) { FloorLayout() }
    }

    // Bumped when a redesign moves items' default anchors, invalidating saved offsets
    // (offsets are deltas FROM the default position, so they only make sense against
    // the layout they were saved under). v2 = kitchen slot layout (range unit + bays).
    // v3 = furniture became drag-and-drop placed items (no longer auto-populated
    // defaults) — saves below v3 get their default furniture converted to PlacedItems
    // once, on load (see the migration in MainActivity's restore effect).
    // v4 = every portable appliance — washer/dryer/water heater and the whole kitchen
    // (counter segments, stove, sink, dishwasher, microwave, oven, hood, fridge) —
    // likewise became placed items, so they can be moved/rotated/flipped; appliance
    // records and documents are re-keyed to the new placed ids during the migration.
    // v5 = the kitchen re-seeded as one flush run along the left (-X) wall — fridge,
    // counter(s), stove with the oven tucked under its cooktop, sink, dishwasher, all
    // facing the room; saved kitchen pieces are snapped onto the run's slots on load
    // (see FloorLayout.relocateKitchenAppliancesToLeftWall).
    // v6 = gym equipment (treadmill, exercise bike, weights) became placed items like
    // every other portable — previously the only category still sharing this flat,
    // per-room-type-blind offsets map, so a second gym's equipment moved in lockstep
    // with the first's.
    private const val ITEM_OFFSETS_VERSION = 6

    /** The `_v` marker the given itemOffsets JSON was saved under — drives one-time load
     *  migrations. Blank/broken JSON reports current (nothing to migrate). */
    fun itemOffsetsVersion(json: String): Int =
        if (json.isBlank()) ITEM_OFFSETS_VERSION
        else try { JSONObject(json).optInt("_v", 1) } catch (e: Exception) { ITEM_OFFSETS_VERSION }

    fun serializeItemOffsets(offsets: Map<RoomItem, Pair<Float, Float>>): String {
        val obj = JSONObject()
        obj.put("_v", ITEM_OFFSETS_VERSION)
        offsets.forEach { (item, off) ->
            obj.put(item.name, JSONObject().apply { put("dx", off.first); put("dz", off.second) })
        }
        return obj.toString()
    }

    fun deserializeItemOffsets(json: String): Map<RoomItem, Pair<Float, Float>> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            // Offsets saved before the kitchen redesign (v2) are calibrated against the
            // old appliance anchors — drop the kitchen ones once (defaults apply instead).
            val preKitchen = obj.optInt("_v", 1) < 2
            obj.keys().asSequence().mapNotNull { k ->
                runCatching {
                    val o = obj.getJSONObject(k)
                    RoomItem.valueOf(k) to (o.getDouble("dx").toFloat() to o.getDouble("dz").toFloat())
                }.getOrNull()
            }.filterNot { (item, _) -> preKitchen && item.room == RoomType.KITCHEN }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    fun serializePlacedItems(items: List<PlacedItem>): String {
        val arr = JSONArray()
        items.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("placementId", p.placementId)
                put("item", p.item.name)
                put("dx", p.dx); put("dz", p.dz)
                if (p.rotDeg != 0) put("rot", p.rotDeg)
                if (p.flipX) put("fx", true)
                if (p.flipZ) put("fz", true)
                if (p.tracked) put("tr", true)
                if (p.colorIndex != 0) put("ci", p.colorIndex)
                if (p.electric) put("ev", true)
            })
        }
        return arr.toString()
    }

    fun deserializePlacedItems(json: String): List<PlacedItem> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    PlacedItem(
                        id          = o.getString("id"),
                        placementId = o.getString("placementId"),
                        item        = RoomItem.valueOf(o.getString("item")),
                        dx          = o.getDouble("dx").toFloat(),
                        dz          = o.getDouble("dz").toFloat(),
                        rotDeg      = o.optInt("rot", 0),
                        flipX       = o.optBoolean("fx", false),
                        flipZ       = o.optBoolean("fz", false),
                        tracked     = o.optBoolean("tr", false),
                        colorIndex  = o.optInt("ci", 0),
                        electric    = o.optBoolean("ev", false),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun serializeRemovedInstances(keys: Set<String>): String {
        val arr = JSONArray()
        keys.forEach { arr.put(it) }
        return arr.toString()
    }

    fun deserializeRemovedInstances(json: String): Set<String> {
        if (json.isBlank()) return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }
}
