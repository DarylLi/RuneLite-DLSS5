# RuneLite DLSS5 / WebGPU

An experimental Windows renderer for Old School RuneScape, developed by
**[SamGCoder](https://github.com/SamG-Coder)** on top of
[RuneLite](https://github.com/runelite/runelite) and [117 HD](https://github.com/117HD/RLHD).

The project replaces scene rendering with a standalone WebGPU/Vulkan renderer,
with hardware ray-query shadows and water reflections, 117 HD materials, and
NVIDIA DLSS Super Resolution/DLAA where supported.

**Status:** active graphics research. The current changes have passed synthetic
GPU tests on an RTX 5080, but are not a claim of finished AAA graphics, complete
shadow correctness, or a measured gameplay FPS improvement. The latest build
still needs a logged-in visual review.

## Current rendering work

- Preserve the authored 256×256 HD textures instead of reducing them to 128×128.
- Isolated material texture arrays with full mip chains, trilinear filtering,
  and 8× anisotropic filtering; no neighbouring-material atlas bleed.
- Linear-light, alpha-weighted color mips and vector-normalized normal mips.
- Raster sunlight guides keep shadowing consistent with mapped/smoothed normals
  and preserve unlit materials. Receiver-plane checks reduce cross-surface leaks.
- Water reflections use hardware geometry hits, roughness-dependent sample counts,
  and depth-validated visible-hit material colors. Off-screen hits retain a simpler
  triangle-material fallback. Reflections are currently water-only.
- DLAA and DLSS Quality work with offset and resized 3D viewports. GPU work is
  drained before replacing size-dependent NGX resources.
- Existing progressive chunk capture, roof visibility, regional water colors,
  opaque-before-water ordering, and temporal guides are retained.

## DLSS naming and availability

The repository name reflects the experimental project. Public build defaults
use the public Streamline SDK and **do not require or distribute DLSS 5 preview
binaries**. The optional Neural Rendering path needs separately provisioned,
authorized, matching NVIDIA runtimes and is off by default. A DLL filename alone
is not evidence that a feature works; capability checks and evaluation counters
are used. Frame Generation and Ray Reconstruction are capability-dependent and
were unavailable in the tested preview configuration.

## Build on Windows

Requirements: JDK 21, CMake 3.24+, LLVM-MinGW (clang/clang++/mingw32-make), a Vulkan
SDK with glslangValidator, and the project-specific patched wgpu-native library.

The public source includes the custom WebGPU headers but **does not include a
prebuilt patched wgpu-native DLL/import library**. Before building, provision
`native/third_party/wgpu-native/lib/wgpu_native.dll` and
`libwgpu_native.dll.a` matching the supplied headers and the pinned versions in
[native dependency notes](native/third_party/README.md). A stock wgpu-native
release lacks the native Vulkan interop exports this renderer requires. The
complete patch/build recipe for that dependency remains a publication follow-up;
a fresh checkout alone cannot yet build the native renderer.

```powershell
# Public SDK configuration; downloads the pinned official Streamline SDK.
.\native\Build-Native.ps1
ctest --test-dir native/build --output-on-failure

# Set JAVA_HOME to your JDK 21 installation first.
cd plugins/117-hd
.\gradlew.bat test webGpuDistributionJar --console=plain
```

The modified `plugins/117-hd` source is included directly in this repository.
No second fork/submodule checkout is needed. Java builds resolve RuneLite 1.12.x
from its Maven repository; native dependency provisioning is separate.

Only if you already have authorized matching experimental runtimes:

```powershell
.\native\Build-Native.ps1 -EnableNeuralRendering
```

The Java distribution JAR is written to
`plugins/117-hd/build/libs/RuneLite-DLSS5.jar`; native files are staged under
`.runtime/runelite-webgpu`. Neither is committed to Git.

## Jagex launcher installation

After building/provisioning the runtime, use the existing RuneLite installation:

```powershell
.\Install-JagexOverride.ps1 -SkipBuild
# For the separately provisioned experimental build, also pass -EnableNeuralRendering.
```

The installer checks the JAR and dependencies, snapshots the previous client,
installs binaries with SHA-256 verification, and updates RuneLite's launch
configuration. It can stop the installed RuneLite process during replacement.
Launch RuneLite normally through Jagex Launcher afterward.
`Restore-JagexRuneLite.ps1` restores the official launcher configuration.
Do not commit launcher/session files, account data, game caches, or credentials.

## Validation and next work

See [rendering validation](docs/RENDERING_UPGRADE_2026-09-07.md) for the checks and
[graphics roadmap](docs/GRAPHICS_ROADMAP.md) for remaining asset, lighting,
reflection, and performance work. Synthetic tests are not substitutes for
logged-in gameplay and visual comparisons across regions.

## Credits and licences

Original project integration and enhancements: **[SamGCoder / SamG-Coder](https://github.com/SamG-Coder)**.

This is a derivative project, not a claim of authorship over RuneLite, 117 HD,
third-party assets, NVIDIA technology, or Jagex's game content. Existing source
copyright headers and asset notices are preserved.

- [RuneLite](https://github.com/runelite/runelite): BSD-2-Clause.
- [117 HD](https://github.com/117HD/RLHD): BSD-2-Clause code, separately licensed textures.
- [threepp](https://github.com/markaren/threepp) / [SamGCoder's threepp work](https://github.com/SamG-Coder/threepp): MIT-derived native bridge.
- [wgpu-native](https://github.com/gfx-rs/wgpu-native) / [wgpu](https://github.com/gfx-rs/wgpu): MIT OR Apache-2.0.
- [WebGPU headers](https://github.com/webgpu-native/webgpu-headers): BSD-3-Clause.
- [NVIDIA Streamline](https://github.com/NVIDIA-RTX/Streamline): source and runtime terms differ; NVIDIA runtimes are obtained separately.
- 117 HD's asset contributors include 117, j-meds, SirFancyBacon, Jin-Jiyunsun,
  deathlezz, Romain Dancre, leenor_net, and the providers recorded in the full
  [texture attribution notice](LICENSES/117HD-texture-attributions.txt).

Read [LICENSE](LICENSE), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and
[LICENSES](LICENSES) for scope, notices, and exceptions. RuneScape, Old School
RuneScape, and Jagex are associated with Jagex; NVIDIA/DLSS are associated with
NVIDIA. This project is not affiliated with or endorsed by Jagex, NVIDIA,
RuneLite, or 117 HD. Game assets are not relicensed by this repository.
