pub fn needs_full_refresh(
    incremental: bool,
    newest_cached_id: &str,
    received_ids: &[String],
) -> bool {
    incremental
        && !newest_cached_id.is_empty()
        && !received_ids.iter().any(|id| id == newest_cached_id)
}

#[cfg(test)]
mod tests {
    use super::needs_full_refresh;
    #[test]
    fn only_requests_fallback_when_overlap_is_missing() {
        assert!(!needs_full_refresh(true, "m2", &["m1".into(), "m2".into()]));
        assert!(needs_full_refresh(true, "m2", &["m3".into()]));
        assert!(!needs_full_refresh(false, "m2", &[]));
    }
}
