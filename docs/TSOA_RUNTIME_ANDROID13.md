# TSOA_FE runtime fixes for Android 13

The existing debug APK at `66f51bf` became playable on an Honor X8 with
Android 13 after applying the runtime corrections below. The successful
device log identifies DXVK 2.4.1, Mesa Turnip 26.2.0 / Adreno 610 and
Direct3D 11.0, feature level 11.1. These changes incorporate those corrections
into the APK build and guest startup; the rebuilt APK still needs a device test.

## Corrections

- `BOX64_FORCE_LD_PRELOAD=0`: `RTLD_NEXT` must skip the Redirector itself.
  With the flag set to 1, its `realpath` hook resolved back to itself and
  recursed until the guest stack overflowed.
- Relocate the built-in Box64 ELF interpreter and RUNPATH. Starting through
  an explicit ARM64 loader hid its old `com.winlator` interpreter path;
  Wine's child-process re-execution subsequently failed.
- `WINEESYNC=0`: the tested device rejected esync's POSIX shared-memory
  initialization. The graphics, audio and Box64 preset selections are retained.
- `DISPLAY=<current rootfs>/tmp/.X11-unix/X0`: native ARM64 XCB contains a
  socket base compiled for `com.winlator`. An absolute socket path is supported
  by the packaged XCB and reaches the running XServer.
- Generate a private copy of the Turnip ICD with its `library_path` pointing
  to the installed driver under the current rootfs, then select it with
  `VK_DRIVER_FILES`. The original manifest and its API/architecture metadata
  are preserved. Native ARM64 Vulkan-loader accesses bypass the x86 interposer.

These startup corrections apply when Redirector V2 is explicitly requested,
including dedicated TSOA launch. The relocated built-in Box64 is verified by
SHA256 and size, including on upgrades where `current_box64_version` was
already `0.4.4`. A replacement is staged, verified and installed atomically;
setup failure is logged before starting Wine or its WinHandler receiver.

Existing home `.box64rc` diagnostics and the manually generated ICD files are
left intact. Fresh installs receive the required environment without creating
or editing a home RC. Verbose Wine, Vulkan-loader and DXVK logs are optional.

## Build

The GitHub Actions debug workflow prepares and verifies the Box64 asset before
Gradle assembles the APK. Do not commit the generated replacement `.tzst`.
The original asset is pinned by archive and binary SHA256; the build changes
only PT_INTERP/RUNPATH while checking code sections, TLS, dependencies and
64 KiB PT_LOAD alignment. It generates `box64/tsoa-relocation.json` in assets
for the runtime installer's integrity/path checks.

For a manual Linux build, install Python 3.9+, `patchelf`, `zstd`, `binutils`
and the existing Android SDK/NDK/JDK requirements, then run:

```sh
python3 -m unittest discover -s tools/tests -p 'test_prepare_tsoa_box64.py' -v
python3 tools/prepare_tsoa_box64.py
python3 tools/prepare_tsoa_box64.py --check
./gradlew --no-daemon :app:assembleDebug
```

Run the packaging tests before preparation: they intentionally verify the
tracked original archive. Preparation itself supports repeated execution.
The generated metadata and reports under `build/` are not source files.

The interpreter is prepared for the tested application ID and Android user 0:
`/data/user/0/com.naninovel.mobile/files/rootfs`. Changing the package/user
requires a corresponding Box64 build path. A path mismatch fails explicitly
rather than starting an incompatible executable.

## Verification and remaining work

Packaging tests inspect the actual ARM64 asset without executing it. Java
helper tests run real JSON parsing and filesystem operations with minimal
Android interface stubs; they cover relocation, preservation, repeated
installation and failure without replacing usable files. The workflow runs
these checks before building. They do not replace testing the APK on Android.

Confirm the rebuilt APK finds the current ICD and reports Adreno 610 / DXVK
and Direct3D initialization. The launcher's JOGAR flow remains as before;
automatic game launch directly from the application icon is separate work.

The successful device log also reported FMOD without an audio device, a
WindowsVideoMedia DXGI DeviceManager limitation and a remote DNS failure.
Playability/rendering were confirmed; audio, video and remote features were
not independently verified. No audio workaround is included here.

The existing workflow uses an automatically generated debug signing key on
each fresh runner. A new artifact is not guaranteed to update the installed
APK with the same signature; stable signing is separate configuration. Keep
the tested installation and game data when evaluating the new build.
