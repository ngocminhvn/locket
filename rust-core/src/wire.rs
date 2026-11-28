use crate::models::{ChatMessage, ChatPage, FeedDelta, HistoryPage, Moment};
use crate::CoreError;

const MAGIC: &[u8; 4] = b"LKT1";
const SCHEMA_VERSION: u16 = 4;
const FEED_RECORD: u8 = 1;
const HISTORY_RECORD: u8 = 2;
const CHAT_RECORD: u8 = 3;
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

pub fn encode_history(page: &HistoryPage) -> Result<Vec<u8>, CoreError> {
    let mut out = header(HISTORY_RECORD);
    put_optional_string(&mut out, page.next_timestamp.as_deref())?;
    put_optional_string(&mut out, page.next_document.as_deref())?;
    put_count(&mut out, page.moments.len())?;
    for moment in &page.moments {
        encode_moment(&mut out, moment)?;
    }
    Ok(out)
}

pub fn encode_chat(page: &ChatPage) -> Result<Vec<u8>, CoreError> {
    let mut out = header(CHAT_RECORD);
    put_optional_string(&mut out, page.next_page_token.as_deref())?;
    put_count(&mut out, page.messages.len())?;
    for message in &page.messages {
        encode_chat_message(&mut out, message)?;
    }
    Ok(out)
}

fn header(record_type: u8) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&SCHEMA_VERSION.to_be_bytes());
    out.push(record_type);
    out
}

fn put_count(out: &mut Vec<u8>, count: usize) -> Result<(), CoreError> {
    if count > MAX_RECORDS {
        return Err(CoreError::Limit("too many records"));
    }
    out.extend_from_slice(&(count as u32).to_be_bytes());
    Ok(())
}

fn encode_chat_message(out: &mut Vec<u8>, value: &ChatMessage) -> Result<(), CoreError> {
    put_string(out, &value.id)?;
    put_string(out, &value.sender_uid)?;
    put_string(out, &value.body)?;
    out.extend_from_slice(&value.created_at_ms.to_be_bytes());
    put_optional_string(out, value.client_token.as_deref())?;
    put_optional_string(out, value.reply_moment_uid.as_deref())?;
    put_optional_string(out, value.thumbnail_url.as_deref())?;
    put_count(out, value.reactions.len())?;
    for reaction in &value.reactions {
        put_string(out, &reaction.sender_uid)?;
        put_string(out, &reaction.emoji)?;
        out.extend_from_slice(&reaction.created_at_ms.to_be_bytes());
    }
    Ok(())
}

fn encode_moment(out: &mut Vec<u8>, value: &Moment) -> Result<(), CoreError> {
    put_string(out, &value.id)?;
    put_string(out, &value.user_uid)?;
    put_string(out, &value.thumbnail_url)?;
    put_optional_string(out, value.video_url.as_deref())?;
    put_string(out, &value.caption)?;
    out.extend_from_slice(&value.timestamp_seconds.to_be_bytes());
    out.extend_from_slice(&value.timestamp_nanoseconds.to_be_bytes());
    put_string(out, &value.md5)?;
    put_string(out, &value.overlays_json)?;
    out.push(value.missing_fields);
    out.push(u8::from(value.overlay.is_some()));
    if let Some(overlay) = &value.overlay {
        for text in [&overlay.kind, &overlay.text, &overlay.alt_text] {
            put_string(out, text)?;
        }
        out.extend_from_slice(&overlay.foreground.to_be_bytes());
        put_count(out, overlay.colors.len())?;
        for color in &overlay.colors {
            out.extend_from_slice(&color.to_be_bytes());
        }
        out.push(overlay.max_lines);
        put_string(out, &overlay.icon_kind)?;
        put_string(out, &overlay.icon_data)?;
        out.extend_from_slice(&overlay.icon_color.to_be_bytes());
        out.extend_from_slice(&overlay.rating_bits.to_be_bytes());
        put_string(out, &overlay.comment)?;
        put_string(out, &overlay.spotify_url)?;
    }
    Ok(())
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
