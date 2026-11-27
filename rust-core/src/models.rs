#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Moment {
    pub id: String,
    pub user_uid: String,
    pub thumbnail_url: String,
    pub video_url: Option<String>,
    pub caption: String,
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
