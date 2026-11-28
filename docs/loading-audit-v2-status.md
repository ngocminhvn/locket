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

Persistent upload journal was implemented separately; next launch restores safe work. No automatic retry of ambiguous post confirmation.

## P1/P2
Implemented: parallel Room hydration, sized grid/calendar/viewer image requests, adjacent image/backdrop prefetch, 300-entry LRU prefetch deduplication, 10-second background feed refresh throttle (explicit post/delete refresh bypasses it), background profile refresh, avatar prefetch, QR entry point.
Partial: full feed/history share first-page source; own history warms separately. Still no persisted history coverage/TTL, no separate latest-moment record beyond existing session/Room cache, no measured cache-window policy. Grid/calendar use neutral placeholders rather than animating every tile. Avatar fallback exists in some screens only.
Deferred: Groups/Rollcall response mapping and actual UI, CDN/rotating-token cache key and Coil 3, expanded pager retention, unread server count, crossfade policy, finer sync queue, conversation-creation latency. Do not infer data/remote-config values or replace real records with mock data. Verify API response schema, authenticated listener behavior, and device timing before declaring parity.

## Verification
Not compiled or tested in this session; CI remains owner-triggered. Test real cold start, offline/online, invite and search, account-switch isolation, profile refresh, pagination and video before measuring. No actual performance numbers claimed.
