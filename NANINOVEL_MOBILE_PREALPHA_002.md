# Naninovel Mobile — Pre-alpha source preparation 002

This is an incremental source update over Pre-alpha 001. It is **not a compiled APK** and has not yet been runtime-tested.

## Changes in this preparation

- Applied the TSOA application icon extracted from the supplied `TSOA.exe` Windows `RT_GROUP_ICON`/`RT_ICON` resources.
- Replaced Android launcher icons for mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi.
- Rebuilt adaptive icon foreground assets as a transparent white emblem over a matching TSOA-red adaptive background.
- Preserved the extracted original 192x192 PNG at `app/src/main/assets/naninovel/tsoa_icon.png` with a provenance note.
- Updated localized app labels in Portuguese and Russian resources to `Naninovel Mobile` so those locales do not display the upstream Winlator name.

## Inherited from Pre-alpha 001

- Android application ID `com.naninovel.mobile`; Java namespace/JNI package remains `com.winlator` to avoid renaming the upstream Java/native JNI surface.
- Application label `Naninovel Mobile`.
- New container resolution default `native`, preserving each phone's actual landscape aspect ratio rather than forcing 16:9.
- New container Vulkan default `Turnip`; OpenGL fallback remains `GLADIO`.
- Zink-only variables removed from the default environment string.
- Java, manifest/provider, and native absolute data/cache/socket paths updated for the new application ID.
- Upstream native modules and driver assets retained for the first test; driver removal is deferred until runtime validation.

## Still not implemented

- Dedicated one-screen launcher and automatic startup into `TSOA.exe`.
- Automatic creation and persistence of the dedicated container.
- Game-data import and validation.
- Return-to-launcher behavior after the game exits.
- Removal of unused graphics drivers and compatibility components.

## Game folder structure

The user-provided screenshots show the Unity Windows game root as `game/`, containing `TSOA.exe`, `UnityPlayer.dll`, `GameAssembly.dll`, `baselib.dll`, `UnityCrashHandler64.exe`, and `TSOA_Data/`. The `TSOA_Data` directory contains 19 visible entries, including `il2cpp_data`, `Plugins`, `Resources`, `StreamingAssets`, and large `.assets.resS` files. The complete folder must be preserved without renaming or partial extraction.

## Game payload packaging decision

Do not put the approximately 7 GB game folder inside a single APK. Keep game data in a separate package or importable folder; the launcher can import it once into app-private storage and then start the configured Windows executable. A separate payload keeps the APK installable and makes game-data updates independent of the launcher.

## Signing

The final order is: package all app code/resources -> `zipalign` -> `apksigner`. Any file added or changed after signing invalidates the signature. The user may retain and use their own private signing key for release builds; do not share the key.

## Build status

No APK compilation or runtime test is claimed. The current workspace previously lacked Android SDK/NDK/Build Tools and could not download missing Gradle dependencies. The next build attempt needs an environment with the required Android toolchain and Gradle artifacts.
