package com.aritr.rova.ui.library

import org.json.JSONObject

/**
 * ADR-0030 — pure (de)serialize of the sidecar `stableKey → entry` map.
 * Uses org.json (real impl on testImplementation, so this is JVM-testable).
 * Tolerant: any parse failure yields an empty map (the sidecar is best-effort
 * cosmetic metadata — a corrupt file must never crash the Library).
 */
object LibraryMetadataCodec {

    private const val FAVORITE = "favorite"
    private const val CUSTOM_TITLE = "customTitle"
    private const val LAST_PLAYED_AT = "lastPlayedAt"
    private const val POSITION_MS = "positionMs"

    fun toJson(map: Map<String, LibraryMetadataEntry>): String {
        val root = JSONObject()
        for ((key, e) in map) {
            if (e.isEmpty()) continue
            val obj = JSONObject()
            if (e.favorite) obj.put(FAVORITE, true)
            if (e.customTitle != null) obj.put(CUSTOM_TITLE, e.customTitle)
            if (e.lastPlayedAt != null) obj.put(LAST_PLAYED_AT, e.lastPlayedAt)
            e.positionMs?.let { if (it > 0L) obj.put(POSITION_MS, it) }
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
                        positionMs = if (obj.has(POSITION_MS)) obj.optLong(POSITION_MS).takeIf { it > 0L } else null,
                    )
                    if (!entry.isEmpty()) put(key, entry)
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Drop empty entries and any key absent from [keep] (deleted rows). */
    fun prune(map: Map<String, LibraryMetadataEntry>, keep: Set<String>): Map<String, LibraryMetadataEntry> =
        map.filter { (k, v) -> k in keep && !v.isEmpty() }
}
