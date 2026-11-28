package com.locket.app.ui

internal enum class MomentUploadRecovery { RESUME, REQUIRE_USER, CLEANUP }

internal fun uploadRecovery(confirmed: Boolean, attemptedPost: Boolean, failed: Boolean): MomentUploadRecovery = when {
    confirmed -> MomentUploadRecovery.CLEANUP
    attemptedPost || failed -> MomentUploadRecovery.REQUIRE_USER
    else -> MomentUploadRecovery.RESUME
}
