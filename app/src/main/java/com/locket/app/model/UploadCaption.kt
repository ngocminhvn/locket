package com.locket.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonParser

internal class UploadCaption private constructor(val caption: String, val overlays: JsonArray) {
    companion object {
        fun validate(caption: String, raw: String): UploadCaption {
            require(raw.length <= 65536 && raw.toByteArray(Charsets.UTF_8).size <= 65536) { "Overlay quá lớn" }
            val overlays = JsonParser.parseString(raw).asJsonArray
            require(overlays.size() <= 1) { "Mỗi khoảnh khắc chỉ gửi một overlay" }
            if (overlays.size() == 0) return UploadCaption(caption, overlays)

            val overlay = overlays[0].asJsonObject
            val data = CaptionOverlays.obj(overlay, "data")
            val type = CaptionOverlays.string(data, "type")
            require(CaptionOverlays.string(overlay, "overlay_type") == "caption" && CaptionOverlays.isSupported(type)) {
                "Loại overlay chưa được hỗ trợ"
            }
            if (type == "link") {
                val payload = CaptionOverlays.obj(data, "payload")
                require(LinkCaption.create(CaptionOverlays.string(data, "text"), CaptionOverlays.string(payload, "url")) != null) { "Liên kết không hợp lệ" }
            }
            if (type == "review") {
                val payload = CaptionOverlays.obj(data, "payload")
                val rating = payload?.get("rating")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.asDouble
                val comment = CaptionOverlays.string(payload, "comment")
                require(rating != null && rating in 0.5..5.0 && (rating * 2.0) % 1.0 == 0.0 &&
                    comment.codePointCount(0, comment.length) <= 36) { "Đánh giá không hợp lệ" }
            }
            return UploadCaption(caption.takeIf { type == "standard" }.orEmpty(), overlays)
        }
    }
}
