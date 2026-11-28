# Unread conversation badge

Based on the supplied unread-count schema audit of original 1.247.3:
- DM unread means explicitly is_read == false. Keep existing mapper, model and Room 0/1 flag. Missing flag does not count.
- The fixed chat navigation counts conversations with the flag, locally from the existing StateFlow. No extra requests or message scans.
- Conversation rows show a yellow 14dp dot and stronger preview/time, not a numeric per-message badge. The navigation badge is a yellow count with display cap 99+ (accessibility exposes actual count).
- Existing authenticated conversation Listen and successful markAsRead handling are unchanged.
- Groups not implemented; do not fabricate group count or assume a DM unread_count field.
- Not compiled or verified on a real account in this change.
