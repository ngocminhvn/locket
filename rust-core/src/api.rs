use serde_json::Value;
use std::fmt;

use crate::feed;
use crate::models::{ChatMessage, ChatPage, FeedDelta, HistoryPage, Moment, Reaction};

const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, PartialEq, Eq)]
pub enum CoreError {
    Invalid(&'static str),
    Server { status: i64, message: String },
    Limit(&'static str),
}

impl fmt::Display for CoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Invalid(message) | Self::Limit(message) => f.write_str(message),
            Self::Server { status, message } => write!(f, "server status {status}: {message}"),
        }
    }
}

pub fn parse_feed_response(bytes: &[u8]) -> Result<FeedDelta, CoreError> {
    if bytes.len() > MAX_RESPONSE_BYTES {
        return Err(CoreError::Limit("response exceeds parser limit"));
    }
    let root: Value =
        serde_json::from_slice(bytes).map_err(|_| CoreError::Invalid("invalid response JSON"))?;
    let response = root.get("result").unwrap_or(&root);
    let status = response
        .get("status")
        .and_then(Value::as_i64)
        .ok_or(CoreError::Invalid("missing callable status"))?;
    let token = response
        .get("sync_token")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_owned);
    if status == 304 {
        return Ok(FeedDelta {
            not_modified: true,
            sync_token: token,
            moments: Vec::new(),
        });
    }
    if status != 200 {
        let message = response
            .get("errors")
            .and_then(Value::as_array)
            .and_then(|v| v.first())
            .and_then(Value::as_str)
            .unwrap_or("request rejected")
            .to_owned();
        return Err(CoreError::Server { status, message });
    }
    if response
        .get("errors")
        .and_then(Value::as_array)
        .is_some_and(|v| !v.is_empty())
    {
        return Err(CoreError::Server {
            status,
            message: "feed contained errors".to_owned(),
        });
    }
    let data = response
        .get("data")
        .ok_or(CoreError::Invalid("missing feed data"))?;
    let rows = if let Some(rows) = data.as_array() {
        rows
    } else {
        data.get("data")
            .and_then(Value::as_array)
            .ok_or(CoreError::Invalid("feed data is not an array"))?
    };
    let moments = rows
        .iter()
        .map(parse_moment)
        .collect::<Result<Vec<_>, _>>()?;
    Ok(FeedDelta {
        not_modified: false,
        sync_token: token,
        moments: feed::dedupe(moments),
    })
}

fn parse_moment(value: &Value) -> Result<Moment, CoreError> {
    let text = |name| {
        value
            .get(name)
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_owned()
    };
    let id = {
        let canonical = text("canonical_uid");
        if canonical.is_empty() {
            text("id")
        } else {
            canonical
        }
    };
    let user_uid = text("user");
    let thumbnail_url = text("thumbnail_url");
    if id.is_empty() || user_uid.is_empty() || thumbnail_url.is_empty() {
        return Err(CoreError::Invalid(
            "moment is missing identity or thumbnail",
        ));
    }
    let date = value
        .get("date")
        .and_then(Value::as_object)
        .ok_or(CoreError::Invalid("moment is missing date"))?;
    let seconds = date
        .get("_seconds")
        .and_then(|v| v.as_i64().or_else(|| v.as_str()?.parse().ok()))
        .ok_or(CoreError::Invalid("invalid moment seconds"))?;
    let nanos = date
        .get("_nanoseconds")
        .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
        .unwrap_or(0);
    let timestamp_nanoseconds = u32::try_from(nanos)
        .ok()
        .filter(|v| *v < 1_000_000_000)
        .ok_or(CoreError::Invalid("invalid moment nanoseconds"))?;
    Ok(Moment {
        id,
        user_uid,
        thumbnail_url,
        video_url: value
            .get("video_url")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(str::to_owned),
        caption: text("caption"),
        overlays_json: value.get("overlays").filter(|v| v.is_array()).map(Value::to_string).unwrap_or_else(|| "[]".to_owned()),
        timestamp_seconds: seconds,
        timestamp_nanoseconds,
        md5: text("md5"),
    })
}

