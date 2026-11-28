package com.locket.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonNull

object CaptionOverlays {
    fun icon(type: String, data: String, color: String? = null) = JsonObject().apply {
        addProperty("type", type)
        addProperty("data", data)
        color?.let { addProperty("color", it) }
    }

    fun make(type: String, text: String, id: String = "caption:$type",
        colors: List<String> = emptyList(), textColor: String = "#FFFFFFE6",
        blur: String? = "regular", maxLines: Int = 1,
        icon: JsonObject? = null, payload: JsonObject? = null): JsonObject = JsonObject().apply {
        addProperty("overlay_id", id)
        addProperty("overlay_type", "caption")
        addProperty("alt_text", text)
        add("data", JsonObject().apply {
            addProperty("type", type)
            addProperty("text", text)
            addProperty("text_color", textColor)
            addProperty("max_lines", maxLines)
            add("background", JsonObject().apply {
                add("colors", JsonArray().apply { colors.forEach { add(it) } })
                blur?.let { addProperty("material_blur", it) }
            })
            add("icon", icon ?: JsonNull.INSTANCE)
            add("payload", payload ?: JsonNull.INSTANCE)
        })
    }

    fun string(obj: JsonObject?, key: String): String = runCatching {
        obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }.getOrDefault("")
    fun obj(obj: JsonObject?, key: String): JsonObject? =
        obj?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    fun first(json: String?): JsonObject? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            JsonParser.parseString(json).asJsonArray.firstOrNull { el ->
                el.isJsonObject && string(el.asJsonObject, "overlay_type") == "caption" &&
                    string(obj(el.asJsonObject, "data"), "type") in setOf(
                        "standard", "review", "time", "location", "weather", "streak", "static_content", "music", "star_sign", "status", "link"
                    )
            }?.asJsonObject
        }.getOrNull()
    }

    fun data(moment: Moment) = obj(first(moment.overlaysJson), "data")
    fun label(moment: Moment): String {
        val d = data(moment) ?: return moment.caption
        val value = string(d, "text").ifBlank { string(first(moment.overlaysJson), "alt_text") }
        val icon = obj(d, "icon")
        val prefix = if (string(icon, "type") == "emoji") string(icon, "data") else ""
        return listOf(prefix, value).filter { it.isNotBlank() }.joinToString(" ").ifBlank { moment.caption }
    }
}
