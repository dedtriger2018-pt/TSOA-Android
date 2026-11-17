# Experimental full launcher: 3D lobby

This experiment is a complete copy of the working `1ccdda4` launcher. It replaces only the lobby scenery with an OpenGL ES 3 courtyard; game launching, Wine/Box64, game audio, saves, controls, translations and game filters are preserved. The source lives on `experiment/lobby-3d`, separate from `tsoa-dedicated`.

The scene uses actual perspective geometry, instanced architecture and rain, GGX/Smith/Schlick metallic/roughness lighting, a procedural wet floor, three moving point lights, fog/clouds and lightning. A depth texture drives depth of field. Quarter-resolution blur supports DOF and Bloom; no external model/image dependency is required. It uses analytic environment reflections rather than traced object reflections or shadow maps.

The `3D` corner button changes PBR, lights, DOF, Bloom, rain, focus, exposure, 640/960/1280 internal resolution and 30/60 FPS. Defaults are 960 and 30 FPS. Drag the scenery to move the camera and touch the floor for ripples. Existing reduced-motion mode freezes animation. The GL context is destroyed when the lobby pauses or the game opens, freeing scene resources; it rebuilds on return. Unsupported/failed rendering falls back to the previous lobby and keeps the Play button usable.

The package remains `com.naninovel.mobile` because the tested native runtime uses its paths. It does not install alongside the normal launcher. Export saves before replacing it; a different debug certificate requires uninstalling the previous APK. No phone installation or data removal is automated.

The experimental GitHub workflow performs the normal full source/NDK build and uploads `TSOA-Lobby3D-Lab-debug`. The locally supplied APK was built by compiling the actual modified Java against the exact working APK, filtering replaced MainActivity definitions through dexlib2 and adding the new D8 output. Native libraries, assets, resources and manifest remain byte-identical to that baseline. Signature, ZIP alignment/CRC, class ownership, duplicate types and instruction/reference integrity are independently checked. This local certificate is for the disposable experiment only.

Host GLES validation renders the exact Java mesh/shader data through color/depth/blur/composite passes in landscape and portrait. All five programs and RGBA16F/RGBA8 depth framebuffers passed; DOF, PBR, light, rain and Bloom toggles measurably affect the image. These checks validate rendering math and packaging, not Android lifecycle, Honor performance or game execution of this experimental APK.
