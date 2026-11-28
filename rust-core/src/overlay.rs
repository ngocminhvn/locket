use serde_json::Value;

const MAX_OVERLAY_BYTES: usize = 64 * 1024;
const MAX_TEXT_BYTES: usize = 4096;
const MAX_COLORS: usize = 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptionOverlay {
    pub(crate) kind: String,
    pub(crate) text: String,
    pub(crate) alt_text: String,
    pub(crate) foreground: u32,
    pub(crate) colors: Vec<u32>,
    pub(crate) max_lines: u8,
    pub(crate) icon_kind: String,
    pub(crate) icon_data: String,
    pub(crate) icon_color: u32,
    pub(crate) rating_bits: u32,
    pub(crate) comment: String,
    pub(crate) spotify_url: String,
}

pub(crate) fn decode(value: Option<&Value>) -> (String, Option<CaptionOverlay>) {
    let Some(value) = value.filter(|v| v.is_array()) else {
        return ("[]".to_owned(), None);
    };
    let raw = value.to_string();
    let caption = (raw.len() <= MAX_OVERLAY_BYTES)
        .then(|| value.as_array()?.iter().find_map(parse_caption))
        .flatten();
    (raw, caption)
}

fn text(value: &Value) -> String {
    let result = match value {
        Value::String(s) => s.clone(),
        Value::Number(_) | Value::Bool(_) => value.to_string(),
        _ => String::new(),
    };
    if result.len() <= MAX_TEXT_BYTES {
        result
    } else {
        String::new()
    }
}

fn number(value: &Value) -> Option<f64> {
    value
        .as_f64()
        .or_else(|| value.as_str()?.parse().ok())
        .filter(|x| x.is_finite())
}

fn color(value: &Value, fallback: u32) -> u32 {
    let Some(raw) = value.as_str() else {
        return fallback;
    };
    let hex = raw.strip_prefix('#').unwrap_or(raw);
    if !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return fallback;
    }
    match (hex.len(), u32::from_str_radix(hex, 16)) {
        (6, Ok(rgb)) => 0xff00_0000 | rgb,
        (8, Ok(rgba)) => rgba.rotate_right(8),
        _ => fallback,
    }
}

fn parse_caption(value: &Value) -> Option<CaptionOverlay> {
    if value["overlay_type"].as_str()? != "caption" {
        return None;
    }
    let data = &value["data"];
    let kind = data["type"].as_str()?;
    if !matches!(
        kind,
        "standard" | "review" | "weather" | "time" | "location" | "streak"
            | "static_content" | "music" | "star_sign" | "status" | "link"
    ) {
        return None;
    }
    let icon = &data["icon"];
    let payload = &data["payload"];
    let rating = number(&payload["rating"]).unwrap_or_default().clamp(0.0, 5.0) as f32;
    let foreground = color(&data["text_color"], 0xffff_ffff);
    let colors = data["background"]["colors"]
        .as_array()
        .filter(|values| values.len() <= MAX_COLORS && values.iter().all(Value::is_string))
        .map(|values| values.iter().map(|v| color(v, 0x9900_0000)).collect())
        .unwrap_or_default();
    Some(CaptionOverlay {
        kind: kind.to_owned(),
        text: text(&data["text"]),
        alt_text: text(&value["alt_text"]),
        foreground,
        colors,
        max_lines: number(&data["max_lines"]).unwrap_or(4.0).clamp(1.0, 4.0) as u8,
        icon_kind: text(&icon["type"]),
        icon_data: text(&icon["data"]),
        icon_color: color(&icon["color"], foreground),
        rating_bits: rating.to_bits(),
        comment: text(&payload["comment"]),
        spotify_url: text(&payload["spotify_url"]),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn selects_known_caption_and_preserves_raw_fields() {
        let value = json!([null, {"overlay_type":"caption","data":{"type":"poll"}},
            {"overlay_type":"caption","alt_text":"🍜","data":{"type":"review",
                "text_color":"#11223380","background":{"colors":["#AA0011","#44556622"]},
                "payload":{"rating":4.5,"comment":"Ngon 🍜","future":{"list":[null,42]}}}}]);
        let (raw, caption) = decode(Some(&value));
        assert_eq!(serde_json::from_str::<Value>(&raw).unwrap(), value);
        let caption = caption.unwrap();
        assert_eq!(caption.kind, "review");
        assert_eq!(caption.foreground, 0x80112233);
        assert_eq!(caption.colors, [0xffaa0011, 0x22445566]);
        assert_eq!(f32::from_bits(caption.rating_bits), 4.5);
        assert_eq!(caption.comment, "Ngon 🍜");
    }

    #[test]
    fn null_and_unknown_have_no_render_caption() {
        for value in [
            Value::Null,
            json!([]),
            json!([{"overlay_type":"caption","data":{"type":"future"}}]),
        ] {
            assert!(decode(Some(&value)).1.is_none());
        }
    }
}
