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

    private val supported = setOf(
        "standard", "review", "weather", "time", "location", "streak", "static_content",
        "music", "star_sign", "status", "link"
    )
    private val cache = object : LinkedHashMap<String, CaptionOverlay?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CaptionOverlay?>) = size > 128
    }

    internal fun fromRaw(json: String?): CaptionOverlay? {
        if (json.isNullOrBlank() || json == "[]" || json.length > 65536) return null
        synchronized(cache) {
            if (cache.containsKey(json)) return cache[json]
        }
        val parsed = decode(first(json))
        synchronized(cache) { cache[json] = parsed }
        return parsed
    }

    internal fun cacheDecoded(json: String, caption: CaptionOverlay?) {
        if (json != "[]" && json.length <= 65536) synchronized(cache) { cache[json] = caption }
    }

    internal fun first(json: String?): JsonObject? {
        if (json.isNullOrBlank() || json.length > 65536 || json.toByteArray(Charsets.UTF_8).size > 65536) return null
        return runCatching { firstCaption(JsonParser.parseString(json).asJsonArray) }.getOrNull()
    }

    internal fun fromPayload(raw: String, overlays: JsonArray?): CaptionOverlay? {
        if (overlays == null || raw.length > 65536 || raw.toByteArray(Charsets.UTF_8).size > 65536) return null
        return decode(firstCaption(overlays)).also { cacheDecoded(raw, it) }
    }

    private fun firstCaption(overlays: JsonArray): JsonObject? =
        overlays.firstNotNullOfOrNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.takeIf {
                string(it, "overlay_type") == "caption" && isSupported(string(obj(it, "data"), "type"))
            }
        }

    internal fun isSupported(kind: String) = kind in supported

    internal fun decode(overlay: JsonObject?): CaptionOverlay? {
        val data = obj(overlay, "data") ?: return null
        val kind = string(data, "type")
        if (!isSupported(kind)) return null
        val payload = obj(data, "payload")
        val icon = obj(data, "icon")
        val foreground = color(string(data, "text_color"), -1)
        val colors = obj(data, "background")?.get("colors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.takeIf { it.size() <= 16 && it.all { entry -> entry.isJsonPrimitive && entry.asJsonPrimitive.isString } }
            ?.map { color(it.asString, 0x99000000.toInt()) }.orEmpty()
        return CaptionOverlay(
            kind, boundedText(data, "text"), boundedText(overlay, "alt_text"), foreground, colors,
            (number(data, "max_lines") ?: 4.0).coerceIn(1.0, 4.0).toInt(),
            boundedText(icon, "type"), boundedText(icon, "data"), color(string(icon, "color"), foreground),
            (number(payload, "rating") ?: 0.0).coerceIn(0.0, 5.0).toFloat(),
            boundedText(payload, "comment"), boundedText(payload, "spotify_url")
        )
    }

    private fun boundedText(value: JsonObject?, key: String): String =
        string(value, key).takeIf { it.length <= 4096 && it.toByteArray(Charsets.UTF_8).size <= 4096 }.orEmpty()

    private fun number(value: JsonObject?, key: String): Double? =
        string(value, key).toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun color(raw: String, fallback: Int): Int {
        val hex = raw.removePrefix("#")
        if (hex.length !in listOf(6, 8) || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return fallback
        val value = hex.toLong(16).toInt()
        return if (hex.length == 6) value or 0xff000000.toInt() else Integer.rotateRight(value, 8)
    }

    fun label(moment: Moment): String {
        val overlay = moment.overlay ?: return moment.caption
        val text = overlay.text.ifBlank { overlay.altText }
        val icon = overlay.iconData.takeIf { overlay.iconKind == "emoji" }.orEmpty()
        return when {
            icon.isBlank() -> text.ifBlank { moment.caption }
            text.isBlank() -> icon
            else -> "$icon $text"
        }
    }
}

internal data class CaptionOverlay(
    val kind: String,
    val text: String,
    val altText: String,
    val foreground: Int,
    val colors: List<Int>,
    val maxLines: Int,
    val iconKind: String,
    val iconData: String,
    val iconColor: Int,
    val rating: Float,
    val comment: String,
    val spotifyUrl: String
)
