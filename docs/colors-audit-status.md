# Original Locket palette alignment

Basis: uploaded locket-colors-audit.md, original 1.247.3, audited baseline ff250b5. Applied against main 7837563, not the old audit checkout.

- Root #060505, modal #171717, sheet #242424, sheet surface #2B2B2B, avatar #383737, search #191919, journal day #252525.
- Accent #FFB703, text on yellow #1A1200, destructive #FF4343, distinct Gold #FFB800. Preserve optional custom camera themes and launcher designs.
- Shared Home/Memory backdrop decodes the actual image at 16px, scales it and uses a black 80% scrim. This is the low-cost approximation recommended by the audit, not an exact reproduction of the original native blur; no dominant-color sampling.
- Chat uses neutral chrome gradients at the audit's alpha stops, received #333333, sent white 90%, composer #191919. The removed implementation actually sampled shared photos at the current HEAD, rather than the avatar mentioned in the older audit.
- Fixed nav uses #201F1F at 92% opacity with existing glass border.
- Android window colors and default launcher accent aligned. Preserve existing resource names; no native asset cleanup without confirming references.
- No change to unread counts, network schema, layout, or build triggers. No CI/device visual verification performed. Camera preview native colors, exact login/Home composer colors and original splash remain unverified.
