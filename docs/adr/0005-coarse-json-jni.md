# 0005. Coarse JSON JNI

The native boundary is one `byte[] -> byte[]` call. Version, bounds, and panic are encoded as structured JSON errors. Kotlin treats any failure as emergency mode.
