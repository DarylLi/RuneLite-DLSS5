# Standalone native dependencies

This directory makes the RuneLite native renderer independently buildable. It
does not load or link against `C:\ThreeBrowserNVNGX`.

`wgpu-native` is the pinned Windows GNU build based on wgpu-native v29.0.1.1
(wgpu-native commit `6aed50955d934ac36049ba8d002034841633ae02`, wgpu commit
`923b896955655600c38ac93d6e9ae26845682f80`). It adds Vulkan device, image, and
command-buffer accessors required for Streamline and Vulkan ray queries.

`nvidia-bridge` contains the extracted Vulkan Streamline and ray-query bridge
implementation. The build downloads NVIDIA Streamline 2.12.0 from NVIDIA's
official GitHub release using a pinned SHA-256 hash. Streamline, NGX, and their
runtime DLLs remain subject to NVIDIA's licenses.

The optional DLSS 5 Neural Rendering path uses the NVIDIA-signed Streamline
2.13 / NGX 310.8 preview binaries provisioned in `.runtime/webgpu`. Every
preview DLL is pinned by SHA-256 during CMake configuration, and the bridge is
compiled only when `RLW_ENABLE_DLSS5_NEURAL_RENDERING` is enabled. It does not
read or load files from the original ThreeBrowser project. Capability reporting
stays disabled unless Streamline accepts feature 1004 on the active adapter;
the presence of the DLL alone is never reported as feature support.

The preview's NGX feature-name mapping is applied only inside the RuneLite
process after validating the complete writable driver name table, and the
original pointer is restored during shutdown. Set
`RLW_DISABLE_DLSS5_NGX_MAPPING=1` before launch to disable that mapping for
driver troubleshooting. `RLW_STREAMLINE_VERBOSE=1` and
`RLW_DLSS5_TELEMETRY=1` enable opt-in diagnostics.

The renderer DLL and all runtime dependencies are staged into
`.runtime/runelite-webgpu` by `native/Build-Native.ps1`.
