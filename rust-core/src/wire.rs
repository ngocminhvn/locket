use crate::models::{FeedDelta, Moment};
use crate::CoreError;

const MAGIC: &[u8; 4] = b"LKT1";
const SCHEMA_VERSION: u16 = 1;
const FEED_RECORD: u8 = 1;
const MAX_RECORDS: usize = 100_000;
const MAX_STRING_BYTES: usize = 8 * 1024 * 1024;

pub fn encode_feed(feed: &FeedDelta) -> Result<Vec<u8>, CoreError> {
    if feed.moments.len() > MAX_RECORDS {
        return Err(CoreError::Limit("too many feed records"));
    }
    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&SCHEMA_VERSION.to_be_bytes());
    out.push(FEED_RECORD);
    out.push(u8::from(feed.not_modified));
    put_optional_string(&mut out, feed.sync_token.as_deref())?;
    out.extend_from_slice(&(feed.moments.len() as u32).to_be_bytes());
    for moment in &feed.moments {
        encode_moment(&mut out, moment)?;
    }
    Ok(out)
}

fn encode_moment(out: &mut Vec<u8>, value: &Moment) -> Result<(), CoreError> {
    put_string(out, &value.id)?;
    put_string(out, &value.user_uid)?;
    put_string(out, &value.thumbnail_url)?;
    put_optional_string(out, value.video_url.as_deref())?;
    put_string(out, &value.caption)?;
    out.extend_from_slice(&value.timestamp_seconds.to_be_bytes());
    out.extend_from_slice(&value.timestamp_nanoseconds.to_be_bytes());
    put_string(out, &value.md5)
}

fn put_optional_string(out: &mut Vec<u8>, value: Option<&str>) -> Result<(), CoreError> {
    out.push(u8::from(value.is_some()));
    if let Some(value) = value {
        put_string(out, value)?;
    }
    Ok(())
}

fn put_string(out: &mut Vec<u8>, value: &str) -> Result<(), CoreError> {
    let bytes = value.as_bytes();
    if bytes.len() > MAX_STRING_BYTES {
        return Err(CoreError::Limit("string exceeds wire limit"));
    }
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}
