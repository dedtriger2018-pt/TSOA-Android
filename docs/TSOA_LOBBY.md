# Lobby de chuva

The dedicated launcher follows the approved storm-sky reference: centered text over dark clouds, mist and wet pavement. Separate portrait and landscape artwork contains no baked-in text or buttons. Native Canvas layers add automatic rain, drifting mist, puddle ripples and faint text reflections at approximately 30 FPS. Touching the ground adds pooled waves and a small distortion of its reflections; dragging the sky moves the cloud layers slightly. The low-resolution floor mesh runs only while a touch wave is active, with at most six waves at once. They pause when the launcher loses visibility or opens the game. Reduced motion keeps a static scene.

The centered title and text-only buttons adapt to portrait/landscape without the earlier city artwork or orbit HUD. Every button has at least a 48 dp touch target. About opens the game's Steam page directly: https://store.steampowered.com/app/1672670/.

## Settings

`com.winlator.tsoa.TsoaOptions` stores launcher preferences in `naninovel_launcher`, separate from runtime compatibility preferences. Video, input and audio settings apply on the next game launch; ambience settings update immediately.

- Resolution, preserved/stretched aspect, Android system bars and a DXVK frame limit.
- Optional game-only SDR color presets labeled HDR simulado (off by default, Suave or Marcante). These reuse the existing Winlator color pass with a bounded lightness curve and adaptive vibrance. They do not enable HDR output or change the lobby. Manual in-game brightness/contrast/gamma filters remain usable.
- Optional controls: hidden, one ESC button, the existing RTS layout or a custom profile. The existing editor supports key mapping, movement, resizing and element opacity. Creating a custom layout duplicates ESC instead of editing the minimal layout. Reset preserves custom profiles and keeps a copy of the prior ESC layout.
- Cursor sensitivity, tap-to-click and long-press right click. The existing two-finger right click remains available.
- Session audio gain/mute through ALSA/PulseAudio. This does not fix FMOD/device compatibility or change the phone's global volume.
- Rain intensity, background brightness, reduced motion, keep-screen-on and optional haptics.
- Quiet rain/wind ambience with its own enable/volume settings, separate from game audio. It fades in gently and stops immediately before game launch, on focus loss or when the launcher leaves the foreground. It also pauses when headphones are disconnected and avoids interrupting music already playing in another app.
- Optional auto-launch once per launcher instance, with a two-second delay cancellable by touching the background, opening another option or pressing Back. It never starts a game while the launcher is paused.

## HDR-style game color

The enhanced presets replace the earlier small brightness/contrast/gamma offsets. Suave uses strength 0.45; Marcante uses 1.0. A bounded HSL-lightness S curve deepens shadows and lifts highlights, followed by gamut-limited adaptive vibrance. Black, white, neutral grays and saturated primary endpoints remain stable. The shader uses one source texture sample and the same existing color pass; no bloom, LUT texture or additional rendering pass is added. This remains SDR enhancement, not HDR display output.

The game activity can be reused because it is singleTask. It now checks the saved mode on resume and applies it only when the lobby preference actually changed. This fixes stale selections while preserving manual filters when the preference is unchanged. Switching Off clears automatic enhancement; manual color adjustments and other effects remain intact.

`TSOA_FE_DIAG` and exported `redirector.log` include a mode-applied record. The first actual color draw per effect/program/strength also records `hdrStrength`, surface dimensions, initial source framebuffer status and GL error. File logging is posted off the GL thread and no per-frame log is emitted. This distinguishes a selected menu mode from an actual shader draw on the phone.

`python3 tools/tests/test_tsoa_game_color.py` exercises the production Java helper/effect with minimal platform interfaces: mode reuse, live uniform changes, bounded strength, Off behavior and preservation of manual effects. Host validation additionally compiled and rendered the exact Java-generated GLSL on Mesa GLES3, checking finite/gamut-bound output, Off identity, neutral/primary preservation and visible color changes. It does not establish device FPS or validate the Honor's BGRA framebuffer; those require the device draw diagnostic and visual test.

## Ambient audio

