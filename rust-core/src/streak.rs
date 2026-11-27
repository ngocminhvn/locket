use chrono::{DateTime, Duration, TimeZone, Utc};
use chrono_tz::Tz;
use std::collections::HashSet;

pub fn calculate(
    timestamps_seconds: &[i64],
    now_millis: i64,
    timezone: &str,
) -> Result<i32, &'static str> {
    let tz: Tz = timezone.parse().map_err(|_| "invalid timezone")?;
    let now: DateTime<Utc> = Utc
        .timestamp_millis_opt(now_millis)
        .single()
        .ok_or("invalid current time")?;
    let days: HashSet<_> = timestamps_seconds
        .iter()
        .filter_map(|seconds| Utc.timestamp_opt(*seconds, 0).single())
        .map(|value| value.with_timezone(&tz).date_naive())
        .collect();
    let today = now.with_timezone(&tz).date_naive();
    let mut day = if days.contains(&today) {
        today
    } else {
        let yesterday = today - Duration::days(1);
        if days.contains(&yesterday) {
            yesterday
        } else {
            return Ok(0);
        }
    };
    let mut count = 0i32;
    while days.contains(&day) {
        count = count.saturating_add(1);
        day -= Duration::days(1);
    }
    Ok(count)
}

#[cfg(test)]
mod tests {
    use super::calculate;
    #[test]
    fn uses_named_timezone_calendar_days() {
        let timestamps = [1_700_000_000, 1_699_913_600];
        assert_eq!(
            calculate(&timestamps, 1_700_000_000_000, "Asia/Ho_Chi_Minh").unwrap(),
            2
        );
    }
}
