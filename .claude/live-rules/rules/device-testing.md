---
description: Device testing on the shared emulator
priority: 15
---
- Run `adb devices` before deciding a change can't be device-tested. A shared redroid Android 15 emulator (`emulator-5554`) is normally attached and approved for AkaStudio.
- Code touching device-only APIs (ContentResolver/MediaStore, ExifInterface on real files, GL/EGL, MediaPipe/LiteRT, platform codecs, Compose window/BACK behaviour) needs an instrumented test in the module's `src/androidTest`, run on the emulator. JVM and screenshot tests alone don't verify those seams.
- Wrap every adb or `connected*AndroidTest` command in the device lock: `ANDROID_SERIAL=emulator-5554 flock $HOME/.cache/akastudio-emulator-5554.lock <command>`.
- On the device, install/uninstall only `com.akane.akastudio` and its `.test` instrumentation packages. Never touch `com.akane.inspiration` or other apps, and never change global settings. Test media goes under `/sdcard/Pictures/AkaStudioQA/` or `/sdcard/Download/`.
- A device test that exposes a product bug stays failing and gets reported; never delete or `@Ignore` it to go green.
