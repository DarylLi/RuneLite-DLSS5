# RuneLite 117 HD WebGPU + DLSS 5 port

## Objective

Retain RuneLite and 117 HD's scene extraction, materials, lighting rules, and
plugin behavior while replacing the OpenGL renderer with the native WebGPU
(Vulkan) backend from `C:\ThreeBrowserNVNGX`. NVIDIA Streamline then consumes
the native Vulkan images and command buffers for DLSS.

## Architecture

The port has three boundaries:

1. **RuneLite draw callbacks** provide scene geometry and frame timing.
2. **117 HD backend-neutral scene state** owns materials, environments, lights,
   camera data, and model uploads.
3. **Standalone RuneLite native runtime** owns the WebGPU device, Vulkan
   swapchain, command submission, and Streamline/NGX features. It is built from
   `native/` and does not load or link against ThreeBrowserNVNGX.

The Java side is in `plugins/117-hd/src/main/java/rs117/hd/webgpu`; its matching
C ABI is `native/include/runelite_webgpu.h`. Native binaries are deliberately
not committed. Run `native/Build-Native.ps1` to build and stage the standalone
runtime into the ignored `.runtime/runelite-webgpu` directory.

## Required frame resources

DLSS Super Resolution requires distinct HDR input/output images, native depth,
dense motion vectors, camera matrices, jitter, exposure, and reset state.
Frame Generation additionally needs a HUD-less color image and a separately
composited UI image. Ray Reconstruction must not be enabled until the renderer
produces genuine noisy ray-traced lighting plus albedo, normal, roughness, and
specular guides.

## Implementation milestones

- [x] Pin RuneLite and 117 HD upstreams.
- [x] Add Java ABI bindings for the ThreeBrowserNVNGX runtime and DLSS requests.
- [x] Add an independent C++ WebGPU/Vulkan DLL with a RuneLite-specific ABI.
- [x] Build and stage wgpu-native without a ThreeBrowser runtime dependency.
- [ ] Extract the Streamline/NGX bridge into the independent native project.
- [ ] Extract OpenGL calls from `HdPlugin` lifecycle into a backend interface.
- [x] Add an isolated experimental plugin and `runWebGpu` launcher.
- [x] Implement WebGPU canvas attach, resize, and Vulkan presentation.
- [x] Upload and alpha-compose RuneLite's BGRA interface buffer through WebGPU.
- [ ] Add explicit device-loss recovery and automatic OpenGL fallback.
- [ ] Port model/scene buffers and opaque/transparent draw passes.
- [ ] Port 117 HD GLSL shaders to WebGPU-compatible SPIR-V/WGSL contracts.
- [ ] Add HDR scene color, depth, motion-vector, and exposure render targets.
- [ ] Evaluate DLSS SR before UI composition; validate temporal reset behavior.
- [ ] Separate RuneLite UI composition and add Frame Generation tagging.
- [ ] Add ray-query lighting and only then enable Ray Reconstruction.
- [ ] Capture deterministic comparison frames and performance telemetry.

## Non-goals for the first playable build

Frame Generation, Ray Reconstruction, and experimental Neural Rendering stay
disabled until their complete input contracts are produced. Enabling feature
flags without valid temporal and material inputs causes artifacts and can make
Streamline retain invalid Vulkan resources.