`assets/tsoa_lobby_rain.wav` is a 30-second, 24 kHz, 16-bit stereo excerpt of a real rain recording by kvgarlic, released under CC0. Four seconds of overlap create a continuous loop; the original stereo field is preserved. Source, license, preparation steps and integrity metrics are documented in [TSOA_RAIN_AUDIO.md](TSOA_RAIN_AUDIO.md) and its JSON companion.

`AudioTrack.MODE_STATIC` holds one PCM buffer of 2,880,000 bytes. Its infinite loop points use audio frames, rather than restarting a player after completion. Loading and PCM validation happen on a worker, the sound fades in at 18% gain by default, and pauses immediately before the game or on leaving the foreground. The file reader rejects malformed/oversized WAVs. `tools/generate_tsoa_ambience.py --check` validates the shipped asset with Python's standard library; preparing it again requires the verified source recording, host FFmpeg and NumPy. These tools are not Android dependencies.

## Saves and diagnostics

Android's document picker exports/imports a ZIP containing only the game's actual Unity save/configuration directory:

`files/rootfs/home/xuser/.wine/drive_c/users/xuser/AppData/LocalLow/Caramel-Mocha Studio/TSOA_FE`

Restoration validates a staging directory, then preserves the previous directory before installing the incoming saves. Absolute paths, traversal, backslashes, duplicate paths, foreign files and archives exceeding 256 MB unpacked/8192 entries are rejected. Logs and symbolic links are excluded from save exports. JOGAR is blocked during maintenance I/O.

Diagnostics export the last 4 MB of available Redirector/Unity logs and device/app metadata. File verification checks presence and nonzero size of essential files; it does not claim Steam checksum verification.

## Silent loading

The `Iniciando` and `Instalando Arquivos do Sistema` overlays are Android dialogs, not loading PNGs. Their window alpha and dim amount are zero only in the dedicated launcher/game. Their progress state, touch blocking, extraction, dismissal and callbacks remain intact. Legacy editor dialogs keep normal visibility. Explicit game-folder import progress and actual error messages remain visible in the lobby.

## Game audio configuration

Earlier device logs show native libasound looking for the old package's `usr/share/alsa/alsa.conf`, then reporting `Unknown PCM default`; FMOD subsequently reports that no audio device initialized. The packaged top-level ALSA configuration itself includes an absolute `com.winlator` path, so selecting that file under the current root is insufficient.

When the Android ALSA server is present, `TsoaAudioConfig` selects the complete packaged `etc/alsa/conf.d/android_aserver.conf` through `ALSA_CONFIG_PATH` and its current `usr/lib/alsa-lib` through `ALSA_PLUGIN_DIR`. The server socket, audio driver, shared-memory policy, libraries and Wine/Box64 settings are preserved. Missing optional files produce a warning without blocking the game. The selected paths are included in the Redirector launch log for device diagnostics. Native libasound configuration resolution and the runtime helper tests cover the path selection; audible FMOD playback still requires a device test.

## Runtime compatibility

The working Honor Android 13 setup from `226e818` is preserved: pinned patchelf 0.19.3, the exact verified Box64 binary, Redirector V2/preload behavior, esync setting, display socket and Turnip paths. This patch does not replace native libraries or reset compatibility preferences; it additionally selects the current package paths for the existing ALSA bridge.

## Validation

Run `python3 tools/tests/test_tsoa_save_archive.py` with a full JDK 17+. The audio asset and bounded PCM loader are checked with `python3 tools/generate_tsoa_ambience.py --check` and `python3 tools/tests/test_tsoa_pcm_loop.py`. The existing runtime helper harness now also covers ALSA path selection and its nonfatal fallback. The test exercises real files, save round trips, preservation of the old saves, new installations and malformed/unsafe/oversized ZIP rejection. GitHub Actions runs it before the normal Android APK build, alongside the existing runtime checks.

On the device, verify portrait/landscape, touch waves and cloud dragging, return from the editor/Steam/game, animation and sound suspension, several full audio loop cycles, ambience volume/mute and audio focus/headphone changes, optional game-only HDR presets, ESC and RTS, save export/restore, game audio controls and auto-launch cancellation. A Java compile alone does not verify rendering or gameplay on the Honor.
