use locket_core::{parse_feed_response, wire};

#[test]
fn parses_callable_feed_and_preserves_nanos() {
    let input = br#"{"result":{"status":200,"sync_token":"opaque-token","data":[{"canonical_uid":"moment-a","user":"user-a","thumbnail_url":"https://example.invalid/a.jpg","caption":"","overlays":[{"overlay_type":"caption","data":{"type":"review","payload":{"rating":4.5}}}],"date":{"_seconds":1700000000,"_nanoseconds":123456789},"md5":"digest"}]}}"#;
    let feed = parse_feed_response(input).unwrap();
    assert!(!feed.not_modified);
    assert_eq!(feed.sync_token.as_deref(), Some("opaque-token"));
    assert_eq!(feed.moments[0].timestamp_nanoseconds, 123456789);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&feed.moments[0].overlays_json).unwrap()[0]["data"]["payload"]["rating"], 4.5);
    let encoded = wire::encode_feed(&feed).unwrap();
    assert_eq!(&encoded[..4], b"LKT1");
}

#[test]
fn merge_keeps_last_value_filters_tombstones_and_is_stable() {
    let ids = vec!["a", "b", "a", "c"]
        .into_iter()
        .map(str::to_owned)
        .collect::<Vec<_>>();
    let owners = vec!["u", "u", "u", "hidden"]
        .into_iter()
        .map(str::to_owned)
        .collect::<Vec<_>>();
    let timestamps = [10, 10, 20, 30];
    let order =
        locket_core::feed::merge_indices(&ids, &owners, &timestamps, &[], &["hidden".to_owned()]);
    assert_eq!(order, vec![2, 1]);
    let deleted =
        locket_core::feed::merge_indices(&ids, &owners, &timestamps, &["a".to_owned()], &[]);
    assert!(!deleted.contains(&2));
}

#[test]
fn represents_304_as_not_modified_delta() {
    let feed = parse_feed_response(br#"{"result":{"status":304,"sync_token":"same"}}"#).unwrap();
    assert!(feed.not_modified);
    assert!(feed.moments.is_empty());
}

#[test]
fn rejects_errors_malformed_rows_and_invalid_nanos() {
    assert!(parse_feed_response(br#"{"result":{"status":401,"errors":["sign in"]}}"#).is_err());
    assert!(parse_feed_response(br#"{"result":{"status":200,"data":[{}]}}"#).is_err());
    assert!(parse_feed_response(br#"{"result":{"status":200,"data":[{"canonical_uid":"a","user":"u","thumbnail_url":"x","date":{"_seconds":1,"_nanoseconds":1000000000}}]}}"#).is_err());
}
