#include "runelite_webgpu.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <iostream>
#include <cstring>
#include <cstdio>
#include <vector>

namespace {
struct PackedSceneVertex {
    float position[3];
    uint32_t color;
    float uv[2];
    int32_t textureId;
    uint32_t sourceFlags;
    uint32_t vanillaColor;
};

LRESULT CALLBACK windowProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

bool rayCounters(const RLWCapabilities& capabilities,
                 unsigned long long& evaluations, unsigned long long& failures) {
    const char* counters = std::strstr(capabilities.status, "RTX ");
    return counters && std::sscanf(counters, "RTX %llu/%llu", &evaluations, &failures) == 2;
}
}

int main(int argc, char** argv) {
    std::cout << std::unitbuf;
    HINSTANCE instance = GetModuleHandleW(nullptr);
    WNDCLASSW windowClass{};
    windowClass.lpfnWndProc = windowProc;
    windowClass.hInstance = instance;
    windowClass.lpszClassName = L"RuneLite.WebGPU.Smoke";
    RegisterClassW(&windowClass);
    HWND hwnd = CreateWindowW(windowClass.lpszClassName, L"RuneLite WebGPU smoke",
        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 640, 480,
        nullptr, nullptr, instance, nullptr);
    if (!rlw_create(hwnd, 640, 480)) {
        std::cerr << rlw_last_error() << '\n';
        return 1;
    }
    rlw_set_rtx_enabled(0);
    RLWVisualSettings visual{};
    visual.struct_size = sizeof(visual);
    visual.style_mode = 1;
    visual.render_scale = 0.75f;
    visual.normal_strength = 1.0f;
    visual.saturation = 1.0f;
    visual.contrast = 1.05f;
    visual.ambient_intensity = 0.42f;
    visual.exposure = 1.0f;
    visual.time_of_day = 12.0f;
    visual.textures_enabled = 1;
    visual.rtx_shadow_strength = 0.82f;
    visual.rtx_reflection_strength = 0.58f;
    visual.water_quality = 1;
    visual.water_wave_strength = 0.4f;
    visual.water_clarity = 0.7f;
    if (!rlw_set_visual_settings(&visual)) {
        std::cerr << rlw_last_error() << '\n';
        rlw_destroy();
        return 6;
    }
    RLWCapabilities capabilities{};
    capabilities.struct_size = sizeof(capabilities);
    rlw_get_capabilities(&capabilities);
    std::cout << "backend=" << rlw_backend_name()
              << " adapter=" << capabilities.adapter_name
              << " streamline=" << capabilities.streamline_initialized
              << " vulkan-attached=" << capabilities.vulkan_attached
              << " dlss-sr=" << capabilities.dlss_super_resolution
              << " dlss-fg=" << capabilities.dlss_frame_generation
              << " dlss-rr=" << capabilities.dlss_ray_reconstruction
              << " ray-query=" << capabilities.ray_query
              << " status=\"" << capabilities.status << "\"\n";
    if (!rlw_clear(0.04f, 0.08f, 0.16f, 1.0f)) {
        std::cerr << rlw_last_error() << '\n';
        rlw_destroy();
        return 2;
    }
    if (capabilities.dlss_super_resolution) {
        RLWFeatureRequest request{};
        request.struct_size = sizeof(request);
        request.dlss_mode = argc > 1 ? RLW_DLSS_MAX_QUALITY : RLW_DLSS_DLAA;
        request.output_width = 640;
        request.output_height = 480;
        request.pre_exposure = 1.0f;
        request.exposure_scale = 1.0f;
        request.color_buffers_hdr = 1;
        request.auto_exposure = 1;
        request.ray_reconstruction = capabilities.dlss_ray_reconstruction;
        request.frame_generation = capabilities.dlss_frame_generation;
        if (!rlw_request_features(&request)) {
            std::cerr << "DLSS request failed: " << rlw_last_error() << '\n';
            rlw_destroy();
            return 3;
        }
    }
    // A visible receiver, elevated occluder, water tile, and two moving faces.
    // The previous identity projection clipped every vertex at z >= 192, so
    // the smoke test never exercised lighting or water on covered pixels.
    const float positions[] = {
        -96, 0, 128, 96, 0, 128, 96, 0, 384,
        -96, 0, 128, 96, 0, 384, -96, 0, 384,
        -24, -64, 224, 24, -64, 224, 24, -64, 288,
        -24, -64, 224, 24, -64, 288, -24, -64, 288,
        32, -2, 128, 96, -2, 128, 96, -2, 192,
        32, -2, 128, 96, -2, 192, 32, -2, 192,
        -32.0f, 24.0f, 192.0f, 32.0f, 24.0f, 192.0f, 0.0f, -32.0f, 192.0f,
        -24.0f, 12.0f, 224.0f, 24.0f, 12.0f, 224.0f, 0.0f, -24.0f, 224.0f,
    };
    PackedSceneVertex sourceVertices[24]{};
    for (uint32_t vertex = 0; vertex < 24u; ++vertex) {
        std::memcpy(sourceVertices[vertex].position, positions + vertex * 3u, sizeof(float) * 3u);
        sourceVertices[vertex].color = 0x03ffu;
        sourceVertices[vertex].uv[0] = vertex % 3u == 1u ? 1.0f : 0.0f;
        sourceVertices[vertex].uv[1] = vertex % 3u == 2u ? 1.0f : 0.0f;
        const bool water = vertex >= 12u && vertex < 18u;
        sourceVertices[vertex].textureId = water ? -2 : 0;
        sourceVertices[vertex].sourceFlags = water ? 0x40000002u : vertex < 6u ? 2u : 1u;
        sourceVertices[vertex].vanillaColor = sourceVertices[vertex].color;
    }
	std::vector<uint32_t> texture(128u * 128u, 0xff40a0ffu);
	std::vector<uint32_t> normals(128u * 128u, 0xffff8080u);
	const float materialParameters[] = {1.0f, 0.82f, 0.0f, 0.0f};
	if (!rlw_upload_texture_atlas(
			texture.data(), texture.data(), normals.data(), materialParameters,
			128, 128, 128, 1)) {
		std::cerr << rlw_last_error() << '\n';
		rlw_destroy();
		return 4;
	}
    const float identity[] = {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1,
    };
    // Orthographic, oblique world-to-clip transform with reverse depth. All
    // test geometry lies inside the viewport, and world up is negative Y.
    const float viewProjection[] = {
        1.0f / 128.0f, 0, 0, 0,
        0, -1.0f / 128.0f, 0, 0,
        0, 1.0f / 512.0f, -1.0f / 512.0f, 0,
        0, -0.5f, 0.9f, 1,
    };
    const float cameraPosition[] = {0.0f, -256.0f, 0.0f};
    float lightRecords[16]{};
    lightRecords[0] = 0.0f;
    lightRecords[1] = -64.0f;
    lightRecords[2] = 224.0f;
    lightRecords[3] = 320.0f;
    lightRecords[8] = 1.0f;
    lightRecords[9] = 0.35f;
    lightRecords[10] = 0.12f;
    lightRecords[11] = 4.0f;
    std::vector<uint32_t> interfacePixels(640u * 480u, 0u);
    unsigned long long previousRayEvaluations = 0u;
    unsigned long long previousRayFailures = 0u;
    if (capabilities.ray_query &&
        !rayCounters(capabilities, previousRayEvaluations, previousRayFailures)) {
        std::cerr << "RTX counters missing from initial capability status\n";
        rlw_destroy();
        return 8;
    }
    for (int frame = 0; frame < 8; ++frame) {
        std::cout << "smoke-frame=" << frame << "\n";
        if (frame == 2) {
            visual.render_scale = 1.0f;
            rlw_set_rtx_enabled(capabilities.ray_query);
            rlw_set_rtx_dynamic_shadows(capabilities.ray_query);
        }
        if (frame == 3) rlw_set_rtx_reflections(capabilities.ray_query);
        if (frame == 4) visual.time_of_day = 0.0f;
        if (frame == 5) {
            visual.time_of_day = 12.0f;
            visual.ambient_intensity = 1.0f;
        }
        if (frame == 6) {
            visual.ambient_intensity = 0.42f;
            visual.time_of_day = 15.0f;
        }
        if (frame == 7) rlw_set_rtx_enabled(0);
        if (!rlw_set_visual_settings(&visual)) {
            std::cerr << rlw_last_error() << '\n';
            rlw_destroy();
            return 7;
        }
        // Keep immutable sector offsets while hiding/showing the occluder.
        // Later append one static triangle and shift the dynamic suffix, as
        // real chunk loading does, without replacing the existing epoch.
        const bool appendStaticSector = frame >= 6;
        const uint32_t staticVertexCount = appendStaticSector ? 21u : 18u;
        const uint32_t dynamicVertexCount = frame < 2 ? 3u : 6u;
        const uint32_t frameVertexCount = staticVertexCount + dynamicVertexCount;
        PackedSceneVertex vertices[27]{};
        // The first sector is mixed, the second opaque, and the third water,
        // exercising both cached indexed partitions and ordinary direct draws.
        std::memcpy(vertices, sourceVertices, 6u * sizeof(PackedSceneVertex));
        std::memcpy(vertices + 6u, sourceVertices + 12u, 3u * sizeof(PackedSceneVertex));
        std::memcpy(vertices + 9u, sourceVertices + 6u, 6u * sizeof(PackedSceneVertex));
        std::memcpy(vertices + 15u, sourceVertices + 15u, 3u * sizeof(PackedSceneVertex));
        if (appendStaticSector) {
            for (uint32_t corner = 0u; corner < 3u; ++corner) {
                vertices[18u + corner] = sourceVertices[6u + corner];
                vertices[18u + corner].position[0] -= 48.0f;
                vertices[18u + corner].position[1] = -32.0f;
                vertices[18u + corner].position[2] += 48.0f;
            }
        }
        std::memcpy(vertices + staticVertexCount, sourceVertices + 18u,
                    dynamicVertexCount * sizeof(PackedSceneVertex));
        vertices[staticVertexCount].position[0] = -32.0f + static_cast<float>(frame) * 4.0f;
        vertices[staticVertexCount + 1u].position[0] = 32.0f + static_cast<float>(frame) * 4.0f;
        vertices[staticVertexCount + 2u].position[0] = static_cast<float>(frame) * 4.0f;
        const uint64_t sectorIds[] = {10u, 20u, 30u, 40u};
        const uint32_t sectorFirst[] = {0u, 9u, 15u, 18u};
        const uint32_t sectorCounts[] = {9u, 6u, 3u, 3u};
        const uint32_t sectorFlags[] = {1u, frame == 3 ? 0u : 1u, 1u, 1u};
        if (!rlw_set_static_sector_manifest(sectorIds, sectorFirst, sectorCounts,
                sectorFlags, appendStaticSector ? 4u : 3u)) {
            std::cerr << "Static manifest rejected: " << rlw_last_error() << '\n';
            rlw_destroy();
            return 10;
        }
        if (!rlw_present_scene(
                vertices, frameVertexCount, staticVertexCount, appendStaticSector ? 2u : 1u,
                lightRecords, 1, viewProjection, identity,
                cameraPosition,
                32, 24, frame < 5 ? 576 : 544, frame < 5 ? 432 : 400, 0x102040u,
                interfacePixels.data(), 640, 480)) {
            std::cerr << rlw_last_error() << '\n';
            rlw_destroy();
            return 5;
        }
        if (capabilities.ray_query) {
            rlw_get_capabilities(&capabilities);
            unsigned long long evaluations = 0u;
            unsigned long long failures = 0u;
            const unsigned long long expectedPasses =
                frame < 2 ? 0u : (frame == 2 || frame == 7) ? 1u : 2u;
            if (!rayCounters(capabilities, evaluations, failures) ||
                evaluations != previousRayEvaluations + expectedPasses ||
                failures != previousRayFailures) {
                std::cerr << "RTX regression at frame " << frame
                          << ": expected " << expectedPasses
                          << " lighting/reflection passes and no new failures; "
                          << capabilities.status << "; " << rlw_last_error() << '\n';
                rlw_destroy();
                return 9;
            }
            previousRayEvaluations = evaluations;
            previousRayFailures = failures;
        }
    }
    rlw_get_capabilities(&capabilities);
    std::cout << "post-frame status=\"" << capabilities.status << "\"\n";
    RLWDlssRuntimeStatus dlssStatus{};
    dlssStatus.struct_size = sizeof(dlssStatus);
    if (rlw_get_dlss_runtime_status(&dlssStatus)) {
        if (capabilities.dlss_super_resolution &&
            (dlssStatus.super_resolution_evaluations != 8 || dlssStatus.super_resolution_failures != 0)) {
            std::cerr << "Offset/resized DLSS viewport failed: " << dlssStatus.super_resolution_reason << '\n';
            return 11;
        }
        std::cout << "dlss-evaluations=" << dlssStatus.super_resolution_evaluations
                  << " dlss-failures=" << dlssStatus.super_resolution_failures
                  << " dlss-result=" << dlssStatus.super_resolution_last_result
                  << " dlss-reason=\"" << dlssStatus.super_resolution_reason << "\"\n";
    }
    rlw_destroy();
    DestroyWindow(hwnd);
    return 0;
}

