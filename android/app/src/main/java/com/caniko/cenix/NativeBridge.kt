package com.caniko.cenix

object NativeBridge {
    @Volatile
    var loaded = false
        private set

    @Volatile
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("cenix_jni")
            loaded = true
        } catch (error: UnsatisfiedLinkError) {
            loadError = error.message
        }
    }

    @JvmStatic
    external fun filterAndOrderApps(request: ByteArray): ByteArray?

    fun filter(request: ByteArray): FilterOutcome {
        if (!loaded) {
            return FilterOutcome(false, "", emptyList(), "UNAVAILABLE")
        }
        return try {
            FilterProtocol.decodeResponse(filterAndOrderApps(request))
        } catch (error: Throwable) {
            FilterOutcome(false, "", emptyList(), "JNI")
        }
    }
}
