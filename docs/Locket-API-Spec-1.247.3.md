# Locket API Spec — bản 1.247.3 (com.locket.Locket)

> Spec trích tĩnh từ APK gốc Locket 1.247.3, để đưa cho AI agent build app tương tự.
> **Nguồn:** base APK tách từ XAPK APKPure (XAPK SHA-256 `6c8f360ca5fabb0569f8937f8a6729b70d4211a31fb31d036f92af4d5ba191b5` đã verify khớp), base APK 61.710.453 byte, chữ ký Play App Signing SHA-1 `187a27d3d7364a044307f56e66230f973dccd5b7`.
> **Phương pháp:** static only trên `assets/index.android.bundle` đã decompile (Hermes HBC v96, hermes-dec 0.1.8, 1.865.874 dòng) + manifest/dex/resources. Không chạy app, không bắt traffic runtime. Chỗ nào chưa xác định được ghi rõ **unknown** — không bịa field.
> **Ngày trích:** 2026-11-27. File machine-readable tương ứng: `locket-api-1.247.3.json`.

## Mục lục

- [1. Tổng quan](#1-tổng-quan)
- [2. Protocol callable chung](#2-protocol-callable-chung)
- [3. Firebase config](#3-firebase-config)
- [4. Auth](#4-auth)
- [5. Upload media](#5-upload-media)
- [6. Bảng endpoint (80 callable)](#6-bảng-endpoint-80-callable)
- [7. Realtime — Firestore](#7-realtime--firestore)
- [8. Groups WebSocket](#8-groups-websocket)
- [9. Analytics merge](#9-analytics-merge)
- [10. Remote Config (key liên quan API)](#10-remote-config-key-liên-quan-api)
- [11. Bằng chứng test live 2026-11-27](#11-bằng-chứng-test-live-2026-11-27)
- [12. Chưa xác minh — cần runtime traffic](#12-chưa-xác-minh--cần-runtime-traffic)

## 1. Tổng quan

- App gốc là **Expo SDK 54 + React Native + Hermes**, không phải Kotlin/Java thuần. Toàn bộ logic API/auth/upload nằm trong JS bundle; 5 file dex chỉ chứa SDK + native module.
- Mọi API Locket đều là **Firebase Cloud Functions callable** qua custom domain `https://api.locketcamera.com` (lấy từ Remote Config, xem §2). Không có REST API riêng nào khác.
- Upload media đi **trực tiếp Firebase Storage**, không qua endpoint upload REST.
- Realtime qua **Firestore listeners** (JS SDK) + **WebSocket riêng cho group** (receive-only).
- Firebase project: **`locket-4252a`**.

## 2. Protocol callable chung

```
POST https://api.locketcamera.com/<tên callable>
Header: Authorization: Bearer <Firebase ID token>   (SDK tự gắn)
        Content-Type: application/json
Body:   {"data": {...payload, "analytics": {...}}}   (analytics merge ở §9)
```

- **Custom domain:** `getCustomDomain()` đọc Remote Config `functions_domains` (default `'["https://api.locketcamera.com"]'`), `JSON.parse` rồi **chọn ngẫu nhiên 1 entry**, cache lại. Rỗng/null → SDK fallback `us-central1`.
- **Response envelope** (validate bằng zod): `{status: number, errors?: string[], data?: unknown}`.
- **Validation:** `validateFunctionResponseWithNestedData` throw nếu `status >= 400` **hoặc** `errors` non-empty; message lỗi = `errors[0]` hoặc `'Unknown cloud function error'`.
- **Status code đã xác minh từ call site:**

| Code | Ý nghĩa |
|---|---|
| 200 | OK |
| 304 | Không có moment mới (`getLatestMomentV2`, dùng cache) |
| 400 | Lỗi client chung; `sendVerificationCode` 400 → `Other` |
| 451 | Abusive content (`ABUSIVE_CONTENT_STATUS_CODE`) |
| 600 | Đã tồn tại — `sendVerificationCode` → `ExistingPhoneNumber`; `validateEmailAddress` → email đã thuộc tài khoản khác |
| 601 | Không tìm thấy / đã dùng — `sendVerificationCode` → `UnknownPhoneNumber`; `updateEmailAddress` → `email_already_in_use` |
| 602 | `sendVerificationCode` → `UsePasswordInstead` |

## 3. Firebase config

| Key | Giá trị |
|---|---|
| Project ID | `locket-4252a` |
| API key | `AIzaSyB5dTd-xiLD5dEfWq5OpptnQtnMpE0W0u8` |
| App ID | `1:641029076083:android:eac8183b796b856d4fa606` |
| Sender ID | `641029076083` |
| Default storage bucket | `locket-4252a.appspot.com` |
| Bucket ảnh | `gs://locket-img` |
| Bucket video | `gs://locket-video` |
| Bucket rollcall | `gs://locket-rollcall` |

> API key trên là **client key public của app gốc, bị giới hạn (restrict) theo Android app** — gọi REST không kèm header sẽ bị `403 "Requests from this Android client application <empty> are blocked."`. Muốn gọi Identity Toolkit / Secure Token REST phải kèm header `X-Android-Package: com.locket.Locket` + `X-Android-Cert: 187A27D3D7364A044307F56E66230F973DCCD5B7` (SHA-1 cert gốc). Đã verify: có header thì `signInWithPassword` với email lạ trả `400 EMAIL_NOT_FOUND` (tức key + restriction đã qua).

## 4. Auth

### 4.1 Phone OTP — 2 bước qua callable (luồng chính)

**Bước 1 — `sendVerificationCode({phone, operation, device_model, use_password_if_available?})`:**
- `phone`: E.164 (`parsePhoneNumber(input, 'US')`).
- `operation`: `'sign_in'` (onboarding), `'change_number'` (đổi số). Giá trị khác **unknown**.
- `device_model`: `DeviceInfo.getModel()`, `''` khi lỗi.
- `use_password_if_available`: lấy từ RC `android_auth_config`, default `true`. Chỉ có ở onboarding, không có ở đổi số.
- Status: `600` ExistingPhoneNumber, `601` UnknownPhoneNumber, `602` UsePasswordInstead, `400` Other.

**Bước 2 — server quyết `data.method`:**
- `method == 'firebase'` → client gọi Firebase `signInWithPhoneNumber(phone, forceResend=true)` → SMS OTP → `confirmation.confirm(code)`.
- `method` khác → `checkVerificationCode({phone, verification_code})` → `data.token` → `signInWithCustomToken(token)`. Nhánh này **không check status**, chỉ check token có mặt hay không.
- Đổi số: nếu có Firebase confirmation object → `confirmation.confirm(code)`; không có → `verifyEditedPhoneNumber({phone, verification_code})`, throw `PhoneVerificationError` nếu `data.error` hoặc `status >= 400`.

### 4.2 Email / password — qua callable, KHÔNG sign-in Firebase trực tiếp

- **Đăng ký:** `androidSignupRecaptchaEnabled() == false` → `createAccountWithEmailPassword({email, password})`. `== true` → thử **AppCheck** trước: có token → gọi V1 (token do Functions SDK gắn kèm, **không nằm trong body**); AppCheck null → **reCAPTCHA Enterprise** (`RecaptchaAction.SIGNUP`) → `createAccountWithEmailPasswordV2({email, password, recaptcha_token})`. Response `data.token` → `signInWithCustomToken`.
- **Đăng nhập email:** `signInWithPhonePassword({phone, password})` → `data.token` → `signInWithCustomToken`.
- **Link email:** `linkEmailPassword({email, password})` → `data.token` → `signInWithCustomToken`.
- **Đổi email:** `updateEmailAddress({email})`; status `601` = `email_already_in_use`; `data.token` → `signInWithCustomToken`.
- **Quên mật khẩu:** `sendPasswordResetEmail({email})` nếu authType email, `{phone}` nếu khác; yêu cầu `status === 200`.
- **Validate email:** `validateEmailAddress({email, operation: 'create_account'})`; status `600` = email đã tồn tại. `operation` tại 2 call site truyền bằng biến → **unknown**.
- **Hoàn thiện tài khoản tạm:** `finalizeTemporaryUser({first_name, last_name, require_username, username})`; yêu cầu `status === 200` rồi `updateProfile(displayName)`. Return khi status ≠ 200 **unknown**.
- **Username:** `validateUsername({username})`, `validateUsernameV2({username, recaptcha_token})`, `setUsername({username})`, `setUsernameV2({username, recaptcha_token})`, `suggestUsernames({first_name, last_name})`, `getUserByUsername({username})`.

> Khác naive-locket 2024: repo 2024 dùng `FirebaseAuth.signInWithEmail` trực tiếp. Gốc 1.247.3 **không** đăng nhập email kiểu đó nữa.

### 4.3 AppCheck / reCAPTCHA

- **AppCheck:** provider **Play Integrity** (debug khi có cờ debug); `initializeAppCheck({provider, isTokenAutoRefreshEnabled: true})`. `getAppCheckToken(context, screen)` dùng ở bước signup email, trước reCAPTCHA.
- **reCAPTCHA Enterprise:** `RecaptchaAction` `LOGIN='login'`, `SIGNUP='signup'`, `custom(action)`. RC gates (default `true`): `android_signup_recaptcha_enabled`, `android_usernames_recaptcha_enabled`, `android_friending_recaptcha_enabled`, `android_group_membership_recaptcha_enabled`, `android_data_export_recaptcha_enabled`, `android_location_caption_recaptcha_enabled`.
- `recaptcha_token` xuất hiện trong body của: `createAccountWithEmailPasswordV2`, `sendFriendRequestV2`, `validateUsernameV2`, `setUsernameV2`, `requestUserDataExport` (khi gate bật), `groupUserOp`/`createGroup`.

### 4.4 Token refresh

- ID token qua RNFB Auth `getIdToken()` / `getIdTokenResult()`; Groups WS gọi `getIdToken(true)` (force refresh) trước khi kết nối.
- **Refresh KHÔNG nằm trong JS bundle:** 0 hit `securetoken`/`grant_type`/`refresh_token`/`expiresIn` trong `decompiled.js`; chỉ có trong native RNFB Auth SDK (dex strings: `securetoken.googleapis.com/v1`, `identitytoolkit.googleapis.com/v2`). Firebase Auth SDK tự refresh qua `POST securetoken.googleapis.com/v1/token`; `grant_type=refresh_token`, `expiresIn=3600` là chuẩn Firebase Auth — **chưa xác minh từ bundle này** (cơ chế này đã xác minh ở app Wan 1.1.9, native `TokenModule`).

## 5. Upload media

- **Không có signed-URL, không có direct-upload HTTP, không có endpoint upload REST** — upload là Firebase Storage SDK thuần.
- **Thumbnail ảnh:** path `users/{uid}/moments/thumbnails/{randomId}{ext}`; `randomId` = **20 ký tự A–Z a–z 0–9 qua `Math.random`** (không phải UUID); `contentType = image/webp` nếu đuôi `.webp`, ngược lại `image/jpeg`; `cacheControl` từ RC `image_upload_cache_control` (default `'public, max-age=604800'`); resize thumbnail theo RC (`quality 0.83`, `size 1020`, `use_webp_thumbnail true`).
- **Video:** path `users/{uid}/moments/videos/{randomId}.mp4`; `cacheControl` từ RC `video_upload_cache_control` (default `'public, max-age=604800'`).
- **Bucket:** ảnh `gs://locket-img`, video `gs://locket-video`, rollcall `gs://locket-rollcall`. Nếu RC bucket getter trả rỗng → fallback **default bucket Firebase `locket-4252a.appspot.com`** (không phải hardcode bucket khác).
- **Download URL:** `ref(fullPath).getDownloadURL()` gọi sau mỗi upload; URL này dùng làm `thumbnail_url` / `video_url` gửi `postMomentV2`.
- **MD5:** `MD5 hex của chuỗi base64 thumbnail` (expo-crypto `digestStringAsync` MD5). Ảnh camera-roll: base64 + `'_' + Date.now()` nối thêm trước khi hash — cùng một ảnh hash khác nhau mỗi lần upload.
- **Rollcall:** path `users/{uid}/rollcall_posts/{a2}/{fileId}.{jpg|webp}` (đuôi `webp` iff contentType `image/webp`, ngược lại `jpg`); metadata `{contentType, cacheControl?, customMetadata: {creator, visibility: 'private'}}`; `putFile` bọc `withAggressiveRetry` rồi `getDownloadURL()`. Ý nghĩa segment `a2` + mapping tham số `processAssetSafely` **unknown**.
- **Đăng bài:** `_createAndPostMoment` build `{thumbnail_url, caption, recipients, overlays, md5}` + streak payload (chỉ khi `enableStreakRecord` và chưa update hôm nay → `{update_streak_for_yyyymmdd: <today YYYYMMDD>}`) + `group: {id, message_client_token: ''}` nếu có group_id + `video_url` nếu có video → gọi `postMomentV2`. Nếu `androidAbusiveContentHandlingEnabled()` → `assertPostMomentSucceeded(result)`; abusive → throw `PostMomentError` (code 451).

## 6. Bảng endpoint (80 callable)

Tổng 80 endpoint trong bảng tên (module 2558, `decompiled.js` dòng 1025528–1026397). 77 endpoint có request fields xác định được, 3 endpoint payload rỗng đã xác minh (`generateInviteToken`, `fetchRewardedReferrals` gọi không arg, `deleteUserAccount` gọi với `{}`), **0 endpoint unknown hoàn toàn**. Field lồng nhau giữ dạng `parent.child`. Mọi callable đều bị merge thêm `analytics` ở tầng `callableFunction` (xem §9) nên `analytics` không liệt kê lại trong từng bảng.

### Auth (16)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `finalizeTemporaryUser` | `first_name`, `last_name`, `require_username`, `username` | status 200 = ok. |
| `sendVerificationCode` | `phone`, `operation`, `device_model`, `use_password_if_available` | 600 = ExistingPhoneNumber (số đã có tài khoản, dialog account_already_exists); 601 = UnknownPhoneNumber; 602 = UsePasswordInstead; 400 = Other; 200 + data.method='firebase' -> tiếp tục Firebase signInWithPhoneNumber. Luồng đổi số @1359495: 600 -> ném PhoneVerificationError 'couldnt_verify_number'. |
| `checkVerificationCode` | `phone`, `verification_code` |  |
| `validateEmailAddress` | `email`, `operation` | 600 = email đã thuộc tài khoản khác (dialog email_account_already_exists, log logAttemptedAddEmailToExistingAccount); 200 = ok. |
| `createAccountWithEmailPassword` | `email`, `password` | Payload r6 = a0 của wrapper, do caller build {email: userEmail, password} (@1857188); response data.token = custom token, dùng signInWithCustomToken. |
| `createAccountWithEmailPasswordV2` | `email`, `password`, `recaptcha_token` | Payload = copy của {email, password} + recaptcha_token; response data.token = custom token. |
| `linkEmailPassword` | `email`, `password` |  |
| `signInWithPhonePassword` | `phone`, `password` |  |
| `sendPasswordResetEmail` | `phone`, `email` | Chỉ 1 trong 2 field tùy loại đăng nhập (phone hoặc email); status 200 = ok, khác -> 'Failed to send password reset email'. Các hit @953154/@957818 là Firebase Auth native SDK (r0.native.sendPasswordResetEmail), KHÔNG phải callable này. |
| `verifyEditedPhoneNumber` | `phone`, `verification_code` | Lỗi nếu data.error có giá trị hoặc status>=400 -> ném PhoneVerificationError ('couldnt_verify_number' / 'make_sure_you_entered_the_verification_code_correctly'). |
| `updateEmailAddress` | `email` |  |
| `validateUsername` | `username` |  |
| `validateUsernameV2` | `username`, `recaptcha_token` |  |
| `suggestUsernames` | `first_name`, `last_name` |  |
| `setUsername` | `username` |  |
| `setUsernameV2` | `username`, `recaptcha_token` |  |
### Moment (9)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `deleteMomentV2` | `owner_uid`, `moment_uid`, `delete_globally` |  |
| `getLatestMomentV2` | `should_count_missed_moments`, `last_fetch`, `sync_token`, `include_group_moments` | status 304 = không có moment mới (bỏ qua xử lý); 200 + data[0] = moment mới nhất (canonical_uid, video_url, thumbnail_url, caption, user, date._seconds/_nanoseconds, overlays, md5, group_id). |
| `postMomentV2` | `thumbnail_url`, `caption`, `recipients`, `overlays`, `md5`, `group.id`, `group.message_client_token`, `video_url` | group chỉ có khi group_id set (group={id, message_client_token}); payload merge thêm getStreakUploadPayload() qua copyDataProperties (field streak không xác định tĩnh được); sau khi post, nếu androidAbusiveContentHandlingEnabled thì assertPostMomentSucceeded. |
| `getSampledMoments` | `start`, `end`, `count` | Log lỗi 'getSampledMoments failed (status ...' khi thất bại. |
| `reactToMoment` | `moment_uid`, `owner_uid`, `reaction`, `intensity` |  |
| `markReactionAsViewed` | `moment_uid`, `reaction_uid` |  |
| `markMomentAsViewed` | `moment_uid` |  |
| `getMomentViews` | `moment_uid`, `end_before` |  |
| `reportMoment` | `owner_uid`, `moment_uid` | status 200 = ok; log 'reportMoment failed' khi lỗi. |
### Bạn bè / Follow (13)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `acceptFriendRequest` | `user_uid` |  |
| `deleteFriendRequest` | `user_uid`, `direction` |  |
| `sendFriendRequest` | `user_uid` | Payload {user_uid} là mutation variables của useSendFriendRequest (vd caller @1587273 build {user_uid}); V2 = copy + recaptcha_token. |
| `sendFriendRequestV2` | `user_uid`, `recaptcha_token` | Payload = copy của {user_uid} + recaptcha_token (khi recaptcha bật; ngược lại gọi sendFriendRequest). |
| `removeFriend` | `user_uid` | status 401 hoặc response.errors khác rỗng -> throw 'error removing friend'. Các hit @1608953/@1774859 chỉ là đọc method removeFriend của hook useFriends(), không phải lời gọi callable. |
| `blockFriend` | `user_uid` |  |
| `unblockUser` | `user_uid` |  |
| `toggleFriendHidden` | `user_uid` |  |
| `suggestFriends` | `contacts` |  |
| `getFriendRecommendations` | `dismissed_user_ids`, `version` |  |
| `getFriendshipStatus` | `user_id` |  |
| `sendFollowRequest` | `celebrity_uid` |  |
| `deleteFollowRequest` | `user_uid`, `direction` |  |
### Group (7)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `getGroupsState` | `current_group_ids`, `last_fetched_at` |  |
| `getGroupChatsUpdates` | `groups` |  |
| `getGroupMessages` | `group_id`, `before_timestamp`, `limit`, `client_cache_options.current_messages` | client_cache_options.current_messages = [{id, created_at}]. Hit @1266555 là định nghĩa wrapper trong group-client, không phải lời gọi callable. |
| `createGroup` | `name`, `image_url`, `users`, `initial_message.content`, `initial_message.client_token`, `initial_message.intensity`, `recaptcha_token` | initial_message là object lồng {content, client_token, intensity} (null-safe). Hit @1266567 là định nghĩa wrapper trong group-client. |
| `updateGroup` | `group_id`, `name`, `image_url` | @1274289 {group_id,name}; @1274358 {group_id,image_url}. |
| `groupChatOp` | `type`, `group_id`, `client_token`, `intensity`, `message`, `moment_id`, `timestamp`, `message_id`, `emoji`, `muted` | Field thay đổi theo op type: @1274738 send-message {type,group_id,client_token,intensity,message,moment_id}; @1274878 {type,group_id,timestamp}; @1274957/@1275249/@1275435 {type,group_id,message_id}; @1275072 {type,group_id,message_id,emoji}; @1275373 {type,group_id,muted}. |
| `groupUserOp` | `type`, `group_id`, `user_id`, `recaptcha_token`, `timestamp`, `fully_remove_self` | @1274433 {type,group_id,user_id,recaptcha_token}; @1274543 thêm {timestamp, fully_remove_self}. |
### Chat (6)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `sendChatMessageV2` | `msg`, `receiver_uid`, `moment_uid`, `client_token` |  |
| `markAsRead` | `conversation_uid` | Wrapper group-client @1266628 chỉ forward arg đầu (không xác định tĩnh được); field conversation_uid từ callsite 1:1 @1732631. |
| `reportMessage` | `message_uid`, `conversation_uid` | Wrapper group-client @1266674 chỉ forward arg đầu (không xác định tĩnh được); field từ callsite @1733030. |
| `deleteChatMessage` | `message_uid`, `conversation_uid` | status 200 = ok. |
| `sendChatMessageReaction` | `conversation_id`, `message_id`, `emoji` | Chung 1 đoạn code với removeChatMessageReaction, chọn send/remove theo flag; call chung @1733567. |
| `removeChatMessageReaction` | `conversation_id`, `message_id`, `emoji` | Chung 1 đoạn code với sendChatMessageReaction, chọn send/remove theo flag; call chung @1733567. |
### User (8)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `fetchUserV2` | `user_uid` | status 200 -> data.data parse qua safeParseUser rồi cache (setUser/setTimestamp); response trả kèm phone=null. |
| `fetchUsers` | `user_uids` |  |
| `requestUserDataExport` | `email` | Payload {email} do submit handler t8 build từ email state (@1478963); khi androidDataExportRecaptchaEnabled thì gửi copy + recaptcha_token. Response data.status 'pending'/'completed' (completed -> file_url). |
| `changeProfileInfo` | `profile_picture_url`, `badge`, `first_name`, `last_name`, `birthday.encoded_mdd`, `username_discoverability_disabled`, `targeted_ads_opt_out` | Mỗi callsite update 1 field riêng: profile_picture_url (@1336449/@1336570), badge (@1357298), first_name+last_name (@1456708), birthday={encoded_mdd} (@1473306), username_discoverability_disabled (@1477608), targeted_ads_opt_out (@1477726). |
| `setUserPreferences` | `preferences` |  |
| `deleteUserAccount` | — (gọi không payload / `{}`) | Gọi với object rỗng {}. |
| `getUserByUsername` | `username` | status 200 = ok. |
| `setNotificationToken` | `notification_token`, `installation_id` | Response validate bằng validateFunctionResponseWithNestedData. Hit @950885 gọi wrapper setNotificationToken của module 2568 với data null (luồng sign-out), không phải callable trực tiếp. |
### Rollcall (9)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `postRollcall` | `week_of_year`, `year`, `items`, `tagged_users` | Payload build @1347500: week_of_year/year từ arg, items = processed assets, tagged_users = arg thứ 5 (optional). Hit @1841806 là lời gọi wrapper postRollcall, không phải callable trực tiếp. |
| `deleteRollcallPost` | `post_uid` |  |
| `postRollcallComment` | `body`, `post_item_uid`, `post_uid`, `post_user_uid`, `reply_user_uid`, `postUid`, `postItemUid`, `postUserUid` | 2 shape: snake_case @1347896 {body,post_item_uid,post_uid,post_user_uid,reply_user_uid} và camelCase @1831102 {body,postUid,postItemUid,postUserUid}. |
| `deleteRollcallComment` | `post_uid`, `post_user_uid`, `post_comment_id` |  |
| `reportRollcallComment` | `post_uid`, `post_user_uid`, `post_comment_id` |  |
| `reportRollcallPost` | `post_uid`, `post_user_uid` |  |
| `likeRollcallComment` | `post_uid`, `post_user_uid`, `post_comment_id`, `like` |  |
| `postRollcallReaction` | `post_item_uid`, `post_uid`, `post_user_uid`, `reaction`, `scale`, `x`, `y`, `rotation`, `postItemUid`, `postUid`, `postUserUid` | 2 shape: snake_case @1348007 {post_item_uid,post_uid,post_user_uid,reaction,scale,x,y,rotation} và camelCase @1835395 {postItemUid,postUid,postUserUid,reaction,rotation}. |
| `getRollcallPosts` | `year`, `week_of_year` | queryFn validate payload bằng GetRollcallPostsRequestSchema; callsite initialData @1293274 truyền thẳng _closure2_slot0 (userUid) — không khớp schema {year, week_of_year}. |
### Referral (5)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `getLinkBasedRewardedReferralSenderState` | `campaign` |  |
| `linkBasedRewardedReferralSenderPhoneOp` | `type`, `phone`, `is_retry`, `verification_code` | 3 dạng call: @1235813 {type,phone,is_retry}; @1235871 {type}; @1236014 {type,phone,verification_code}. |
| `checkLinkBasedRewardedReferralRedemption` | `referral_token` |  |
| `linkBasedRewardedReferralRecipientOp` | `type`, `referral_token`, `phone`, `verification_code`, `is_retry` | @1236073 {type,referral_token,phone,verification_code}; @1236135 {type,referral_token,phone,is_retry}. |
| `fetchRewardedReferrals` | — (gọi không payload / `{}`) | Gọi không có argument. |
### Invite (2)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `generateInviteToken` | — (gọi không payload / `{}`) | Gọi không có argument. |
| `fetchUserForInviteToken` | `invite_token` | Validate response: phải có user data; nếu là celebrity thì phải có celebrity_data. |
### Khác (5)

| Endpoint | Request fields | Ghi chú |
|---|---|---|
| `reportAd` | `auction_id`, `placement_id`, `auction_type`, `content_type`, `markup`, `network`, `reason` | Thành công iff data.status===200 (số 422 trong file là jump target, không phải status code). |
| `sendSupportMessage` | `email`, `body`, `app_build`, `app_version`, `beta_tester`, `tags`, `debug_info` | debug_info chỉ thêm khi androidSendDebugInfo bật; body = device-info + '\n\n' + message; status 200 = ok. |
| `createDynamicLink` | `dynamicLinkInfo` |  |
| `resolveDynamicLink` | `link` | Hit @1673475 là Firebase Dynamic Links SDK resolveDynamicLink(url string, module 3056), KHÔNG phải callable này. |
| `setClientData` | `time_zone`, `experiments.android_rollcall_notifications_v2` |  |

## 7. Realtime — Firestore

- Database mặc định `(default)`: `users`, `moments`, `conversations`. Database history: **`locket`** — `history/{uid}/entries`.
- Collections / listeners (`onSnapshot`) đã xác nhận:

| Path | Ghi chú |
|---|---|
| `users/{uid}` | `getUserByUid` |
| `users/{uid}/friends` | listener |
| `users/{uid}/incoming_friend_requests` | listener |
| `users/{uid}/outgoing_friend_requests` | listener |
| `users/{uid}/conversations` | listener |
| `users/{uid}/deleted_moments` | listener, `orderBy('date','desc')` |
| `users/{uid}/blocked_users` | listener |
| `history/{uid}/entries` (db `locket`) | listener, query `where('user','==',...)` + `orderBy('date','desc')` + `startAt/endAt`; options có thể gồm `{source: 'cache'}` |
| `moments/{uid}/reactions` | listener |
| `conversations/{id}/messages` | listener, `orderBy` |

- Auth metadata: `authorization: Bearer <ID token>` (JS SDK tự gắn; bản Wan dùng gRPC interceptor với metadata tương tự).
- `getLatestMomentV2` parse: `data.errors` non-empty → return sớm; `data.data[0]` = moment `{canonical_uid, video_url, thumbnail_url, caption, user, date: {_seconds, _nanoseconds}, overlays, md5, group_id}`; sau đó `fetchUserByUidWithCache(user)`. Status `304` → dùng cache `LATEST_MOMENT`.

## 8. Groups WebSocket

- URL: `wss://api.locketcamera.com/wss_v2/groups` (RC `groups_websocket_url`, default từ `app.config`).
- Headers: `Authorization: Bearer <ID token>` (force refresh trước khi kết nối), `X-Client-Platform: android`, `X-Client-Version: <app version>`.
- **Receive-only: không có `ws.send` trong code** — client chỉ nhận, không gửi qua WS.
- Message types: `connected` (mở stream khi onopen); `groupUpdates` → `{type, groupId}`; `groupChatUpdate` → `{type, payload: toàn bộ message}`. Type khác bị bỏ qua; lỗi parse → warn.
- Reconnect: manager bọc reconnect + listener `AppState` (disconnect khi không active) + NetInfo (disconnect khi offline).
- Feature modes: `enabled`, `sync_only`, `sync_enabled_no_creation`, `websocket_only`, `disabled`. `computeIsGroupsEnabled` với mode `'sync_only'` **chưa xác minh chắc chắn**.
- Heartbeat: RC `websocket_groups_heartbeat_interval` default `30` — **không có usage trong JS**, cơ chế heartbeat unknown.
- Mapping payload: `groupPayloadToSnapshot` → `{id, lastUpdatedAt, lastReadAt|null, latestMessageJson, metadataJson: {id, name, image_url, muted, users}}`; message → `{id, clientToken, groupId, senderId, sentAt, updatedAt, status: 'sent', bodyJson, reactionsJson}`; validate bằng zod `GroupSchema`.

## 9. Analytics merge

- Payload gửi đi = `merge(userPayload, analyticsParams())` tại `callableFunction` — **analytics được merge vào MỌI callable**, không có callable nào bypass.
- Cấu trúc `analyticsParams()`:

```json
{
  "analytics": {
    "platform": "android",
    "android_build": "<DeviceInfo.getBuildNumber()>",
    "android_version": "<DeviceInfo.getVersion()>",
    "experiments": {"android_flag_1": "<RC getNumber>", "...": "...", "android_flag_10": "<RC getNumber>"}
  },
  "amplitude": {"session_id": "<Amplitude sessionId>", "device_id": "<Amplitude deviceId>"},
  "google_analytics": {"app_instance_id": "<Firebase Analytics appInstanceId>"}
}
```

- `experiments`: `android_flag_1..android_flag_10` (`HELPER_FLAG_COUNT = 10`, đã xác minh); giá trị do server RC quyết (`getNumber('android_flag_N')`, không có default trong code).
- `amplitude` chỉ thêm khi cả `session_id` và `device_id` đều truthy; `google_analytics` chỉ thêm khi `app_instance_id` truthy.
- **Test live cho thấy `postMomentV2` vẫn trả 200 khi KHÔNG có analytics trong body** (xem §11) — server hiện tại không bắt buộc analytics, ít nhất với endpoint này.
- Unknown: `setAnalyticsUserId` dùng Amplitude hay Firebase Analytics; tên event nào bị `isAmplitudeEventRemotelyEnabled` gate (RC `remotely_enabled_amplitude_events`, JSON array); hành vi gate của `android_readops_amplitude_logging` (RC default `false`); usage của `getHelperPerfFlag` (`android_perf_flag_N`).

## 10. Remote Config (key liên quan API)

Fetch: `minimumFetchIntervalMillis = getNumber('refresh_interval') * 1000` (default 43200s), rồi `fetchAndActivate()`. Default `false` là fallback khi server không đẩy RC — giá trị thật trên production do server RC quyết.

| Key | Default |
|---|---|
| `functions_domains` | `'["https://api.locketcamera.com"]'` (từ `app.config` `api.baseUrl`) |
| `storage_bucket` | `gs://locket-img` |
| `video_storage_bucket` | `gs://locket-video` |
| `rollcall_storage_bucket` | `gs://locket-rollcall` |
| `image_upload_cache_control` | `'public, max-age=604800'` |
| `video_upload_cache_control` | `'public, max-age=604800'` |
| `android_upload_thumbnail_image_quality` | `0.83` |
| `android_upload_thumbnail_image_size` | `1020` |
| `android_upload_use_webp_thumbnail` | `true` |
| `android_signup_recaptcha_enabled` | `true` |
| `android_usernames_recaptcha_enabled` | `true` |
| `android_friending_recaptcha_enabled` | `true` |
| `android_group_membership_recaptcha_enabled` | `true` |
| `android_data_export_recaptcha_enabled` | `true` |
| `android_location_caption_recaptcha_enabled` | `true` |
| `android_abusive_content_handling_enabled` | `false` |
| `android_readops_amplitude_logging` | `false` |
| `groups_feature_mode` | `'enabled'` |
| `groups_user_limit` | `16` |
| `groups_websocket_url` | `wss://api.locketcamera.com/wss_v2/groups` |
| `groups_force_full_refetch_on_cold_start` | `''` |
| `websocket_groups_heartbeat_interval` | `30` |
| `max_friends_count` | `20` |
| `android_fetch_users_batch_size` | `20` |
| `android_auth_config` | JSON: `auth_button_config:'bifurcated'`, `create_account_auth_type_config:'email'`, `sign_in_auth_type_config:'email_primary_phone_secondary'`, `prompt_for_email_after_phone_sign_in:true`, `allow_skip_email_after_phone_sign_in:true`, `unlink_phone_number_setting:false`, `use_password_for_phone_auth:true`, `allow_skip_email_existing_user:true`, `prompt_username_sign_up:false`, `prompt_username_sign_in:false`, `allow_skip_username_sign_in:false` |
| `android_rollcall_enabled` | `true` |
| `android_rollcall_banner_config` | JSON `{display_mode:'off', recurring_schedule:null, profile_always:true}` |
| `streak_config` | `{enabled:true, enable_streak_record:true, minimum_display_count:0, widget_streak_visibility_setting_enabled:true, calendar_minimum_streak_visualization_count:1}` |
| `android_camera_video_recording` | `{default:{maximum_duration:2500, size:720, file_type:'mp4'}, gold:{maximum_duration:10000, size:720, file_type:'mp4'}}` |
| `remotely_enabled_amplitude_events` | Không có default (RC `getString`, JSON array) |
| `android_flag_1..10` | Qua `getHelperFlag`, không có default trong code |
| `android_perf_flag_N` | Qua `getHelperPerfFlag`, usage chưa trace |
| `refresh_interval` | `43200` |

Bảng default đầy đủ ~90 keys (kèm `feature_gates`, `paywall_config`, `overlay_items`, history/ads, sentry...) xem `orig/flows_extracted.md` mục 8.

## 11. Bằng chứng test live 2026-11-27

- **Phương pháp:** script Python `orig/test_naive_api.py` — sign-in email/password qua Identity Toolkit REST (kèm header `X-Android-Package`/`X-Android-Cert`) → upload ảnh test lên bucket `locket-img` → `POST postMomentV2` → `fetchUserV2` + `getLatestMomentV2`.
- **Ảnh test:** hình vuông xanh trơn 512×512 tự tạo, không nội dung cá nhân; caption `'test naive api 2024'`.
- **Kết quả:**

| Bước | Kết quả |
|---|---|
| Sign-in Identity Toolkit | 200 OK |
| Upload thumbnail lên `locket-img` | 200 OK |
| `postMomentV2` shape 2024 (`thumbnail_url`/`caption`/`md5` thật/`recipients`/`overlays`, **không analytics**) | **200, tạo moment thật** |
| `fetchUserV2` | 200 OK |
| `getLatestMomentV2` | 200 OK |

- **Probe không auth cùng ngày:** `postMomentV2`/`fetchUserV2`/`getLatestMomentV2` không token → `200 {result:{errors:['Please sign in'],status:401}}`; Bearer giả → `{error:{message:'Unauthenticated',status:'UNAUTHENTICATED'}}`.
- **Kết luận:** API shape 2024 vẫn được backend hiện tại chấp nhận, kể cả không có analytics merge. Moment test đã đăng lên acc test của user, user tự xóa. Email/password acc test **không lưu trong bất kỳ file nào**.

## 12. Chưa xác minh — cần runtime traffic

- Request nào thực sự chạy lúc runtime; `postMomentV2` có bị chặn khi thiếu/khác analytics không (test live chỉ chứng minh 1 lần thành công, không chứng minh mọi trường hợp).
- Giá trị literal của enum `SendVerificationErrorCode`.
- `sendVerificationCode` `operation` ngoài `'sign_in'`/`'change_number'`; `validateEmailAddress` `operation` tại call site truyền bằng biến.
- `grant_type`/`expiresIn` của refresh token (native SDK).
- Ý nghĩa segment `a2` trong path rollcall + mapping tham số `processAssetSafely`.
- `computeIsGroupsEnabled` với mode `'sync_only'`; heartbeat Groups WS.
- `setAnalyticsUserId` dùng Amplitude hay Firebase Analytics; tên event bị `isAmplitudeEventRemotelyEnabled` gate.
- Nội dung đầy đủ các default `JSON.stringify(getDefault…())` (paywall, upsell, quick reactions, camera config...).
- Giá trị return của `finalizeTemporaryUser` khi status ≠ 200.
- Tổng số callable chính xác (một số slot r-number trống chưa đếm lại).
- `fetch_streak`/`excluded_users` mà Wan 1.1.9 gửi trong `getLatestMomentV2` là field cũ đã bỏ ở 1.247.3 hay Wan tự thêm.
- `sent_to_all`/`sent_to_self_only` của Wan là quy ước riêng của Wan — gốc không có 2 field này (0 hit toàn bundle).

## Nguồn

- `orig/evidence_goc/decompiled.js` — bundle gốc decompile (Hermes HBC v96, hermes-dec 0.1.8).
- `REA-Locket-Goc-1.247.3.md` — báo cáo REA gốc đầy đủ.
- `orig/endpoints_extracted.json` — bảng 80 endpoint machine-readable (nguồn của §6).
- `orig/flows_extracted.md` — auth/upload/realtime/analytics/Remote Config chi tiết kèm số dòng.
- `orig/firebase_config.txt` — Firebase config trích từ APK gốc (không gửi kèm file này cho bên thứ ba một cách vô tội vạ — key trong đó là client key public nhưng bị restrict theo app).
- `orig/naive-locket/` — repo hanngoc1406/naive-locket (2024-08) để đối chiếu shape 2024.
- `orig/test_naive_api.py` — script test live đã chạy thành công 2026-11-27.
