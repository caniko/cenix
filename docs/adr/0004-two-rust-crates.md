# 0004. Two Rust crates

`cenix-core` is safe Rust with no JNI. `cenix-jni` is the only `unsafe` crate and only for the JNI entrypoint. Kotlin never compiles Rust; it loads `libcenix_jni.so`.
