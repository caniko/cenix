package com.caniko.cenix

import com.caniko.cenix.uniffi.DiagnosticsConfig
import com.caniko.cenix.uniffi.initDiagnostics
import com.caniko.cenix.uniffi.nativePanicked

object NativeDiagnostics {
    @JvmStatic
    fun install() {
        initDiagnostics(
            DiagnosticsConfig(
                level = if (BuildConfig.DEBUG) "debug" else "warn",
                releaseRedaction = BuildConfig.REDACT_LOGS,
            ),
        )
    }

    @JvmStatic
    fun panicked(): Boolean = nativePanicked()
}
