#[repr(i32)]
pub enum TokenAction {
    Missing = 0,
    UseCurrent = 1,
    Refresh = 2,
}

pub fn token_action(has_token: bool, stale: bool, has_refresh: bool) -> TokenAction {
    if !has_token {
        TokenAction::Missing
    } else if stale && has_refresh {
        TokenAction::Refresh
    } else if stale {
        TokenAction::Missing
    } else {
        TokenAction::UseCurrent
    }
}
