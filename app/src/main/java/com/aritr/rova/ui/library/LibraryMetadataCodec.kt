package com.aritr.rova.ui.library

import org.json.JSONObject

object LibraryMetadataCodec {
    private const val FAVORITE = "favorite"
    private const val CUSTOM_TITLE = "customTitle"
    private const val LAST_PLAYED_AT = "lastPlayedAt"
    private const val POSITIONS_BY_SIDE = "positionsBySide"
    private const val LEGACY_POSITION_MS = "positionMs" // read-only back-compat (#127 interim)

    fun toJson(map: Map<String, LibraryMetadataEntry>): String {
        val root = JSONObject()
        for ((key, e) in map) {
            if (e.isEmpty()) continue
            val obj = JSONObject()
            if (e.favorite) obj.put(FAVORITE, true)
            if (e.customTitle != null) obj.put(CUSTOM_TITLE, e.customTitle)
            if (e.lastPlayedAt != null) obj.put(LAST_PLAYED_AT, e.lastPlayedAt)
            val positive = e.positionsBySide.filterValues { it > 0L }
            if (positive.isNotEmpty()) {
                val pos = JSONObject()
                for ((slot, v) in positive) pos.put(slot, v)
                obj.put(POSITIONS_BY_SIDE, pos)
            }
            root.put(key, obj)
        }
        return root.toString()
    }

    fun fromJson(text: String): Map<String, LibraryMetadataEntry> {
        if (text.isBlank()) return emptyMap()
        return try {
            val root = JSONObject(text)
            buildMap {
                for (key in root.keys()) {
                    val obj = root.optJSONObject(key) ?: continue
                    val entry = LibraryMetadataEntry(
                        favorite = obj.optBoolean(FAVORITE, false),
                        customTitle = if (obj.has(CUSTOM_TITLE) && !obj.isNull(CUSTOM_TITLE)) obj.getString(CUSTOM_TITLE) else null,
                        lastPlayedAt = if (obj.has(LAST_PLAYED_AT) && !obj.isNull(LAST_PLAYED_AT)) obj.getLong(LAST_PLAYED_AT) else null,
                        positionsBySide = readPositions(obj),
                    )
                    if (!entry.isEmpty()) put(key, entry)
                }
            }
        } catch (e: Exception) {
            emptyMap() // Tolerant: corrupt sidecar never crashes the Library.
        }
    }

    private fun readPositions(obj: JSONObject): Map<String, Long> {
        val nested = obj.optJSONObject(POSITIONS_BY_SIDE)
        if (nested != null) {
            val out = LinkedHashMap<String, Long>()
            for (slot in nested.keys()) {
                val v = nested.optLong(slot)
                if (v > 0L) out[slot] = v
            }
            return out
        }
        // Back-compat: old flat positionMs → single slot.
        if (obj.has(LEGACY_POSITION_MS)) {
            val v = obj.optLong(LEGACY_POSITION_MS)
            if (v > 0L) return mapOf("" to v)
        }
        return emptyMap()
    }

    fun prune(map: Map<String, LibraryMetadataEntry>, keep: Set<String>): Map<String, LibraryMetadataEntry> =
        map.filter { (k, v) -> k in keep && !v.isEmpty() }
}