// Unwrap nested Firestore values without dropping unknown overlay data.
fn unwrap_firestore_value(value: &Value) -> Value {
    if let Some(map) = value.get("mapValue") {
        let fields = map.get("fields").and_then(Value::as_object);
        return Value::Object(fields.into_iter().flatten().map(|(k, v)| (k.clone(), unwrap_firestore_value(v))).collect());
    }
    if let Some(array) = value.get("arrayValue") {
        return Value::Array(array.get("values").and_then(Value::as_array)
            .into_iter().flatten().map(unwrap_firestore_value).collect());
    }
    if let Some(n) = value.get("integerValue") {
        return n.as_i64().or_else(|| n.as_str()?.parse().ok()).map(Value::from).unwrap_or_else(|| n.clone());
    }
    for key in ["stringValue", "doubleValue", "booleanValue", "timestampValue", "nullValue"] {
        if let Some(v) = value.get(key) { return if key == "nullValue" { Value::Null } else { v.clone() }; }
    }
    value.clone()
}

pub fn parse_history_response(
    bytes: &[u8],
    own_uid: &str,
    own_only: bool,
    limit: usize,
) -> Result<HistoryPage, CoreError> {
    let root = parse_json(bytes)?;
    let rows = root
        .as_array()
        .ok_or(CoreError::Invalid("history response is not an array"))?;
    let documents: Vec<&Value> = rows.iter().filter_map(|row| row.get("document")).collect();
    let mut moments = Vec::with_capacity(documents.len());
    for document in &documents {
        let fields = document
            .get("fields")
            .ok_or(CoreError::Invalid("history document has no fields"))?;
        let owner = firestore_string(fields, "user");
        if own_only && owner != own_uid {
            continue;
        }
        let name = document
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let id = {
            let value = firestore_string(fields, "canonical_uid");
            if value.is_empty() {
                name.rsplit('/').next().unwrap_or_default().to_owned()
            } else {
                value
            }
        };
        let thumbnail_url = firestore_string(fields, "thumbnail_url");
        let timestamp = firestore_timestamp(fields, "date")?;
        if id.is_empty() || owner.is_empty() || thumbnail_url.is_empty() {
            return Err(CoreError::Invalid("history moment is incomplete"));
        }
        moments.push(Moment {
            id,
            user_uid: owner,
            thumbnail_url,
            video_url: nonempty(firestore_string(fields, "video_url")),
            caption: firestore_string(fields, "caption"),
            overlays_json: fields.get("overlays").map(unwrap_firestore_value).filter(Value::is_array).map(|v| v.to_string()).unwrap_or_else(|| "[]".to_owned()),
            timestamp_seconds: timestamp.0,
            timestamp_nanoseconds: timestamp.1,
            md5: firestore_string(fields, "md5"),
        });
    }
    let (next_timestamp, next_document) = if documents.len() >= limit {
        let last = documents
            .last()
            .ok_or(CoreError::Invalid("missing history cursor document"))?;
        let timestamp = last
            .get("fields")
            .and_then(|f| f.get("date"))
            .and_then(|v| v.get("timestampValue"))
            .and_then(Value::as_str)
            .ok_or(CoreError::Invalid("missing history cursor timestamp"))?;
        let name = last
            .get("name")
            .and_then(Value::as_str)
            .ok_or(CoreError::Invalid("missing history cursor name"))?;
        (Some(timestamp.to_owned()), Some(name.to_owned()))
    } else {
        (None, None)
    };
    Ok(HistoryPage {
        moments,
        next_timestamp,
        next_document,
    })
}

