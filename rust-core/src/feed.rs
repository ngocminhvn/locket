use std::collections::{HashMap, HashSet};

use crate::models::Moment;

/// Preserve server order while rejecting duplicate canonical IDs. An empty ID
/// never becomes an identity key and is rejected by the parser before here.
pub fn dedupe(moments: Vec<Moment>) -> Vec<Moment> {
    let mut seen = HashSet::with_capacity(moments.len());
    moments
        .into_iter()
        .filter(|moment| seen.insert(moment.id.clone()))
        .collect()
}

pub fn merge_indices(
    ids: &[String],
    owners: &[String],
    timestamps: &[i64],
    deleted: &[String],
    hidden: &[String],
) -> Vec<i32> {
    if ids.len() != owners.len() || ids.len() != timestamps.len() {
        return Vec::new();
    }
    let deleted: HashSet<&str> = deleted.iter().map(String::as_str).collect();
    let hidden: HashSet<&str> = hidden.iter().map(String::as_str).collect();
    let mut positions: HashMap<&str, (usize, usize)> = HashMap::new();
    for (index, id) in ids.iter().enumerate() {
        positions
            .entry(id.as_str())
            .and_modify(|value| value.1 = index)
            .or_insert((index, index));
    }
    let mut kept: Vec<(usize, usize)> = positions
        .into_values()
        .filter(|(_, last)| {
            !ids[*last].is_empty()
                && !deleted.contains(ids[*last].as_str())
                && !hidden.contains(owners[*last].as_str())
        })
        .collect();
    kept.sort_by(|(left_first, left), (right_first, right)| {
        timestamps[*right]
            .cmp(&timestamps[*left])
            .then_with(|| left_first.cmp(right_first))
    });
    kept.into_iter()
        .filter_map(|(_, value)| i32::try_from(value).ok())
        .collect()
}
