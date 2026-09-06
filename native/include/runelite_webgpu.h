#pragma once

#include <stdint.h>

#if defined(_WIN32) && defined(RLW_BUILD_DLL)
#define RLW_API __declspec(dllexport)
#elif defined(_WIN32)
#define RLW_API __declspec(dllimport)
#else
#define RLW_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

typedef struct RLWCapabilities {
    uint32_t struct_size;
    uint32_t vendor_id;
    uint32_t device_id;
    int is_rtx;
    int streamline_present;
    int streamline_initialized;
    int vulkan_attached;
    int dlss_super_resolution;
    int dlss_frame_generation;
    int dlss_ray_reconstruction;
    int reflex;
    char adapter_name[128];
    char status[256];
    int native_ray_tracing;
    int ray_query;
    int dlss_neural_rendering;
    int dlss_neural_rendering_api_loaded;
    int dlss_neural_rendering_dll_loaded;
    int dlss_neural_rendering_direct_supported;
    int dlss_neural_rendering_ngx_mapping_patched;
    // Hardware support alone is insufficient for Frame Generation. This is
    // false until the swapchain Present hook and UI resource tags are valid.
    int dlss_frame_generation_present_hook;
    int dlss_ray_reconstruction_guides;
    // Raw NVIDIA hardware/plugin support for diagnostics. The older
    // dlss_frame_generation field is the executable end-to-end capability.
    int dlss_frame_generation_hardware;
} RLWCapabilities;

enum RLWDlssMode {
    RLW_DLSS_OFF = 0,
    RLW_DLSS_MAX_PERFORMANCE = 1,
    RLW_DLSS_BALANCED = 2,
    RLW_DLSS_MAX_QUALITY = 3,
    RLW_DLSS_ULTRA_PERFORMANCE = 4,
    RLW_DLSS_ULTRA_QUALITY = 5,
    RLW_DLSS_DLAA = 6
};

typedef struct RLWFeatureRequest {
    uint32_t struct_size;
    uint32_t dlss_mode;
    uint32_t output_width;
    uint32_t output_height;
    float pre_exposure;
    float exposure_scale;
    int color_buffers_hdr;
    int auto_exposure;
    int alpha_upscaling;
    int frame_generation;
    int ray_reconstruction;
    int neural_rendering;
} RLWFeatureRequest;

typedef struct RLWVisualSettings {
    uint32_t struct_size;
    uint32_t style_mode;
    float render_scale;
    float normal_strength;
    float saturation;
    float contrast;
    float ambient_intensity;
    float exposure;
    float time_of_day;
    int textures_enabled;
    int weather_mode;
    float weather_intensity;
    float wind_strength;
    int water_quality;
    float water_wave_strength;
    float water_clarity;
    float rtx_shadow_strength;
    float rtx_reflection_strength;
    int rtx_reflection_quality;
    float dlss_sharpness;
    int smooth_model_shading;
    float model_smoothing_strength;
} RLWVisualSettings;

// Read-only effective state for the settings panel.  Capability support only
// says that a feature may be used; this structure reports what Streamline
// actually accepted and evaluated for the current viewport.
typedef struct RLWDlssRuntimeStatus {
    uint32_t struct_size;
    uint32_t requested_mode;
    int super_resolution_requested;
    int super_resolution_configured;
    int super_resolution_active;
    int ray_reconstruction_requested;
    int ray_reconstruction_configured;
    int ray_reconstruction_active;
    int neural_rendering_requested;
    int neural_rendering_configured;
    int neural_rendering_active;
    uint32_t render_width;
    uint32_t render_height;
    uint32_t output_width;
    uint32_t output_height;
    uint64_t super_resolution_evaluations;
    uint64_t super_resolution_failures;
    uint64_t ray_reconstruction_evaluations;
    uint64_t ray_reconstruction_failures;
    uint64_t neural_rendering_evaluations;
    uint64_t neural_rendering_failures;
    int32_t super_resolution_last_result;
    int32_t ray_reconstruction_last_result;
    int32_t neural_rendering_last_result;
    char super_resolution_reason[256];
    char ray_reconstruction_reason[256];
    char neural_rendering_reason[256];
} RLWDlssRuntimeStatus;

RLW_API int rlw_create(void* canvas_hwnd, int width, int height);
RLW_API void rlw_resize(int width, int height);
RLW_API void rlw_set_vsync(int enabled);
RLW_API void rlw_set_rtx_enabled(int enabled);
RLW_API void rlw_set_rtx_dynamic_shadows(int enabled);
RLW_API void rlw_set_rtx_reflections(int enabled);
RLW_API int rlw_set_visual_settings(const RLWVisualSettings* settings);
RLW_API int rlw_clear(float red, float green, float blue, float alpha);
RLW_API int rlw_present_bgra(const uint32_t* pixels, int width, int height);
RLW_API int rlw_upload_texture_atlas(
    const uint32_t* rgba_pixels,
    const uint32_t* vanilla_rgba_pixels,
    const uint32_t* normal_pixels,
    const float* material_parameters,
    int width,
    int height,
    int tile_size,
    int texture_count);
// Declares the static-sector ranges resident in packed_vertices. Entries may
// be supplied in any order; the native runtime sorts a private copy. Bit 0 of
// flags marks a sector active for rasterization and RTX traversal. Omitted
// previously-known IDs are deactivated in RTX without destroying the scene.
// Passing count == 0 explicitly hides every static sector. The pointed arrays
// only need to remain valid for the duration of this call.
RLW_API int rlw_set_static_sector_manifest(
    const uint64_t* sector_ids,
    const uint32_t* first_vertices,
    const uint32_t* vertex_counts,
    const uint32_t* flags,
    uint32_t count);
RLW_API int rlw_present_scene(
    const void* packed_vertices,
    uint32_t vertex_count,
    uint32_t static_vertex_count,
    uint64_t static_revision,
    const float* light_records,
    uint32_t light_count,
    const float* view_projection,
    const float* camera_view_to_clip,
    const float* camera_position,
    int viewport_x,
    int viewport_y,
    int viewport_width,
    int viewport_height,
    uint32_t clear_color,
    const uint32_t* interface_pixels,
    int interface_width,
    int interface_height);
RLW_API void rlw_destroy(void);
RLW_API const char* rlw_last_error(void);
RLW_API const char* rlw_backend_name(void);
RLW_API int rlw_get_capabilities(RLWCapabilities* capabilities);
RLW_API int rlw_get_dlss_runtime_status(RLWDlssRuntimeStatus* status);
RLW_API int rlw_request_features(const RLWFeatureRequest* request);
RLW_API uint64_t rlw_last_dynamic_hash(void);
// Synchronous diagnostic readback, never used by the frame loop. Stage 0 is
// linear HDR after RTX lighting; stage 1 is display-referred Neural Rendering
// output; stage 2 is HDR with water reflections composed. Pixels are tightly
// packed RGBA IEEE binary16. A null pixels pointer
// queries the current dimensions. Returns zero if the stage is unavailable.
RLW_API int rlw_read_scene_rgba16(int stage, uint16_t* pixels,
    uint64_t capacity_bytes, uint32_t* width, uint32_t* height);

#ifdef __cplusplus
}
#endif
