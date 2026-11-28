// Receipt comparison is conservative: unknown timestamps never imply a receipt.
pub(crate) fn status(created: i64, read: i64, delivered: i64) -> i32 {
    if created <= 0 { 0 } else if read >= created { 2 } else if delivered >= created { 1 } else { 0 }
}
#[cfg(test)]
mod tests {
    use super::status;
    #[test]
    fn requires_server_timestamp_at_or_after_message() {
        assert_eq!(status(0, 100, 100), 0);
        assert_eq!(status(100, 99, 0), 0);
        assert_eq!(status(100, 0, 100), 1);
        assert_eq!(status(100, 100, 200), 2);
    }
}
