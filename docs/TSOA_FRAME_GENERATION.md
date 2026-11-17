# Experimental native 2× frame generation

The Video settings contain one off-by-default option, **Geração de quadros 2× (experimental)**, with matching English and Simplified Chinese menu text. It changes only the Android game renderer. It does not change Wine, Proton, Box64, the Turnip driver, the game files, source FPS limit, or the existing HDR/bloom/FXAA/CAS/FSR settings.

This first version has one conservative profile. It estimates bidirectional motion with GLES 3 shaders and synthesizes at most one intermediate image per retained pair of real scenes. It does not require proprietary optical-flow hardware or a purchased DLL. GLES 3 availability does not establish performance or correct driver behavior on every device; the Honor X8/Adreno 610 still needs device validation.

## Pipeline

1. Visible X window damage identifies changed source content. Cursor movement and extra display requests do not identify new game frames. Several damage notifications may coalesce into one captured scene; the measured cadence describes retained scene updates, not a guest FPS counter.
2. The existing renderer applies spatial filters and color/HDR/bloom into a separate RGBA8 history target. The software cursor is excluded from history. Two targets retain the previous/current complete processed scenes.
3. A pure Java clock learns the monotonic source cadence and schedules previous/current real images plus at most one midpoint. It keeps approximately one source-period of presentation latency once active, respects the panel refresh limit, and skips missed intermediate slots rather than queuing them. Near/above the panel rate it uses normal rendering without history allocation.
4. Only when a midpoint has a display slot, the GPU builds a two-level image pyramid and forward/backward block-matching flow. Nine small preparation passes are reused for the pair. Search displacement is bounded to 32 processed-image pixels per axis; the fine grid is at most quarter resolution and 320 × 180 pixels.
5. One presentation pass reprojects the two real scenes. Reciprocal confidence, photometric disagreement, occlusion, stationary/saturated UI and a GPU scene-change check select the current real pixel when synthesis is uncertain. Fraction 0/1 copies the exact previous/current texture. There is no extrapolation or plain whole-frame blending substitute.
6. The latest mouse cursor is drawn after presentation. Android ESC/RTS controls are outside the captured scene. Generated submissions never release guest buffers, send X Present completion, or advance Wine pacing.

`Choreographer` schedules bounded main-thread display callbacks; GL work remains on the renderer thread. At most one future callback and one source wake are queued. A static source drains its pending presentation then stops scheduling. Pause/detach remove callbacks and invalidate history; resolution, geometry, effects and EGL changes invalidate incompatible frames. Magnifier/cursor-relative panning use the normal rendering path.

There is no 20-FPS hard gate: the clock adapts across source rates while respecting the display capacity. For example 20→up to40 and30→up to60 are possible on a60Hz panel;45FPS cannot become90 visibleFPS on that panel. Non-divisor rates have uneven display intervals and skipped midpoints. A source gap of250ms resets history; large/uncertain motion falls back to real pixels. Interpolation does not improve game simulation/input response and adds latency.

## Resources and fallback

The two full-size RGBA8 histories cost `width × height × 8` bytes:20,736,000 bytes at2400×1080. Flow/image/scene targets together use at most1,152,004 bytes. A48MiB history budget bounds allocation. No history/flow textures are allocated while disabled or while the source cadence saturates the display.

Framebuffer completeness, shader compilation and link status are checked. Unavailable history/flow, a failed presentation or eight consecutive GL submissions exceeding twice the panel period bypass generation for the session and keep normal game rendering. Submission timing measures CPU/driver stalls, not GPU duration or physical displayFPS. The setting remains saved, and returning to a game activity retries the requested mode. Invalid EGL contexts are never used for GL deletion; retained contexts receive deferred cleanup.

The option is experimental. A low-resolution motion budget limits cost but cannot guarantee a speed gain, artifact-free text/occlusion, exact2× presentation, or Snapdragon-wide compatibility. No new native library is included.

## Device check

Export saves before replacing an APK if installation requires uninstalling it. First test the existing960×544 game setting and enable only frame generation among the new optional filters, retaining the already working HDR if desired. An optional30FPS source cap makes a30→60 comparison easier; FG itself does not force that cap.

Check camera/object motion, scene changes, subtitles, ESC/mouse responsiveness, pause/resume and returning from the lobby. If performance or artifacts worsen, turn generation off. Review real captures and generated submissions separately:

```sh
adb shell run-as com.naninovel.mobile tail -n 300 files/tsoa_redirector_v2/redirector.log | grep -F 'Frame generation:'
```

Active summaries are rate-limited to five seconds and record source period, real captures, generated submissions, skips and history dimensions. A bypass reason is logged once on failure. These counters are not a physical screenFPS measurement; a device trace/video is needed to establish delivered cadence and frame pacing.

## Validation and references

GitHub Actions runs actual production Java clock, presenter, interpolation-resource and widget-pacing harnesses before the normal APK build:

```sh
python3 tools/tests/test_tsoa_frame_clock.py
python3 tools/tests/test_tsoa_frame_interpolation.py
python3 tools/tests/test_tsoa_frame_presenter.py
python3 tools/tests/test_tsoa_frame_pacing.py
```

The clock tests cover30/20/10/45/60+FPS, jitter, coalescing, rate/refresh changes, source ordering, late/stalled sources and finite scheduling. Resource tests trace actual helper GL calls with platform doubles. Presenter tests verify monotonic displayed content, native captures, independent live cursor, history limits, resize/context recovery and failure latches. Widget tests exercise the actual view against deterministic Android scheduling interfaces. These JVM doubles do not measure GPU execution or Android display latency.

Independent host EGL/GLES3 rendering validates the exact Java-generated shaders: moving objects appear between real positions and differ from both current-only output and simple frame blending; static fields, scene cuts, bounded motion, alpha, odd/one-dimensional textures and occlusion remain finite and bounded. Android API35/AAPT2 and D8 verify source/resources/bytecode separately. Host results do not establish Adreno610 speed or stability.

Design research informed the compositor integration; this GLES block matcher is an original implementation, not an imported WFG/GameHub engine:

- [WinNative frame generation](https://github.com/WinNative-Emu/WinNative/blob/main/docs/FRAME-GENERATION.md)
- [WinNative compositor design](https://github.com/WinNative-Emu/WinNative/blob/main/docs/lsfg-frame-generation.md)
- [Win-FG Native compositor lessons](https://github.com/The412Banner/win-fg/blob/master/README.md)
- [Android Choreographer](https://developer.android.com/reference/android/view/Choreographer)
- [Android GLSurfaceView](https://developer.android.com/reference/android/opengl/GLSurfaceView)
