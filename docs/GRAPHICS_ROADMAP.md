# Graphics roadmap

The goal is a more realistic renderer that keeps OSRS readable. The September 7
pass improves filtering, shadows, and DLSS correctness; it does not replace the
low-polygon game world with AAA assets.

## Next visual milestones

1. Capture fixed-camera comparisons at a river, forest, building interior,
   dense town, and roof transition. Include midday, sunset, night, and rain.
   Track shadow acne, detached contacts, roof leaks, foliage coverage, reflection
   stability, texture seams, and moving-character silhouettes.
2. Finish shadow visibility parity: ray-query alpha tests for foliage/cutouts,
   translucent-effect policy, thin geometry and contact cases, and soft-sun
   visibility with temporal accumulation and disocclusion rejection. The current
   tracing uses opaque ray flags and a coarse visibility grid; these remain limits.
3. Extend reflected-hit materials to full UV/barycentric texture sampling for
   off-screen objects. The current depth-validated visible-hit reuse improves
   detail only for visible hits; the off-screen fallback uses triangle color.
   Add a reflection history buffer before increasing stochastic sample counts.
4. Author material-specific roughness/normal/height data and physically consistent
   sun/sky lighting. Add local-light exposure handling, measured fog parameters,
   and stable indirect-light/occlusion rather than baked contrast tweaks.
5. Introduce optional, licensed replacement meshes for vegetation, rocks,
   architecture and props, preserving collision/gameplay silhouettes. Use LODs,
   instancing, material budgets and region streaming before increasing density.

## Performance measurement

Measure real gameplay CPU and GPU frame time separately, including p50/p95/p99,
chunk-transition stalls, VRAM, resolution, DLSS mode and visible object count.
Use a 16.7 ms total frame budget as the initial 60 FPS target; do not infer an FPS
gain from synthetic dispatch success. Keep 4x4/2x2/per-pixel reflection tiers and
bound glossy samples. Compare disabled vs enabled effects at the same camera.

256-pixel material layers with full mipmaps use roughly 1 MiB per material across
HD diffuse, vanilla diffuse and normal arrays; 128-pixel source images do not gain
new detail merely from resizing. Consider BC texture compression, retaining
vanilla at its native size, and streaming after profiling startup/VRAM. Public
reproduction also needs the complete patched wgpu-native source/build recipe.
