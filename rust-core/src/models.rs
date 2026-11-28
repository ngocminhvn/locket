#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Moment {
    pub id: String,
    pub user_uid: String,
    pub thumbnail_url: String,
    pub video_url: Option<String>,
    pub caption: String,
    pub overlays_json: String,
    pub overlay: Option<crate::overlay::CaptionOverlay>,
    pub timestamp_seconds: i64,
    pub timestamp_nanoseconds: u32,
    pub md5: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FeedDelta {
    pub not_modified: bool,
    pub sync_token: Option<String>,
    pub moments: Vec<Moment>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryPage {
    pub moments: Vec<Moment>,
    pub next_timestamp: Option<String>,
    pub next_document: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reaction {
    pub sender_uid: String,
    pub emoji: String,
    pub created_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatMessage {
    pub id: String,
    pub sender_uid: String,
    pub body: String,
    pub created_at_ms: i64,
    pub client_token: Option<String>,
    pub reply_moment_uid: Option<String>,
    pub thumbnail_url: Option<String>,
    pub reactions: Vec<Reaction>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChatPage {
    pub messages: Vec<ChatMessage>,
    pub next_page_token: Option<String>,
}
