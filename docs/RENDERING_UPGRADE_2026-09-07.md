# Rendering upgrade — 7 September 2026

## Implemented

Textures now retain 256-pixel 117 HD detail and use per-material GPU array
layers, full mip chains, trilinear sampling and 8x anisotropy. Diffuse mips are
filtered in linear light with alpha weighting; normal mips are renormalized.
Mapped normals use an orthonormal tangent frame, including mirrored UVs.

Shadow composition uses the raster sunlight fraction, preserving mapped/smooth
material lighting and unlit surfaces. The coarse sample grid now matches its
interpolation coordinates. Receiver-plane rejection prevents blending across
separate surfaces; isolated receivers trace a fallback visibility ray. A smaller
world-scale-aware origin bias reduces detached contacts. No random screen-space
AO noise was introduced.

Reflections use two/four deterministic glossy rays in enhanced/ultra tiers where
roughness warrants them, with a single exact ray for near-mirror materials.
Depth-validated visible-hit sampling retains texture and lighting detail from
the actual traced surface, falling back when the hit is off-screen or occluded.
The ray rate remains tiered; there is no claim of a full off-screen textured
path tracer or a temporal reflection denoiser.

DLSS options now follow the tagged 3D viewport while retaining full allocation
sizes for UI composition. Tag bounds and native-supported render ranges are
checked. Resizing retires GPU work before replacing NGX resources: the expanded
fixture originally reproduced a process crash after successful evaluations,
which is resolved by the explicit resize lifetime handling. Ordinary frames do
not invoke that resize wait.

## Validation

Windows, Vulkan, NVIDIA GeForce RTX 5080, locally provisioned experimental
runtime configuration:

- Release native build including GLSL compilation: passed.
- 4 native CTests: passed, including GPU pixel checks and offset/resized viewport
  checks for DLAA and DLSS Quality. Each DLSS fixture records 8 evaluations and
  zero failures, plus 10 RTX evaluations and zero failures, and exits cleanly.
- 52 Java tests: zero failures, errors or skips.
- Rebuilt distribution JAR includes the project and third-party licence notices.
- Installer copied and SHA-256 verified the JAR and all 16 required runtime DLLs.
  Existing installation was snapshotted. Jagex Launcher was left running.

Pixel evidence from the final run:

```text
HDR shadow pixels=3809 mean shadow/lit=0.456743
NR mean shadow/lit=0.813247
Reflected object pixels=2208 mean hit/miss delta=0.00153826
Mipmapped checker maximum linear-grey error=0.00390625
Unlit material shadow difference=0
Isolated material-layer maximum error=0
Textured reflected-hit pixels=2780
```

Built and installed artifact SHA-256 values:

```text
RuneLite-DLSS5.jar  414194e52fb73534725f365898e5a7cb719e8d4af62c4023d85c0e05c98cf4ac
runelite_webgpu.dll  f9e4ae6cb31fb8d56a4c3cefd4a2b7cd7202a2cf7c3a6417a27235ca7daddea0
```

## Remaining limits

This is not a completed AAA conversion or evidence that every shadow defect is
fixed. Alpha-tested foliage tracing, temporal soft shadows, off-screen textured
reflection materials, higher-detail meshes and region-specific lighting remain
work items in the graphics roadmap. GPU dispatch/pixel tests do not establish a
gameplay FPS gain. Mipmaps add memory, and the texture-size increase has a real
VRAM cost.

A logged-in gameplay check could not be performed: the computer-use connection
exposed browsers but returned `cua.getApp is not a function` for Jagex Launcher,
even after refreshing the session. The installed binaries are ready for a normal
launcher start. No gameplay screenshot or live visual approval is claimed.

The public snapshot excludes runtime binaries and personal state. It includes
modified 117 HD sources directly, upstream licence notices, and the experimental
bridge source. Clean native builds still require the separately provisioned
patched wgpu-native library; its complete source/build recipe remains missing.
