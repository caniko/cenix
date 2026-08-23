# 0002. No INTERNET permission

The APK must not declare `android.permission.INTERNET`. Filtering and launch stay on-device. `tools/` audits the source manifest and APK bytes for that string.
