# Naninovel Mobile — Pre-alpha source preparation 001

This is a source preparation based on the user-supplied `winlator-app-main` source archive. It is **not a compiled APK** and has not yet been runtime-tested.

## Changes in this preparation

- Android application ID changed to `com.naninovel.mobile`; Java namespace/JNI package remains `com.winlator` to avoid renaming the upstream Java/native JNI surface.
- Application label changed to `Naninovel Mobile`.
- New container resolution default changed to `native`. The upstream `XServerDisplayActivity.resolveScreenInfo("native")` already resolves the current display width and height (rounded down to even values), so the game uses the phone's actual landscape aspect ratio instead of forcing 16:9.
- New container Vulkan default changed from Vortek to Turnip; OpenGL fallback remains GLADIO.
- Removed Zink-only variables from the default environment string.
- Updated Java, manifest/provider, and native absolute data/cache/socket paths for the new application ID.
- Kept the upstream native modules and driver assets intact for the first test; removal is deferred until the launcher starts successfully on device.

## Still not implemented in this preparation

- Dedicated one-screen launcher and automatic startup into the Naninovel executable.
- Automatic creation and persistence of the dedicated container.
- Import of the game data package and persistence of the executable path.
- Return-to-launcher behavior after the game exits.
- Driver/package pruning.

These items depend on the exact game executable path and on validating the actual working Winlator container settings. They should not be guessed.

## Game payload packaging decision

Do not put the approximately 7 GB game folder inside a single APK. APKs are ZIP-based and the Android APK signature parser explicitly rejects ZIP64 APKs. A package larger than the classic 32-bit ZIP addressing range would require ZIP64. Use a separate game-data package (for example, a `.npak`/archive or a folder selected once by the launcher), then copy/import it into the app's private data directory. The user-facing experience can still be one-tap after first setup.

For Google Play, current published limits are smaller still: base module 500 MB, each asset pack 1.5 GB, all install-time modules/asset packs 4 GB, and on-demand/fast-follow asset packs cumulatively 30 GB.

## Signing

The final order is: package all app code/resources -> `zipalign` -> `apksigner`. Any file added or changed after `apksigner` invalidates the APK signature. The user may retain and use their own private signing key for all release builds. Do not share that key.

## Build environment blocker in the current workspace

The workspace has OpenJDK 21 and CMake, but no Android SDK, Android platform `android.jar`, NDK 24.0.8215888, Android Build Tools, or cached Gradle distribution/Android Gradle Plugin. Network DNS is unavailable in the build container, so these dependencies cannot be installed here during this run. This source preparation therefore cannot honestly be reported as a compiled or installable APK.
