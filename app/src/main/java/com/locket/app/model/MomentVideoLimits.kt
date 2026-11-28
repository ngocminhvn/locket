package com.locket.app.model

/** Owner-configured limits shared by recording, import and upload. */
internal object MomentVideoLimits {
    const val MAX_DURATION_MS = 20_000L
    const val MAX_BYTES = 100_000_000L
}
