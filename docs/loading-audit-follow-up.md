# Upload durability and loading audit follow-up

## Implemented

- The foreground authenticated Firestore Listen connections cover feed, friends, conversations and the active conversation. REST remains the recovery path; a live connection has not been verified on a real account in this change.
- The first feed request has its own loading/error state and a cold-start placeholder.
- Pending chat sends use actual request tokens and are reconciled against server messages.
- Media3 playback keeps the poster until the first frame, with a bounded video cache and adjacent video prefetch.
- Sending a moment now persists the exact encoded thumbnail, optional video, recipients and caption before dismissing review. The journal is in private, non-backed-up app storage, separated by account.
- Completed storage uploads are checkpointed. A write-ahead marker precedes the post callable. Confirmed posts are never retried automatically. Ambiguous posts are surfaced for an explicit decision after checking history.
- Process exit retains queued work; next authenticated launch restores it. Explicit logout cancels work and clears that account's private queue. No worker runs after the app process dies; the next launch resumes safe work.

## Device verification checklist (not yet executed)

1. Fresh install/login: no fabricated feed data; loading state ends on success or error.
2. Launch with cached photos in airplane mode: existing content remains visible.
3. Two authenticated devices: send/delete a moment and observe the other feed; add/remove a friend and send chat messages; check delta delivery and fallback after reconnect.
4. Send a moment offline, kill and reopen the process, reconnect: retry the retained failed job; confirm exactly one real post.
5. Kill during thumbnail/video upload: restore the journal and resume; kill during post confirmation: check history before choosing any retry.
6. Delete confirmed jobs from the journal; ensure logout and account change do not show another account's pending media.
7. Swipe across video moments and history: poster remains visible until decoded frame; verify cache reuse, memory, battery and frame timing.
8. Run Kotlin compilation and signed release build with R8 only when requested.

The full 495-line local audit was not available to this session. This note covers the findings supplied in the chat, not a claim of complete parity.
