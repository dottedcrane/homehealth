package com.homehealth.db

import com.homehealth.data.TaskFrequency
import com.homehealth.model.CustomAppliance
import com.homehealth.model.CustomTask
import com.homehealth.model.FeatureSide
import com.homehealth.model.FloorLayout
import com.homehealth.model.HomeFeature
import com.homehealth.model.PlacedDeck
import com.homehealth.model.PlacedItem
import com.homehealth.model.PlacedYardDecor
import com.homehealth.model.RoomItem
import com.homehealth.model.RoomPlacement
import com.homehealth.model.RoomType
import com.homehealth.model.WallKey
import com.homehealth.model.WallMode
import com.homehealth.model.YardDecorKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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

    // Single-slot HVAC placement — side + along-wall offset, same shape as
    // serializeFeatureOffsets' per-entry object, but standalone since HVAC isn't a HomeFeature.
    fun serializeHvacPlacement(placement: Pair<FeatureSide, Float>?): String {
        if (placement == null) return ""
        val (side, along) = placement
        return JSONObject().apply { put("side", side.name); put("along", along) }.toString()
    }

    fun deserializeHvacPlacement(json: String): Pair<FeatureSide, Float>? {
        if (json.isBlank()) return null
        return try {
            val o = JSONObject(json)
            FeatureSide.valueOf(o.getString("side")) to o.getDouble("along").toFloat()
        } catch (e: Exception) { null }
    }

    // Single-slot solar array placement — onLeftSlope + along-ridge offset, same shape as
    // serializeHvacPlacement.
    fun serializeSolarArrayPlacement(placement: Pair<Boolean, Float>?): String {
        if (placement == null) return ""
        val (onLeftSlope, along) = placement
        return JSONObject().apply { put("onLeftSlope", onLeftSlope); put("along", along) }.toString()
    }

    fun deserializeSolarArrayPlacement(json: String): Pair<Boolean, Float>? {
        if (json.isBlank()) return null
        return try {
            val o = JSONObject(json)
            o.getBoolean("onLeftSlope") to o.getDouble("along").toFloat()
        } catch (e: Exception) { null }
    }

    // Single-slot EV Battery placement — same shape as serializeHvacPlacement.
    fun serializeEvBatteryPlacement(placement: Pair<FeatureSide, Float>?): String {
        if (placement == null) return ""
        val (side, along) = placement
        return JSONObject().apply { put("side", side.name); put("along", along) }.toString()
    }

    fun deserializeEvBatteryPlacement(json: String): Pair<FeatureSide, Float>? {
        if (json.isBlank()) return null
        return try {
            val o = JSONObject(json)
            FeatureSide.valueOf(o.getString("side")) to o.getDouble("along").toFloat()
        } catch (e: Exception) { null }
    }

    fun serializePlacedYardDecor(items: List<PlacedYardDecor>): String {
        val arr = JSONArray()
        items.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("kind", d.kind.name)
                put("dx", d.dx); put("dz", d.dz)
            })
        }
        return arr.toString()
    }

    fun deserializePlacedYardDecor(json: String): List<PlacedYardDecor> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    PlacedYardDecor(
                        id   = o.getString("id"),
                        kind = YardDecorKind.valueOf(o.getString("kind")),
                        dx   = o.getDouble("dx").toFloat(),
                        dz   = o.getDouble("dz").toFloat(),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun serializeCustomAppliances(items: List<CustomAppliance>): String {
        val arr = JSONArray()
        items.forEach { ca ->
            arr.put(JSONObject().apply {
                put("id", ca.id)
                put("name", ca.name)
            })
        }
        return arr.toString()
    }

    fun deserializeCustomAppliances(json: String): List<CustomAppliance> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    CustomAppliance(id = o.getString("id"), name = o.getString("name"))
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
    }

    fun serializeCustomTasks(items: List<CustomTask>): String {
        val arr = JSONArray()
        items.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                // On-disk key name kept as "applianceId" — targetKey broadened its meaning (any
                // MaintenanceTarget.key, not just a CustomAppliance.id) but not its JSON shape,
                // so no schema/version bump is needed for the rename.
                put("applianceId", t.targetKey)
                put("title", t.title)
                put("frequency", t.frequency.name)
            })
        }
        return arr.toString()
    }

    fun deserializeCustomTasks(json: String): List<CustomTask> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    CustomTask(
                        id        = o.getString("id"),
                        targetKey = o.getString("applianceId"),
                        title     = o.getString("title"),
                        frequency = TaskFrequency.valueOf(o.getString("frequency")),
                    )
                }.getOrNull()
            }
        } catch (e: Exception) { emptyList() }
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
                        id      = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
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

    fun serializeItemOffsets(offsets: Map<RoomItem, Pair<Float, Float>>): String {
        val obj = JSONObject()
        offsets.forEach { (item, off) ->
            obj.put(item.name, JSONObject().apply { put("dx", off.first); put("dz", off.second) })
        }
        return obj.toString()
    }

    fun deserializeItemOffsets(json: String): Map<RoomItem, Pair<Float, Float>> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { k ->
                runCatching {
                    val o = obj.getJSONObject(k)
                    RoomItem.valueOf(k) to (o.getDouble("dx").toFloat() to o.getDouble("dz").toFloat())
                }.getOrNull()
            }.toMap()
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
