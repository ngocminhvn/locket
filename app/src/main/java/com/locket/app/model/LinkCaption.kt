package com.locket.app.model

import java.net.URI
import com.google.gson.JsonObject

internal data class LinkCaption private constructor(val title: String, val url: String) {
    fun overlay() = CaptionOverlays.make("link", title, payload = JsonObject().apply { addProperty("url", url) })
    companion object {
        fun validUrl(raw: String): String? {
            if (raw.isBlank() || raw.length > 4096 || raw.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return null
            return raw
        }
        fun create(title: String, url: String): LinkCaption? {
            val name = title.trim().takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
            return LinkCaption(name, validUrl(url.trim()) ?: return null)
        }
    }
}