pub fn parse_chat_response(bytes: &[u8]) -> Result<ChatPage, CoreError> {
    let root = parse_json(bytes)?;
    let documents = root
        .get("documents")
        .and_then(Value::as_array)
        .map(Vec::as_slice)
        .unwrap_or(&[]);
    let mut messages = Vec::new();
    for document in documents {
        let fields = document
            .get("fields")
            .ok_or(CoreError::Invalid("chat document has no fields"))?;
        let id = document
            .get("name")
            .and_then(Value::as_str)
            .and_then(|v| v.rsplit('/').next())
            .unwrap_or_default()
            .to_owned();
        let sender_uid = firestore_string(fields, "sender");
        let body = firestore_string(fields, "body");
        let created_at_ms = timestamp_millis(fields, "created_at")?;
        let deleted = fields
            .get("deleted_moment")
            .is_some_and(|v| v.get("nullValue").is_none());
        let thumbnail_url = if deleted {
            None
        } else {
            nonempty(firestore_string(fields, "thumbnail_url"))
        };
        if id.is_empty() || sender_uid.is_empty() || (body.is_empty() && thumbnail_url.is_none()) {
            continue;
        }
        let reactions = parse_reactions(fields)?;
        messages.push(ChatMessage {
            id,
            sender_uid,
            body,
            created_at_ms,
            client_token: nonempty(firestore_string(fields, "client_token")),
            reply_moment_uid: nonempty(firestore_string(fields, "reply_moment")),
            thumbnail_url,
            reactions,
        });
    }
    messages.sort_by_key(|m| m.created_at_ms);
    messages.dedup_by(|a, b| a.id == b.id);
    let next_page_token = root
        .get("nextPageToken")
        .and_then(Value::as_str)
        .filter(|v| !v.is_empty())
        .map(str::to_owned);
    Ok(ChatPage {
        messages,
        next_page_token,
    })
}

fn parse_json(bytes: &[u8]) -> Result<Value, CoreError> {
    if bytes.len() > MAX_RESPONSE_BYTES {
        return Err(CoreError::Limit("response exceeds parser limit"));
    }
    serde_json::from_slice(bytes).map_err(|_| CoreError::Invalid("invalid response JSON"))
}

fn firestore_string(fields: &Value, name: &str) -> String {
    fields
        .get(name)
        .and_then(|v| v.get("stringValue"))
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned()
}

fn firestore_timestamp(fields: &Value, name: &str) -> Result<(i64, u32), CoreError> {
    let text = fields
        .get(name)
        .and_then(|v| v.get("timestampValue"))
        .and_then(Value::as_str)
        .ok_or(CoreError::Invalid("missing Firestore timestamp"))?;
    let parsed = chrono::DateTime::parse_from_rfc3339(text)
        .map_err(|_| CoreError::Invalid("invalid Firestore timestamp"))?;
    Ok((parsed.timestamp(), parsed.timestamp_subsec_nanos()))
}

fn timestamp_millis(fields: &Value, name: &str) -> Result<i64, CoreError> {
    firestore_timestamp(fields, name).map(|(seconds, nanos)| {
        seconds
            .saturating_mul(1000)
            .saturating_add(i64::from(nanos / 1_000_000))
    })
}

fn nonempty(value: String) -> Option<String> {
    (!value.is_empty()).then_some(value)
}

fn parse_reactions(fields: &Value) -> Result<Vec<Reaction>, CoreError> {
    let values = fields
        .get("reactions")
        .and_then(|v| v.get("arrayValue"))
        .and_then(|v| v.get("values"))
        .and_then(Value::as_array);
    let mut result = Vec::new();
    for value in values.into_iter().flatten() {
        let fields = value
            .get("mapValue")
            .and_then(|v| v.get("fields"))
            .ok_or(CoreError::Invalid("invalid reaction fields"))?;
        let sender_uid = firestore_string(fields, "sender");
        let emoji = firestore_string(fields, "emoji");
        if sender_uid.is_empty() || emoji.is_empty() {
            continue;
        }
        let created_at_ms = timestamp_millis(fields, "created_at").unwrap_or(0);
        result.push(Reaction {
            sender_uid,
            emoji,
            created_at_ms,
        });
    }
    Ok(result)
}
