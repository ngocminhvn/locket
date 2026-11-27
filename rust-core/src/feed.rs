use std::collections::HashSet;

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
