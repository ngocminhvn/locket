use locket_core::{parse_chat_response, parse_history_response};

#[test]
fn history_preserves_exact_cursor_and_timestamp_nanos() {
    let row = r#"[{"document":{"name":"projects/p/databases/locket/documents/history/u/entries/moment-a","fields":{"canonical_uid":{"stringValue":"moment-a"},"user":{"stringValue":"user-a"},"thumbnail_url":{"stringValue":"https://example.invalid/a.jpg"},"date":{"timestampValue":"2023-11-14T22:13:20.123456789Z"},"overlays":{"arrayValue":{"values":[{"mapValue":{"fields":{"overlay_type":{"stringValue":"caption"},"data":{"mapValue":{"fields":{"type":{"stringValue":"review"},"payload":{"mapValue":{"fields":{"rating":{"doubleValue":4.5},"comment":{"stringValue":"review"}}}}}}}}}}]}}}}}]"#;
    let page = parse_history_response(row.as_bytes(), "user-a", true, 1).unwrap();
    let overlay: serde_json::Value = serde_json::from_str(&page.moments[0].overlays_json).unwrap();
    assert_eq!(overlay[0]["data"]["payload"]["rating"], 4.5);
    assert_eq!(overlay[0]["data"]["payload"]["comment"], "review");
    assert_eq!(page.moments[0].timestamp_nanoseconds, 123_456_789);
    assert_eq!(
        page.next_timestamp.as_deref(),
        Some("2023-11-14T22:13:20.123456789Z")
    );
    assert!(page
        .next_document
        .as_deref()
        .unwrap()
        .ends_with("/moment-a"));
}

#[test]
fn chat_keeps_reactions_and_hides_deleted_attachment() {
    let row = r#"{"documents":[{"name":"projects/p/databases/(default)/documents/conversations/c/messages/m1","fields":{"body":{"stringValue":"real body"},"sender":{"stringValue":"user-a"},"created_at":{"timestampValue":"2023-11-14T22:13:20.123Z"},"thumbnail_url":{"stringValue":"https://example.invalid/a.jpg"},"deleted_moment":{"mapValue":{"fields":{}}},"reactions":{"arrayValue":{"values":[{"mapValue":{"fields":{"sender":{"stringValue":"user-b"},"emoji":{"stringValue":"💛"},"created_at":{"timestampValue":"2023-11-14T22:13:21Z"}}}}]}}}}],"nextPageToken":"opaque"}"#;
    let page = parse_chat_response(row.as_bytes()).unwrap();
    let message = &page.messages[0];
    assert_eq!(message.body, "real body");
    assert_eq!(message.thumbnail_url, None);
    assert_eq!(message.reactions[0].emoji, "💛");
    assert_eq!(page.next_page_token.as_deref(), Some("opaque"));
}

#[test]
fn malformed_firestore_timestamps_are_errors() {
    let row = br#"[{"document":{"name":"x/m","fields":{"user":{"stringValue":"u"},"thumbnail_url":{"stringValue":"x"},"date":{"timestampValue":"not-a-time"}}}}]"#;
    assert!(parse_history_response(row, "u", false, 24).is_err());
}
