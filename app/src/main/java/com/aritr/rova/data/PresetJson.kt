package com.aritr.rova.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.absoluteValue

/**
 * ADR-0026 — pure codec for the USER CUSTOM presets persisted in
 * `RovaSettings.customPresetsJson`. Built-ins are never written here.
 *
 * Writer emits the v2 envelope: {presetSchemaVersion, presets:[...]}.
 * Reader is additive-tolerant and branches on the root token:
 *  - legacy root JSONArray (today's "[]" / array-of-objects)
 *  - v2 root JSONObject envelope
 * Missing id -> stable derived `custom.<hash>`. A single malformed object is
 * skipped (not the whole list). Any id with the reserved `builtin.` prefix is
 * re-namespaced to `custom.*` so a custom can never impersonate a built-in.
 */
object PresetJson {

    private const val VERSION = 3

    fun encode(customs: List<RovaPreset>): String {
        val arr = JSONArray()
        customs.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", ensureCustomId(p.id, p))
                put("name", p.name)
                put("duration", p.duration)
                put("interval", p.intervalSeconds) // key name kept; meaning now seconds (presetSchemaVersion=3)
                put("loopCount", p.loopCount)
                put("resolution", p.resolution)
            })
        }
        return JSONObject().apply {
            put("presetSchemaVersion", VERSION)
            put("presets", arr)
        }.toString()
    }

    fun decode(raw: String): List<RovaPreset> {
        val trimmed = raw.trim()
        val version: Int
        val array: JSONArray = try {
            when {
                trimmed.startsWith("[") -> { version = 1; JSONArray(trimmed) }
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    version = root.optInt("presetSchemaVersion", 1)
                    root.optJSONArray("presets") ?: JSONArray()
                }
                else -> return emptyList()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val toSeconds: (Int) -> Int = if (version >= 3) { v -> v } else { v -> v * 60 } // v<3 stored minutes
        val out = ArrayList<RovaPreset>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val preset = try {
                RovaPreset(
                    name = obj.getString("name"),
                    duration = obj.getInt("duration"),
                    intervalSeconds = toSeconds(obj.getInt("interval")),
                    loopCount = obj.getInt("loopCount"),
                    resolution = obj.getString("resolution"),
                    id = "",
                    isBuiltIn = false,
                )
            } catch (e: Exception) {
                continue // skip one malformed object, keep the rest
            }
            out.add(preset.copy(id = ensureCustomId(obj.optString("id", ""), preset)))
        }
        return out
    }

    /** A custom id that never collides with / impersonates a `builtin.*` id. */
    private fun ensureCustomId(rawId: String, p: RovaPreset): String {
        if (rawId.isNotEmpty() && rawId.startsWith("custom.")) return rawId
        // Missing, blank, or reserved `builtin.` -> derive a stable custom id.
        val key = "${p.name}|${p.duration}|${p.intervalSeconds}|${p.loopCount}|${p.resolution}"
        // toLong() before absoluteValue: Int.MIN_VALUE.absoluteValue overflows to a
        // negative Int; widening to Long first keeps the derived id non-negative (review).
        return "custom." + key.hashCode().toLong().absoluteValue.toString(16)
    }
}
