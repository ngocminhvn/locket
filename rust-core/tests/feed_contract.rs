use locket_core::{parse_feed_response, wire};

#[test]
fn parses_callable_feed_and_preserves_nanos() {
    let input = br#"{"result":{"status":200,"sync_token":"opaque-token","data":[{"canonical_uid":"moment-a","user":"user-a","thumbnail_url":"https://example.invalid/a.jpg","caption":"","date":{"_seconds":1700000000,"_nanoseconds":123456789},"md5":"digest"}]}}"#;
    let feed = parse_feed_response(input).unwrap();
    assert!(!feed.not_modified);
    assert_eq!(feed.sync_token.as_deref(), Some("opaque-token"));
    assert_eq!(feed.moments[0].timestamp_nanoseconds, 123456789);
    let encoded = wire::encode_feed(&feed).unwrap();
    assert_eq!(&encoded[..4], b"LKT1");
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
