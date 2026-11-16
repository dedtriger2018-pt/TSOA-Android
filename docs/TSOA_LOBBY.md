# Lobby de chuva

The dedicated launcher follows the approved storm-sky reference: centered text over dark clouds, mist and wet pavement. Separate portrait and landscape artwork contains no baked-in text or buttons. Native Canvas layers add automatic rain, drifting mist, puddle ripples and faint text reflections at approximately 30 FPS. They pause when the launcher loses visibility or opens the game. Reduced motion keeps a static scene.

The centered title and text-only buttons adapt to portrait/landscape without the earlier city artwork or orbit HUD. Every button has at least a 48 dp touch target. About opens the game's Steam page directly: https://store.steampowered.com/app/1672670/.

## Settings

`com.winlator.tsoa.TsoaOptions` stores launcher preferences in `naninovel_launcher`, separate from runtime compatibility preferences. Video, input and audio settings apply on the next game launch; ambience settings update immediately.

- Resolution, preserved/stretched aspect, Android system bars and a DXVK frame limit.
- Optional controls: hidden, one ESC button, the existing RTS layout or a custom profile. The existing editor supports key mapping, movement, resizing and element opacity. Creating a custom layout duplicates ESC instead of editing the minimal layout. Reset preserves custom profiles and keeps a copy of the prior ESC layout.
- Cursor sensitivity, tap-to-click and long-press right click. The existing two-finger right click remains available.
- Session audio gain/mute through ALSA/PulseAudio. This does not fix FMOD/device compatibility or change the phone's global volume.
- Rain intensity, background brightness, reduced motion, keep-screen-on and optional haptics.
- Quiet rain/wind ambience with its own enable/volume settings, separate from game audio. It fades in gently and stops immediately before game launch, on focus loss or when the launcher leaves the foreground. It also pauses when headphones are disconnected and avoids interrupting music already playing in another app.
- Optional auto-launch once per launcher instance, with a two-second delay cancellable by touching the background, opening another option or pressing Back. It never starts a game while the launcher is paused.

## Ambient audio

`assets/tsoa_lobby_rain.wav` is an original synthesized 20-second, 24 kHz, 16-bit mono loop. It mixes filtered noise and damped water-like grains, with no music or external recording. `tools/generate_tsoa_ambience.py` reproduces and checks it with host NumPy; NumPy is not an Android or build dependency. The asset is checked for clipping, RMS/peak bounds and a zero-step loop boundary. MediaPlayer opens the uncompressed WAV asset, manages audio focus and fades from silence at 18% gain by default. The renderer and sound do not run behind the game.

## Saves and diagnostics

Android's document picker exports/imports a ZIP containing only the game's actual Unity save/configuration directory:

`files/rootfs/home/xuser/.wine/drive_c/users/xuser/AppData/LocalLow/Caramel-Mocha Studio/TSOA_FE`

Restoration validates a staging directory, then preserves the previous directory before installing the incoming saves. Absolute paths, traversal, backslashes, duplicate paths, foreign files and archives exceeding 256 MB unpacked/8192 entries are rejected. Logs and symbolic links are excluded from save exports. JOGAR is blocked during maintenance I/O.

Diagnostics export the last 4 MB of available Redirector/Unity logs and device/app metadata. File verification checks presence and nonzero size of essential files; it does not claim Steam checksum verification.

## Silent loading

The `Iniciando` and `Instalando Arquivos do Sistema` overlays are Android dialogs, not loading PNGs. Their window alpha and dim amount are zero only in the dedicated launcher/game. Their progress state, touch blocking, extraction, dismissal and callbacks remain intact. Legacy editor dialogs keep normal visibility. Explicit game-folder import progress and actual error messages remain visible in the lobby.

## Runtime compatibility

The working Honor Android 13 setup from `226e818` is preserved: pinned patchelf 0.19.3, the exact verified Box64 binary, Redirector V2/preload behavior, esync setting, display socket and Turnip paths. This UI change does not replace native libraries or reset compatibility preferences.

## Validation

Run `python3 tools/tests/test_tsoa_save_archive.py` with a full JDK 17+. The test exercises real files, save round trips, preservation of the old saves, new installations and malformed/unsafe/oversized ZIP rejection. GitHub Actions runs it before the normal Android APK build, alongside the existing runtime checks.

On the device, verify portrait/landscape, return from the editor/Steam/game, animation and sound suspension, ambience volume/mute and audio focus/headphone changes, ESC and RTS, save export/restore, game audio controls and auto-launch cancellation. A Java compile alone does not verify rendering or gameplay on the Honor.
