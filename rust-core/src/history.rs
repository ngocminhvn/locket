#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryCursor {
    pub timestamp: String,
    pub document_name: String,
}

impl HistoryCursor {
    pub fn new(timestamp: String, document_name: String) -> Option<Self> {
        if timestamp.is_empty() || document_name.is_empty() {
            None
        } else {
            Some(Self {
                timestamp,
                document_name,
            })
        }
    }
}
