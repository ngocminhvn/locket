# Loading audit v2 — follow-up against ff250b5

This is a code-level implementation record for the uploaded 495-line audit, not a runtime benchmark.

## P0 (section 10)
1–8: authenticated feed/chat listeners, targeted refresh after post/delete, separate initial feed loading, poster-first Media3 video, bounded adjacent video cache, memory video, and pending chat reconciliation are implemented in earlier commits.
9: own-history first page now starts in startup sync; calendar prefetches visible and adjacent months.
10: Friends screen uses targeted refresh; inbox/recommendations are cached in account-scoped SharedPreferences (chosen to avoid a Room migration for these small lists). Inbox also listens to Firestore.
11: batches of up to 20; fallback in parallel batches of four; confirmed name/avatar values and expiration persist for 24 hours. Stale values remain available if refresh fails.
12: general account details cache includes fetch time; UI renders cached values and refreshes in background.
13: actual invite link cached by account and Link platform; Friends QR button opens the same real QR sheet.
14: 350 ms debounce, cancellation of in-flight search HTTP request and 5-minute per-screen result cache.

Persistent upload journal: see the durability notes below. No automatic retry of ambiguous post confirmation.

## P1/P2
Implemented: parallel Room hydration, sized grid/calendar/viewer image requests, adjacent image/backdrop prefetch, 300-entry LRU prefetch deduplication, 10-second background feed refresh throttle (explicit post/delete refresh bypasses it), background profile refresh, avatar prefetch, QR entry point.
Partial: full feed/history share first-page source; own history warms separately. Still no persisted history coverage/TTL, no separate latest-moment record beyond existing session/Room cache, no measured cache-window policy. Grid/calendar use neutral placeholders rather than animating every tile. Avatar fallback exists in some screens only.
Deferred: Groups/Rollcall response mapping and actual UI, CDN/rotating-token cache key and Coil 3, expanded pager retention, crossfade policy, finer sync queue, conversation-creation latency. Do not infer data/remote-config values or replace real records with mock data. Verify API response schema, authenticated listener behavior, and device timing before declaring parity.

## Verification
Not compiled or tested in this session; CI remains owner-triggered. Test real cold start, offline/online, invite and search, account-switch isolation, profile refresh, pagination and video before measuring. No actual performance numbers claimed.

---

## Upload durability and device verification

### Implementation

- The foreground authenticated Firestore Listen connections cover feed, friends, conversations and the active conversation. REST remains the recovery path; a live connection has not been verified on a real account in this change.
- The first feed request has its own loading/error state and a cold-start placeholder.
- Pending chat sends use actual request tokens and are reconciled against server messages.
- Media3 playback keeps the poster until the first frame, with a bounded video cache and adjacent video prefetch.
- Sending a moment now persists the exact encoded thumbnail, optional video, recipients and caption before dismissing review. The journal is in private, non-backed-up app storage, separated by account.
- Completed storage uploads are checkpointed. A write-ahead marker precedes the post callable. Confirmed posts are never retried automatically. Ambiguous posts are surfaced for an explicit decision after checking history.
- Process exit retains queued work; next authenticated launch restores it. Explicit logout cancels work and clears that account's private queue. No worker runs after the app process dies; the next launch resumes safe work.

### Device verification checklist (not yet executed)

1. Fresh install/login: no fabricated feed data; loading state ends on success or error.
2. Launch with cached photos in airplane mode: existing content remains visible.
3. Two authenticated devices: send/delete a moment and observe the other feed; add/remove a friend and send chat messages; check delta delivery and fallback after reconnect.
4. Send a moment offline, kill and reopen the process, reconnect: retry the retained failed job; confirm exactly one real post.
5. Kill during thumbnail/video upload: restore the journal and resume; kill during post confirmation: check history before choosing any retry.
6. Delete confirmed jobs from the journal; ensure logout and account change do not show another account's pending media.
7. Swipe across video moments and history: poster remains visible until decoded frame; verify cache reuse, memory, battery and frame timing.
8. Run Kotlin compilation and signed release build with R8 only when requested.


---

## Original palette audit

Basis: uploaded locket-colors-audit.md, original 1.247.3, audited baseline ff250b5. Applied against main 7837563, not the old audit checkout.

- Root #060505, modal #171717, sheet #242424, sheet surface #2B2B2B, avatar #383737, search #191919, journal day #252525.
- Accent #FFB703, text on yellow #1A1200, destructive #FF4343, distinct Gold #FFB800. Preserve optional custom camera themes and launcher designs.
- Shared Home/Memory backdrop decodes the actual image at 16px, scales it and uses a black 80% scrim. This is the low-cost approximation recommended by the audit, not an exact reproduction of the original native blur; no dominant-color sampling.
- Chat uses neutral chrome gradients at the audit's alpha stops, received #333333, sent white 90%, composer #191919. The removed implementation actually sampled shared photos at the current HEAD, rather than the avatar mentioned in the older audit.
- Fixed nav uses #201F1F at 92% opacity with existing glass border.
- Android window colors and default launcher accent aligned. Preserve existing resource names; no native asset cleanup without confirming references.
- No change to unread counts, network schema, layout, or build triggers. No CI/device visual verification performed. Camera preview native colors, exact login/Home composer colors and original splash remain unverified.

---

## Unread conversation audit

Based on the supplied unread-count schema audit of original 1.247.3:
- DM unread means explicitly is_read == false. Keep existing mapper, model and Room 0/1 flag. Missing flag does not count.
- The fixed chat navigation counts conversations with the flag, locally from the existing StateFlow. No extra requests or message scans.
- Conversation rows show a yellow 14dp dot and stronger preview/time, not a numeric per-message badge. The navigation badge is a yellow count with display cap 99+ (accessibility exposes actual count).
- Existing authenticated conversation Listen and successful markAsRead handling are unchanged.
- Groups not implemented; do not fabricate group count or assume a DM unread_count field.
- Not compiled or verified on a real account in this change.
