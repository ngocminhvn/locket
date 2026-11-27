use serde_json::Value;
use std::fmt;

use crate::feed;
use crate::models::{FeedDelta, Moment};

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
        timestamp_seconds: seconds,
        timestamp_nanoseconds,
        md5: text("md5"),
    })
}
