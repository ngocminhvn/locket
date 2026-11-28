pub fn needs_full_refresh(
    incremental: bool,
    newest_cached_id: &str,
    received_ids: &[String],
) -> bool {
    incremental
        && !newest_cached_id.is_empty()
        && !received_ids.iter().any(|id| id == newest_cached_id)
}

/// Build the Firebase callable envelope, preserving explicit null for ordinary chat.
pub fn build_send_chat_message_payload(
    receiver_uid: &str,
    message: &str,
    moment_uid: Option<&str>,
    client_token: &str,
) -> Result<Vec<u8>, crate::CoreError> {
    if receiver_uid.trim().is_empty() || message.trim().is_empty() {
        return Err(crate::CoreError::Invalid("Thiếu người nhận hoặc nội dung"));
    }
    if client_token.trim().is_empty() {
        return Err(crate::CoreError::Invalid("Thiếu client token"));
    }
    let payload = serde_json::json!({
        "data": {
            "msg": message,
            "receiver_uid": receiver_uid,
            "moment_uid": moment_uid.filter(|uid| !uid.trim().is_empty()),
            "client_token": client_token,
        }
    });
    serde_json::to_vec(&payload)
        .map_err(|_| crate::CoreError::Invalid("cannot encode chat payload"))
}

#[cfg(test)]
mod tests {
    use super::{build_send_chat_message_payload, needs_full_refresh};

    const TOKEN: &str = "123e4567-e89b-42d3-a456-426614174000";

    #[test]
    fn ordinary_chat_keeps_explicit_null_and_unicode() {
        for message in ["hello", "❤️", "👨‍👩‍👧‍👦", "tiếng Việt \"\n"] {
            let bytes = build_send_chat_message_payload("friend", message, None, TOKEN).unwrap();
            let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
            let expected = serde_json::json!({
                "data": {"msg": message, "receiver_uid": "friend",
                    "moment_uid": null, "client_token": TOKEN}
            });
            assert_eq!(value, expected);
        }
    }

    #[test]
    fn blank_moment_is_null_but_reply_preserves_uid() {
        for uid in [Some(""), Some("  "), Some("canonical-moment")] {
            let bytes = build_send_chat_message_payload("friend", "reply", uid, TOKEN).unwrap();
            let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
            let expected = uid.filter(|s| !s.trim().is_empty());
            assert_eq!(value["data"]["moment_uid"], serde_json::json!(expected));
            assert!(value["data"].as_object().unwrap().contains_key("moment_uid"));
        }
    }

    #[test]
    fn invalid_chat_input_is_rejected() {
        for (receiver, message, token) in [("", "hi", TOKEN), ("friend", " ", TOKEN), ("friend", "hi", "")] {
            assert!(build_send_chat_message_payload(receiver, message, None, token).is_err());
        }
    }

    #[test]
    fn only_requests_fallback_when_overlap_is_missing() {
        assert!(!needs_full_refresh(true, "m2", &["m1".into(), "m2".into()]));
        assert!(needs_full_refresh(true, "m2", &["m3".into()]));
        assert!(!needs_full_refresh(false, "m2", &[]));
    }
}
