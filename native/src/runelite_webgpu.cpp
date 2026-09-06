#include "runelite_webgpu.h"
#include "scene_draw_order.h"

#ifndef WGPU_SHARED_LIBRARY
#define WGPU_SHARED_LIBRARY
#endif
#include "wgpu.h"
#include "material_upload.h"
#include "ray_query_bridge.h"
#include "streamline_bridge.h"
#include <vulkan/vulkan.h>

#ifndef NOMINMAX
#define NOMINMAX
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <algorithm>
#include <atomic>
#include <array>
#include <cctype>
#include <chrono>
#include <cstddef>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

// Mirrored by RuneLiteWebGpuNativeTest: protect the JNA/native boundary as
// visual controls and capability fields are appended to the shared structs.
static_assert(sizeof(RLWCapabilities) == 468u);
static_assert(offsetof(RLWCapabilities, dlss_frame_generation_present_hook) == 456u);
static_assert(offsetof(RLWCapabilities, dlss_ray_reconstruction_guides) == 460u);
static_assert(offsetof(RLWCapabilities, dlss_frame_generation_hardware) == 464u);
static_assert(sizeof(RLWFeatureRequest) == 48u);
static_assert(offsetof(RLWFeatureRequest, neural_rendering) == 44u);
static_assert(sizeof(RLWVisualSettings) == 88u);
static_assert(offsetof(RLWVisualSettings, water_quality) == 52u);
static_assert(offsetof(RLWVisualSettings, rtx_shadow_strength) == 64u);
static_assert(offsetof(RLWVisualSettings, model_smoothing_strength) == 84u);
static_assert(sizeof(RLWDlssRuntimeStatus) == 896u);
static_assert(offsetof(RLWDlssRuntimeStatus, super_resolution_evaluations) == 64u);
static_assert(offsetof(RLWDlssRuntimeStatus, super_resolution_last_result) == 112u);
static_assert(offsetof(RLWDlssRuntimeStatus, super_resolution_reason) == 124u);
static_assert(offsetof(RLWDlssRuntimeStatus, neural_rendering_reason) == 636u);

struct PackedSceneVertex {
    float position[3];
    uint32_t color;
    float uv[2];
    int32_t textureId;
    uint32_t sourceFlags;
    uint32_t vanillaColor;
};
static_assert(sizeof(PackedSceneVertex) == 36u);

struct VisualSettings {
    uint32_t styleMode{1};
    float renderScale{1.0f};
    float normalStrength{1.0f};
    float saturation{1.0f};
    float contrast{1.05f};
    float ambientIntensity{0.42f};
    float exposure{1.0f};
    float timeOfDay{12.0f};
    bool texturesEnabled{true};
    uint32_t weatherMode{};
    float weatherIntensity{};
    float windStrength{0.35f};
    uint32_t waterQuality{1};
    float waterWaveStrength{1.0f};
    float waterClarity{0.58f};
    float rtxShadowStrength{0.82f};
    float rtxReflectionStrength{0.58f};
    uint32_t rtxReflectionQuality{1};
    float dlssSharpness{0.20f};
    bool smoothModelShading{true};
    float modelSmoothingStrength{0.90f};
};

struct StaticSectorRange {
    uint64_t id{};
    uint32_t firstVertex{};
    uint32_t vertexCount{};
    bool active{};

    bool operator==(const StaticSectorRange&) const = default;
};

struct StaticSectorVisibilityChange {
    uint64_t id{};
    bool active{};
};

struct PreparedSceneRange {
    uint32_t firstVertex{};
    uint32_t vertexCount{};
    uint32_t opaqueCount{};
    WGPUBuffer indexBuffer{};
    uint64_t indexCapacity{};
};

struct Runtime {
    std::mutex mutex;
    // Device-loss callbacks may run on wgpu-native's internal thread.  Keep
    // the callback lock-free and let the next Java/JNA entry point publish a
    // stable error instead of crossing the Rust FFI boundary again.
    std::atomic<bool> deviceLost{false};
    std::atomic<bool> uncapturedDeviceError{false};
    // The callback publishes this fixed buffer before setting the atomic flag;
    // the Java/native caller reads it only after the acquire in
    // stopForDeviceLoss, avoiding heap work on wgpu-native's callback thread.
    char uncapturedDeviceErrorMessage[512]{};
    // Every device owns a monotonically increasing callback generation.  A
    // Destroyed callback from a released wgpu-native device is allowed to
    // arrive late, so callbacks must prove that they still belong to the
    // current device before changing renderer state.
    std::atomic<uint64_t> deviceGeneration{0};
    bool nativeInteropActive{};
    WGPUInstance instance{};
    WGPUSurface surface{};
    WGPUAdapter adapter{};
    WGPUDevice device{};
    WGPUQueue queue{};
    WGPUTexture uiTexture{};
    WGPUTextureView uiView{};
    WGPUSampler uiSampler{};
    WGPUShaderModule uiShader{};
    WGPURenderPipeline uiPipeline{};
    WGPURenderPipeline uiBlendPipeline{};
    WGPUBindGroup uiBindGroup{};
    WGPUBindGroup uiBlendBindGroup{};
    WGPUBuffer sceneVertexBuffer{};
    uint64_t sceneVertexCapacity{};
    std::unordered_map<uint32_t, PreparedSceneRange> staticDrawRanges;
    PreparedSceneRange dynamicDrawRange;
    std::vector<uint32_t> drawIndexScratch;
    WGPUBuffer sceneUniformBuffer{};
    WGPUBindGroup sceneBindGroup{};
    WGPUTexture sceneTextureAtlas{};
    WGPUTextureView sceneTextureAtlasView{};
    WGPUTexture sceneVanillaTextureAtlas{};
    WGPUTextureView sceneVanillaTextureAtlasView{};
    WGPUTexture sceneNormalAtlas{};
    WGPUTextureView sceneNormalAtlasView{};
    WGPUSampler sceneTextureSampler{};
    WGPUBuffer sceneMaterialBuffer{};
    WGPUShaderModule sceneShader{};
    WGPURenderPipeline scenePipeline{};
    WGPUTexture depthTexture{};
    WGPUTextureView depthView{};
    WGPUTexture hdrColorTexture{};
    WGPUTextureView hdrColorView{};
    WGPUTexture motionTexture{};
    WGPUTextureView motionView{};
    WGPUTexture normalRoughnessTexture{};
    WGPUTextureView normalRoughnessView{};
    WGPUTexture diffuseAlbedoTexture{};
    WGPUTextureView diffuseAlbedoView{};
    WGPUTexture specularAlbedoTexture{};
    WGPUTextureView specularAlbedoView{};
    WGPUTexture reflectionTexture{};
    WGPUTextureView reflectionView{};
    WGPUTexture neuralInputTexture{};
    WGPUTextureView neuralInputView{};
    WGPUTexture neuralRenderingTexture{};
    WGPUTextureView neuralRenderingView{};
    WGPUTexture specularHitDistanceTexture{};
    WGPUTextureView specularHitDistanceView{};
    WGPUTexture exposureTexture{};
    WGPUTextureView exposureView{};
    WGPUTexture dlssOutputTexture{};
    WGPUTextureView dlssOutputView{};
    WGPUSampler finalSampler{};
    WGPUShaderModule finalShader{};
    WGPURenderPipeline finalPipeline{};
    WGPURenderPipeline neuralInputPipeline{};
    WGPUBuffer finalToneMapUniformBuffer{};
    WGPUBuffer finalPassthroughUniformBuffer{};
    WGPUBuffer finalSkyUniformBuffer{};
    WGPUBindGroup finalHdrBindGroup{};
    WGPUBindGroup finalReflectionBindGroup{};
    WGPUBindGroup finalNeuralRenderingBindGroup{};
    WGPUBindGroup finalDlssBindGroup{};
    WGPUBindGroup finalDlssDisplayBindGroup{};
    WGPUBindGroup neuralInputHdrBindGroup{};
    WGPUBindGroup neuralInputReflectionBindGroup{};
    WGPUBindGroup skyBindGroup{};
    // The scene shader needs the previous jittered transform to emit motion
    // vectors which include the raster jitter. Streamline's matrix constants,
    // however, are explicitly unjittered and therefore keep separate history.
    float previousViewProjection[16]{};
    float previousStreamlineViewProjection[16]{};
    bool hasPreviousViewProjection{};
    uint64_t frameIndex{};
    std::chrono::steady_clock::time_point animationStart{std::chrono::steady_clock::now()};
    uint64_t raySceneHash{};
    uint64_t lastRaySceneBuildFrame{};
    uint32_t raySceneVertexCount{};
    uint32_t raySceneEpoch{};
    uint64_t raySceneRevision{UINT64_MAX};
    // Replacing a ray scene must not destroy acceleration structures still in
    // use by an earlier WebGPU submission.  A queue-completion ticket lets
    // raster frames continue while those resources retire instead of blocking
    // the client thread in vkDeviceWaitIdle.
    uint64_t nextRaySceneRetirementTicket{};
    uint64_t pendingRaySceneRetirementTicket{};
    std::atomic<uint64_t> completedRaySceneRetirementTicket{0};
    std::atomic<uint64_t> failedRaySceneRetirementTicket{0};
    std::vector<uint32_t> raySceneIndices;
    // The Java streamer owns sector selection; native keeps a sorted copy so
    // stale ranges can be omitted from raster draws and masked in the RTX
    // TLAS without rebuilding the retained static prefix.
    std::vector<StaticSectorRange> staticSectorManifest;
    std::unordered_map<uint64_t, bool> raySectorActivity;
    bool staticSectorManifestEnabled{};
    bool staticSectorManifestChanged{};
    WGPUTexture dynamicPositionTexture{};
    uint32_t dynamicPositionTextureWidth{};
    uint32_t dynamicPositionTextureHeight{};
    uint32_t dynamicRayVertexCount{};
    bool dynamicRayMeshActive{};
    std::vector<float> dynamicPositionPixels;
    std::vector<uint32_t> dynamicRayIndices;
    uint32_t uiWidth{};
    uint32_t uiHeight{};
    uint32_t sceneTextureWidth{1};
    uint32_t sceneTextureHeight{1};
    uint32_t sceneTextureTileSize{1};
    uint32_t sceneTextureCount{1};
    WGPUTextureFormat surfaceFormat{WGPUTextureFormat_BGRA8Unorm};
    WGPUCompositeAlphaMode alphaMode{WGPUCompositeAlphaMode_Opaque};
    uint32_t width{1};
    uint32_t height{1};
    uint32_t vendorId{};
    uint32_t deviceId{};
    bool isRtx{};
    bool vsync{true};
    bool immediatePresentSupported{};
    bool rayQueryFeatureEnabled{};
    // Stage RTX features independently.  The first live path is ray-query
    // lighting only; it must not implicitly opt the frame into Streamline,
    // reflections, DLSS, ray reconstruction or frame generation.
    bool rtxShadowsEnabled{};
    bool rtxDynamicShadowsEnabled{};
    bool rtxReflectionsEnabled{};
    VisualSettings visual{};
    bool nvidiaPrepared{};
    bool configured{};
    std::string adapterName;
    std::string backendName{"Unavailable"};
    std::string error;
    std::vector<uint8_t> uploadPixels;
    std::vector<uint8_t> uploadVertices;
    std::vector<float> currentScenePositions;
    std::vector<float> previousDynamicPositions;
    uint32_t previousDynamicVertexCount{};
    uint64_t uploadedStaticRevision{UINT64_MAX};
    uint32_t uploadedStaticVertexCount{};
    uint32_t staticFadeStartVertex{};
    uint32_t staticFadeEndVertex{};
    uint64_t staticFadeStartFrame{};
    uint64_t lastDynamicInputHash{14695981039346656037ull};
    StreamlineDLSSOptions dlssRequest{};
    uint32_t dlssRenderWidth{};
    uint32_t dlssRenderHeight{};
    uint32_t dlssOutputWidth{};
    uint32_t dlssOutputHeight{};
    bool dlssRayReconstructionRequested{};
    bool dlssNeuralRenderingRequested{};
    bool dlssNeuralRenderingFailedThisSession{};
    bool dlssNeuralRenderingReleasePending{};
    bool temporalResetRequested{true};
    uint64_t temporalJitterIndex{};
    uint64_t temporalStaticRevision{UINT64_MAX};
    float previousCameraPosition[3]{};
    bool hasPreviousCameraPosition{};
};

Runtime g;

uint32_t sceneWidth() {
    if (g.dlssRenderWidth) return g.dlssRenderWidth;
    return std::max(1u, static_cast<uint32_t>(std::lround(
        static_cast<double>(g.width) * static_cast<double>(g.visual.renderScale))));
}

uint32_t sceneHeight() {
    if (g.dlssRenderHeight) return g.dlssRenderHeight;
    return std::max(1u, static_cast<uint32_t>(std::lround(
        static_cast<double>(g.height) * static_cast<double>(g.visual.renderScale))));
}

uint32_t resolvedWeatherMode() {
    // Auto is deliberately a slowly varying fair-weather atmosphere. Rain and
    // storms remain explicit choices so opening RuneLite can never surprise a
    // player with heavy precipitation or lightning.
    return g.visual.weatherMode == 4u ? 1u : std::min(g.visual.weatherMode, 3u);
}

float animationSeconds() {
    return std::chrono::duration<float>(
        std::chrono::steady_clock::now() - g.animationStart).count();
}

float resolvedWeatherIntensity() {
    if (g.visual.weatherMode != 4u) return g.visual.weatherIntensity;
    const float elapsedSeconds = animationSeconds();
    const float cloudCycle = 0.5f + 0.5f * std::sin(
        elapsedSeconds / 150.0f + g.visual.timeOfDay * 0.23f);
    return std::clamp(
        g.visual.weatherIntensity * (0.35f + 0.50f * cloudCycle),
        0.0f, 0.72f);
}

WGPUStringView stringView(const char* value) {
    return WGPUStringView{value, value ? WGPU_STRLEN : 0};
}

std::string fromStringView(WGPUStringView value) {
    if (!value.data) return {};
    const size_t length = value.length == WGPU_STRLEN ? std::strlen(value.data) : value.length;
    return std::string(value.data, length);
}

void setError(std::string message) {
    g.error = std::move(message);
}

bool stopForDeviceLoss() {
    if (g.deviceLost.load(std::memory_order_acquire)) {
        setError("The WebGPU device was reset by a GPU workload. RTX and DLSS "
                 "have been stopped; restart RuneLite to recreate the device.");
        return true;
    }
    if (g.uncapturedDeviceError.load(std::memory_order_acquire)) {
        const std::string detail = g.uncapturedDeviceErrorMessage[0]
            ? std::string(": ") + g.uncapturedDeviceErrorMessage
            : std::string();
        setError("WebGPU reported an asynchronous device error" + detail +
                 ". The renderer was stopped before another native GPU call "
                 "could be issued.");
        return true;
    }
    return false;
}

bool processDeviceEventsAndCheckForLoss() {
    // wgpu-native reports a number of validation/device failures
    // asynchronously.  Drain those callbacks between independent queue
    // submissions so a failed Vulkan/WebGPU stage can never be followed by
    // another native call on the same frame.
    if (g.device) wgpuDevicePoll(g.device, 0, nullptr);
    if (g.instance) wgpuInstanceProcessEvents(g.instance);
    return !stopForDeviceLoss();
}

void forgetNativeTexture(WGPUTexture texture) {
    // wgpuTextureGetNativeVulkanImage crosses into wgpu-native.  Never make
    // that call after a device reset, or after the raw Vulkan bridges have
    // already been shut down during normal teardown.
    if (!texture || !g.nativeInteropActive ||
        g.deviceLost.load(std::memory_order_acquire) ||
        g.uncapturedDeviceError.load(std::memory_order_acquire)) return;
    void* image = wgpuTextureGetNativeVulkanImage(texture);
    if (!image) return;
    rayQueryBridgeForgetImage(image);
    streamlineForgetVulkanImage(image);
}

bool waitFor(WGPUFuture future) {
    if (!g.instance || future.id == 0) return true;
    WGPUFutureWaitInfo info{};
    info.future = future;
    for (int i = 0; i < 20000 && !info.completed; ++i) {
        wgpuInstanceWaitAny(g.instance, 1, &info, 0);
        wgpuInstanceProcessEvents(g.instance);
        if (!info.completed) Sleep(i % 64 == 63 ? 1 : 0);
    }
    return info.completed != 0;
}

void releaseUiResources() {
    if (g.uiBindGroup) wgpuBindGroupRelease(g.uiBindGroup);
    if (g.uiBlendBindGroup) wgpuBindGroupRelease(g.uiBlendBindGroup);
    if (g.uiPipeline) wgpuRenderPipelineRelease(g.uiPipeline);
    if (g.uiBlendPipeline) wgpuRenderPipelineRelease(g.uiBlendPipeline);
    if (g.uiShader) wgpuShaderModuleRelease(g.uiShader);
    if (g.uiSampler) wgpuSamplerRelease(g.uiSampler);
    if (g.uiView) wgpuTextureViewRelease(g.uiView);
    if (g.uiTexture) wgpuTextureRelease(g.uiTexture);
    g.uiBindGroup = nullptr;
    g.uiBlendBindGroup = nullptr;
    g.uiPipeline = nullptr;
    g.uiBlendPipeline = nullptr;
    g.uiShader = nullptr;
    g.uiSampler = nullptr;
    g.uiView = nullptr;
    g.uiTexture = nullptr;
    g.uiWidth = 0;
    g.uiHeight = 0;
    g.uploadPixels.clear();
}

void releaseDepthResources() {
    forgetNativeTexture(g.dlssOutputTexture);
    forgetNativeTexture(g.specularHitDistanceTexture);
    forgetNativeTexture(g.neuralRenderingTexture);
    forgetNativeTexture(g.neuralInputTexture);
    forgetNativeTexture(g.reflectionTexture);
    forgetNativeTexture(g.exposureTexture);
    forgetNativeTexture(g.specularAlbedoTexture);
    forgetNativeTexture(g.diffuseAlbedoTexture);
    forgetNativeTexture(g.normalRoughnessTexture);
    forgetNativeTexture(g.motionTexture);
    forgetNativeTexture(g.hdrColorTexture);
    forgetNativeTexture(g.depthTexture);
    if (g.finalHdrBindGroup) wgpuBindGroupRelease(g.finalHdrBindGroup);
    if (g.finalReflectionBindGroup) wgpuBindGroupRelease(g.finalReflectionBindGroup);
    if (g.finalNeuralRenderingBindGroup)
        wgpuBindGroupRelease(g.finalNeuralRenderingBindGroup);
    if (g.finalDlssBindGroup) wgpuBindGroupRelease(g.finalDlssBindGroup);
    if (g.finalDlssDisplayBindGroup) wgpuBindGroupRelease(g.finalDlssDisplayBindGroup);
    if (g.neuralInputHdrBindGroup) wgpuBindGroupRelease(g.neuralInputHdrBindGroup);
    if (g.neuralInputReflectionBindGroup)
        wgpuBindGroupRelease(g.neuralInputReflectionBindGroup);
    if (g.skyBindGroup) wgpuBindGroupRelease(g.skyBindGroup);
    if (g.dlssOutputView) wgpuTextureViewRelease(g.dlssOutputView);
    if (g.dlssOutputTexture) wgpuTextureRelease(g.dlssOutputTexture);
    if (g.specularHitDistanceView) wgpuTextureViewRelease(g.specularHitDistanceView);
    if (g.specularHitDistanceTexture) wgpuTextureRelease(g.specularHitDistanceTexture);
    if (g.reflectionView) wgpuTextureViewRelease(g.reflectionView);
    if (g.reflectionTexture) wgpuTextureRelease(g.reflectionTexture);
    if (g.neuralRenderingView) wgpuTextureViewRelease(g.neuralRenderingView);
    if (g.neuralRenderingTexture) wgpuTextureRelease(g.neuralRenderingTexture);
    if (g.neuralInputView) wgpuTextureViewRelease(g.neuralInputView);
    if (g.neuralInputTexture) wgpuTextureRelease(g.neuralInputTexture);
    if (g.exposureView) wgpuTextureViewRelease(g.exposureView);
    if (g.exposureTexture) wgpuTextureRelease(g.exposureTexture);
    if (g.specularAlbedoView) wgpuTextureViewRelease(g.specularAlbedoView);
    if (g.specularAlbedoTexture) wgpuTextureRelease(g.specularAlbedoTexture);
    if (g.diffuseAlbedoView) wgpuTextureViewRelease(g.diffuseAlbedoView);
    if (g.diffuseAlbedoTexture) wgpuTextureRelease(g.diffuseAlbedoTexture);
    if (g.normalRoughnessView) wgpuTextureViewRelease(g.normalRoughnessView);
    if (g.normalRoughnessTexture) wgpuTextureRelease(g.normalRoughnessTexture);
    if (g.motionView) wgpuTextureViewRelease(g.motionView);
    if (g.motionTexture) wgpuTextureRelease(g.motionTexture);
    if (g.hdrColorView) wgpuTextureViewRelease(g.hdrColorView);
    if (g.hdrColorTexture) wgpuTextureRelease(g.hdrColorTexture);
    if (g.depthView) wgpuTextureViewRelease(g.depthView);
    if (g.depthTexture) wgpuTextureRelease(g.depthTexture);
    g.exposureView = nullptr;
    g.exposureTexture = nullptr;
    g.specularAlbedoView = nullptr;
    g.specularAlbedoTexture = nullptr;
    g.diffuseAlbedoView = nullptr;
    g.diffuseAlbedoTexture = nullptr;
    g.normalRoughnessView = nullptr;
    g.normalRoughnessTexture = nullptr;
    g.motionView = nullptr;
    g.motionTexture = nullptr;
    g.hdrColorView = nullptr;
    g.hdrColorTexture = nullptr;
    g.depthView = nullptr;
    g.depthTexture = nullptr;
    g.dlssOutputView = nullptr;
    g.dlssOutputTexture = nullptr;
    g.finalHdrBindGroup = nullptr;
    g.finalReflectionBindGroup = nullptr;
    g.finalNeuralRenderingBindGroup = nullptr;
    g.finalDlssBindGroup = nullptr;
    g.finalDlssDisplayBindGroup = nullptr;
    g.neuralInputHdrBindGroup = nullptr;
    g.neuralInputReflectionBindGroup = nullptr;
    g.skyBindGroup = nullptr;
    g.specularHitDistanceView = nullptr;
    g.specularHitDistanceTexture = nullptr;
    g.reflectionView = nullptr;
    g.reflectionTexture = nullptr;
    g.neuralRenderingView = nullptr;
    g.neuralRenderingTexture = nullptr;
    g.neuralInputView = nullptr;
    g.neuralInputTexture = nullptr;
    g.hasPreviousViewProjection = false;
    g.temporalResetRequested = true;
    g.temporalJitterIndex = 0;
}

bool disableDlssConfiguration() {
    if (!streamlineReleaseViewport(0)) return false;
    StreamlineDLSSOptions off{};
    off.outputWidth = g.width;
    off.outputHeight = g.height;
    off.colorBuffersHDR = true;
    off.useAutoExposure = true;
    streamlineRequestFeatures(off, false, false);
    g.dlssRequest = off;
    g.dlssRayReconstructionRequested = false;
    g.dlssNeuralRenderingRequested = false;
    g.dlssNeuralRenderingReleasePending = false;
    g.dlssRenderWidth = 0;
    g.dlssRenderHeight = 0;
    g.dlssOutputWidth = 0;
    g.dlssOutputHeight = 0;
    g.temporalResetRequested = true;
    g.temporalJitterIndex = 0;
    return true;
}

void releaseStaticDrawRanges() {
    for (auto& [firstVertex, range] : g.staticDrawRanges) {
        if (range.indexBuffer) wgpuBufferRelease(range.indexBuffer);
    }
    g.staticDrawRanges.clear();
}

void releaseSceneResources() {
    releaseStaticDrawRanges();
    if (g.dynamicDrawRange.indexBuffer)
        wgpuBufferRelease(g.dynamicDrawRange.indexBuffer);
    g.dynamicDrawRange = {};
    g.drawIndexScratch.clear();
    releaseDepthResources();
    forgetNativeTexture(g.dynamicPositionTexture);
    if (g.dynamicPositionTexture) wgpuTextureRelease(g.dynamicPositionTexture);
    if (g.finalPipeline) wgpuRenderPipelineRelease(g.finalPipeline);
    if (g.neuralInputPipeline) wgpuRenderPipelineRelease(g.neuralInputPipeline);
    if (g.finalShader) wgpuShaderModuleRelease(g.finalShader);
    if (g.finalSampler) wgpuSamplerRelease(g.finalSampler);
    if (g.finalToneMapUniformBuffer) wgpuBufferRelease(g.finalToneMapUniformBuffer);
    if (g.finalPassthroughUniformBuffer)
        wgpuBufferRelease(g.finalPassthroughUniformBuffer);
    if (g.finalSkyUniformBuffer) wgpuBufferRelease(g.finalSkyUniformBuffer);
    if (g.scenePipeline) wgpuRenderPipelineRelease(g.scenePipeline);
    if (g.sceneShader) wgpuShaderModuleRelease(g.sceneShader);
    if (g.sceneBindGroup) wgpuBindGroupRelease(g.sceneBindGroup);
    if (g.sceneTextureSampler) wgpuSamplerRelease(g.sceneTextureSampler);
    if (g.sceneMaterialBuffer) wgpuBufferRelease(g.sceneMaterialBuffer);
    if (g.sceneNormalAtlasView) wgpuTextureViewRelease(g.sceneNormalAtlasView);
    if (g.sceneNormalAtlas) wgpuTextureRelease(g.sceneNormalAtlas);
    if (g.sceneTextureAtlasView) wgpuTextureViewRelease(g.sceneTextureAtlasView);
    if (g.sceneTextureAtlas) wgpuTextureRelease(g.sceneTextureAtlas);
    if (g.sceneVanillaTextureAtlasView) wgpuTextureViewRelease(g.sceneVanillaTextureAtlasView);
    if (g.sceneVanillaTextureAtlas) wgpuTextureRelease(g.sceneVanillaTextureAtlas);
    if (g.sceneUniformBuffer) wgpuBufferRelease(g.sceneUniformBuffer);
    if (g.sceneVertexBuffer) wgpuBufferRelease(g.sceneVertexBuffer);
    g.scenePipeline = nullptr;
    g.sceneShader = nullptr;
    g.sceneBindGroup = nullptr;
    g.sceneTextureSampler = nullptr;
    g.sceneMaterialBuffer = nullptr;
    g.sceneNormalAtlasView = nullptr;
    g.sceneNormalAtlas = nullptr;
    g.sceneTextureAtlasView = nullptr;
    g.sceneTextureAtlas = nullptr;
    g.sceneVanillaTextureAtlasView = nullptr;
    g.sceneVanillaTextureAtlas = nullptr;
    g.sceneUniformBuffer = nullptr;
    g.sceneVertexBuffer = nullptr;
    g.sceneVertexCapacity = 0;
    g.dynamicPositionTexture = nullptr;
    g.dynamicPositionTextureWidth = 0;
    g.dynamicPositionTextureHeight = 0;
    g.dynamicRayVertexCount = 0;
    g.dynamicRayMeshActive = false;
    g.dynamicPositionPixels.clear();
    g.dynamicRayIndices.clear();
    g.finalPipeline = nullptr;
    g.neuralInputPipeline = nullptr;
    g.finalShader = nullptr;
    g.finalSampler = nullptr;
    g.finalToneMapUniformBuffer = nullptr;
    g.finalPassthroughUniformBuffer = nullptr;
    g.finalSkyUniformBuffer = nullptr;
    g.raySceneHash = 0;
    g.lastRaySceneBuildFrame = 0;
    g.raySceneVertexCount = 0;
    g.raySceneEpoch = 0;
    g.raySceneRevision = UINT64_MAX;
    g.nextRaySceneRetirementTicket = 0;
    g.pendingRaySceneRetirementTicket = 0;
    g.completedRaySceneRetirementTicket.store(0, std::memory_order_release);
    g.failedRaySceneRetirementTicket.store(0, std::memory_order_release);
    g.raySceneIndices.clear();
    g.staticSectorManifest.clear();
    g.raySectorActivity.clear();
    g.staticSectorManifestEnabled = false;
    g.staticSectorManifestChanged = false;
    g.sceneTextureWidth = g.sceneTextureHeight = g.sceneTextureTileSize = g.sceneTextureCount = 1;
    g.uploadVertices.clear();
    g.currentScenePositions.clear();
    g.previousDynamicPositions.clear();
    g.previousDynamicVertexCount = 0;
    g.uploadedStaticRevision = UINT64_MAX;
    g.uploadedStaticVertexCount = 0;
    g.staticFadeStartVertex = 0;
    g.staticFadeEndVertex = 0;
    g.staticFadeStartFrame = 0;
    g.lastDynamicInputHash = 14695981039346656037ull;
    g.temporalStaticRevision = UINT64_MAX;
    g.hasPreviousCameraPosition = false;
}

void releaseRuntime() {
    const bool deviceFailed =
        g.deviceLost.load(std::memory_order_acquire) ||
        g.uncapturedDeviceError.load(std::memory_order_acquire);
    // Invalidate all callbacks before releasing any old device state.  In
    // particular, the normal Destroyed notification must not poison the next
    // device created in this process.
    g.deviceGeneration.fetch_add(1, std::memory_order_acq_rel);
    if (g.surface && g.configured && !deviceFailed) wgpuSurfaceUnconfigure(g.surface);
    // The bridges own Vulkan views into WebGPU textures.  Tear those views down
    // while the textures still exist, then prevent resource-release helpers
    // from looking the native images up again.
    rayQueryBridgeShutdown();
    streamlineShutdown();
    g.nativeInteropActive = false;
    releaseUiResources();
    releaseSceneResources();
    if (g.queue) wgpuQueueRelease(g.queue);
    if (g.device) wgpuDeviceRelease(g.device);
    if (g.adapter) wgpuAdapterRelease(g.adapter);
    if (g.surface) wgpuSurfaceRelease(g.surface);
    if (g.instance) wgpuInstanceRelease(g.instance);
    g.queue = nullptr;
    g.device = nullptr;
    g.adapter = nullptr;
    g.surface = nullptr;
    g.instance = nullptr;
    g.configured = false;
    g.rayQueryFeatureEnabled = false;
    g.rtxShadowsEnabled = false;
    g.rtxDynamicShadowsEnabled = false;
    g.rtxReflectionsEnabled = false;
    g.nvidiaPrepared = false;
    g.dlssRequest = {};
    g.dlssRenderWidth = 0;
    g.dlssRenderHeight = 0;
    g.dlssOutputWidth = 0;
    g.dlssOutputHeight = 0;
    g.dlssRayReconstructionRequested = false;
    g.dlssNeuralRenderingRequested = false;
    g.dlssNeuralRenderingFailedThisSession = false;
    g.dlssNeuralRenderingReleasePending = false;
    g.temporalResetRequested = true;
    g.temporalJitterIndex = 0;
    g.temporalStaticRevision = UINT64_MAX;
    g.hasPreviousCameraPosition = false;
    g.adapterName.clear();
    g.backendName = "Unavailable";
    g.deviceLost.store(false, std::memory_order_release);
    g.uncapturedDeviceError.store(false, std::memory_order_release);
    g.uncapturedDeviceErrorMessage[0] = '\0';
}

bool createUiResources(uint32_t width, uint32_t height) {
    releaseUiResources();
    if (!g.device || width == 0 || height == 0) return false;

    WGPUTextureDescriptor textureDescriptor{};
    textureDescriptor.label = stringView("RuneLite interface texture");
    textureDescriptor.dimension = WGPUTextureDimension_2D;
    textureDescriptor.size = WGPUExtent3D{width, height, 1};
    textureDescriptor.format = WGPUTextureFormat_BGRA8Unorm;
    textureDescriptor.mipLevelCount = 1;
    textureDescriptor.sampleCount = 1;
    textureDescriptor.usage = WGPUTextureUsage_TextureBinding | WGPUTextureUsage_CopyDst;
    g.uiTexture = wgpuDeviceCreateTexture(g.device, &textureDescriptor);
    if (!g.uiTexture) return false;
    g.uiView = wgpuTextureCreateView(g.uiTexture, nullptr);

    WGPUSamplerDescriptor samplerDescriptor{};
    samplerDescriptor.addressModeU = WGPUAddressMode_ClampToEdge;
    samplerDescriptor.addressModeV = WGPUAddressMode_ClampToEdge;
    samplerDescriptor.addressModeW = WGPUAddressMode_ClampToEdge;
    samplerDescriptor.magFilter = WGPUFilterMode_Linear;
    samplerDescriptor.minFilter = WGPUFilterMode_Linear;
    samplerDescriptor.mipmapFilter = WGPUMipmapFilterMode_Nearest;
    samplerDescriptor.maxAnisotropy = 1;
    g.uiSampler = wgpuDeviceCreateSampler(g.device, &samplerDescriptor);

    static constexpr char shaderSource[] = R"WGSL(
@group(0) @binding(0) var uiTexture: texture_2d<f32>;
@group(0) @binding(1) var uiSampler: sampler;
struct VertexOutput { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32> };
@vertex fn vs_main(@builtin(vertex_index) index: u32) -> VertexOutput {
  var positions = array<vec2<f32>, 3>(vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
  var uvs = array<vec2<f32>, 3>(vec2(0.0, 1.0), vec2(2.0, 1.0), vec2(0.0, -1.0));
  var output: VertexOutput;
  output.position = vec4(positions[index], 0.0, 1.0);
  output.uv = uvs[index];
  return output;
}
@fragment fn fs_opaque(input: VertexOutput) -> @location(0) vec4<f32> {
  let color = textureSample(uiTexture, uiSampler, input.uv);
  // RuneLite's software framebuffer is packed as 0x00RRGGBB. It is already
  // the final scene + interface composite, so its unused alpha byte must not
  // make the scene transparent when copied to the WebGPU surface.
  return vec4(color.rgb, 1.0);
}
@fragment fn fs_blend(input: VertexOutput) -> @location(0) vec4<f32> {
  return textureSample(uiTexture, uiSampler, input.uv);
}
)WGSL";
    WGPUShaderSourceWGSL wgsl{};
    wgsl.chain.sType = WGPUSType_ShaderSourceWGSL;
    wgsl.code = stringView(shaderSource);
    WGPUShaderModuleDescriptor shaderDescriptor{};
    shaderDescriptor.nextInChain = &wgsl.chain;
    shaderDescriptor.label = stringView("RuneLite interface shader");
    g.uiShader = wgpuDeviceCreateShaderModule(g.device, &shaderDescriptor);

    WGPUColorTargetState target{};
    target.format = g.surfaceFormat;
    target.blend = nullptr;
    target.writeMask = WGPUColorWriteMask_All;
    WGPUFragmentState fragment{};
    fragment.module = g.uiShader;
    fragment.entryPoint = stringView("fs_opaque");
    fragment.targetCount = 1;
    fragment.targets = &target;
    WGPURenderPipelineDescriptor pipelineDescriptor{};
    pipelineDescriptor.label = stringView("RuneLite interface pipeline");
    pipelineDescriptor.vertex.module = g.uiShader;
    pipelineDescriptor.vertex.entryPoint = stringView("vs_main");
    pipelineDescriptor.primitive.topology = WGPUPrimitiveTopology_TriangleList;
    pipelineDescriptor.primitive.frontFace = WGPUFrontFace_CCW;
    pipelineDescriptor.primitive.cullMode = WGPUCullMode_None;
    pipelineDescriptor.multisample.count = 1;
    pipelineDescriptor.multisample.mask = 0xffffffffu;
    pipelineDescriptor.fragment = &fragment;
    g.uiPipeline = wgpuDeviceCreateRenderPipeline(g.device, &pipelineDescriptor);

    WGPUBlendState blend{};
    blend.color.srcFactor = WGPUBlendFactor_SrcAlpha;
    blend.color.dstFactor = WGPUBlendFactor_OneMinusSrcAlpha;
    blend.color.operation = WGPUBlendOperation_Add;
    blend.alpha.srcFactor = WGPUBlendFactor_One;
    blend.alpha.dstFactor = WGPUBlendFactor_OneMinusSrcAlpha;
    blend.alpha.operation = WGPUBlendOperation_Add;
    target.blend = &blend;
    fragment.entryPoint = stringView("fs_blend");
    // UI is emitted in a separate pass after all scene/temporal work, so it is
    // deliberately depth-free and excluded from DLSS/RTX inputs.
    pipelineDescriptor.depthStencil = nullptr;
    pipelineDescriptor.label = stringView("RuneLite interface blend pipeline");
    g.uiBlendPipeline = wgpuDeviceCreateRenderPipeline(g.device, &pipelineDescriptor);

    if (!g.uiView || !g.uiSampler || !g.uiShader || !g.uiPipeline || !g.uiBlendPipeline) {
        releaseUiResources();
        return false;
    }

    WGPUBindGroupLayout layout = wgpuRenderPipelineGetBindGroupLayout(g.uiPipeline, 0);
    WGPUBindGroupEntry entries[2]{};
    entries[0].binding = 0;
    entries[0].textureView = g.uiView;
    entries[1].binding = 1;
    entries[1].sampler = g.uiSampler;
    WGPUBindGroupDescriptor bindGroupDescriptor{};
    bindGroupDescriptor.label = stringView("RuneLite interface bind group");
    bindGroupDescriptor.layout = layout;
    bindGroupDescriptor.entryCount = 2;
    bindGroupDescriptor.entries = entries;
    g.uiBindGroup = wgpuDeviceCreateBindGroup(g.device, &bindGroupDescriptor);
    wgpuBindGroupLayoutRelease(layout);

    layout = wgpuRenderPipelineGetBindGroupLayout(g.uiBlendPipeline, 0);
    bindGroupDescriptor.label = stringView("RuneLite interface blend bind group");
    bindGroupDescriptor.layout = layout;
    g.uiBlendBindGroup = wgpuDeviceCreateBindGroup(g.device, &bindGroupDescriptor);
    wgpuBindGroupLayoutRelease(layout);
    g.uiWidth = width;
    g.uiHeight = height;
    return g.uiBindGroup != nullptr && g.uiBlendBindGroup != nullptr;
}

bool createFinalPipeline() {
    if (g.finalPipeline && g.neuralInputPipeline && g.finalShader && g.finalSampler &&
        g.finalToneMapUniformBuffer && g.finalPassthroughUniformBuffer &&
        g.finalSkyUniformBuffer) return true;
    static constexpr char shaderSource[] = R"WGSL(
@group(0) @binding(0) var hdrTexture: texture_2d<f32>;
@group(0) @binding(1) var hdrSampler: sampler;
struct FinalOptions {
  values: vec4<f32>, // mode, DLSS sharpness, sharpen enabled, saturation
  inverseViewProjection: mat4x4<f32>,
  cameraPosition: vec4<f32>, // xyz position, display-space contrast
  sunDirectionTime: vec4<f32>,
  environment: vec4<f32>, // weather mode, intensity, wind, elapsed seconds
};
@group(0) @binding(2) var<uniform> finalOptions: FinalOptions;
struct VertexOutput { @builtin(position) position: vec4<f32>, @location(0) uv: vec2<f32> };
@vertex fn vs_main(@builtin(vertex_index) index: u32) -> VertexOutput {
  var positions = array<vec2<f32>, 3>(vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
  var uvs = array<vec2<f32>, 3>(vec2(0.0, 1.0), vec2(2.0, 1.0), vec2(0.0, -1.0));
  var output: VertexOutput;
  output.position = vec4(positions[index], 0.0, 1.0);
  output.uv = uvs[index];
  return output;
}
fn aces(color: vec3<f32>) -> vec3<f32> {
  let a = 2.51;
  let b = 0.03;
  let c = 2.43;
  let d = 0.59;
  let e = 0.14;
  return clamp((color * (a * color + b)) / (color * (c * color + d) + e), vec3(0.0), vec3(1.0));
}
fn hash33(value: vec3<f32>) -> f32 {
  let p = fract(value * vec3(0.1031, 0.1030, 0.0973));
  let q = p + vec3(dot(p, p.yxz + vec3(33.33)));
  return fract((q.x + q.y) * q.z);
}
fn hash21(value: vec2<f32>) -> f32 {
  var p = fract(vec3(value.x, value.y, value.x) * vec3(0.1031, 0.1030, 0.0973));
  p += vec3(dot(p, p.yzx + vec3(33.33)));
  return fract((p.x + p.y) * p.z);
}
fn valueNoise2(value: vec2<f32>) -> f32 {
  let cell = floor(value);
  let local = fract(value);
  let blend = local * local * (3.0 - 2.0 * local);
  let a = hash21(cell);
  let b = hash21(cell + vec2(1.0, 0.0));
  let c = hash21(cell + vec2(0.0, 1.0));
  let d = hash21(cell + vec2(1.0, 1.0));
  return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}
fn cloudNoise(value: vec2<f32>) -> f32 {
  return valueNoise2(value) * 0.55
    + valueNoise2(value * 2.03 + vec2(11.7, 4.3)) * 0.28
    + valueNoise2(value * 4.11 - vec2(3.1, 8.4)) * 0.17;
}
fn skyDirection(uv: vec2<f32>) -> vec3<f32> {
  let ndc = vec2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
  let nearClip = vec4(ndc, 1.0, 1.0);
  let farClip = vec4(ndc, 0.05, 1.0);
  let nearWorld = finalOptions.inverseViewProjection * nearClip;
  let farWorld = finalOptions.inverseViewProjection * farClip;
  let nearPoint = nearWorld.xyz / max(abs(nearWorld.w), 1e-6);
  let farPoint = farWorld.xyz / max(abs(farWorld.w), 1e-6);
  return normalize(farPoint - nearPoint);
}
fn octahedralSkyUv(direction: vec3<f32>) -> vec2<f32> {
  let denominator = max(
    abs(direction.x) + abs(direction.y) + abs(direction.z), 1e-6);
  var projected = direction.xz / denominator;
  if (direction.y < 0.0) {
    let folded = (vec2(1.0) - abs(projected.yx)) * sign(projected);
    projected = folded;
  }
  return projected * 0.5 + vec2(0.5);
}
fn proceduralSky(uv: vec2<f32>) -> vec3<f32> {
  let ray = skyDirection(uv);
  let sunDirection = normalize(finalOptions.sunDirectionTime.xyz);
  let sunElevation = -sunDirection.y;
  let day = smoothstep(-0.16, 0.10, sunElevation);
  let twilight = 1.0 - smoothstep(-0.08, 0.24, abs(sunElevation));
  let up = clamp(-ray.y * 0.5 + 0.5, 0.0, 1.0);
  let horizon = pow(1.0 - abs(-ray.y), 3.0);
  let nightZenith = vec3(0.003, 0.008, 0.025);
  let nightHorizon = vec3(0.018, 0.026, 0.055);
  let dayZenith = vec3(0.055, 0.24, 0.58);
  let dayHorizon = vec3(0.42, 0.67, 0.88);
  var color = mix(
    mix(nightHorizon, nightZenith, up),
    mix(dayHorizon, dayZenith, up), day);
  color += vec3(0.55, 0.16, 0.045) * horizon * twilight;
  let sunAmount = pow(max(dot(ray, sunDirection), 0.0), 900.0);
  color += vec3(7.0, 4.8, 2.5) * sunAmount * day;
  let moonDirection = -sunDirection;
  let moonAmount = pow(max(dot(ray, moonDirection), 0.0), 1400.0);
  color += vec3(0.55, 0.68, 0.95) * moonAmount * (1.0 - day);
  let weatherMode = finalOptions.environment.x;
  let weatherIntensity = clamp(finalOptions.environment.y, 0.0, 1.0);
  let wind = clamp(finalOptions.environment.z, 0.0, 1.0);
  let elapsed = finalOptions.environment.w;
  let overhead = -ray.y;
  let cloudDomain = ray.xz / max(0.18, overhead + 0.42) * 2.4
    + vec2(elapsed * (0.004 + 0.026 * wind), elapsed * 0.003);
  let noise = cloudNoise(cloudDomain);
  var coverage = 0.04 * weatherIntensity;
  if (weatherMode > 0.5) { coverage = 0.38 + 0.30 * weatherIntensity; }
  if (weatherMode > 1.5) { coverage = 0.62 + 0.24 * weatherIntensity; }
  if (weatherMode > 2.5) { coverage = 0.74 + 0.20 * weatherIntensity; }
  let cloud = smoothstep(1.0 - coverage - 0.10, 1.0 - coverage + 0.06, noise)
    * smoothstep(-0.10, 0.09, overhead);
  let cloudDay = mix(vec3(0.045, 0.055, 0.075), vec3(0.72, 0.77, 0.82), day);
  let cloudStorm = mix(cloudDay, vec3(0.075, 0.09, 0.115),
    weatherIntensity * smoothstep(1.4, 2.8, weatherMode));
  let cloudEdge = smoothstep(0.0, 0.9, noise) * (0.16 + 0.24 * day);
  color = mix(color, cloudStorm + vec3(cloudEdge), cloud * (0.70 + 0.25 * weatherIntensity));
  // Generate one stable, anti-aliased star per sparse octahedral cell. The
  // previous quantized 3-D ray grid changed cells across triangle-sized
  // screen regions, producing popping wedges while the camera rotated.
  let starGrid = octahedralSkyUv(ray) * 820.0;
  let starCell = floor(starGrid);
  let starLocal = fract(starGrid);
  let starSeed = hash21(starCell + vec2(17.0, 53.0));
  let starCenter = vec2(
    hash21(starCell + vec2(101.0, 29.0)),
    hash21(starCell + vec2(47.0, 193.0))) * 0.56 + vec2(0.22);
  let starDistance = length(starLocal - starCenter);
  let starRadius = mix(0.065, 0.14, hash21(starCell + vec2(7.0, 211.0)));
  let starAa = max(fwidth(starDistance), 0.012);
  let starDisc = 1.0 - smoothstep(
    starRadius - starAa, starRadius + starAa, starDistance);
  let starExists = smoothstep(0.9965, 0.9995, starSeed);
  let starTemperature = hash21(starCell + vec2(307.0, 89.0));
  let starColor = mix(
    vec3(0.68, 0.78, 1.0), vec3(1.0, 0.83, 0.64), starTemperature);
  let starHorizonFade = smoothstep(0.02, 0.15, overhead);
  color += starColor * starDisc * starExists * starHorizonFade
    * (1.0 - day) * (1.0 - cloud) * 1.15;
  let stormPeriod = floor(elapsed * 0.16);
  let stormPhase = fract(elapsed * 0.16);
  let stormEvent = step(0.86, hash21(vec2(stormPeriod, 71.0)));
  let lightning = pow(max(sin(stormPhase * 12.56637), 0.0), 28.0)
    * stormEvent * weatherIntensity * step(2.5, weatherMode);
  color += vec3(0.42, 0.52, 0.78) * lightning;
  return max(color, vec3(0.0));
}
fn rainCoverage(uv: vec2<f32>) -> f32 {
  let weatherMode = finalOptions.environment.x;
  let amount = clamp(finalOptions.environment.y, 0.0, 1.0)
    * step(1.5, weatherMode);
  let wind = clamp(finalOptions.environment.z, 0.0, 1.0);
  let elapsed = finalOptions.environment.w;
  let advected = vec2(
    uv.x + elapsed * (0.035 + wind * 0.11),
    uv.y + elapsed * (0.72 + amount * 0.34));
  let scale = vec2(210.0, 72.0);
  let cell = floor(advected * scale);
  let local = fract(advected * scale);
  let random = hash21(cell);
  let center = fract(random + local.y * (0.10 + 0.22 * wind));
  let width = abs(local.x - center);
  let dash = smoothstep(0.08, 0.24, local.y) * (1.0 - smoothstep(0.62, 0.96, local.y));
  let streak = (1.0 - smoothstep(0.012, 0.042, width)) * dash
    * step(random, 0.34 + amount * 0.46);
  return streak * amount;
}
fn sharpenedScene(uv: vec2<f32>, centerSample: vec4<f32>, center: vec3<f32>) -> vec3<f32> {
  let dimensions = vec2<f32>(textureDimensions(hdrTexture));
  let texel = vec2(1.0) / max(dimensions, vec2(1.0));
  let northSample = textureSampleLevel(hdrTexture, hdrSampler, uv - vec2(0.0, texel.y), 0.0);
  let southSample = textureSampleLevel(hdrTexture, hdrSampler, uv + vec2(0.0, texel.y), 0.0);
  let westSample = textureSampleLevel(hdrTexture, hdrSampler, uv - vec2(texel.x, 0.0), 0.0);
  let eastSample = textureSampleLevel(hdrTexture, hdrSampler, uv + vec2(texel.x, 0.0), 0.0);
  // Keep the reconstructed sky out of the edge kernel. Otherwise scene/sky
  // boundaries create a bright one-pixel halo after Super Resolution.
  let north = select(center, northSample.rgb, northSample.a > 0.001);
  let south = select(center, southSample.rgb, southSample.a > 0.001);
  let west = select(center, westSample.rgb, westSample.a > 0.001);
  let east = select(center, eastSample.rgb, eastSample.a > 0.001);
  let average = (north + south + west + east) * 0.25;
  let minimumColor = min(center, min(min(north, south), min(west, east)));
  let maximumColor = max(center, max(max(north, south), max(west, east)));
  let strength = clamp(finalOptions.values.y, 0.0, 1.0) * 0.72;
  return clamp(center + (center - average) * strength,
    minimumColor * 0.92, maximumColor * 1.08 + vec3(1e-4));
}
@fragment fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
  let mode = finalOptions.values.x;
  let sampled = textureSampleLevel(hdrTexture, hdrSampler, input.uv, 0.0);
  if (mode > 1.5) {
    return vec4(proceduralSky(input.uv), 1.0);
  }
  var sourceColor = max(sampled.rgb, vec3(0.0));
  if (sampled.a <= 0.001) {
    sourceColor = proceduralSky(input.uv);
  }
  if (finalOptions.values.z > 0.5 && sampled.a > 0.001) {
    sourceColor = max(sharpenedScene(input.uv, sampled, sourceColor), vec3(0.0));
  }
  let rain = rainCoverage(input.uv);
  if (mode > 0.5) {
    // Display-referred Neural Rendering input has already received weather in
    // the tone-map staging pass. Mode 1 is a strict passthrough so rain is not
    // composited a second time after NVIDIA returns the enhanced image.
    return vec4(sourceColor, 1.0);
  }
  let linearColor = sourceColor + vec3(0.28, 0.38, 0.52) * rain * 0.52;
  let mapped = aces(linearColor);
  // 117 grades the lit, display-referred image. Applying contrast to linear
  // albedo before lighting subtracts 0.025 from every dark texel at the 117 HD
  // preset's 105% contrast, crushing much of the diffuse atlas to black and
  // contaminating the albedo guides consumed by RTX/DLSS. Keep scene/G-buffer
  // values linear and ungraded, then apply the user controls exactly once here.
  var displayColor = pow(mapped, vec3(1.0 / 2.2));
  let luminance = dot(displayColor, vec3(0.2126, 0.7152, 0.0722));
  displayColor = mix(
    vec3(luminance), displayColor, clamp(finalOptions.values.w, 0.0, 2.0));
  displayColor = clamp(
    (displayColor - vec3(0.5)) * clamp(finalOptions.cameraPosition.w, 0.5, 1.5)
      + vec3(0.5),
    vec3(0.0), vec3(1.0));
  return vec4(displayColor, 1.0);
}
)WGSL";
    WGPUShaderSourceWGSL wgsl{};
    wgsl.chain.sType = WGPUSType_ShaderSourceWGSL;
    wgsl.code = stringView(shaderSource);
    WGPUShaderModuleDescriptor shader{};
    shader.nextInChain = &wgsl.chain;
    shader.label = stringView("RuneLite HDR final shader");
    g.finalShader = wgpuDeviceCreateShaderModule(g.device, &shader);

    WGPUSamplerDescriptor sampler{};
    sampler.addressModeU = WGPUAddressMode_ClampToEdge;
    sampler.addressModeV = WGPUAddressMode_ClampToEdge;
    sampler.addressModeW = WGPUAddressMode_ClampToEdge;
    sampler.magFilter = WGPUFilterMode_Linear;
    sampler.minFilter = WGPUFilterMode_Linear;
    sampler.mipmapFilter = WGPUMipmapFilterMode_Nearest;
    sampler.maxAnisotropy = 1;
    g.finalSampler = wgpuDeviceCreateSampler(g.device, &sampler);

    auto createPipeline = [&](const char* label, WGPUTextureFormat format) {
        WGPUColorTargetState target{};
        target.format = format;
        target.writeMask = WGPUColorWriteMask_All;
        WGPUFragmentState fragment{};
        fragment.module = g.finalShader;
        fragment.entryPoint = stringView("fs_main");
        fragment.targetCount = 1;
        fragment.targets = &target;
        WGPURenderPipelineDescriptor pipeline{};
        pipeline.label = stringView(label);
        pipeline.vertex.module = g.finalShader;
        pipeline.vertex.entryPoint = stringView("vs_main");
        pipeline.primitive.topology = WGPUPrimitiveTopology_TriangleList;
        pipeline.primitive.frontFace = WGPUFrontFace_CCW;
        pipeline.primitive.cullMode = WGPUCullMode_None;
        pipeline.multisample.count = 1;
        pipeline.multisample.mask = 0xffffffffu;
        pipeline.fragment = &fragment;
        return wgpuDeviceCreateRenderPipeline(g.device, &pipeline);
    };
    g.finalPipeline = createPipeline("RuneLite HDR final pipeline", g.surfaceFormat);
    g.neuralInputPipeline = createPipeline(
        "RuneLite DLSS 5 display-referred input pipeline",
        WGPUTextureFormat_RGBA16Float);

    WGPUBufferDescriptor uniform{};
    uniform.label = stringView("RuneLite final tone-map options");
    constexpr uint64_t finalUniformBytes = 128u;
    uniform.size = finalUniformBytes;
    uniform.usage = WGPUBufferUsage_Uniform | WGPUBufferUsage_CopyDst;
    g.finalToneMapUniformBuffer = wgpuDeviceCreateBuffer(g.device, &uniform);
    uniform.label = stringView("RuneLite final passthrough options");
    g.finalPassthroughUniformBuffer = wgpuDeviceCreateBuffer(g.device, &uniform);
    uniform.label = stringView("RuneLite procedural sky options");
    g.finalSkyUniformBuffer = wgpuDeviceCreateBuffer(g.device, &uniform);
    float toneMapOptions[32]{};
    float passthroughOptions[32]{};
    float skyOptions[32]{};
    passthroughOptions[0] = 1.0f;
    skyOptions[0] = 2.0f;
    if (g.finalToneMapUniformBuffer)
        wgpuQueueWriteBuffer(g.queue, g.finalToneMapUniformBuffer, 0,
                             toneMapOptions, sizeof(toneMapOptions));
    if (g.finalPassthroughUniformBuffer)
        wgpuQueueWriteBuffer(g.queue, g.finalPassthroughUniformBuffer, 0,
                             passthroughOptions, sizeof(passthroughOptions));
    if (g.finalSkyUniformBuffer)
        wgpuQueueWriteBuffer(g.queue, g.finalSkyUniformBuffer, 0,
                             skyOptions, sizeof(skyOptions));
    return g.finalShader && g.finalSampler && g.finalPipeline &&
        g.neuralInputPipeline && g.finalToneMapUniformBuffer &&
        g.finalPassthroughUniformBuffer && g.finalSkyUniformBuffer;
}

bool createFinalBindGroups() {
    if (!g.finalPipeline || !g.neuralInputPipeline || !g.finalSampler ||
        !g.finalToneMapUniformBuffer || !g.finalPassthroughUniformBuffer ||
        !g.finalSkyUniformBuffer ||
        !g.hdrColorView || !g.reflectionView || !g.neuralInputView ||
        !g.neuralRenderingView || !g.dlssOutputView) return false;
    if (g.finalHdrBindGroup) wgpuBindGroupRelease(g.finalHdrBindGroup);
    if (g.finalReflectionBindGroup) wgpuBindGroupRelease(g.finalReflectionBindGroup);
    if (g.finalNeuralRenderingBindGroup)
        wgpuBindGroupRelease(g.finalNeuralRenderingBindGroup);
    if (g.finalDlssBindGroup) wgpuBindGroupRelease(g.finalDlssBindGroup);
    if (g.finalDlssDisplayBindGroup) wgpuBindGroupRelease(g.finalDlssDisplayBindGroup);
    if (g.neuralInputHdrBindGroup) wgpuBindGroupRelease(g.neuralInputHdrBindGroup);
    if (g.neuralInputReflectionBindGroup)
        wgpuBindGroupRelease(g.neuralInputReflectionBindGroup);
    if (g.skyBindGroup) wgpuBindGroupRelease(g.skyBindGroup);
    g.finalHdrBindGroup = nullptr;
    g.finalReflectionBindGroup = nullptr;
    g.finalNeuralRenderingBindGroup = nullptr;
    g.finalDlssBindGroup = nullptr;
    g.finalDlssDisplayBindGroup = nullptr;
    g.neuralInputHdrBindGroup = nullptr;
    g.neuralInputReflectionBindGroup = nullptr;
    g.skyBindGroup = nullptr;
    auto create = [&](const char* label, WGPUBindGroupLayout layout,
                      WGPUTextureView view, WGPUBuffer options) {
        WGPUBindGroupEntry entries[3]{};
        entries[0].binding = 0;
        entries[0].textureView = view;
        entries[1].binding = 1;
        entries[1].sampler = g.finalSampler;
        entries[2].binding = 2;
        entries[2].buffer = options;
        entries[2].offset = 0;
        entries[2].size = 128u;
        WGPUBindGroupDescriptor descriptor{};
        descriptor.label = stringView(label);
        descriptor.layout = layout;
        descriptor.entryCount = 3;
        descriptor.entries = entries;
        return wgpuDeviceCreateBindGroup(g.device, &descriptor);
    };
    WGPUBindGroupLayout finalLayout =
        wgpuRenderPipelineGetBindGroupLayout(g.finalPipeline, 0);
    g.finalHdrBindGroup = create(
        "RuneLite HDR final bind group", finalLayout, g.hdrColorView,
        g.finalToneMapUniformBuffer);
    g.finalReflectionBindGroup = create(
        "RuneLite RTX reflection final bind group", finalLayout, g.reflectionView,
        g.finalToneMapUniformBuffer);
    g.finalNeuralRenderingBindGroup = create(
        "RuneLite DLSS 5 Neural Rendering final bind group", finalLayout,
        g.neuralRenderingView, g.finalPassthroughUniformBuffer);
    g.finalDlssBindGroup = create(
        "RuneLite DLSS HDR final bind group", finalLayout, g.dlssOutputView,
        g.finalToneMapUniformBuffer);
    g.finalDlssDisplayBindGroup = create(
        "RuneLite DLSS display final bind group", finalLayout, g.dlssOutputView,
        g.finalPassthroughUniformBuffer);
    wgpuBindGroupLayoutRelease(finalLayout);

    WGPUBindGroupLayout neuralLayout =
        wgpuRenderPipelineGetBindGroupLayout(g.neuralInputPipeline, 0);
    g.neuralInputHdrBindGroup = create(
        "RuneLite DLSS 5 HDR staging bind group", neuralLayout, g.hdrColorView,
        g.finalToneMapUniformBuffer);
    g.neuralInputReflectionBindGroup = create(
        "RuneLite DLSS 5 reflection staging bind group", neuralLayout,
        g.reflectionView, g.finalToneMapUniformBuffer);
    // Sky generation ignores the sampled texture. neuralInputView is a valid,
    // non-aliased source while hdrColorView is the render target.
    g.skyBindGroup = create(
        "RuneLite procedural sky bind group", neuralLayout,
        g.neuralInputView, g.finalSkyUniformBuffer);
    wgpuBindGroupLayoutRelease(neuralLayout);
    return g.finalHdrBindGroup && g.finalReflectionBindGroup &&
        g.finalNeuralRenderingBindGroup && g.finalDlssBindGroup &&
        g.finalDlssDisplayBindGroup && g.neuralInputHdrBindGroup &&
        g.neuralInputReflectionBindGroup && g.skyBindGroup;
}

bool createDepthResources() {
    releaseDepthResources();
    auto createTexture = [](const char* label, WGPUTextureFormat format,
                            WGPUTextureUsage usage, uint32_t width, uint32_t height,
                            WGPUTexture& texture, WGPUTextureView& view) {
        WGPUTextureDescriptor descriptor{};
        descriptor.label = stringView(label);
        descriptor.dimension = WGPUTextureDimension_2D;
        descriptor.size = WGPUExtent3D{width, height, 1};
        descriptor.format = format;
        descriptor.mipLevelCount = 1;
        descriptor.sampleCount = 1;
        descriptor.usage = usage;
        texture = wgpuDeviceCreateTexture(g.device, &descriptor);
        if (!texture) return false;
        view = wgpuTextureCreateView(texture, nullptr);
        return view != nullptr;
    };
    const WGPUTextureUsage inputUsage =
        WGPUTextureUsage_RenderAttachment | WGPUTextureUsage_TextureBinding;
    const WGPUTextureUsage storageInputUsage = inputUsage | WGPUTextureUsage_StorageBinding |
        WGPUTextureUsage_CopySrc;
    const uint32_t renderWidth = sceneWidth();
    const uint32_t renderHeight = sceneHeight();
    if (!createTexture("RuneLite inverted depth", WGPUTextureFormat_Depth32Float,
                       inputUsage, renderWidth, renderHeight, g.depthTexture, g.depthView) ||
        !createTexture("RuneLite HDR scene color", WGPUTextureFormat_RGBA16Float,
                       storageInputUsage, renderWidth, renderHeight,
                       g.hdrColorTexture, g.hdrColorView) ||
        !createTexture("RuneLite dense motion vectors", WGPUTextureFormat_RG16Float,
                       inputUsage, renderWidth, renderHeight, g.motionTexture, g.motionView) ||
        !createTexture("RuneLite normal roughness", WGPUTextureFormat_RGBA16Float,
                       inputUsage, renderWidth, renderHeight,
                       g.normalRoughnessTexture, g.normalRoughnessView) ||
        !createTexture("RuneLite diffuse albedo", WGPUTextureFormat_RGBA8Unorm,
                       inputUsage, renderWidth, renderHeight,
                       g.diffuseAlbedoTexture, g.diffuseAlbedoView) ||
        !createTexture("RuneLite specular albedo", WGPUTextureFormat_RGBA8Unorm,
                       inputUsage, renderWidth, renderHeight,
                       g.specularAlbedoTexture, g.specularAlbedoView) ||
        !createTexture("RuneLite RTX reflections", WGPUTextureFormat_RGBA16Float,
                       storageInputUsage, renderWidth, renderHeight,
                       g.reflectionTexture, g.reflectionView) ||
        !createTexture("RuneLite DLSS 5 display-referred input",
                       WGPUTextureFormat_RGBA16Float, storageInputUsage,
                       renderWidth, renderHeight,
                       g.neuralInputTexture, g.neuralInputView) ||
        !createTexture("RuneLite DLSS 5 Neural Rendering output",
                       WGPUTextureFormat_RGBA16Float, storageInputUsage,
                       renderWidth, renderHeight,
                       g.neuralRenderingTexture, g.neuralRenderingView) ||
        !createTexture("RuneLite specular hit distance", WGPUTextureFormat_R32Float,
                       storageInputUsage, renderWidth, renderHeight,
                       g.specularHitDistanceTexture, g.specularHitDistanceView) ||
        !createTexture("RuneLite exposure", WGPUTextureFormat_R32Float,
                       WGPUTextureUsage_TextureBinding | WGPUTextureUsage_CopyDst,
                       1, 1, g.exposureTexture, g.exposureView) ||
        !createTexture("RuneLite DLSS output", WGPUTextureFormat_RGBA16Float,
                       WGPUTextureUsage_RenderAttachment | WGPUTextureUsage_TextureBinding |
                           WGPUTextureUsage_StorageBinding,
                       g.width, g.height, g.dlssOutputTexture, g.dlssOutputView)) {
        releaseDepthResources();
        return false;
    }
    // The scene shader has already applied the user-facing exposure control.
    // Tag a neutral pre-exposure value so DLSS does not compensate for it a
    // second time.
    const float exposure = 1.0f;
    WGPUTexelCopyTextureInfo destination{};
    destination.texture = g.exposureTexture;
    WGPUTexelCopyBufferLayout layout{};
    layout.bytesPerRow = sizeof(float);
    layout.rowsPerImage = 1;
    const WGPUExtent3D extent{1, 1, 1};
    wgpuQueueWriteTexture(g.queue, &destination, &exposure, sizeof(exposure), &layout, &extent);
    return createFinalBindGroups();
}

bool createSceneBindGroup() {
    if (!g.scenePipeline || !g.sceneUniformBuffer || !g.sceneTextureAtlasView ||
        !g.sceneVanillaTextureAtlasView || !g.sceneNormalAtlasView ||
        !g.sceneTextureSampler || !g.sceneMaterialBuffer)
        return false;
    if (g.sceneBindGroup) wgpuBindGroupRelease(g.sceneBindGroup);
    g.sceneBindGroup = nullptr;

    WGPUBindGroupLayout layout = wgpuRenderPipelineGetBindGroupLayout(g.scenePipeline, 0);
    WGPUBindGroupEntry entries[6]{};
    entries[0].binding = 0;
    entries[0].buffer = g.sceneUniformBuffer;
    entries[0].size = sizeof(float) * 64;
    entries[1].binding = 1;
    entries[1].textureView = g.sceneTextureAtlasView;
    entries[2].binding = 2;
    entries[2].sampler = g.sceneTextureSampler;
    entries[3].binding = 3;
    entries[3].textureView = g.sceneNormalAtlasView;
    entries[4].binding = 4;
    entries[4].buffer = g.sceneMaterialBuffer;
    entries[4].size = sizeof(float) * 4u * g.sceneTextureCount;
    entries[5].binding = 5;
    entries[5].textureView = g.sceneVanillaTextureAtlasView;
    WGPUBindGroupDescriptor bindGroup{};
    bindGroup.label = stringView("RuneLite WebGPU scene bind group");
    bindGroup.layout = layout;
    bindGroup.entryCount = 6;
    bindGroup.entries = entries;
    g.sceneBindGroup = wgpuDeviceCreateBindGroup(g.device, &bindGroup);
    wgpuBindGroupLayoutRelease(layout);
    return g.sceneBindGroup != nullptr;
}

bool createSceneTextureAtlas(
    const uint32_t* pixels,
    const uint32_t* vanillaPixels,
    const uint32_t* normalPixels,
    const float* materialParameters,
    uint32_t width,
    uint32_t height,
    uint32_t tileSize,
    uint32_t textureCount) {
    if (!g.device || !g.queue || !pixels || !vanillaPixels || !normalPixels ||
        !materialParameters ||
        width == 0 || height == 0 || tileSize == 0 || textureCount == 0)
        return false;

    WGPULimits limits = WGPU_LIMITS_INIT;
    if (width % tileSize || height % tileSize || (tileSize & (tileSize - 1u)) ||
        uint64_t(textureCount) > uint64_t(width / tileSize) * (height / tileSize) ||
        wgpuDeviceGetLimits(g.device, &limits) != WGPUStatus_Success ||
        textureCount > limits.maxTextureArrayLayers) return false;

    if (g.sceneBindGroup) wgpuBindGroupRelease(g.sceneBindGroup);
    if (g.sceneTextureSampler) wgpuSamplerRelease(g.sceneTextureSampler);
    if (g.sceneMaterialBuffer) wgpuBufferRelease(g.sceneMaterialBuffer);
    if (g.sceneNormalAtlasView) wgpuTextureViewRelease(g.sceneNormalAtlasView);
    if (g.sceneNormalAtlas) wgpuTextureRelease(g.sceneNormalAtlas);
    if (g.sceneTextureAtlasView) wgpuTextureViewRelease(g.sceneTextureAtlasView);
    if (g.sceneTextureAtlas) wgpuTextureRelease(g.sceneTextureAtlas);
    if (g.sceneVanillaTextureAtlasView) wgpuTextureViewRelease(g.sceneVanillaTextureAtlasView);
    if (g.sceneVanillaTextureAtlas) wgpuTextureRelease(g.sceneVanillaTextureAtlas);
    g.sceneBindGroup = nullptr;
    g.sceneTextureSampler = nullptr;
    g.sceneMaterialBuffer = nullptr;
    g.sceneNormalAtlasView = nullptr;
    g.sceneNormalAtlas = nullptr;
    g.sceneTextureAtlasView = nullptr;
    g.sceneTextureAtlas = nullptr;
    g.sceneVanillaTextureAtlasView = nullptr;
    g.sceneVanillaTextureAtlas = nullptr;

    uint32_t mipCount = 1;
    for (uint32_t size = tileSize; size > 1; size /= 2) ++mipCount;
    if (!uploadMaterialLayers(g.device, g.queue, pixels, width, tileSize, textureCount, mipCount, false,
            g.sceneTextureAtlas, g.sceneTextureAtlasView) ||
        !uploadMaterialLayers(g.device, g.queue, vanillaPixels, width, tileSize, textureCount, mipCount, false,
            g.sceneVanillaTextureAtlas, g.sceneVanillaTextureAtlasView) ||
        !uploadMaterialLayers(g.device, g.queue, normalPixels, width, tileSize, textureCount, mipCount, true,
            g.sceneNormalAtlas, g.sceneNormalAtlasView)) return false;
    WGPUSamplerDescriptor sampler{};
    sampler.addressModeU = WGPUAddressMode_Repeat;
    sampler.addressModeV = WGPUAddressMode_Repeat;
    sampler.addressModeW = WGPUAddressMode_ClampToEdge;
    sampler.magFilter = WGPUFilterMode_Linear;
    sampler.minFilter = WGPUFilterMode_Linear;
    sampler.mipmapFilter = WGPUMipmapFilterMode_Linear;
    sampler.lodMaxClamp = float(mipCount - 1);
    sampler.maxAnisotropy = 8;
    g.sceneTextureSampler = wgpuDeviceCreateSampler(g.device, &sampler);
    if (!g.sceneTextureSampler) return false;

    WGPUBufferDescriptor materialBuffer{};
    materialBuffer.label = stringView("RuneLite 117 material parameters");
    materialBuffer.size = sizeof(float) * 4u * textureCount;
    materialBuffer.usage = WGPUBufferUsage_Storage | WGPUBufferUsage_CopyDst;
    g.sceneMaterialBuffer = wgpuDeviceCreateBuffer(g.device, &materialBuffer);
    if (!g.sceneMaterialBuffer) return false;
    wgpuQueueWriteBuffer(g.queue, g.sceneMaterialBuffer, 0, materialParameters, materialBuffer.size);

    g.sceneTextureWidth = width;
    g.sceneTextureHeight = height;
    g.sceneTextureTileSize = tileSize;
    g.sceneTextureCount = textureCount;
    return !g.scenePipeline || !g.sceneUniformBuffer || createSceneBindGroup();
}

bool createSceneResources() {
    if (g.scenePipeline && g.sceneUniformBuffer && g.sceneBindGroup)
        return createFinalPipeline() && (g.depthView || createDepthResources());

    static constexpr char shaderSource[] = R"WGSL(
struct SceneUniforms {
  viewProjection: mat4x4<f32>,
  previousViewProjection: mat4x4<f32>,
  atlas: vec4<f32>, // pixel width, pixel height, tile size, texture count
  frame: vec4<f32>, // time seconds, frame index, sector fade start, fade factor
  visual: vec4<f32>, // normal strength, saturation, contrast, exposure
  lighting: vec4<f32>, // ambient intensity and sun direction
  features: vec4<f32>, // textures, style mode, daylight, model smoothing strength
  environment: vec4<f32>, // weather mode, intensity, wind, water quality
  water: vec4<f32>, // wave strength, clarity, rain amount, weather dimming
  camera: vec4<f32>, // world-space camera position
};
@group(0) @binding(0) var<uniform> scene: SceneUniforms;
@group(0) @binding(1) var textureAtlas: texture_2d_array<f32>;
@group(0) @binding(2) var textureSampler: sampler;
@group(0) @binding(3) var normalAtlas: texture_2d_array<f32>;
@group(0) @binding(4) var<storage, read> materialParameters: array<vec4<f32>>;
@group(0) @binding(5) var vanillaTextureAtlas: texture_2d_array<f32>;

struct VertexInput {
  @location(0) position: vec3<f32>,
  @location(1) previousPosition: vec3<f32>,
  @location(2) packedHsl: u32,
  @location(3) uv: vec2<f32>,
  @location(4) textureId: i32,
  @location(5) sourceFlags: u32,
  @location(6) vanillaPackedHsl: u32,
  @builtin(vertex_index) vertexIndex: u32,
};
struct VertexOutput {
  @builtin(position) position: vec4<f32>,
  @location(0) color: vec3<f32>,
  @location(1) uv: vec2<f32>,
  @location(2) @interpolate(flat) textureId: i32,
  @location(3) worldPosition: vec3<f32>,
  @location(4) currentClip: vec4<f32>,
  @location(5) previousClip: vec4<f32>,
  @location(6) @interpolate(flat) sourceFlags: u32,
  @location(7) terrainBlend: vec3<f32>,
  // Smooth model normal decoded per vertex. Terrain and explicitly flat model
  // faces leave this unused and retain their geometric derivative normal.
  @location(8) smoothNormal: vec3<f32>,
  @location(9) @interpolate(flat) sectorFade: f32,
};

struct FragmentOutput {
  @location(0) hdrColor: vec4<f32>,
  @location(1) motion: vec2<f32>,
  @location(2) normalRoughness: vec4<f32>,
  @location(3) diffuseAlbedo: vec4<f32>,
  @location(4) specularAlbedo: vec4<f32>,
};

fn hueChannel(p: f32, q: f32, input: f32) -> f32 {
  var t = input;
  if (t < 0.0) { t += 1.0; }
  if (t > 1.0) { t -= 1.0; }
  if (t < 1.0 / 6.0) { return p + (q - p) * 6.0 * t; }
  if (t < 1.0 / 2.0) { return q; }
  if (t < 2.0 / 3.0) { return p + (q - p) * (2.0 / 3.0 - t) * 6.0; }
  return p;
}

fn hslToRgb(packed: u32) -> vec3<f32> {
  let hue = f32((packed >> 10u) & 63u) / 64.0 + 0.0078125;
  let saturation = f32((packed >> 7u) & 7u) / 8.0 + 0.0625;
  let luminance = f32(packed & 127u) / 128.0;
  let q = select(luminance + saturation - luminance * saturation,
                 luminance * (1.0 + saturation), luminance < 0.5);
  let p = 2.0 * luminance - q;
  let srgb = vec3(hueChannel(p, q, hue + 1.0 / 3.0),
                  hueChannel(p, q, hue),
                  hueChannel(p, q, hue - 1.0 / 3.0));
  let low = srgb / 12.92;
  let high = pow((srgb + vec3(0.055)) / 1.055, vec3(2.4));
  return select(low, high, srgb > vec3(0.04045));
}

struct SurfaceSample {
  color: vec4<f32>,
  material: vec4<f32>,
  atlasUv: vec2<f32>,
  layer: i32,
  flags: u32,
  textured: u32,
  opacity: f32,
};

fn sampleSurface(
    textureId: i32,
    uv: vec2<f32>,
    vertexColor: vec3<f32>,
    hdTexturesEnabled: bool) -> SurfaceSample {
  var output = SurfaceSample(
    vec4(vertexColor, 1.0),
    vec4(1.0, 0.82, 0.0, 0.0),
    vec2(0.0), 0, 0u, 0u, 1.0);
  if (textureId >= 0 && f32(textureId) < scene.atlas.w) {
    output.atlasUv = uv;
    output.layer = textureId;
    let hdTexel = textureSample(textureAtlas, textureSampler, uv, textureId);
    let vanillaTexel = textureSample(vanillaTextureAtlas, textureSampler, uv, textureId);
    let texel = select(vanillaTexel, hdTexel, hdTexturesEnabled);
    if (hdTexturesEnabled) {
      output.material = materialParameters[u32(textureId)];
      output.flags = u32(output.material.w + 0.5);
    }
    let overrideBaseColor = hdTexturesEnabled && (output.flags & 1u) != 0u;
    let materialColor = select(texel.rgb * vertexColor, texel.rgb, overrideBaseColor);
    output.color = vec4(materialColor * output.material.x, 1.0);
    output.textured = 1u;
    // 117's diffuse PNGs are not all cutout textures.  Some opaque building
    // materials contain unused/cleared alpha texels; applying that channel to
    // every material punched view-dependent holes through roofs and walls.
    // Bit 3 is inherited from materials.json hasTransparency.  Vanilla mode
    // continues to honour its texture-provider alpha exactly.
    let hdCutoutMaterial = (output.flags & 8u) != 0u;
    output.opacity = select(texel.a, 1.0,
      hdTexturesEnabled && !hdCutoutMaterial);
  }
  return output;
}

fn sampleSurfaceNormal(surface: SurfaceSample) -> vec3<f32> {
  if (surface.textured == 0u)
    { return vec3(0.0, 0.0, 1.0); }
  return textureSample(normalAtlas, textureSampler, surface.atlasUv, surface.layer).xyz * 2.0 - 1.0;
}

fn decodeModelNormal(sourceFlags: u32) -> vec3<f32> {
  let encoded = vec2(
    f32((sourceFlags >> 16u) & 0xffu),
    f32((sourceFlags >> 24u) & 0xffu)) / 255.0 * 2.0 - 1.0;
  var normal = vec3(encoded, 1.0 - abs(encoded.x) - abs(encoded.y));
  let fold = clamp(-normal.z, 0.0, 1.0);
  normal.x += select(fold, -fold, normal.x >= 0.0);
  normal.y += select(fold, -fold, normal.y >= 0.0);
  return normalize(normal);
}

fn waveSlope(position: vec2<f32>, direction: vec2<f32>, frequency: f32,
             speed: f32, amplitude: f32, pixelFootprint: f32) -> vec2<f32> {
  let axis = normalize(direction);
  let phase = dot(position, axis) * frequency + scene.frame.x * speed;
  // Attenuate waves before their phase advances by pi radians per pixel.
  // Unfiltered small waves flicker into sharp specular stripes when zoomed
  // out, especially at the lower internal resolutions used by DLSS.
  let resolved = 1.0 - smoothstep(0.75, 3.14159265, frequency * pixelFootprint);
  return axis * cos(phase) * amplitude * resolved;
}

fn waterHash(position: vec2<f32>) -> f32 {
  return fract(sin(dot(position, vec2(127.1, 311.7))) * 43758.5453123);
}

fn waterNoise(position: vec2<f32>) -> f32 {
  let cell = floor(position);
  let local = fract(position);
  let blend = local * local * (3.0 - 2.0 * local);
  let a = waterHash(cell);
  let b = waterHash(cell + vec2(1.0, 0.0));
  let c = waterHash(cell + vec2(0.0, 1.0));
  let d = waterHash(cell + vec2(1.0, 1.0));
  return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

fn waterRainRipple(position: vec2<f32>) -> vec2<f32> {
  let cellSize = 18.0;
  let cell = floor(position / cellSize);
  let local = fract(position / cellSize) - vec2(0.5);
  let random = fract(sin(dot(cell, vec2(12.9898, 78.233))) * 43758.5453);
  let age = fract(scene.frame.x * (0.55 + scene.environment.z * 0.35) + random);
  let radius = length(local) * 2.0;
  let ring = exp(-abs(radius - age) * 34.0) * (1.0 - age);
  return normalize(local + vec2(1e-4)) * ring * scene.water.z;
}

fn isWaterSurface(textureId: i32, flags: u32) -> bool {
  let terrain = (flags & 2u) != 0u;
  let blended = (flags & 0x80000000u) != 0u && textureId != -2;
  return terrain && (textureId == -2 ||
    (!blended && (flags & 0x40000000u) != 0u));
}

@vertex fn vs_main(input: VertexInput) -> VertexOutput {
  var output: VertexOutput;
  let worldPosition = vec4(input.position, 1.0);
  output.position = scene.viewProjection * worldPosition;
  let hdTexturedModel = scene.features.x > 0.5 && (input.sourceFlags & 1u) != 0u;
  let hdPackedHsl = select(input.packedHsl, 90u, hdTexturedModel);
  // Textured vanilla water stores brightness in its vertex colors. The water
  // sentinel replaces that texture, so its authored tint is needed even when
  // HD textures are off; otherwise brightness becomes pale grey/pink water.
  let useAuthoredColor = scene.features.x > 0.5 ||
    isWaterSurface(input.textureId, input.sourceFlags);
  output.color = hslToRgb(select(input.vanillaPackedHsl, hdPackedHsl, useAuthoredColor));
  output.uv = input.uv;
  output.textureId = input.textureId;
  output.worldPosition = input.position;
  output.currentClip = output.position;
  output.previousClip = scene.previousViewProjection * vec4(input.previousPosition, 1.0);
  output.sourceFlags = input.sourceFlags;
  let triangleCorner = input.vertexIndex % 3u;
  output.terrainBlend = vec3(
    select(0.0, 1.0, triangleCorner == 0u),
    select(0.0, 1.0, triangleCorner == 1u),
    select(0.0, 1.0, triangleCorner == 2u));
  output.smoothNormal = decodeModelNormal(input.sourceFlags);
  let fadeStart = u32(max(scene.frame.z, 0.0));
  let fadeEnd = u32(max(scene.camera.w, 0.0));
  let inNewSectorRange = input.vertexIndex >= fadeStart && input.vertexIndex < fadeEnd;
  output.sectorFade = select(1.0, clamp(scene.frame.w, 0.0, 1.0), inNewSectorRange);
  return output;
}

@fragment fn fs_main(input: VertexOutput) -> FragmentOutput {
  let hdTexturesEnabled = scene.features.x > 0.5;
  var surfaceA = sampleSurface(input.textureId, input.uv, input.color, hdTexturesEnabled);
  var surfaceB = surfaceA;
  var surfaceC = surfaceA;
  var blendWeights = vec3(1.0, 0.0, 0.0);
  let terrainSurface = (input.sourceFlags & 2u) != 0u;
  let terrainBlendActive =
    terrainSurface && (input.sourceFlags & 0x80000000u) != 0u
      && input.textureId != -2;
  let underwaterFloor = terrainSurface && !terrainBlendActive
    && (input.sourceFlags & 0x20000000u) != 0u;
  // Extra ground-atlas cells have no vanilla cache texture. Preserve the
  // authored seabed HSL when HD textures are disabled instead of sampling the
  // atlas' white sentinel through translucent water.
  if (underwaterFloor && !hdTexturesEnabled) {
    surfaceA = sampleSurface(-1, input.uv, input.color, false);
    surfaceB = surfaceA;
    surfaceC = surfaceA;
  }
  if (terrainBlendActive) {
    let materialBId = i32((input.sourceFlags >> 5u) & 0x1fffu) - 2;
    let materialCId = i32((input.sourceFlags >> 18u) & 0x1fffu) - 2;
    surfaceB = sampleSurface(materialBId, input.uv, input.color, hdTexturesEnabled);
    surfaceC = sampleSurface(materialCId, input.uv, input.color, hdTexturesEnabled);
    blendWeights = max(input.terrainBlend, vec3(0.0));
    blendWeights /= max(dot(blendWeights, vec3(1.0)), 1e-6);
  }

  var opacity = surfaceA.opacity * blendWeights.x
    + surfaceB.opacity * blendWeights.y
    + surfaceC.opacity * blendWeights.z;
  opacity *= input.sectorFade;
  if (!terrainSurface) {
    let faceTransparency = f32((input.sourceFlags >> 8u) & 0xffu) / 255.0;
    opacity *= 1.0 - faceTransparency;
  }
  // Preserve translucent RuneLite effects without requiring an incorrectly
  // ordered transparent depth pass. A stable 4x4 coverage mask gives fishing
  // splashes, smoke and spell billboards their intended fractional coverage
  // while fully transparent faces disappear instead of becoming black quads.
  let coverageX = u32(input.position.x) & 3u;
  let coverageY = u32(input.position.y) & 3u;
  let bayer = array<f32, 16>(
    0.0, 0.5, 0.125, 0.625,
    0.75, 0.25, 0.875, 0.375,
    0.1875, 0.6875, 0.0625, 0.5625,
    0.9375, 0.4375, 0.8125, 0.3125);
  if (opacity <= bayer[coverageY * 4u + coverageX]) { discard; }
  var baseColor = surfaceA.color * blendWeights.x
    + surfaceB.color * blendWeights.y
    + surfaceC.color * blendWeights.z;
  var material = surfaceA.material * blendWeights.x
    + surfaceB.material * blendWeights.y
    + surfaceC.material * blendWeights.z;
  let unlitWeight =
    select(0.0, blendWeights.x, (surfaceA.flags & 2u) != 0u)
    + select(0.0, blendWeights.y, (surfaceB.flags & 2u) != 0u)
    + select(0.0, blendWeights.z, (surfaceC.flags & 2u) != 0u);
  // Reflection eligibility must come from the tile classification, not the
  // material name.  The 117 atlas also contains model/effect materials such as
  // WATER_DROPLETS; treating every WATER_* material as a world water surface
  // made those model triangles write a reflection mask and produced large,
  // view-dependent coloured triangles over dry land.  Java deliberately
  // clears the blend-candidate bit only for terrain faces classified as water.
  let waterSurface = isWaterSurface(input.textureId, input.sourceFlags);
  if (input.textureId == -2 && terrainSurface) {
    // Java uses this sentinel for every classified water face, including
    // regional overrides. Keep its authored color: a second animated diffuse
    // sine pattern produced cyan bands independent of the actual waves.
    material = vec4(1.0, 0.07, 4.0, 4.0);
  }
  let dx = dpdx(input.worldPosition);
  let dy = dpdy(input.worldPosition);
  var normal = normalize(cross(dx, dy));
  // Keep all lighting/G-buffer normals in RuneLite world space (up is -Y).
  // Forcing them toward +Y lit the opposite faces to the RTX sun rays, so
  // the shadow pass removed sunlight from surfaces rasterization left dark.
  let toCamera = scene.camera.xyz - input.worldPosition;
  if (dot(normal, toCamera) < 0.0) { normal = -normal; }
  let geometricNormal = normal;
  // Model bit 2 marks faces with an authored vertex-normal stream.
  // Interpolating the client's
  // animation-aware vertex normals removes the low-poly faceting while the
  // dot correction keeps their orientation consistent with this raster pass.
  // Terrain, flat-shaded faces and models without valid normals stay exact.
  if (!terrainSurface && (input.sourceFlags & 4u) != 0u
      && dot(input.smoothNormal, input.smoothNormal) > 1e-5) {
    var authoredNormal = normalize(input.smoothNormal);
    if (dot(authoredNormal, normal) < 0.0) { authoredNormal = -authoredNormal; }
    let requestedSmoothing = clamp(scene.features.w, 0.0, 1.0);
    // A perceptual response makes the middle of the UI slider visibly useful
    // while retaining exact off/on endpoints.
    let perceptualSmoothing = 1.0 -
      (1.0 - requestedSmoothing) * (1.0 - requestedSmoothing);
    normal = normalize(mix(normal, authoredNormal, perceptualSmoothing));
  }
  let hasTexture = surfaceA.textured != 0u
    || surfaceB.textured != 0u || surfaceC.textured != 0u;
  if (hasTexture && hdTexturesEnabled) {
    let duvdx = dpdx(input.uv);
    let duvdy = dpdy(input.uv);
    let determinant = duvdx.x * duvdy.y - duvdx.y * duvdy.x;
    if (abs(determinant) > 1e-6) {
      let rawTangent = (dx * duvdy.y - dy * duvdx.y) / determinant;
      let rawBitangent = (-dx * duvdy.x + dy * duvdx.x) / determinant;
      let projectedTangent = rawTangent - normal * dot(normal, rawTangent);
      let tangent = normalize(select(cross(normal, rawBitangent), projectedTangent,
        dot(projectedTangent, projectedTangent) > 1e-10));
      let handedness = select(-1.0, 1.0, dot(cross(normal, tangent), rawBitangent) >= 0.0);
      let bitangent = cross(normal, tangent) * handedness;
      var mapped = sampleSurfaceNormal(surfaceA);
      if (terrainBlendActive) {
        mapped = normalize(
          mapped * blendWeights.x
          + sampleSurfaceNormal(surfaceB) * blendWeights.y
          + sampleSurfaceNormal(surfaceC) * blendWeights.z);
      }
      normal = normalize(
        tangent * mapped.x * scene.visual.x +
        bitangent * mapped.y * scene.visual.x + normal * mapped.z);
      if (dot(normal, geometricNormal) < 0.0) { normal = -normal; }
    }
  }
  var hdrOpacity = 1.0;
  if (waterSurface) {
    let waterPosition = input.worldPosition.xz;
    let waveStrength = scene.water.x;
    let waterQuality = scene.environment.w;
    let wind = scene.environment.z;
    let waterPixelFootprint = max(length(dx.xz), length(dy.xz));
    // Slowly advected domain warping breaks up the obvious repeating sine
    // bands while remaining deterministic in world space and temporally
    // coherent for DLSS motion history.
    let coarseWarp = waterNoise(
      waterPosition * 0.0062 + vec2(scene.frame.x * 0.017, -scene.frame.x * 0.011));
    let crossWarp = waterNoise(
      waterPosition * 0.0137 + vec2(-scene.frame.x * 0.023, scene.frame.x * 0.019));
    let warpedWaterPosition = waterPosition
      + (vec2(coarseWarp, crossWarp) - vec2(0.5)) * (10.0 + wind * 7.0);
    let amplitudeVariation = 0.76 + 0.42 * waterNoise(
      waterPosition * 0.0031 + vec2(scene.frame.x * 0.006, scene.frame.x * 0.004));
    var slope = waveSlope(
      warpedWaterPosition, vec2(0.86, 0.51), 0.031, 0.72 + wind * 0.64,
      0.095 * waveStrength * amplitudeVariation, waterPixelFootprint);
    slope += waveSlope(
      warpedWaterPosition, vec2(-0.38, 0.92), 0.047, -0.51 - wind * 0.33,
      0.068 * waveStrength * amplitudeVariation, waterPixelFootprint);
    if (waterQuality > 0.5) {
      slope += waveSlope(
        warpedWaterPosition, vec2(0.24, -0.97), 0.091, 1.36 + wind * 0.82,
        0.038 * waveStrength, waterPixelFootprint);
      slope += waveSlope(
        warpedWaterPosition, vec2(-0.94, -0.34), 0.137, -1.88,
        0.021 * waveStrength, waterPixelFootprint);
    }
    if (waterQuality > 1.5) {
      slope += waveSlope(
        warpedWaterPosition, vec2(0.69, -0.72), 0.245, 2.42 + wind,
        0.012 * waveStrength, waterPixelFootprint);
      let rippleResolution = 1.0 - smoothstep(0.5, 2.0, waterPixelFootprint);
      slope += waterRainRipple(waterPosition) * 0.055
        * waveStrength * rippleResolution;
    }
    // Scene heights increase downward; world-up and sunlight use negative Y.
    let generatedWaterNormal = normalize(vec3(-slope.x, -1.0, -slope.y));
    // Do not retain the flat derivative normal of the underlying RuneScape
    // terrain triangle here. Even a small contribution from that faceted
    // normal sends adjacent low-roughness reflection rays toward alternating
    // sky/scene hits, exposing the tile triangulation as bright cyan wedges.
    // Wave strength is already encoded in slope, so the generated normal
    // naturally becomes a flat world-up water plane at zero strength.
    normal = generatedWaterNormal;

    let regionalColor = baseColor.rgb;
    let regionalLuminance = dot(regionalColor, vec3(0.2126, 0.7152, 0.0722));
    // Regional water tiles retain useful shallow/deep variation. Use that
    // signal as a tint hint; it is not a geometric shoreline depth. Darken
    // the authored hue instead of mixing blue into swamp/blood water.
    let shallowHint = smoothstep(0.10, 0.48, regionalLuminance);
    let deepTint = regionalColor * mix(0.32, 0.62, shallowHint);
    let clarity = scene.water.y;
    let transmitted = mix(deepTint, regionalColor, 0.12 + clarity * 0.55);
    let viewDirection = normalize(scene.camera.xyz - input.worldPosition);
    let fresnel = 0.02 + 0.98 * pow(
      1.0 - clamp(abs(dot(normal, viewDirection)), 0.0, 1.0), 5.0);
    let daySky = mix(vec3(0.015, 0.028, 0.070),
                     vec3(0.18, 0.43, 0.66), scene.features.z);
    let reflectedSky = daySky * scene.water.w;
    let fresnelWeight = clamp(fresnel * (0.64 + waterQuality * 0.10), 0.0, 0.92);
    baseColor = vec4(mix(transmitted, reflectedSky, fresnelWeight), 1.0);
    // Opaque seabed geometry completes before any water faces are drawn.
    // Blend the lit water layer over it so clarity exposes the
    // seabed, while grazing-angle Fresnel remains appropriately reflective.
    hdrOpacity = clamp(
      0.40 + (1.0 - clarity) * 0.30 + fresnelWeight * 0.32,
      0.40, 0.94);
    let waterRoughness = clamp(
      0.16 - waterQuality * 0.045 + scene.water.z * 0.09,
      0.045, 0.28);
    material = vec4(
      max(material.x, 1.0), waterRoughness,
      max(material.z, 4.0 + waterQuality * 1.5), material.w);
  }
  let currentNdc = input.currentClip.xy / max(abs(input.currentClip.w), 1e-6);
  let previousNdc = input.previousClip.xy / max(abs(input.previousClip.w), 1e-6);
  // Streamline consumes current-to-previous screen-space motion. The Y scale
  // converts clip-space-up to texture-space-down; jitter is intentionally
  // retained and advertised through motionVectorsJittered.
  let motion = (previousNdc - currentNdc) * vec2(0.5, -0.5);
  var output: FragmentOutput;
  let sunDirection = normalize(scene.lighting.yzw);
  let weatherAmount = scene.environment.y * step(0.5, scene.environment.x);
  let ambientFloor = scene.lighting.x * mix(1.0, 0.76, weatherAmount);
  let directLight = ambientFloor +
    (1.0 - ambientFloor) * 1.25 * scene.features.z * scene.water.w *
      max(dot(normal, sunDirection), 0.0);
  let unlit = unlitWeight >= 0.5;
  output.hdrColor = vec4(
    baseColor.rgb * select(directLight, 1.0, unlit) * scene.visual.w,
    hdrOpacity);
  output.motion = motion;
  output.normalRoughness = vec4(normal, clamp(material.y, 0.04, 1.0));
  // Alpha records the fraction of this pixel lit by sunlight. RTX can remove
  // that contribution exactly without re-estimating the mapped/smooth normal.
  output.diffuseAlbedo = vec4(baseColor.rgb,
    select(clamp((directLight - ambientFloor) / max(directLight, 1e-5), 0.0, 1.0), 0.0, unlit));
  // Alpha is the reflection eligibility mask consumed by the ray-query pass.
  // Keep the first live RTX reflection stage water-only. The scene buffer
  // classifies water on terrain faces; glossy roofs, paths, model effects and
  // metals must not opt in merely because of their material name or strength.
  let reflectionMask = select(0.0, 1.0, waterSurface);
  // The ray pass interprets RGB as Schlick reflectance at normal incidence.
  // Artist specular strengths of 4-7 are not dielectric F0: converting them
  // as 0.04 * strength made water reflect 16-28% even directly from above.
  let specularF0 = select(clamp(0.04 * material.z, 0.0, 1.0), 0.02, waterSurface);
  output.specularAlbedo = vec4(
    vec3(specularF0), reflectionMask);
  return output;
}
)WGSL";

    WGPUShaderSourceWGSL wgsl{};
    wgsl.chain.sType = WGPUSType_ShaderSourceWGSL;
    wgsl.code = stringView(shaderSource);
    WGPUShaderModuleDescriptor shaderDescriptor{};
    shaderDescriptor.nextInChain = &wgsl.chain;
    shaderDescriptor.label = stringView("RuneLite WebGPU scene shader");
    g.sceneShader = wgpuDeviceCreateShaderModule(g.device, &shaderDescriptor);
    if (!g.sceneShader) return false;

    WGPUVertexAttribute attributes[7]{};
    attributes[0].format = WGPUVertexFormat_Float32x3;
    attributes[0].offset = 0;
    attributes[0].shaderLocation = 0;
    attributes[1].format = WGPUVertexFormat_Float32x3;
    attributes[1].offset = 12;
    attributes[1].shaderLocation = 1;
    attributes[2].format = WGPUVertexFormat_Uint32;
    attributes[2].offset = 24;
    attributes[2].shaderLocation = 2;
    attributes[3].format = WGPUVertexFormat_Float32x2;
    attributes[3].offset = 28;
    attributes[3].shaderLocation = 3;
    attributes[4].format = WGPUVertexFormat_Sint32;
    attributes[4].offset = 36;
    attributes[4].shaderLocation = 4;
    attributes[5].format = WGPUVertexFormat_Uint32;
    attributes[5].offset = 40;
    attributes[5].shaderLocation = 5;
    attributes[6].format = WGPUVertexFormat_Uint32;
    attributes[6].offset = 44;
    attributes[6].shaderLocation = 6;
    WGPUVertexBufferLayout vertexLayout{};
    vertexLayout.arrayStride = 48;
    vertexLayout.stepMode = WGPUVertexStepMode_Vertex;
    vertexLayout.attributeCount = 7;
    vertexLayout.attributes = attributes;

    WGPUColorTargetState colorTargets[5]{};
    colorTargets[0].format = WGPUTextureFormat_RGBA16Float;
    colorTargets[1].format = WGPUTextureFormat_RG16Float;
    colorTargets[2].format = WGPUTextureFormat_RGBA16Float;
    colorTargets[3].format = WGPUTextureFormat_RGBA8Unorm;
    colorTargets[4].format = WGPUTextureFormat_RGBA8Unorm;
    for (auto& target : colorTargets) target.writeMask = WGPUColorWriteMask_All;
    WGPUBlendState hdrBlend{};
    hdrBlend.color.operation = WGPUBlendOperation_Add;
    hdrBlend.color.srcFactor = WGPUBlendFactor_SrcAlpha;
    hdrBlend.color.dstFactor = WGPUBlendFactor_OneMinusSrcAlpha;
    hdrBlend.alpha.operation = WGPUBlendOperation_Add;
    hdrBlend.alpha.srcFactor = WGPUBlendFactor_One;
    hdrBlend.alpha.dstFactor = WGPUBlendFactor_OneMinusSrcAlpha;
    colorTargets[0].blend = &hdrBlend;
    WGPUFragmentState fragment{};
    fragment.module = g.sceneShader;
    fragment.entryPoint = stringView("fs_main");
    fragment.targetCount = 5;
    fragment.targets = colorTargets;
    WGPUDepthStencilState depth{};
    depth.format = WGPUTextureFormat_Depth32Float;
    depth.depthWriteEnabled = WGPUOptionalBool_True;
    depth.depthCompare = WGPUCompareFunction_Greater;
    depth.stencilReadMask = 0xffffffffu;
    depth.stencilWriteMask = 0xffffffffu;
    WGPURenderPipelineDescriptor pipeline{};
    pipeline.label = stringView("RuneLite WebGPU scene pipeline");
    pipeline.vertex.module = g.sceneShader;
    pipeline.vertex.entryPoint = stringView("vs_main");
    pipeline.vertex.bufferCount = 1;
    pipeline.vertex.buffers = &vertexLayout;
    pipeline.primitive.topology = WGPUPrimitiveTopology_TriangleList;
    pipeline.primitive.frontFace = WGPUFrontFace_CCW;
    pipeline.primitive.cullMode = WGPUCullMode_None;
    pipeline.depthStencil = &depth;
    pipeline.multisample.count = 1;
    pipeline.multisample.mask = 0xffffffffu;
    pipeline.fragment = &fragment;
    g.scenePipeline = wgpuDeviceCreateRenderPipeline(g.device, &pipeline);
    if (!g.scenePipeline) return false;

    WGPUBufferDescriptor uniformDescriptor{};
    uniformDescriptor.label = stringView("RuneLite WebGPU scene uniforms");
    uniformDescriptor.size = sizeof(float) * 64;
    uniformDescriptor.usage = WGPUBufferUsage_Uniform | WGPUBufferUsage_CopyDst;
    g.sceneUniformBuffer = wgpuDeviceCreateBuffer(g.device, &uniformDescriptor);
    if (!g.sceneUniformBuffer) return false;

    const uint32_t white = 0xffffffffu;
    const uint32_t neutralNormal = 0xffff8080u;
    const float defaultMaterial[4]{1.0f, 0.82f, 0.0f, 0.0f};
    if (!createSceneTextureAtlas(
            &white, &white, &neutralNormal, defaultMaterial, 1, 1, 1, 1)) return false;
    return g.sceneBindGroup && createFinalPipeline() && createDepthResources();
}

bool ensureSceneVertexCapacity(uint64_t requiredBytes) {
    if (g.sceneVertexBuffer && g.sceneVertexCapacity >= requiredBytes) return true;
    const uint64_t previousCapacity = g.sceneVertexCapacity;
    if (g.sceneVertexBuffer) wgpuBufferRelease(g.sceneVertexBuffer);
    g.sceneVertexBuffer = nullptr;
    // Static sectors arrive incrementally. Exact-size allocation would replace
    // the GPU buffer and force a full-prefix upload for nearly every sector.
    // Start at 64 MiB, then grow geometrically; the 72-tile maximum normally
    // needs no more than the 128/256 MiB steps.
    constexpr uint64_t kInitialSceneVertexCapacity = 64ull * 1024ull * 1024ull;
    g.sceneVertexCapacity = std::max(
        requiredBytes,
        std::max(kInitialSceneVertexCapacity,
                 previousCapacity > 0u ? previousCapacity * 2u : 0u));
    WGPUBufferDescriptor descriptor{};
    descriptor.label = stringView("RuneLite WebGPU scene vertices");
    descriptor.size = g.sceneVertexCapacity;
    descriptor.usage = WGPUBufferUsage_Vertex | WGPUBufferUsage_CopyDst;
    g.sceneVertexBuffer = wgpuDeviceCreateBuffer(g.device, &descriptor);
    // A replacement GPU buffer has no copy of the retained static prefix.
    g.uploadedStaticRevision = UINT64_MAX;
    g.uploadedStaticVertexCount = 0;
    return g.sceneVertexBuffer != nullptr;
}

bool prepareSceneRange(PreparedSceneRange& range, const PackedSceneVertex* vertices,
                       uint32_t firstVertex, uint32_t vertexCount) {
    range.firstVertex = firstVertex;
    range.vertexCount = vertexCount - vertexCount % 3u;
    range.opaqueCount = rlw::partitionWaterTriangles(
        vertices, firstVertex, range.vertexCount, g.drawIndexScratch);
    if (g.drawIndexScratch.empty()) return true;
    const uint64_t bytes = g.drawIndexScratch.size() * sizeof(uint32_t);
    if (!range.indexBuffer || range.indexCapacity < bytes) {
        if (range.indexBuffer) wgpuBufferRelease(range.indexBuffer);
        range.indexBuffer = nullptr;
        range.indexCapacity = std::max(bytes, range.indexCapacity * 2u);
        WGPUBufferDescriptor descriptor{};
        descriptor.label = stringView("RuneLite opaque/water draw indices");
        descriptor.size = range.indexCapacity;
        descriptor.usage = WGPUBufferUsage_Index | WGPUBufferUsage_CopyDst;
        range.indexBuffer = wgpuDeviceCreateBuffer(g.device, &descriptor);
        if (!range.indexBuffer) return false;
    }
    wgpuQueueWriteBuffer(g.queue, range.indexBuffer, 0u,
                        g.drawIndexScratch.data(), bytes);
    return true;
}

void drawSceneRange(WGPURenderPassEncoder pass, const PreparedSceneRange& range,
                    bool water) {
    const uint32_t count = water ? range.vertexCount - range.opaqueCount
                                 : range.opaqueCount;
    if (count == 0u) return;
    if (range.opaqueCount == 0u || range.opaqueCount == range.vertexCount) {
        wgpuRenderPassEncoderDraw(pass, count, 1u, range.firstVertex, 0u);
    } else {
        wgpuRenderPassEncoderSetIndexBuffer(pass, range.indexBuffer,
            WGPUIndexFormat_Uint32, 0u,
            static_cast<uint64_t>(range.vertexCount) * sizeof(uint32_t));
        wgpuRenderPassEncoderDrawIndexed(pass, count, 1u,
            water ? range.opaqueCount : 0u, 0, 0u);
    }
}

bool uploadInterfacePixels(const uint32_t* pixels, uint32_t width, uint32_t height) {
    if (!pixels || width == 0 || height == 0) return false;
    if (!g.uiPipeline || g.uiWidth != width || g.uiHeight != height)
        if (!createUiResources(width, height)) return false;

    const uint32_t sourceRowBytes = width * sizeof(uint32_t);
    const uint32_t uploadRowBytes = (sourceRowBytes + 255u) & ~255u;
    const auto* sourceBytes = reinterpret_cast<const uint8_t*>(pixels);
    const uint8_t* uploadBytes = sourceBytes;
    size_t uploadSize = static_cast<size_t>(sourceRowBytes) * height;
    if (uploadRowBytes != sourceRowBytes) {
        const size_t requiredBytes = static_cast<size_t>(uploadRowBytes) * height;
        if (g.uploadPixels.size() != requiredBytes) g.uploadPixels.resize(requiredBytes);
        for (uint32_t row = 0; row < height; ++row)
            std::memcpy(g.uploadPixels.data() + static_cast<size_t>(row) * uploadRowBytes,
                        sourceBytes + static_cast<size_t>(row) * sourceRowBytes, sourceRowBytes);
        uploadBytes = g.uploadPixels.data();
        uploadSize = requiredBytes;
    }
    WGPUTexelCopyTextureInfo destination{};
    destination.texture = g.uiTexture;
    WGPUTexelCopyBufferLayout layout{};
    layout.bytesPerRow = uploadRowBytes;
    layout.rowsPerImage = height;
    WGPUExtent3D extent{width, height, 1};
    wgpuQueueWriteTexture(g.queue, &destination, uploadBytes, uploadSize, &layout, &extent);
    return true;
}

bool invertMatrix(const float* input, float* output) {
    double augmented[4][8]{};
    for (int row = 0; row < 4; ++row) {
        for (int column = 0; column < 4; ++column)
            augmented[row][column] = input[column * 4 + row];
        augmented[row][4 + row] = 1.0;
    }
    for (int column = 0; column < 4; ++column) {
        int pivot = column;
        for (int row = column + 1; row < 4; ++row)
            if (std::abs(augmented[row][column]) > std::abs(augmented[pivot][column])) pivot = row;
        if (std::abs(augmented[pivot][column]) < 1e-12) return false;
        if (pivot != column)
            for (int element = 0; element < 8; ++element)
                std::swap(augmented[pivot][element], augmented[column][element]);
        const double divisor = augmented[column][column];
        for (int element = 0; element < 8; ++element) augmented[column][element] /= divisor;
        for (int row = 0; row < 4; ++row) {
            if (row == column) continue;
            const double factor = augmented[row][column];
            for (int element = 0; element < 8; ++element)
                augmented[row][element] -= factor * augmented[column][element];
        }
    }
    for (int row = 0; row < 4; ++row)
        for (int column = 0; column < 4; ++column)
            output[column * 4 + row] = static_cast<float>(augmented[row][4 + column]);
    return true;
}

void multiplyMatrix(const float* left, const float* right, float* output) {
    float result[16]{};
    for (int column = 0; column < 4; ++column)
        for (int row = 0; row < 4; ++row)
            for (int component = 0; component < 4; ++component)
                result[column * 4 + row] +=
                    left[component * 4 + row] * right[column * 4 + component];
    std::memcpy(output, result, sizeof(result));
}

void copyRowMajor(std::array<float, 16>& output, const float* columnMajor) {
    for (int row = 0; row < 4; ++row)
        for (int column = 0; column < 4; ++column)
            output[row * 4 + column] = columnMajor[column * 4 + row];
}

struct TemporalJitter {
    std::array<float, 2> pixels{};
    float ndcX{};
    float ndcY{};
    bool enabled{};
};

struct RenderViewport {
    uint32_t left{};
    uint32_t top{};
    uint32_t width{1};
    uint32_t height{1};
};

RenderViewport scaleViewportToTarget(
    int viewportX, int viewportY, int viewportWidth, int viewportHeight,
    int interfaceWidth, int interfaceHeight,
    uint32_t targetWidth, uint32_t targetHeight) {
    const float scaleX = static_cast<float>(std::max(1u, targetWidth)) /
        static_cast<float>(std::max(1, interfaceWidth));
    const float scaleY = static_cast<float>(std::max(1u, targetHeight)) /
        static_cast<float>(std::max(1, interfaceHeight));
    const float rawLeft = static_cast<float>(viewportX) * scaleX;
    const float rawTop = static_cast<float>(viewportY) * scaleY;
    const float rawRight = static_cast<float>(viewportX + viewportWidth) * scaleX;
    const float rawBottom = static_cast<float>(viewportY + viewportHeight) * scaleY;
    const uint32_t left = static_cast<uint32_t>(std::clamp(
        std::floor(rawLeft), 0.0f, static_cast<float>(std::max(1u, targetWidth) - 1u)));
    const uint32_t top = static_cast<uint32_t>(std::clamp(
        std::floor(rawTop), 0.0f, static_cast<float>(std::max(1u, targetHeight) - 1u)));
    const uint32_t right = static_cast<uint32_t>(std::clamp(
        std::ceil(rawRight), static_cast<float>(left + 1u),
        static_cast<float>(std::max(1u, targetWidth))));
    const uint32_t bottom = static_cast<uint32_t>(std::clamp(
        std::ceil(rawBottom), static_cast<float>(top + 1u),
        static_cast<float>(std::max(1u, targetHeight))));
    return {left, top, right - left, bottom - top};
}

RenderViewport scaleViewportBetweenTargets(
    const RenderViewport& source, uint32_t sourceWidth, uint32_t sourceHeight,
    uint32_t targetWidth, uint32_t targetHeight) {
    const double scaleX = static_cast<double>(std::max(1u, targetWidth)) /
                          static_cast<double>(std::max(1u, sourceWidth));
    const double scaleY = static_cast<double>(std::max(1u, targetHeight)) /
                          static_cast<double>(std::max(1u, sourceHeight));
    const uint32_t left = static_cast<uint32_t>(std::clamp(
        std::floor(static_cast<double>(source.left) * scaleX), 0.0,
        static_cast<double>(std::max(1u, targetWidth) - 1u)));
    const uint32_t top = static_cast<uint32_t>(std::clamp(
        std::floor(static_cast<double>(source.top) * scaleY), 0.0,
        static_cast<double>(std::max(1u, targetHeight) - 1u)));
    const uint32_t right = static_cast<uint32_t>(std::clamp(
        std::ceil(static_cast<double>(source.left + source.width) * scaleX),
        static_cast<double>(left + 1u),
        static_cast<double>(std::max(1u, targetWidth))));
    const uint32_t bottom = static_cast<uint32_t>(std::clamp(
        std::ceil(static_cast<double>(source.top + source.height) * scaleY),
        static_cast<double>(top + 1u),
        static_cast<double>(std::max(1u, targetHeight))));
    return {left, top, right - left, bottom - top};
}

float radicalInverse(uint64_t index, uint32_t base) {
    const float inverseBase = 1.0f / static_cast<float>(base);
    float inversePower = inverseBase;
    float value = 0.0f;
    while (index > 0) {
        value += static_cast<float>(index % base) * inversePower;
        index /= base;
        inversePower *= inverseBase;
    }
    return value;
}

TemporalJitter makeTemporalJitter(uint64_t sequenceIndex,
                                  float rasterWidth, float rasterHeight,
                                  uint32_t renderWidth, uint32_t renderHeight,
                                  uint32_t outputWidth, uint32_t outputHeight) {
    // NVIDIA's NRI integration guidance derives the Halton phase count from
    // the configured upscale ratio. This gives DLAA 8 phases, Performance 32,
    // and enough coverage for the more aggressive modes without an unbounded
    // sequence on malformed dimensions.
    const double scaleX = static_cast<double>(std::max(1u, outputWidth)) /
                          static_cast<double>(std::max(1u, renderWidth));
    const double scaleY = static_cast<double>(std::max(1u, outputHeight)) /
                          static_cast<double>(std::max(1u, renderHeight));
    const double scale = std::max(scaleX, scaleY);
    const uint32_t phaseCount = std::clamp(
        static_cast<uint32_t>(std::ceil(8.0 * scale * scale)), 8u, 128u);
    const uint64_t sample = sequenceIndex % phaseCount + 1u;
    TemporalJitter jitter{};
    jitter.pixels = {
        radicalInverse(sample, 2u) - 0.5f,
        radicalInverse(sample, 3u) - 0.5f};
    jitter.ndcX = 2.0f * jitter.pixels[0] / std::max(1.0f, rasterWidth);
    // Pixel coordinates grow downward while clip-space Y grows upward.
    jitter.ndcY = -2.0f * jitter.pixels[1] / std::max(1.0f, rasterHeight);
    jitter.enabled = true;
    return jitter;
}

void applyClipSpaceJitter(const float* source, const TemporalJitter& jitter,
                          float* output) {
    // Premultiply by a clip-space translation. For a column-major matrix this
    // adds jitter * clip.w to the X/Y rows and works for both P and P*V.
    std::memcpy(output, source, sizeof(float) * 16u);
    for (int column = 0; column < 4; ++column) {
        const int element = column * 4;
        output[element] += jitter.ndcX * source[element + 3];
        output[element + 1] += jitter.ndcY * source[element + 3];
    }
}

bool invertViewProjectionForRasterViewport(
    const float* viewProjection,
    int viewportX, int viewportY, int viewportWidth, int viewportHeight,
    int interfaceWidth, int interfaceHeight,
    float* output) {
    float inverseViewProjection[16]{};
    if (!invertMatrix(viewProjection, inverseViewProjection)) return false;

    const float renderWidth = static_cast<float>(sceneWidth());
    const float renderHeight = static_cast<float>(sceneHeight());
    const RenderViewport rasterViewport = scaleViewportToTarget(
        viewportX, viewportY, viewportWidth, viewportHeight,
        interfaceWidth, interfaceHeight, sceneWidth(), sceneHeight());
    const float rasterX = static_cast<float>(rasterViewport.left);
    const float rasterY = static_cast<float>(rasterViewport.top);
    const float rasterWidth = static_cast<float>(rasterViewport.width);
    const float rasterHeight = static_cast<float>(rasterViewport.height);
    const float viewportScaleX = renderWidth / rasterWidth;
    const float viewportScaleY = renderHeight / rasterHeight;

    // Convert full-G-buffer NDC into RuneLite's viewport-local NDC.  The RTX
    // compute shaders reconstruct positions from full-image pixel coordinates,
    // whereas the raster pass writes the scene into this sub-rectangle.
    float surfaceNdcToViewportNdc[16]{
        viewportScaleX, 0.0f, 0.0f, 0.0f,
        0.0f, viewportScaleY, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        viewportScaleX - 1.0f - 2.0f * rasterX / rasterWidth,
        1.0f - viewportScaleY + 2.0f * rasterY / rasterHeight,
        0.0f, 1.0f,
    };
    multiplyMatrix(inverseViewProjection, surfaceNdcToViewportNdc, output);
    return true;
}

uint64_t hashStaticPositions(const float* positions, uint32_t vertexCount) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(positions);
    const size_t byteCount = static_cast<size_t>(vertexCount) * 3u * sizeof(float);
    uint64_t hash = 14695981039346656037ull;
    for (size_t index = 0; index < byteCount; ++index) {
        hash ^= bytes[index];
        hash *= 1099511628211ull;
    }
    return hash;
}

double srgbToLinear(double value) {
    return value <= 0.04045 ? value / 12.92
                            : std::pow((value + 0.055) / 1.055, 2.4);
}

std::array<float, 3> packedHslToLinear(uint32_t packed) {
    const double hue = static_cast<double>((packed >> 10u) & 63u) / 64.0 +
                       1.0 / 128.0;
    const double saturation = static_cast<double>((packed >> 7u) & 7u) / 8.0 +
                              1.0 / 16.0;
    const double luminance = static_cast<double>(packed & 127u) / 128.0;
    const double q = luminance < 0.5
        ? luminance * (1.0 + saturation)
        : luminance + saturation - luminance * saturation;
    const double p = 2.0 * luminance - q;
    auto channel = [&](double value) {
        if (value < 0.0) value += 1.0;
        if (value > 1.0) value -= 1.0;
        double srgb = p;
        if (value < 1.0 / 6.0) srgb = p + (q - p) * 6.0 * value;
        else if (value < 0.5) srgb = q;
        else if (value < 2.0 / 3.0)
            srgb = p + (q - p) * (2.0 / 3.0 - value) * 6.0;
        return static_cast<float>(srgbToLinear(std::clamp(srgb, 0.0, 1.0)));
    };
    return {channel(hue + 1.0 / 3.0), channel(hue),
            channel(hue - 1.0 / 3.0)};
}

std::array<float, 3> averageVertexColor(const PackedSceneVertex* vertices,
                                        uint32_t firstVertex,
                                        uint32_t vertexCount) {
    std::array<double, 3> sum{};
    if (!vertices || vertexCount == 0u) return {};
    for (uint32_t vertex = 0u; vertex < vertexCount; ++vertex) {
        const auto color = packedHslToLinear(vertices[firstVertex + vertex].color);
        for (uint32_t channel = 0u; channel < 3u; ++channel)
            sum[channel] += color[channel];
    }
    const double inverseCount = 1.0 / static_cast<double>(vertexCount);
    return {static_cast<float>(sum[0] * inverseCount),
            static_cast<float>(sum[1] * inverseCount),
            static_cast<float>(sum[2] * inverseCount)};
}

bool appendRayQueryStaticSector(WGPUCommandEncoder encoder,
                                const PackedSceneVertex* vertices,
                                const float* positions,
                                uint64_t id,
                                uint32_t firstVertex,
                                uint32_t vertexCount) {
    if (!encoder || !vertices || !positions || vertexCount == 0u ||
        firstVertex % 3u != 0u || vertexCount % 3u != 0u) return false;
    g.raySceneIndices.resize(vertexCount);
    for (uint32_t index = 0u; index < vertexCount; ++index)
        g.raySceneIndices[index] = firstVertex + index;

    const uint32_t triangleCount = vertexCount / 3u;
    std::vector<float> triangleRadiance(static_cast<size_t>(triangleCount) * 4u);
    std::vector<float> triangleSurface(static_cast<size_t>(triangleCount) * 4u);
    for (uint32_t triangle = 0u; triangle < triangleCount; ++triangle) {
        const uint32_t triangleFirstVertex = firstVertex + triangle * 3u;
        const auto baseColor = averageVertexColor(vertices, triangleFirstVertex, 3u);
        const PackedSceneVertex& first = vertices[triangleFirstVertex];
        const bool terrain = (first.sourceFlags & 2u) != 0u;
        const bool water = terrain &&
            (first.textureId == -2 || (first.sourceFlags & 0x40000000u) != 0u);
        const float roughness = water ? 0.12f : first.textureId >= 0 ? 0.58f : 0.82f;
        const float retainedAmbient = water ? 0.12f : 0.24f;
        const size_t offset = static_cast<size_t>(triangle) * 4u;
        triangleRadiance[offset + 0u] = baseColor[0] * retainedAmbient;
        triangleRadiance[offset + 1u] = baseColor[1] * retainedAmbient;
        triangleRadiance[offset + 2u] = baseColor[2] * retainedAmbient;
        triangleRadiance[offset + 3u] = 1.0f;
        triangleSurface[offset + 0u] = baseColor[0];
        triangleSurface[offset + 1u] = baseColor[1];
        triangleSurface[offset + 2u] = baseColor[2];
        triangleSurface[offset + 3u] = roughness;
    }

    RayQueryStaticSectorFrame frame{};
    frame.id = id;
    frame.firstVertex = firstVertex;
    frame.vertexCount = vertexCount;
    frame.positions = positions + static_cast<size_t>(firstVertex) * 3u;
    frame.indices = g.raySceneIndices.data();
    frame.triangleRadiance = triangleRadiance.data();
    frame.triangleSurface = triangleSurface.data();
    struct Append {
        RayQueryStaticSectorFrame* frame{};
        bool result{};
    } append{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* append = static_cast<Append*>(userdata);
            append->frame->commandBuffer = commandBuffer;
            append->result = rayQueryBridgeAppendStaticSector(*append->frame);
        },
        &append);
    return status == WGPUStatus_Success && append.result;
}

bool setRayQueryStaticSectorVisibility(
    WGPUCommandEncoder encoder,
    const std::vector<StaticSectorVisibilityChange>& changes) {
    if (changes.empty()) return true;
    std::vector<uint64_t> ids(changes.size());
    std::vector<uint32_t> active(changes.size());
    for (std::size_t index = 0u; index < changes.size(); ++index) {
        ids[index] = changes[index].id;
        active[index] = changes[index].active ? 1u : 0u;
    }
    struct Visibility {
        const uint64_t* ids{};
        const uint32_t* active{};
        std::size_t count{};
        bool result{};
    } visibility{ids.data(), active.data(), changes.size(), false};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* visibility = static_cast<Visibility*>(userdata);
            visibility->result = rayQueryBridgeSetStaticSectorVisibility(
                commandBuffer, visibility->ids, visibility->active,
                visibility->count);
        },
        &visibility);
    return status == WGPUStatus_Success && visibility.result;
}

bool requestRaySceneRetirement() {
    if (!g.queue || !g.instance || g.pendingRaySceneRetirementTicket != 0u)
        return g.pendingRaySceneRetirementTicket != 0u;

    uint64_t ticket = ++g.nextRaySceneRetirementTicket;
    if (ticket == 0u) ticket = ++g.nextRaySceneRetirementTicket;
    const uint64_t deviceGeneration =
        g.deviceGeneration.load(std::memory_order_acquire);
    WGPUQueueWorkDoneCallbackInfo callback{};
    callback.mode = WGPUCallbackMode_AllowProcessEvents;
    callback.userdata1 = reinterpret_cast<void*>(
        static_cast<uintptr_t>(deviceGeneration));
    callback.userdata2 = reinterpret_cast<void*>(
        static_cast<uintptr_t>(ticket));
    callback.callback = [](WGPUQueueWorkDoneStatus status, WGPUStringView,
                           void* generationUserdata, void* ticketUserdata) {
        const uint64_t callbackGeneration = static_cast<uint64_t>(
            reinterpret_cast<uintptr_t>(generationUserdata));
        if (callbackGeneration == 0u ||
            callbackGeneration !=
                g.deviceGeneration.load(std::memory_order_acquire)) return;
        const uint64_t callbackTicket = static_cast<uint64_t>(
            reinterpret_cast<uintptr_t>(ticketUserdata));
        if (status == WGPUQueueWorkDoneStatus_Success) {
            g.completedRaySceneRetirementTicket.store(
                callbackTicket, std::memory_order_release);
        } else {
            g.failedRaySceneRetirementTicket.store(
                callbackTicket, std::memory_order_release);
        }
    };
    // This wgpu-native build registers ProcessEvents callbacks but returns a
    // zero Future ID (WaitAny is not implemented for queue completion). The
    // callback/ticket is the fence; treating that ID as failure permanently
    // disabled RTX after the first scene rebase or compaction.
    g.pendingRaySceneRetirementTicket = ticket;
    wgpuQueueOnSubmittedWorkDone(g.queue, callback);
    return true;
}

bool updateRayQueryScene(WGPUCommandEncoder encoder,
                         const PackedSceneVertex* vertices,
                         const float* positions,
                         uint32_t staticVertexCount,
                         uint64_t staticRevision) {
    // Match Java's enlarged static near-field budget. Dense 117-HD locations
    // routinely exceed the old 3.6M prefix before the visible window finishes
    // streaming; RTX must accept the same complete prefix as rasterization.
    constexpr uint32_t kStaticVertexCapacity = 7'199'997u;
    constexpr uint32_t kStaticSectorCapacity = 1024u;
    // Java admits at most roughly this much new static geometry per streaming
    // commit.  Apply the same pacing when RTX is enabled after a large raster
    // prefix already exists.  Without it, one WebGPU submit can contain
    // hundreds of BLAS builds and TLAS refits, which is long enough to trip the
    // Windows GPU watchdog on otherwise healthy RTX hardware.  Always allow
    // one oversized sector so a single dense chunk cannot permanently stall
    // the catch-up cursor.
    constexpr uint32_t kMaxStaticSectorBuildsPerFrame = 4u;
    constexpr uint32_t kMaxStaticVerticesBuiltPerFrame = 99'999u;
    const RayQueryBridgeCapabilities capabilities = rayQueryBridgeCapabilities();
    if (!capabilities.pipelineReady || !vertices || !positions ||
        staticVertexCount < 3u) return false;
    staticVertexCount -= staticVertexCount % 3u;
    if (staticVertexCount > kStaticVertexCapacity) return false;

    const uint32_t incomingEpoch = static_cast<uint32_t>(staticRevision >> 32u);
    const bool resetScene = g.raySceneRevision == UINT64_MAX ||
        g.raySceneEpoch != incomingEpoch ||
        staticVertexCount < g.raySceneVertexCount ||
        (!capabilities.sceneReady && g.raySceneVertexCount != 0u);
    if (resetScene) {
        const bool replacingSubmittedScene =
            capabilities.sceneReady || g.raySceneVertexCount != 0u;
        if (replacingSubmittedScene) {
            if (g.pendingRaySceneRetirementTicket == 0u &&
                !requestRaySceneRetirement()) {
                setError("Unable to fence the previous RTX scene for asynchronous retirement");
                return false;
            }
            const uint64_t retirementTicket =
                g.pendingRaySceneRetirementTicket;
            if (g.failedRaySceneRetirementTicket.load(
                    std::memory_order_acquire) == retirementTicket) {
                g.pendingRaySceneRetirementTicket = 0u;
                setError("The WebGPU queue could not retire the previous RTX scene");
                return false;
            }
            if (g.completedRaySceneRetirementTicket.load(
                    std::memory_order_acquire) != retirementTicket) {
                // No ray pass is submitted while pending. Rasterization keeps
                // presenting, and ProcessEvents will deliver the completion.
                return false;
            }
            g.pendingRaySceneRetirementTicket = 0u;
            if (!rayQueryBridgeBeginSegmentedSceneAfterQueueIdle(
                    kStaticVertexCapacity, kStaticSectorCapacity)) return false;
        } else if (!rayQueryBridgeBeginSegmentedSceneAfterQueueIdle(
                       kStaticVertexCapacity, kStaticSectorCapacity)) {
            return false;
        }
        g.raySceneVertexCount = 0u;
        g.raySceneEpoch = incomingEpoch;
        g.raySceneRevision = UINT64_MAX;
        g.raySectorActivity.clear();
        g.dynamicRayMeshActive = false;
        g.dynamicRayVertexCount = 0u;
    }
    if (staticVertexCount < g.raySceneVertexCount) return false;

    uint32_t appendCursor = g.raySceneVertexCount;
    if (staticVertexCount > appendCursor) {
        if (g.staticSectorManifestEnabled) {
            // Every newly-appended vertex must belong to one manifest entry.
            // Previously-retired ranges before appendCursor may be omitted.
            uint32_t appendedSectors = 0u;
            uint32_t appendedVertices = 0u;
            bool catchUpBudgetExhausted = false;
            for (const auto& range : g.staticSectorManifest) {
                const uint32_t rangeEnd = range.firstVertex + range.vertexCount;
                if (rangeEnd <= appendCursor) continue;
                if (range.firstVertex != appendCursor ||
                    rangeEnd > staticVertexCount) return false;
                const bool exceedsSectorBudget =
                    appendedSectors >= kMaxStaticSectorBuildsPerFrame;
                const bool exceedsVertexBudget = appendedSectors != 0u &&
                    range.vertexCount >
                        kMaxStaticVerticesBuiltPerFrame - std::min(
                            appendedVertices, kMaxStaticVerticesBuiltPerFrame);
                if (exceedsSectorBudget || exceedsVertexBudget) {
                    catchUpBudgetExhausted = true;
                    break;
                }
                if (!appendRayQueryStaticSector(
                        encoder, vertices, positions, range.id,
                        range.firstVertex, range.vertexCount)) return false;
                g.raySectorActivity[range.id] = true;
                appendCursor = rangeEnd;
                ++appendedSectors;
                appendedVertices += range.vertexCount;
                if (appendCursor == staticVertexCount) break;
            }
            // A partial prefix is expected while catching up.  A gap without a
            // budget stop still means the manifest is malformed.
            if (appendCursor != staticVertexCount && !catchUpBudgetExhausted)
                return false;
        } else {
            // Legacy callers do not supply sector boundaries.  Still shard the
            // de-indexed triangle stream so enabling RTX cannot submit the
            // complete retained world as one BLAS.
            const uint32_t appendedVertexCount = std::min(
                staticVertexCount - appendCursor,
                kMaxStaticVerticesBuiltPerFrame);
            const uint64_t legacyId =
                (static_cast<uint64_t>(incomingEpoch) << 32u) |
                static_cast<uint64_t>(appendCursor / 3u);
            if (!appendRayQueryStaticSector(
                    encoder, vertices, positions, legacyId,
                    appendCursor, appendedVertexCount)) return false;
            g.raySectorActivity[legacyId] = true;
            appendCursor += appendedVertexCount;
        }
        g.raySceneVertexCount = appendCursor;
        g.lastRaySceneBuildFrame = g.frameIndex;
    }

    if (g.staticSectorManifestEnabled) {
        std::unordered_set<uint64_t> manifestIds;
        manifestIds.reserve(g.staticSectorManifest.size());
        std::vector<StaticSectorVisibilityChange> visibilityChanges;
        for (const auto& range : g.staticSectorManifest) {
            if (range.firstVertex + range.vertexCount > g.raySceneVertexCount)
                continue;
            manifestIds.insert(range.id);
            const auto iterator = g.raySectorActivity.find(range.id);
            if (iterator == g.raySectorActivity.end()) return false;
            if (iterator->second != range.active) {
                visibilityChanges.push_back({range.id, range.active});
            }
        }
        // Omitting an old resident range is an explicit retirement request.
        // Its BLAS stays allocated, but its fixed TLAS slot stops traversing.
        for (auto& [id, active] : g.raySectorActivity) {
            if (manifestIds.contains(id) || !active) continue;
            visibilityChanges.push_back({id, false});
        }
        if (!setRayQueryStaticSectorVisibility(
                encoder, visibilityChanges)) return false;
        for (const auto& change : visibilityChanges)
            g.raySectorActivity[change.id] = change.active;
    }

    g.raySceneEpoch = incomingEpoch;
    g.raySceneRevision = staticRevision;
    return rayQueryBridgeCapabilities().sceneReady;
}

bool ensureDynamicPositionTexture(uint32_t vertexCount) {
    constexpr uint32_t kTextureWidth = 256u;
    const uint32_t height = std::max(1u, (vertexCount + kTextureWidth - 1u) / kTextureWidth);
    if (g.dynamicPositionTexture && g.dynamicPositionTextureWidth == kTextureWidth &&
        g.dynamicPositionTextureHeight == height) {
        return true;
    }

    forgetNativeTexture(g.dynamicPositionTexture);
    if (g.dynamicPositionTexture) wgpuTextureRelease(g.dynamicPositionTexture);
    g.dynamicPositionTexture = nullptr;
    g.dynamicPositionTextureWidth = 0;
    g.dynamicPositionTextureHeight = 0;

    WGPUTextureDescriptor descriptor{};
    descriptor.label = stringView("RuneLite dynamic RTX positions");
    descriptor.dimension = WGPUTextureDimension_2D;
    descriptor.size = WGPUExtent3D{kTextureWidth, height, 1u};
    descriptor.format = WGPUTextureFormat_RGBA32Float;
    descriptor.mipLevelCount = 1u;
    descriptor.sampleCount = 1u;
    descriptor.usage = WGPUTextureUsage_CopyDst | WGPUTextureUsage_CopySrc;
    g.dynamicPositionTexture = wgpuDeviceCreateTexture(g.device, &descriptor);
    if (!g.dynamicPositionTexture) return false;
    g.dynamicPositionTextureWidth = kTextureWidth;
    g.dynamicPositionTextureHeight = height;
    return true;
}

bool updateDynamicRayQueryScene(WGPUCommandEncoder encoder,
                                const PackedSceneVertex* vertices,
                                const float* positions,
                                uint32_t staticVertexCount, uint32_t vertexCount) {
    if (!encoder || !vertices || !positions || staticVertexCount > vertexCount)
        return false;
    uint32_t dynamicVertexCount = vertexCount - staticVertexCount;
    dynamicVertexCount -= dynamicVertexCount % 3u;
    const RayQueryBridgeCapabilities capabilities = rayQueryBridgeCapabilities();
    if (!capabilities.sceneReady) return false;

    if (dynamicVertexCount == 0u) {
        if (!g.dynamicRayMeshActive) return true;
        struct Destroy {
            bool result{};
        } destroy;
        const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
            encoder,
            [](void* commandBuffer, void* userdata) {
                static_cast<Destroy*>(userdata)->result =
                    rayQueryBridgeDestroyDynamicTriangleMesh(commandBuffer, 1u);
            },
            &destroy);
        if (status == WGPUStatus_Success && destroy.result) {
            g.dynamicRayMeshActive = false;
            g.dynamicRayVertexCount = 0u;
            return true;
        }
        return false;
    }

    if (g.dynamicRayMeshActive && g.dynamicRayVertexCount != dynamicVertexCount) {
        struct Destroy {
            bool result{};
        } destroy;
        const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
            encoder,
            [](void* commandBuffer, void* userdata) {
                static_cast<Destroy*>(userdata)->result =
                    rayQueryBridgeDestroyDynamicTriangleMesh(commandBuffer, 1u);
            },
            &destroy);
        if (status != WGPUStatus_Success || !destroy.result) return false;
        g.dynamicRayMeshActive = false;
        g.dynamicRayVertexCount = 0u;
    }

    if (!ensureDynamicPositionTexture(dynamicVertexCount)) return false;
    const size_t texelCount = static_cast<size_t>(g.dynamicPositionTextureWidth) *
                              g.dynamicPositionTextureHeight;
    g.dynamicPositionPixels.assign(texelCount * 4u, 0.0f);
    for (uint32_t vertex = 0; vertex < dynamicVertexCount; ++vertex) {
        const float* source = positions +
            static_cast<size_t>(staticVertexCount + vertex) * 3u;
        float* destination = g.dynamicPositionPixels.data() +
            static_cast<size_t>(vertex) * 4u;
        destination[0] = source[0];
        destination[1] = source[1];
        destination[2] = source[2];
        destination[3] = 1.0f;
    }
    WGPUTexelCopyTextureInfo destination{};
    destination.texture = g.dynamicPositionTexture;
    WGPUTexelCopyBufferLayout layout{};
    layout.bytesPerRow = g.dynamicPositionTextureWidth * 4u * sizeof(float);
    layout.rowsPerImage = g.dynamicPositionTextureHeight;
    WGPUExtent3D extent{
        g.dynamicPositionTextureWidth, g.dynamicPositionTextureHeight, 1u};
    wgpuQueueWriteTexture(
        g.queue, &destination, g.dynamicPositionPixels.data(),
        g.dynamicPositionPixels.size() * sizeof(float), &layout, &extent);

    RayQueryDynamicTriangleMeshFrame frame{};
    frame.positionsImage = wgpuTextureGetNativeVulkanImage(g.dynamicPositionTexture);
    frame.positionsLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    frame.width = g.dynamicPositionTextureWidth;
    frame.height = g.dynamicPositionTextureHeight;
    frame.vertexCount = dynamicVertexCount;
    frame.handle = 1u;
    struct Update {
        RayQueryDynamicTriangleMeshFrame* frame{};
        const uint32_t* indices{};
        size_t indexCount{};
        bool create{};
        bool result{};
    } update{&frame};

    if (!g.dynamicRayMeshActive) {
        g.dynamicRayIndices.resize(dynamicVertexCount);
        for (uint32_t index = 0; index < dynamicVertexCount; ++index)
            g.dynamicRayIndices[index] = index;
        frame.flags = 2u;
        const auto baseColor = averageVertexColor(
            vertices, staticVertexCount, dynamicVertexCount);
        frame.reflectionRadiance[0] = baseColor[0] * 0.24f;
        frame.reflectionRadiance[1] = baseColor[1] * 0.24f;
        frame.reflectionRadiance[2] = baseColor[2] * 0.24f;
        frame.reflectionRadiance[3] = 1.0f;
        frame.reflectionSurface[0] = baseColor[0];
        frame.reflectionSurface[1] = baseColor[1];
        frame.reflectionSurface[2] = baseColor[2];
        frame.reflectionSurface[3] = 0.72f;
        update.indices = g.dynamicRayIndices.data();
        update.indexCount = g.dynamicRayIndices.size();
        update.create = true;
    }

    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Update*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = value->create
                ? rayQueryBridgeCreateDynamicTriangleMesh(
                      *value->frame, value->indices, value->indexCount)
                : rayQueryBridgeRefitDynamicTriangleMesh(*value->frame);
        },
        &update);
    if (status != WGPUStatus_Success || !update.result) return false;
    g.dynamicRayMeshActive = true;
    g.dynamicRayVertexCount = dynamicVertexCount;
    return true;
}

bool updateRayQueryLights(WGPUCommandEncoder encoder, const float* lightRecords,
                          uint32_t lightCount) {
    if (!encoder || lightCount > 8u || (lightCount > 0u && !lightRecords) ||
        !rayQueryBridgeCapabilities().sceneReady) {
        return false;
    }
    struct Update {
        const float* records{};
        uint32_t count{};
        bool result{};
    } update{lightRecords, lightCount};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Update*>(userdata);
            value->result = rayQueryBridgeUpdateStaticLights(
                commandBuffer, value->records, value->count);
        },
        &update);
    return status == WGPUStatus_Success && update.result;
}

bool evaluateRayQueryLighting(WGPUCommandEncoder encoder, const float* viewProjection,
                              const float* cameraPosition,
                              int viewportX, int viewportY,
                              int viewportWidth, int viewportHeight,
                              int interfaceWidth, int interfaceHeight) {
    const RayQueryBridgeCapabilities capabilities = rayQueryBridgeCapabilities();
    if (!capabilities.sceneReady || !g.hdrColorTexture || !g.depthTexture) return false;
    RayQueryLightingFrame frame{};
    frame.colorImage = wgpuTextureGetNativeVulkanImage(g.hdrColorTexture);
    frame.colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.depthImage = wgpuTextureGetNativeVulkanImage(g.depthTexture);
    frame.depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    frame.materialImage = wgpuTextureGetNativeVulkanImage(g.diffuseAlbedoTexture);
    frame.materialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.width = sceneWidth();
    frame.height = sceneHeight();
    // Mirror the raster viewport in render-target pixels.  The RTX lighting
    // shader shades only this rectangle instead of dispatching across the
    // complete window (which also contains RuneLite UI and cleared margins).
    const float interfaceScaleX = static_cast<float>(frame.width) /
        static_cast<float>(std::max(interfaceWidth, 1));
    const float interfaceScaleY = static_cast<float>(frame.height) /
        static_cast<float>(std::max(interfaceHeight, 1));
    const float rasterLeft = std::max(0.0f, viewportX * interfaceScaleX);
    const float rasterTop = std::max(0.0f, viewportY * interfaceScaleY);
    const float rasterRight = std::min(
        static_cast<float>(frame.width),
        rasterLeft + std::max(1.0f, viewportWidth * interfaceScaleX));
    const float rasterBottom = std::min(
        static_cast<float>(frame.height),
        rasterTop + std::max(1.0f, viewportHeight * interfaceScaleY));
    frame.viewportX = std::min(
        frame.width - 1u, static_cast<uint32_t>(std::floor(rasterLeft)));
    frame.viewportY = std::min(
        frame.height - 1u, static_cast<uint32_t>(std::floor(rasterTop)));
    const uint32_t viewportRight = std::min(
        frame.width, static_cast<uint32_t>(std::ceil(rasterRight)));
    const uint32_t viewportBottom = std::min(
        frame.height, static_cast<uint32_t>(std::ceil(rasterBottom)));
    frame.viewportWidth = std::max(1u, viewportRight - frame.viewportX);
    frame.viewportHeight = std::max(1u, viewportBottom - frame.viewportY);
    if (!invertViewProjectionForRasterViewport(
            viewProjection, viewportX, viewportY, viewportWidth, viewportHeight,
            interfaceWidth, interfaceHeight, frame.inverseViewProjection)) return false;
    frame.cameraPosition[0] = cameraPosition[0];
    frame.cameraPosition[1] = cameraPosition[1];
    frame.cameraPosition[2] = cameraPosition[2];
    frame.cameraPosition[3] = 1.0f;
    // Raster lighting, G-buffer normals and ray directions share RuneLite's
    // world space: model/terrain height increases toward negative Y.
    const float sunAngle = (g.visual.timeOfDay - 6.0f) *
        (2.0f * 3.14159265358979323846f / 24.0f);
    frame.directionalLightDirectionIntensity[0] = std::cos(sunAngle) * 0.72f;
    const float sunElevation = std::sin(sunAngle);
    frame.directionalLightDirectionIntensity[1] = -sunElevation;
    frame.directionalLightDirectionIntensity[2] = 0.42f;
    frame.directionalLightDirectionIntensity[3] = 1.0f;
    const float daylight = std::clamp((sunElevation + 0.08f) / 0.24f, 0.0f, 1.0f);
    frame.directionalVisibilityStrength = g.visual.rtxShadowStrength * daylight;
    const uint32_t weatherMode = resolvedWeatherMode();
    const float weatherIntensity = resolvedWeatherIntensity();
    const float weatherAmount = weatherIntensity * (weatherMode >= 1u ? 1.0f : 0.0f);
    const float weatherDimming = std::clamp(
        1.0f - weatherIntensity *
            (weatherMode >= 3u ? 0.55f
             : weatherMode >= 2u ? 0.42f
             : weatherMode >= 1u ? 0.28f : 0.0f),
        0.35f, 1.0f);
    // AO is disabled until temporally accumulated. These two ABI slots instead
    // carry the raster equation's ambient floor and directional coefficient so
    // the ray pass can shadow only direct sunlight without darkening 117's
    // ambient/sky contribution a second time.
    frame.aoStrength = g.visual.ambientIntensity *
        (1.0f - 0.24f * weatherAmount);
    frame.aoRadius = (1.0f - frame.aoStrength) * 1.25f * daylight * weatherDimming;
    frame.directionalAngularRadius = 0.0093f;
    frame.maxDistance = 8192.0f;
    frame.rayBias = 0.35f;
    frame.directionalSampleCount = 1;
    frame.aoSampleCount = 1;
    frame.frameIndex = static_cast<uint32_t>(g.frameIndex);
    frame.flags = 1u;
    struct Evaluation { RayQueryLightingFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = rayQueryBridgeEvaluate(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

bool evaluateRayQueryReflections(WGPUCommandEncoder encoder, const float* viewProjection,
                                 const float* cameraPosition,
                                 int viewportX, int viewportY,
                                 int viewportWidth, int viewportHeight,
                                 int interfaceWidth, int interfaceHeight) {
    const RayQueryBridgeCapabilities capabilities = rayQueryBridgeCapabilities();
    if (!capabilities.sceneReady || !g.hdrColorTexture || !g.reflectionTexture ||
        !g.depthTexture || !g.normalRoughnessTexture || !g.specularAlbedoTexture ||
        !g.specularHitDistanceTexture) return false;
    RayQueryReflectionFrame frame{};
    frame.sourceColorImage = wgpuTextureGetNativeVulkanImage(g.hdrColorTexture);
    frame.sourceColorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.outputColorImage = wgpuTextureGetNativeVulkanImage(g.reflectionTexture);
    frame.outputColorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.depthImage = wgpuTextureGetNativeVulkanImage(g.depthTexture);
    frame.depthLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    frame.normalRoughnessImage = wgpuTextureGetNativeVulkanImage(g.normalRoughnessTexture);
    frame.normalRoughnessLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.specularAlbedoImage = wgpuTextureGetNativeVulkanImage(g.specularAlbedoTexture);
    frame.specularAlbedoLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.specularAlbedoFormat = VK_FORMAT_R8G8B8A8_UNORM;
    frame.specularHitDistanceImage =
        wgpuTextureGetNativeVulkanImage(g.specularHitDistanceTexture);
    frame.specularHitDistanceLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    frame.specularHitDistanceFormat = VK_FORMAT_R32_SFLOAT;
    frame.width = sceneWidth();
    frame.height = sceneHeight();
    if (!invertViewProjectionForRasterViewport(
            viewProjection, viewportX, viewportY, viewportWidth, viewportHeight,
            interfaceWidth, interfaceHeight, frame.inverseViewProjection)) return false;
    frame.cameraPosition[0] = cameraPosition[0];
    frame.cameraPosition[1] = cameraPosition[1];
    frame.cameraPosition[2] = cameraPosition[2];
    frame.cameraPosition[3] = 1.0f;
    frame.parameters[0] = g.visual.rtxReflectionStrength;
    // Quality controls ray reach, rough-water coverage and the explicitly
    // selected 4x4, 2x2 or full-resolution spatial ray tier.
    const uint32_t reflectionQuality = std::min(g.visual.rtxReflectionQuality, 2u);
    frame.parameters[1] = reflectionQuality == 0u ? 3072.0f
        : reflectionQuality == 1u ? 6144.0f : 8192.0f;
    frame.parameters[2] = 2.0f;
    frame.parameters[3] = reflectionQuality == 0u ? 0.22f
        : reflectionQuality == 1u ? 0.34f : 0.48f;
    const float sunAngle = (g.visual.timeOfDay - 6.0f) *
        (2.0f * 3.14159265358979323846f / 24.0f);
    const float day = std::clamp((std::sin(sunAngle) + 0.08f) / 0.24f, 0.0f, 1.0f);
    const uint32_t weatherMode = resolvedWeatherMode();
    const float weatherIntensity = resolvedWeatherIntensity();
    const float weatherDimming = std::clamp(
        1.0f - weatherIntensity *
            (weatherMode >= 3u ? 0.55f
             : weatherMode >= 2u ? 0.42f
             : weatherMode >= 1u ? 0.28f : 0.0f),
        0.35f, 1.0f);
    frame.environment[0] = (0.015f + (0.18f - 0.015f) * day) * weatherDimming;
    frame.environment[1] = (0.028f + (0.43f - 0.028f) * day) * weatherDimming;
    frame.environment[2] = (0.070f + (0.66f - 0.070f) * day) * weatherDimming;
    frame.environment[3] = 0.88f;
    // Reverse depth plus the explicitly selected spatial ray tier. Temporal
    // jitter remains reserved for Ray Reconstruction, so every preview tier
    // is stable even without a denoiser.
    frame.flags = 1u | 16u | (reflectionQuality << 8u);
    frame.frameIndex = static_cast<uint32_t>(g.frameIndex);
    struct Evaluation { RayQueryReflectionFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = rayQueryBridgeEvaluateReflections(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

StreamlineVulkanResource makeStreamlineResource(
    WGPUTexture texture, uint32_t width, uint32_t height, VkFormat format,
    VkImageLayout layout, VkImageUsageFlags usage, VkImageAspectFlags aspect,
    const RenderViewport* viewport = nullptr) {
    StreamlineVulkanResource resource{};
    resource.image = wgpuTextureGetNativeVulkanImage(texture);
    resource.width = width;
    resource.height = height;
    resource.format = static_cast<uint32_t>(format);
    resource.layout = static_cast<uint32_t>(layout);
    resource.usage = usage;
    resource.aspectMask = aspect;
    resource.mipLevels = 1;
    resource.arrayLayers = 1;
    resource.left = viewport ? viewport->left : 0u;
    resource.top = viewport ? viewport->top : 0u;
    resource.extentWidth = viewport ? viewport->width : width;
    resource.extentHeight = viewport ? viewport->height : height;
    return resource;
}

bool makeStreamlineConstants(
    const float* viewProjection, const float* cameraViewToClip,
    const float* cameraPosition, int viewportWidth, int viewportHeight,
    const TemporalJitter& jitter,
    StreamlineFrameConstants& constants) {
    constants = {};
    float inverseProjection[16]{};
    float inverseCurrent[16]{};
    float inversePrevious[16]{};
    float clipToPrevious[16]{};
    float previousToCurrent[16]{};
    float worldToView[16]{};
    float cameraViewToWorld[16]{};
    if (!invertMatrix(cameraViewToClip, inverseProjection) ||
        !invertMatrix(viewProjection, inverseCurrent)) return false;
    // Streamline requires unjittered projection matrices. Raster motion keeps
    // a separate jittered transform in g.previousViewProjection.
    const float* previous = g.hasPreviousViewProjection
        ? g.previousStreamlineViewProjection
        : viewProjection;
    if (!invertMatrix(previous, inversePrevious)) return false;
    multiplyMatrix(previous, inverseCurrent, clipToPrevious);
    multiplyMatrix(viewProjection, inversePrevious, previousToCurrent);
    multiplyMatrix(inverseProjection, viewProjection, worldToView);
    if (!invertMatrix(worldToView, cameraViewToWorld)) return false;
    copyRowMajor(constants.cameraViewToClip, cameraViewToClip);
    copyRowMajor(constants.clipToCameraView, inverseProjection);
    const float identity[16]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    copyRowMajor(constants.clipToLensClip, identity);
    copyRowMajor(constants.clipToPrevClip, clipToPrevious);
    copyRowMajor(constants.prevClipToClip, previousToCurrent);
    constants.jitterOffset = jitter.pixels;
    constants.motionVectorScale = {1.0f, 1.0f};
    constants.cameraPosition = {cameraPosition[0], cameraPosition[1], cameraPosition[2]};
    auto normalizedAxis = [&](int column) {
        std::array<float, 3> axis{
            cameraViewToWorld[column * 4],
            cameraViewToWorld[column * 4 + 1],
            cameraViewToWorld[column * 4 + 2]};
        const float length = std::sqrt(
            axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (length > 1e-6f) {
            axis[0] /= length;
            axis[1] /= length;
            axis[2] /= length;
        }
        return axis;
    };
    constants.cameraRight = normalizedAxis(0);
    constants.cameraUp = normalizedAxis(1);
    constants.cameraForward = normalizedAxis(2);
    constants.cameraNear = 50.0f;
    constants.cameraFar = 8192.0f;
    const float projectionY = std::max(1e-5f, std::abs(cameraViewToClip[5]));
    constants.cameraFov = 2.0f * std::atan(1.0f / projectionY);
    constants.cameraAspectRatio = static_cast<float>(std::max(1, viewportWidth)) /
                                  static_cast<float>(std::max(1, viewportHeight));
    constants.depthInverted = true;
    constants.cameraMotionIncluded = true;
    constants.motionVectors3D = false;
    constants.reset = g.temporalResetRequested || !g.hasPreviousViewProjection;
    // The raster target contains one vector per covered sample, but it has not
    // undergone the nearest-depth dilation pass expected by this flag.
    constants.motionVectorsDilated = false;
    constants.motionVectorsJittered = jitter.enabled;
    return true;
}

bool evaluateDlss(WGPUCommandEncoder encoder, WGPUTexture colorInput,
                   const float* viewProjection,
                   const float* cameraViewToClip, const float* cameraPosition,
                   const RenderViewport& inputViewport,
                   const TemporalJitter& jitter) {
    const StreamlineFeatureState state = streamlineFeatureState();
    if (!state.dlssConfigured || !colorInput || !g.dlssOutputTexture ||
        !g.depthTexture || !g.motionTexture) return false;
    StreamlineDLSSFrame frame{};
    frame.viewport = 0;
    const VkImageUsageFlags colorInputUsage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_STORAGE_BIT;
    const uint32_t renderWidth = sceneWidth();
    const uint32_t renderHeight = sceneHeight();
    const RenderViewport outputViewport = scaleViewportBetweenTargets(
        inputViewport, renderWidth, renderHeight, g.width, g.height);
    frame.colorInput = makeStreamlineResource(
        colorInput, renderWidth, renderHeight, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, colorInputUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.colorOutput = makeStreamlineResource(
        g.dlssOutputTexture, g.width, g.height, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, colorInputUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &outputViewport);
    frame.depth = makeStreamlineResource(
        g.depthTexture, renderWidth, renderHeight, VK_FORMAT_D32_SFLOAT,
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT, &inputViewport);
    frame.motionVectors = makeStreamlineResource(
        g.motionTexture, renderWidth, renderHeight, VK_FORMAT_R16G16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.hasExposure = false;
    if (!makeStreamlineConstants(
            viewProjection, cameraViewToClip, cameraPosition,
            static_cast<int>(inputViewport.width),
            static_cast<int>(inputViewport.height), jitter, frame.constants)) return false;
    struct Evaluation { StreamlineDLSSFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = streamlineDLSSEvaluate(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

bool evaluateDlssNeuralRendering(
    WGPUCommandEncoder encoder, WGPUTexture colorInput,
    const float* viewProjection, const float* cameraViewToClip,
    const float* cameraPosition, const RenderViewport& viewport,
    const TemporalJitter& jitter) {
    const StreamlineFeatureState state = streamlineFeatureState();
    if (!g.dlssNeuralRenderingRequested || !state.neuralRenderingSupported ||
        !colorInput || !g.neuralRenderingTexture || !g.depthTexture ||
        !g.motionTexture) return false;

    const uint32_t renderWidth = sceneWidth();
    const uint32_t renderHeight = sceneHeight();
    const VkImageUsageFlags colorUsage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags guideUsage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    StreamlineDLSSNRFrame frame{};
    frame.viewport = 0;
    frame.colorInput = makeStreamlineResource(
        colorInput, renderWidth, renderHeight, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, colorUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &viewport);
    frame.colorOutput = makeStreamlineResource(
        g.neuralRenderingTexture, renderWidth, renderHeight,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, colorUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &viewport);
    frame.depth = makeStreamlineResource(
        g.depthTexture, renderWidth, renderHeight, VK_FORMAT_D32_SFLOAT,
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT, &viewport);
    frame.motionVectors = makeStreamlineResource(
        g.motionTexture, renderWidth, renderHeight, VK_FORMAT_R16G16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, guideUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &viewport);
    // Start with NVIDIA's stable automatic-mask path. The RuneLite style
    // presets remain scene controls; they are not undocumented DLSS NR style
    // indices. The NR output stays render-sized and is passed to SR afterwards
    // when upscaling is also enabled.
    frame.options.enabled = true;
    frame.options.intensity = 1.0f;
    frame.options.localToneStrength = 1.0f;
    frame.options.localStructureStrength = 1.0f;
    frame.options.globalToneStrength = 1.0f;
    frame.options.style = 0;
    frame.options.renderPreset = 0;
    frame.options.useAutoMask = true;
    frame.options.skinStructureStrength = 1.0f;
    frame.options.performanceMode = StreamlineDLSSMode::DLAA;
    frame.hasControlMask = false;
    if (!makeStreamlineConstants(
            viewProjection, cameraViewToClip, cameraPosition,
            static_cast<int>(viewport.width), static_cast<int>(viewport.height),
            jitter, frame.constants)) return false;

    struct Evaluation { StreamlineDLSSNRFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = streamlineDLSSNREvaluate(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

bool evaluateRayReconstruction(WGPUCommandEncoder encoder, const float* viewProjection,
                               const float* cameraViewToClip, const float* cameraPosition,
                               const RenderViewport& inputViewport,
                               const TemporalJitter& jitter) {
    const StreamlineFeatureState state = streamlineFeatureState();
    if (!state.rayReconstructionRequested || !g.reflectionTexture ||
        !g.dlssOutputTexture || !g.depthTexture || !g.motionTexture ||
        !g.diffuseAlbedoTexture || !g.specularAlbedoTexture ||
        !g.normalRoughnessTexture || !g.specularHitDistanceTexture) return false;
    const VkImageUsageFlags hdrUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
    const VkImageUsageFlags guideUsage =
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    const uint32_t renderWidth = sceneWidth();
    const uint32_t renderHeight = sceneHeight();
    const RenderViewport outputViewport = scaleViewportBetweenTargets(
        inputViewport, renderWidth, renderHeight, g.width, g.height);
    StreamlineRayReconstructionFrame frame{};
    frame.viewport = 0;
    frame.noisyColor = makeStreamlineResource(
        g.reflectionTexture, renderWidth, renderHeight, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, hdrUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.colorOutput = makeStreamlineResource(
        g.dlssOutputTexture, g.width, g.height, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, hdrUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &outputViewport);
    frame.depth = makeStreamlineResource(
        g.depthTexture, renderWidth, renderHeight, VK_FORMAT_D32_SFLOAT,
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT, &inputViewport);
    frame.motionVectors = makeStreamlineResource(
        g.motionTexture, renderWidth, renderHeight, VK_FORMAT_R16G16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, guideUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.diffuseAlbedo = makeStreamlineResource(
        g.diffuseAlbedoTexture, renderWidth, renderHeight, VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, guideUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.specularAlbedo = makeStreamlineResource(
        g.specularAlbedoTexture, renderWidth, renderHeight, VK_FORMAT_R8G8B8A8_UNORM,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, guideUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.normalRoughness = makeStreamlineResource(
        g.normalRoughnessTexture, renderWidth, renderHeight, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, guideUsage,
        VK_IMAGE_ASPECT_COLOR_BIT, &inputViewport);
    frame.normalRoughnessPacked = true;
    frame.specularHitDistance = makeStreamlineResource(
        g.specularHitDistanceTexture, renderWidth, renderHeight, VK_FORMAT_R32_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        guideUsage | VK_IMAGE_USAGE_STORAGE_BIT, VK_IMAGE_ASPECT_COLOR_BIT,
        &inputViewport);
    frame.hasSpecularHitDistance = true;
    float inverseProjection[16]{};
    float worldToView[16]{};
    float cameraViewToWorld[16]{};
    if (!invertMatrix(cameraViewToClip, inverseProjection)) return false;
    multiplyMatrix(inverseProjection, viewProjection, worldToView);
    if (!invertMatrix(worldToView, cameraViewToWorld)) return false;
    copyRowMajor(frame.worldToCameraView, worldToView);
    copyRowMajor(frame.cameraViewToWorld, cameraViewToWorld);
    if (!makeStreamlineConstants(
            viewProjection, cameraViewToClip, cameraPosition,
            static_cast<int>(inputViewport.width),
            static_cast<int>(inputViewport.height), jitter, frame.constants)) return false;
    struct Evaluation { StreamlineRayReconstructionFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = streamlineRayReconstructionEvaluate(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

bool tagFrameGeneration(WGPUCommandEncoder encoder, WGPUTexture hudlessColor,
                        const float* viewProjection, const float* cameraViewToClip,
                        const float* cameraPosition, int viewportWidth, int viewportHeight,
                        const TemporalJitter& jitter) {
    const StreamlineFeatureState state = streamlineFeatureState();
    if (!state.frameGenerationRequested || !hudlessColor || !g.depthTexture ||
        !g.motionTexture) return false;
    StreamlineFrameGenerationFrame frame{};
    frame.viewport = 0;
    frame.hudlessColor = makeStreamlineResource(
        hudlessColor, g.width, g.height, VK_FORMAT_R16G16B16A16_SFLOAT,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
            VK_IMAGE_USAGE_STORAGE_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT);
    frame.depth = makeStreamlineResource(
        g.depthTexture, g.width, g.height, VK_FORMAT_D32_SFLOAT,
        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_DEPTH_BIT);
    frame.motionVectors = makeStreamlineResource(
        g.motionTexture, g.width, g.height, VK_FORMAT_R16G16_SFLOAT,
        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        VK_IMAGE_ASPECT_COLOR_BIT);
    if (g.uiTexture && g.uiWidth == g.width && g.uiHeight == g.height) {
        frame.ui = makeStreamlineResource(
            g.uiTexture, g.uiWidth, g.uiHeight, VK_FORMAT_B8G8R8A8_UNORM,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            VK_IMAGE_ASPECT_COLOR_BIT);
        frame.hasUi = true;
        frame.uiAlphaOnly = false;
    }
    frame.backbufferWidth = g.width;
    frame.backbufferHeight = g.height;
    frame.backbufferFormat = VK_FORMAT_B8G8R8A8_UNORM;
    frame.framesToGenerate = 1;
    if (!makeStreamlineConstants(
            viewProjection, cameraViewToClip, cameraPosition,
            viewportWidth, viewportHeight, jitter, frame.constants)) return false;
    struct Evaluation { StreamlineFrameGenerationFrame* frame; bool result{}; } evaluation{&frame};
    const WGPUStatus status = wgpuCommandEncoderWithNativeVulkanCommandBuffer(
        encoder,
        [](void* commandBuffer, void* userdata) {
            auto* value = static_cast<Evaluation*>(userdata);
            value->frame->commandBuffer = commandBuffer;
            value->result = streamlineFrameGenerationTag(*value->frame);
        },
        &evaluation);
    return status == WGPUStatus_Success && evaluation.result;
}

bool configureSurface() {
    WGPUSurfaceConfiguration configuration{};
    configuration.device = g.device;
    configuration.format = g.surfaceFormat;
    configuration.usage = WGPUTextureUsage_RenderAttachment;
    configuration.width = g.width;
    configuration.height = g.height;
    configuration.alphaMode = g.alphaMode;
    configuration.presentMode = !g.vsync && g.immediatePresentSupported
        ? WGPUPresentMode_Immediate
        : WGPUPresentMode_Fifo;
    wgpuSurfaceConfigure(g.surface, &configuration);
    g.configured = true;
    return true;
}

bool createInstanceAndSurface(HWND hwnd) {
    // Streamline must install its Vulkan loader proxy before wgpu-native
    // creates the instance/device. Failure is non-fatal: plain WebGPU remains
    // available and capabilities report the precise NVIDIA runtime status.
    g.nvidiaPrepared = streamlinePrepare();
    WGPUInstanceExtras extras{};
    extras.chain.sType = static_cast<WGPUSType>(WGPUSType_InstanceExtras);
    extras.backends = WGPUInstanceBackend_Vulkan;
    extras.flags = WGPUInstanceFlag_Empty;
    WGPUInstanceDescriptor instanceDescriptor{};
    instanceDescriptor.nextInChain = &extras.chain;
    g.instance = wgpuCreateInstance(&instanceDescriptor);
    if (!g.instance) {
        setError("wgpuCreateInstance failed");
        return false;
    }

    WGPUSurfaceSourceWindowsHWND source{};
    source.chain.sType = WGPUSType_SurfaceSourceWindowsHWND;
    source.hinstance = reinterpret_cast<HINSTANCE>(GetWindowLongPtrW(hwnd, GWLP_HINSTANCE));
    if (!source.hinstance) source.hinstance = GetModuleHandleW(nullptr);
    source.hwnd = hwnd;
    WGPUSurfaceDescriptor descriptor{};
    descriptor.nextInChain = &source.chain;
    descriptor.label = stringView("RuneLite WebGPU canvas");
    g.surface = wgpuInstanceCreateSurface(g.instance, &descriptor);
    if (!g.surface) {
        setError("wgpuInstanceCreateSurface failed");
        return false;
    }
    return true;
}

bool requestAdapterAndDevice() {
    WGPURequestAdapterOptions options{};
    options.compatibleSurface = g.surface;
    options.powerPreference = WGPUPowerPreference_HighPerformance;
    WGPURequestAdapterCallbackInfo adapterCallback{};
    // These callbacks write the handles consumed below.  WaitAnyOnly keeps
    // delivery on this caller while waitFor() owns the operation instead of
    // racing an arbitrary wgpu-native worker thread.
    adapterCallback.mode = WGPUCallbackMode_WaitAnyOnly;
    adapterCallback.callback = [](WGPURequestAdapterStatus status, WGPUAdapter adapter,
                                  WGPUStringView message, void* userdata, void*) {
        if (status == WGPURequestAdapterStatus_Success)
            *static_cast<WGPUAdapter*>(userdata) = adapter;
        else
            setError("requestAdapter: " + fromStringView(message));
    };
    adapterCallback.userdata1 = &g.adapter;
    WGPUFuture adapterFuture = wgpuInstanceRequestAdapter(g.instance, &options, adapterCallback);
    if (!g.adapter) waitFor(adapterFuture);
    if (!g.adapter) {
        setError("No Vulkan WebGPU adapter is available");
        return false;
    }

    WGPUAdapterInfo adapterInfo{};
    if (wgpuAdapterGetInfo(g.adapter, &adapterInfo) == WGPUStatus_Success) {
        g.vendorId = adapterInfo.vendorID;
        g.deviceId = adapterInfo.deviceID;
        g.adapterName = fromStringView(adapterInfo.device);
        g.backendName = adapterInfo.backendType == WGPUBackendType_Vulkan ? "Vulkan" : "WebGPU";
        std::string lower = g.adapterName;
        std::transform(lower.begin(), lower.end(), lower.begin(),
            [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        g.isRtx = g.vendorId == 0x10de && lower.find("rtx") != std::string::npos;
        wgpuAdapterInfoFreeMembers(adapterInfo);
    }

    WGPUDeviceDescriptor descriptor{};
    descriptor.label = stringView("RuneLite WebGPU device");
    WGPUSupportedFeatures supported{};
    wgpuAdapterGetFeatures(g.adapter, &supported);
    std::vector<WGPUFeatureName> enabledFeatures;
    const auto nativeRayQueryFeature = static_cast<WGPUFeatureName>(WGPUNativeFeature_RayQuery);
    if (std::find(
            supported.features,
            supported.features + supported.featureCount,
            nativeRayQueryFeature) != supported.features + supported.featureCount) {
        enabledFeatures.push_back(nativeRayQueryFeature);
        g.rayQueryFeatureEnabled = true;
    }
    wgpuSupportedFeaturesFreeMembers(supported);
    descriptor.requiredFeatureCount = enabledFeatures.size();
    descriptor.requiredFeatures = enabledFeatures.data();
    WGPULimits limits = WGPU_LIMITS_INIT;
    if (wgpuAdapterGetLimits(g.adapter, &limits) == WGPUStatus_Success)
        descriptor.requiredLimits = &limits;
    const uint64_t callbackGeneration =
        g.deviceGeneration.fetch_add(1, std::memory_order_acq_rel) + 1u;
    void* const callbackGenerationUserdata = reinterpret_cast<void*>(
        static_cast<uintptr_t>(callbackGeneration));
    g.deviceLost.store(false, std::memory_order_release);
    g.uncapturedDeviceError.store(false, std::memory_order_release);
    g.uncapturedDeviceErrorMessage[0] = '\0';
    descriptor.uncapturedErrorCallbackInfo.userdata1 = callbackGenerationUserdata;
    descriptor.uncapturedErrorCallbackInfo.callback = [](
        WGPUDevice const*, WGPUErrorType, WGPUStringView message,
        void* userdata, void*) {
        const uint64_t generation = static_cast<uint64_t>(
            reinterpret_cast<uintptr_t>(userdata));
        if (generation != 0u &&
            g.deviceGeneration.load(std::memory_order_acquire) == generation) {
            size_t length = 0;
            if (message.data) {
                length = message.length == WGPU_STRLEN
                    ? std::strlen(message.data)
                    : message.length;
                length = std::min(length, sizeof(g.uncapturedDeviceErrorMessage) - 1u);
                std::memcpy(g.uncapturedDeviceErrorMessage, message.data, length);
            }
            g.uncapturedDeviceErrorMessage[length] = '\0';
            g.uncapturedDeviceError.store(true, std::memory_order_release);
        }
    };
    descriptor.deviceLostCallbackInfo.mode = WGPUCallbackMode_AllowProcessEvents;
    descriptor.deviceLostCallbackInfo.userdata1 = callbackGenerationUserdata;
    descriptor.deviceLostCallbackInfo.callback = [](
        WGPUDevice const*, WGPUDeviceLostReason reason, WGPUStringView,
        void* userdata, void*) {
        // Never take g.mutex or mutate std::string from this callback.  It can
        // be delivered by ProcessEvents while rlw_present_scene owns the mutex,
        // and setError used to introduce a data race here.
        if (reason == WGPUDeviceLostReason_Destroyed ||
            reason == WGPUDeviceLostReason_CallbackCancelled) return;
        const uint64_t generation = static_cast<uint64_t>(
            reinterpret_cast<uintptr_t>(userdata));
        if (generation != 0u &&
            g.deviceGeneration.load(std::memory_order_acquire) == generation) {
            g.deviceLost.store(true, std::memory_order_release);
        }
    };
    WGPURequestDeviceCallbackInfo deviceCallback{};
    deviceCallback.mode = WGPUCallbackMode_WaitAnyOnly;
    deviceCallback.callback = [](WGPURequestDeviceStatus status, WGPUDevice device,
                                 WGPUStringView message, void* userdata, void*) {
        if (status == WGPURequestDeviceStatus_Success)
            *static_cast<WGPUDevice*>(userdata) = device;
        else
            setError("requestDevice: " + fromStringView(message));
    };
    deviceCallback.userdata1 = &g.device;
    WGPUFuture deviceFuture = wgpuAdapterRequestDevice(g.adapter, &descriptor, deviceCallback);
    if (!g.device) waitFor(deviceFuture);
    if (!g.device) {
        setError("Unable to create the WebGPU device");
        return false;
    }
    g.queue = wgpuDeviceGetQueue(g.device);
    if (!g.queue) return false;

    WGPUVulkanContextInfo nativeContext{};
    if (wgpuDeviceGetNativeVulkanContext(g.device, &nativeContext) == WGPUStatus_Success) {
        rayQueryBridgeAttachVulkan(RayQueryVulkanContext{
            nativeContext.instance,
            nativeContext.physicalDevice,
            nativeContext.device,
            nativeContext.queue,
            nativeContext.queueFamilyIndex,
            nativeContext.queueIndex,
            g.rayQueryFeatureEnabled,
        });
        streamlineAttachVulkan(StreamlineVulkanContext{
            nativeContext.instance,
            nativeContext.physicalDevice,
            nativeContext.device,
            nativeContext.queue,
            nativeContext.queueFamilyIndex,
            nativeContext.queueIndex,
        });
        g.nativeInteropActive = true;
    }
    return true;
}

bool selectSurfaceFormat() {
    WGPUSurfaceCapabilities capabilities{};
    if (wgpuSurfaceGetCapabilities(g.surface, g.adapter, &capabilities) != WGPUStatus_Success) {
        setError("Unable to query WebGPU surface capabilities");
        return false;
    }
    if (capabilities.formatCount == 0) {
        wgpuSurfaceCapabilitiesFreeMembers(capabilities);
        setError("The RuneLite canvas has no WebGPU surface formats");
        return false;
    }
    g.surfaceFormat = capabilities.formats[0];
    for (size_t i = 0; i < capabilities.formatCount; ++i)
        if (capabilities.formats[i] == WGPUTextureFormat_BGRA8Unorm)
            g.surfaceFormat = WGPUTextureFormat_BGRA8Unorm;
    if (capabilities.alphaModeCount > 0) g.alphaMode = capabilities.alphaModes[0];
    for (size_t i = 0; i < capabilities.presentModeCount; ++i)
        g.immediatePresentSupported |= capabilities.presentModes[i] == WGPUPresentMode_Immediate;
    wgpuSurfaceCapabilitiesFreeMembers(capabilities);
    return true;
}

} // namespace

extern "C" {

int rlw_create(void* canvasHwnd, int width, int height) {
    std::lock_guard<std::mutex> lock(g.mutex);
    releaseRuntime();
    g.animationStart = std::chrono::steady_clock::now();
    g.error.clear();
    g.width = static_cast<uint32_t>(std::max(1, width));
    g.height = static_cast<uint32_t>(std::max(1, height));
    if (!canvasHwnd) {
        setError("RuneLite canvas HWND is null");
        return 0;
    }
    if (!createInstanceAndSurface(static_cast<HWND>(canvasHwnd)) ||
        !requestAdapterAndDevice() || !selectSurfaceFormat() || !configureSurface()) {
        releaseRuntime();
        return 0;
    }
    return 1;
}

void rlw_resize(int width, int height) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return;
    const uint32_t resizedWidth = static_cast<uint32_t>(std::max(1, width));
    const uint32_t resizedHeight = static_cast<uint32_t>(std::max(1, height));
    if (!g.surface || !g.device) {
        g.width = resizedWidth;
        g.height = resizedHeight;
        return;
    }

    const StreamlineFeatureState featureState = streamlineFeatureState();
    const bool temporalResourcesTagged =
        g.dlssRequest.mode != StreamlineDLSSMode::Off ||
        g.dlssNeuralRenderingRequested || featureState.dlssConfigured ||
        featureState.rayReconstructionConfigured ||
        featureState.neuralRenderingConfigured;
    if (temporalResourcesTagged && !streamlineReleaseViewport(0)) {
        setError("RuneLite resize was deferred because Streamline could not flush "
                 "the active RTX/DLSS viewport safely");
        return;
    }

    g.width = resizedWidth;
    g.height = resizedHeight;
    if (g.dlssRequest.mode != StreamlineDLSSMode::Off) {
            StreamlineDLSSOptions resized = g.dlssRequest;
            resized.outputWidth = g.width;
            resized.outputHeight = g.height;
            if (streamlineRequestFeatures(
                    resized, false, g.dlssRayReconstructionRequested)) {
                const StreamlineFeatureState state = streamlineFeatureState();
                if (state.dlssConfigured && state.renderWidth && state.renderHeight &&
                    state.outputWidth == g.width && state.outputHeight == g.height) {
                    g.dlssRequest = resized;
                    g.dlssRenderWidth = state.renderWidth;
                    g.dlssRenderHeight = state.renderHeight;
                    g.dlssOutputWidth = state.outputWidth;
                    g.dlssOutputHeight = state.outputHeight;
                } else {
                    (void)disableDlssConfiguration();
                    setError("DLSS could not be reconfigured for the resized RuneLite surface");
                }
            } else {
                (void)disableDlssConfiguration();
                setError("Streamline rejected the resized DLSS output dimensions");
            }
    } else {
        g.dlssRequest.outputWidth = g.width;
        g.dlssRequest.outputHeight = g.height;
    }
    releaseDepthResources();
    configureSurface();
    g.temporalResetRequested = true;
}

void rlw_set_vsync(int enabled) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return;
    g.vsync = enabled != 0;
    if (g.surface && g.device) configureSurface();
}

void rlw_set_rtx_enabled(int enabled) {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.rtxShadowsEnabled = enabled != 0;
}

void rlw_set_rtx_dynamic_shadows(int enabled) {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.rtxDynamicShadowsEnabled = enabled != 0;
}

void rlw_set_rtx_reflections(int enabled) {
    std::lock_guard<std::mutex> lock(g.mutex);
    g.rtxReflectionsEnabled = enabled != 0;
}

int rlw_set_visual_settings(const RLWVisualSettings* settings) {
    if (!settings || settings->struct_size < sizeof(RLWVisualSettings)) return 0;
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (!std::isfinite(settings->render_scale) ||
        !std::isfinite(settings->normal_strength) ||
        !std::isfinite(settings->saturation) ||
        !std::isfinite(settings->contrast) ||
        !std::isfinite(settings->ambient_intensity) ||
        !std::isfinite(settings->exposure) ||
        !std::isfinite(settings->time_of_day) ||
        !std::isfinite(settings->weather_intensity) ||
        !std::isfinite(settings->wind_strength) ||
        !std::isfinite(settings->water_wave_strength) ||
        !std::isfinite(settings->water_clarity) ||
        !std::isfinite(settings->rtx_shadow_strength) ||
        !std::isfinite(settings->rtx_reflection_strength) ||
        !std::isfinite(settings->dlss_sharpness) ||
        !std::isfinite(settings->model_smoothing_strength)) {
        setError("Invalid WebGPU visual settings");
        return 0;
    }
    const float oldRenderScale = g.visual.renderScale;
    const float newRenderScale = std::clamp(settings->render_scale, 0.5f, 1.0f);
    const bool renderScaleChanged =
        std::abs(oldRenderScale - newRenderScale) > 0.001f;
    if (g.device && renderScaleChanged && g.dlssRenderWidth == 0u) {
        const StreamlineFeatureState featureState = streamlineFeatureState();
        if ((g.dlssNeuralRenderingRequested ||
             featureState.neuralRenderingConfigured) &&
            !streamlineReleaseViewport(0)) {
            setError("Internal resolution change was deferred because DLSS 5 "
                     "Neural Rendering could not flush its active viewport safely");
            return 0;
        }
    }
    g.visual.styleMode = std::min(settings->style_mode, 2u);
    g.visual.renderScale = newRenderScale;
    g.visual.normalStrength = std::clamp(settings->normal_strength, 0.0f, 2.0f);
    g.visual.saturation = std::clamp(settings->saturation, 0.0f, 2.0f);
    g.visual.contrast = std::clamp(settings->contrast, 0.5f, 1.5f);
    g.visual.ambientIntensity = std::clamp(settings->ambient_intensity, 0.05f, 1.0f);
    g.visual.exposure = std::clamp(settings->exposure, 0.25f, 2.0f);
    g.visual.timeOfDay = std::clamp(settings->time_of_day, 0.0f, 24.0f);
    g.visual.texturesEnabled = settings->textures_enabled != 0;
    g.visual.weatherMode = static_cast<uint32_t>(
        std::clamp(settings->weather_mode, 0, 4));
    g.visual.weatherIntensity = std::clamp(settings->weather_intensity, 0.0f, 1.0f);
    g.visual.windStrength = std::clamp(settings->wind_strength, 0.0f, 1.0f);
    g.visual.waterQuality = static_cast<uint32_t>(
        std::clamp(settings->water_quality, 0, 2));
    g.visual.waterWaveStrength = std::clamp(settings->water_wave_strength, 0.0f, 2.0f);
    g.visual.waterClarity = std::clamp(settings->water_clarity, 0.0f, 1.0f);
    g.visual.rtxShadowStrength = std::clamp(settings->rtx_shadow_strength, 0.0f, 1.0f);
    g.visual.rtxReflectionStrength = std::clamp(settings->rtx_reflection_strength, 0.0f, 1.0f);
    g.visual.rtxReflectionQuality = std::min(
        static_cast<uint32_t>(settings->rtx_reflection_quality), 2u);
    g.visual.dlssSharpness = std::clamp(settings->dlss_sharpness, 0.0f, 1.0f);
    g.visual.smoothModelShading = settings->smooth_model_shading != 0;
    g.visual.modelSmoothingStrength = std::clamp(
        settings->model_smoothing_strength, 0.0f, 1.0f);
    if (g.device && renderScaleChanged && g.dlssRenderWidth == 0u)
        releaseDepthResources();
    return 1;
}

int rlw_clear(float red, float green, float blue, float alpha) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (!g.configured) {
        setError("WebGPU surface is not configured");
        return 0;
    }
    WGPUSurfaceTexture surfaceTexture{};
    wgpuSurfaceGetCurrentTexture(g.surface, &surfaceTexture);
    if ((surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal &&
         surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal) ||
        !surfaceTexture.texture) {
        setError("Unable to acquire the WebGPU swapchain texture");
        return 0;
    }
    WGPUTextureView view = wgpuTextureCreateView(surfaceTexture.texture, nullptr);
    WGPUCommandEncoder encoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
    WGPURenderPassColorAttachment color{};
    color.view = view;
    color.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
    color.loadOp = WGPULoadOp_Clear;
    color.storeOp = WGPUStoreOp_Store;
    color.clearValue = WGPUColor{red, green, blue, alpha};
    WGPURenderPassDescriptor passDescriptor{};
    passDescriptor.colorAttachmentCount = 1;
    passDescriptor.colorAttachments = &color;
    WGPURenderPassEncoder pass = wgpuCommandEncoderBeginRenderPass(encoder, &passDescriptor);
    wgpuRenderPassEncoderEnd(pass);
    wgpuRenderPassEncoderRelease(pass);
    WGPUCommandBuffer command = wgpuCommandEncoderFinish(encoder, nullptr);
    wgpuQueueSubmit(g.queue, 1, &command);
    wgpuSurfacePresent(g.surface);
    wgpuCommandBufferRelease(command);
    wgpuCommandEncoderRelease(encoder);
    wgpuTextureViewRelease(view);
    wgpuTextureRelease(surfaceTexture.texture);
    wgpuDevicePoll(g.device, 0, nullptr);
    wgpuInstanceProcessEvents(g.instance);
    if (stopForDeviceLoss()) return 0;
    return 1;
}

int rlw_present_bgra(const uint32_t* pixels, int width, int height) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (!g.configured || !pixels || width <= 0 || height <= 0) {
        setError("Invalid RuneLite interface frame");
        return 0;
    }
    const uint32_t sourceWidth = static_cast<uint32_t>(width);
    const uint32_t sourceHeight = static_cast<uint32_t>(height);
    if (!g.uiPipeline || g.uiWidth != sourceWidth || g.uiHeight != sourceHeight) {
        if (!createUiResources(sourceWidth, sourceHeight)) {
            setError("Unable to create the WebGPU interface pipeline");
            return 0;
        }
    }

    const uint32_t sourceRowBytes = sourceWidth * sizeof(uint32_t);
    const uint32_t uploadRowBytes = (sourceRowBytes + 255u) & ~255u;
    g.uploadPixels.assign(static_cast<size_t>(uploadRowBytes) * sourceHeight, 0);
    const auto* sourceBytes = reinterpret_cast<const uint8_t*>(pixels);
    for (uint32_t row = 0; row < sourceHeight; ++row) {
        std::memcpy(
            g.uploadPixels.data() + static_cast<size_t>(row) * uploadRowBytes,
            sourceBytes + static_cast<size_t>(row) * sourceRowBytes,
            sourceRowBytes
        );
    }
    WGPUTexelCopyTextureInfo destination{};
    destination.texture = g.uiTexture;
    WGPUTexelCopyBufferLayout layout{};
    layout.bytesPerRow = uploadRowBytes;
    layout.rowsPerImage = sourceHeight;
    WGPUExtent3D uploadExtent{sourceWidth, sourceHeight, 1};
    wgpuQueueWriteTexture(
        g.queue,
        &destination,
        g.uploadPixels.data(),
        g.uploadPixels.size(),
        &layout,
        &uploadExtent
    );

    WGPUSurfaceTexture surfaceTexture{};
    wgpuSurfaceGetCurrentTexture(g.surface, &surfaceTexture);
    if ((surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal &&
         surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal) ||
        !surfaceTexture.texture) {
        setError("Unable to acquire the WebGPU swapchain texture for the interface");
        return 0;
    }
    WGPUTextureView surfaceView = wgpuTextureCreateView(surfaceTexture.texture, nullptr);
    WGPUCommandEncoder encoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
    WGPURenderPassColorAttachment color{};
    color.view = surfaceView;
    color.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
    color.loadOp = WGPULoadOp_Clear;
    color.storeOp = WGPUStoreOp_Store;
    color.clearValue = WGPUColor{0.025, 0.045, 0.085, 1.0};
    WGPURenderPassDescriptor passDescriptor{};
    passDescriptor.colorAttachmentCount = 1;
    passDescriptor.colorAttachments = &color;
    WGPURenderPassEncoder pass = wgpuCommandEncoderBeginRenderPass(encoder, &passDescriptor);
    wgpuRenderPassEncoderSetPipeline(pass, g.uiPipeline);
    wgpuRenderPassEncoderSetBindGroup(pass, 0, g.uiBindGroup, 0, nullptr);
    wgpuRenderPassEncoderDraw(pass, 3, 1, 0, 0);
    wgpuRenderPassEncoderEnd(pass);
    wgpuRenderPassEncoderRelease(pass);
    WGPUCommandBuffer command = wgpuCommandEncoderFinish(encoder, nullptr);
    wgpuQueueSubmit(g.queue, 1, &command);
    wgpuSurfacePresent(g.surface);
    wgpuCommandBufferRelease(command);
    wgpuCommandEncoderRelease(encoder);
    wgpuTextureViewRelease(surfaceView);
    wgpuTextureRelease(surfaceTexture.texture);
    wgpuDevicePoll(g.device, 0, nullptr);
    wgpuInstanceProcessEvents(g.instance);
    if (stopForDeviceLoss()) return 0;
    return 1;
}

int rlw_upload_texture_atlas(
    const uint32_t* rgbaPixels,
    const uint32_t* vanillaRgbaPixels,
    const uint32_t* normalPixels,
    const float* materialParameters,
    int width,
    int height,
    int tileSize,
    int textureCount) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (!g.configured || !rgbaPixels || !vanillaRgbaPixels || !normalPixels ||
        !materialParameters ||
        width <= 0 || height <= 0 || tileSize <= 0 || textureCount <= 0) {
        setError("Invalid RuneLite texture atlas");
        return 0;
    }
    if (!createSceneResources() ||
        !createSceneTextureAtlas(
            rgbaPixels,
            vanillaRgbaPixels,
            normalPixels,
            materialParameters,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            static_cast<uint32_t>(tileSize),
            static_cast<uint32_t>(textureCount))) {
        setError("Unable to create the RuneLite texture atlas");
        return 0;
    }
    return 1;
}

int rlw_set_static_sector_manifest(
    const uint64_t* sectorIds,
    const uint32_t* firstVertices,
    const uint32_t* vertexCounts,
    const uint32_t* flags,
    uint32_t count) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    constexpr uint32_t kMaximumManifestSectors = 1024u;
    constexpr uint32_t kMaximumStaticVertices = 7'199'997u;
    if (count > kMaximumManifestSectors ||
        (count > 0u &&
         (!sectorIds || !firstVertices || !vertexCounts || !flags))) {
        setError("Invalid static-sector manifest arrays");
        return 0;
    }

    std::vector<StaticSectorRange> manifest;
    manifest.reserve(count);
    std::unordered_set<uint64_t> ids;
    ids.reserve(count);
    for (uint32_t index = 0u; index < count; ++index) {
        const uint32_t firstVertex = firstVertices[index];
        const uint32_t vertexCount = vertexCounts[index];
        if (vertexCount == 0u || firstVertex % 3u != 0u ||
            vertexCount % 3u != 0u || firstVertex > kMaximumStaticVertices ||
            vertexCount > kMaximumStaticVertices - firstVertex ||
            (flags[index] & ~1u) != 0u || !ids.insert(sectorIds[index]).second) {
            setError("Static-sector manifest contains an invalid, overlapping, or duplicate range");
            return 0;
        }
        manifest.push_back({sectorIds[index], firstVertex, vertexCount,
                            (flags[index] & 1u) != 0u});
    }
    std::sort(manifest.begin(), manifest.end(),
              [](const StaticSectorRange& left,
                 const StaticSectorRange& right) {
                  if (left.firstVertex != right.firstVertex)
                      return left.firstVertex < right.firstVertex;
                  return left.id < right.id;
              });
    uint32_t previousEnd = 0u;
    bool first = true;
    for (const auto& range : manifest) {
        if (!first && range.firstVertex < previousEnd) {
            setError("Static-sector manifest ranges overlap");
            return 0;
        }
        previousEnd = range.firstVertex + range.vertexCount;
        first = false;
    }

    const bool enablingManifest = !g.staticSectorManifestEnabled;
    const bool changed = enablingManifest ||
        manifest != g.staticSectorManifest;
    g.staticSectorManifest = std::move(manifest);
    g.staticSectorManifestEnabled = true;
    if (changed) {
        g.staticSectorManifestChanged = true;
        // Sector retirement/addition changes visibility but not the retained
        // vertex prefix. Reset temporal accumulation without forcing a static
        // buffer upload or an RTX scene epoch.
        g.temporalResetRequested = true;
    }
    if (enablingManifest && g.raySceneVertexCount != 0u) {
        // A legacy aggregate BLAS has synthetic IDs and cannot be reconciled
        // with the first real manifest. Rebuild RTX once while preserving the
        // already-uploaded raster prefix; subsequent visibility changes are
        // mask-only TLAS updates.
        g.raySceneRevision = UINT64_MAX;
    }
    return 1;
}

int rlw_present_scene(
    const void* packedVertices,
    uint32_t vertexCount,
    uint32_t staticVertexCount,
    uint64_t staticRevision,
    const float* lightRecords,
    uint32_t lightCount,
    const float* viewProjection,
    const float* cameraViewToClip,
    const float* cameraPosition,
    int viewportX,
    int viewportY,
    int viewportWidth,
    int viewportHeight,
    uint32_t clearColor,
    const uint32_t* interfacePixels,
    int interfaceWidth,
    int interfaceHeight) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (!g.configured || (vertexCount > 0u && !packedVertices) || !viewProjection ||
        !cameraViewToClip || !cameraPosition || staticVertexCount > vertexCount ||
        lightCount > 8u || (lightCount > 0u && !lightRecords) ||
        !interfacePixels || interfaceWidth <= 0 || interfaceHeight <= 0 ||
        viewportWidth <= 0 || viewportHeight <= 0) {
        setError("Invalid RuneLite WebGPU scene frame");
        return 0;
    }
    if (!createSceneResources() ||
        !uploadInterfacePixels(interfacePixels, static_cast<uint32_t>(interfaceWidth),
                               static_cast<uint32_t>(interfaceHeight))) {
        setError("Unable to prepare RuneLite WebGPU scene resources");
        return 0;
    }

    const auto* vertices = static_cast<const PackedSceneVertex*>(packedVertices);
    g.lastDynamicInputHash = 14695981039346656037ull;
    const auto* dynamicBytes = reinterpret_cast<const uint8_t*>(vertices + staticVertexCount);
    const size_t dynamicByteCount =
        static_cast<size_t>(vertexCount - staticVertexCount) * sizeof(PackedSceneVertex);
    for (size_t index = 0; index < dynamicByteCount; ++index) {
        g.lastDynamicInputHash ^= dynamicBytes[index];
        g.lastDynamicInputHash *= 1099511628211ull;
    }
    const uint64_t vertexBytes = static_cast<uint64_t>(vertexCount) * 48u;
    if (vertexCount > 0 && !ensureSceneVertexCapacity(vertexBytes)) {
        setError("Unable to allocate RuneLite WebGPU scene vertex buffer");
        return 0;
    }
    const uint32_t previousStaticVertexCount = g.uploadedStaticVertexCount;
    const uint32_t staticEpoch = static_cast<uint32_t>(staticRevision >> 32u);
    const uint32_t uploadedStaticEpoch =
        static_cast<uint32_t>(g.uploadedStaticRevision >> 32u);
    const bool staticNeverUploaded = g.uploadedStaticRevision == UINT64_MAX;
    const bool sameStaticEpoch = !staticNeverUploaded && staticEpoch == uploadedStaticEpoch;
    const bool staticAppend = sameStaticEpoch &&
        staticVertexCount > previousStaticVertexCount;
    const bool staticReset = staticNeverUploaded || !sameStaticEpoch ||
        staticVertexCount < previousStaticVertexCount;
    const bool temporalOnlyStaticRevision = sameStaticEpoch &&
        staticVertexCount == previousStaticVertexCount &&
        staticRevision != g.uploadedStaticRevision;
    bool staticChanged = staticAppend || staticReset;
    const uint32_t dynamicVertexCount = vertexCount - staticVertexCount;
    bool cameraCut = !g.hasPreviousCameraPosition;
    if (g.hasPreviousCameraPosition) {
        const float dx = cameraPosition[0] - g.previousCameraPosition[0];
        const float dy = cameraPosition[1] - g.previousCameraPosition[1];
        const float dz = cameraPosition[2] - g.previousCameraPosition[2];
        cameraCut = dx * dx + dy * dy + dz * dz > 512.0f * 512.0f;
    }
    if (staticReset || temporalOnlyStaticRevision || cameraCut ||
        g.previousDynamicVertexCount != dynamicVertexCount) {
        // Dynamic vertices currently have ordinal history rather than stable
        // object IDs. Resetting when topology changes is conservative, but it
        // prevents stale actors from being accumulated into DLSS history.
        g.temporalResetRequested = true;
    }
    if (staticAppend) {
        g.staticFadeStartVertex = previousStaticVertexCount;
        g.staticFadeEndVertex = staticVertexCount;
        g.staticFadeStartFrame = g.frameIndex;
    } else if (staticReset) {
        g.staticFadeStartVertex = 0;
        g.staticFadeEndVertex = 0;
        g.staticFadeStartFrame = g.frameIndex;
    }
    if (vertexCount > 0) {
        g.uploadVertices.resize(static_cast<size_t>(vertexBytes));
        g.currentScenePositions.resize(static_cast<size_t>(vertexCount) * 3u);
        const uint32_t firstStaticUpdate = staticReset ? 0u
            : staticAppend ? previousStaticVertexCount : staticVertexCount;
        const uint32_t firstUpdatedVertex = staticChanged
            ? firstStaticUpdate : staticVertexCount;
        for (uint32_t vertex = firstUpdatedVertex; vertex < vertexCount; ++vertex) {
            uint8_t* destination = g.uploadVertices.data() + static_cast<size_t>(vertex) * 48u;
            const PackedSceneVertex& source = vertices[vertex];
            float* currentPosition = g.currentScenePositions.data() + static_cast<size_t>(vertex) * 3u;
            std::memcpy(currentPosition, source.position, sizeof(source.position));
            std::memcpy(destination, source.position, sizeof(source.position));
            const uint32_t dynamicVertexCount = vertexCount - staticVertexCount;
            const float* previousPosition = vertex >= staticVertexCount && !staticReset &&
                    g.previousDynamicVertexCount == dynamicVertexCount
                ? g.previousDynamicPositions.data() +
                    static_cast<size_t>(vertex - staticVertexCount) * 3u
                : source.position;
            std::memcpy(destination + 12u, previousPosition, sizeof(float) * 3u);
            std::memcpy(destination + 24u, &source.color, sizeof(source.color));
            std::memcpy(destination + 28u, source.uv, sizeof(source.uv));
            std::memcpy(destination + 36u, &source.textureId, sizeof(source.textureId));
            std::memcpy(destination + 40u, &source.sourceFlags, sizeof(source.sourceFlags));
            std::memcpy(destination + 44u, &source.vanillaColor, sizeof(source.vanillaColor));
        }
        if (staticChanged && staticVertexCount > firstStaticUpdate) {
            const uint64_t staticOffset = static_cast<uint64_t>(firstStaticUpdate) * 48u;
            const uint64_t staticBytes =
                static_cast<uint64_t>(staticVertexCount - firstStaticUpdate) * 48u;
            wgpuQueueWriteBuffer(
                g.queue, g.sceneVertexBuffer, staticOffset,
                g.uploadVertices.data() + static_cast<size_t>(staticOffset), staticBytes);
        }
        if (vertexCount > staticVertexCount) {
            const uint64_t dynamicOffset = static_cast<uint64_t>(staticVertexCount) * 48u;
            const uint64_t dynamicBytes = vertexBytes - dynamicOffset;
            wgpuQueueWriteBuffer(
                g.queue, g.sceneVertexBuffer, dynamicOffset,
                g.uploadVertices.data() + static_cast<size_t>(dynamicOffset), dynamicBytes);
        }
    }
    if (staticChanged || temporalOnlyStaticRevision) {
        g.uploadedStaticRevision = staticRevision;
        g.uploadedStaticVertexCount = staticVertexCount;
    }
    if (staticReset) releaseStaticDrawRanges();
    auto prepareStaticRange = [&](uint32_t firstVertex, uint32_t count) {
        auto [entry, inserted] = g.staticDrawRanges.try_emplace(firstVertex);
        if (!inserted && entry->second.vertexCount == count) return true;
        if (prepareSceneRange(entry->second, vertices, firstVertex, count)) return true;
        // A failed allocation must be retried, never treated as a cached range.
        entry->second.vertexCount = UINT32_MAX;
        return false;
    };
    bool drawRangesReady = true;
    if (g.staticSectorManifestEnabled) {
        for (const auto& range : g.staticSectorManifest) {
            if (range.active && range.firstVertex + range.vertexCount <= staticVertexCount)
                drawRangesReady = prepareStaticRange(range.firstVertex, range.vertexCount)
                    && drawRangesReady;
        }
    } else if (staticVertexCount > 0u) {
        drawRangesReady = prepareStaticRange(0u, staticVertexCount);
    }
    drawRangesReady = prepareSceneRange(g.dynamicDrawRange, vertices,
        staticVertexCount, dynamicVertexCount) && drawRangesReady;
    if (!drawRangesReady) {
        setError("Unable to prepare opaque/water draw indices");
        return 0;
    }
    const StreamlineFeatureState streamlineState = streamlineFeatureState();
    const bool dlssFrameActive = streamlineState.dlssConfigured;
    const bool neuralRenderingFrameActive =
        g.dlssNeuralRenderingRequested && streamlineState.neuralRenderingSupported;
    const bool streamlineFrameActive = dlssFrameActive || neuralRenderingFrameActive ||
        g.dlssNeuralRenderingReleasePending;
    const RenderViewport renderViewport = scaleViewportToTarget(
        viewportX, viewportY, viewportWidth, viewportHeight,
        interfaceWidth, interfaceHeight, sceneWidth(), sceneHeight());
    TemporalJitter frameJitter{};
    std::array<float, 16> jitteredViewProjection{};
    const float* rasterViewProjection = viewProjection;
    if (dlssFrameActive) {
        if (g.temporalResetRequested || !g.hasPreviousViewProjection)
            g.temporalJitterIndex = 0;
        frameJitter = makeTemporalJitter(
            g.temporalJitterIndex,
            static_cast<float>(renderViewport.width),
            static_cast<float>(renderViewport.height),
            sceneWidth(), sceneHeight(), g.dlssOutputWidth, g.dlssOutputHeight);
        applyClipSpaceJitter(viewProjection, frameJitter, jitteredViewProjection.data());
        rasterViewProjection = jitteredViewProjection.data();
    } else {
        g.temporalJitterIndex = 0;
    }

    float uniforms[64]{};
    std::memcpy(uniforms, rasterViewProjection, sizeof(float) * 16u);
    std::memcpy(uniforms + 16,
                g.hasPreviousViewProjection ? g.previousViewProjection : rasterViewProjection,
                sizeof(float) * 16u);
    uniforms[32] = static_cast<float>(g.sceneTextureWidth);
    uniforms[33] = static_cast<float>(g.sceneTextureHeight);
    uniforms[34] = static_cast<float>(g.sceneTextureTileSize);
    uniforms[35] = static_cast<float>(g.sceneTextureCount);
    const float frameAnimationSeconds = animationSeconds();
    uniforms[36] = frameAnimationSeconds;
    uniforms[37] = static_cast<float>(g.frameIndex);
    const float staticSectorFade = std::clamp(
        static_cast<float>(g.frameIndex - g.staticFadeStartFrame) / 15.0f,
        0.0f, 1.0f);
    uniforms[38] = staticSectorFade < 1.0f
        ? static_cast<float>(g.staticFadeStartVertex) : 0.0f;
    uniforms[39] = staticSectorFade;
    uniforms[40] = g.visual.normalStrength;
    uniforms[41] = g.visual.saturation;
    uniforms[42] = g.visual.contrast;
    uniforms[43] = g.visual.exposure;
    const float sunAngle = (g.visual.timeOfDay - 6.0f) *
        (2.0f * 3.14159265358979323846f / 24.0f);
    const float sunElevation = std::sin(sunAngle);
    uniforms[44] = g.visual.ambientIntensity;
    uniforms[45] = std::cos(sunAngle) * 0.72f;
    uniforms[46] = -sunElevation;
    uniforms[47] = 0.42f;
    uniforms[48] = g.visual.texturesEnabled ? 1.0f : 0.0f;
    uniforms[49] = static_cast<float>(g.visual.styleMode);
    uniforms[50] = std::clamp((sunElevation + 0.08f) / 0.24f, 0.0f, 1.0f);
    // Default-on Advanced Model Perfection. This is deliberately a shader
    // strength rather than geometry subdivision, so animation/click geometry
    // remains byte-for-byte identical. A future UI control can feed this slot.
    uniforms[51] = g.visual.smoothModelShading
        ? g.visual.modelSmoothingStrength : 0.0f;
    const uint32_t weatherMode = resolvedWeatherMode();
    const float weatherIntensity = resolvedWeatherIntensity();
    const float precipitation = weatherMode >= 2u
        ? weatherIntensity
        : 0.0f;
    const float weatherDimming = std::clamp(
        1.0f - weatherIntensity *
            (weatherMode >= 3u ? 0.55f
             : weatherMode >= 2u ? 0.42f
             : weatherMode >= 1u ? 0.28f : 0.0f),
        0.35f, 1.0f);
    uniforms[52] = static_cast<float>(weatherMode);
    uniforms[53] = weatherIntensity;
    uniforms[54] = g.visual.windStrength;
    uniforms[55] = static_cast<float>(g.visual.waterQuality);
    uniforms[56] = g.visual.waterWaveStrength;
    uniforms[57] = g.visual.waterClarity;
    uniforms[58] = precipitation;
    uniforms[59] = weatherDimming;
    std::memcpy(uniforms + 60u, cameraPosition, sizeof(float) * 3u);
    uniforms[63] = staticSectorFade < 1.0f
        ? static_cast<float>(g.staticFadeEndVertex) : 0.0f;
    wgpuQueueWriteBuffer(g.queue, g.sceneUniformBuffer, 0, uniforms, sizeof(uniforms));

    // The final shader also serves as a linear-HDR sky prepass.  Update all
    // three option buffers with the same camera-correct inverse transform so
    // raster, RTX, Neural Rendering and DLSS receive one coherent sky image.
    float finalUniforms[32]{};
    if (!invertMatrix(rasterViewProjection, finalUniforms + 4u)) {
        finalUniforms[4] = finalUniforms[9] = finalUniforms[14] = finalUniforms[19] = 1.0f;
    }
    std::memcpy(finalUniforms + 20u, cameraPosition, sizeof(float) * 3u);
    finalUniforms[23] = g.visual.contrast;
    finalUniforms[24] = std::cos(sunAngle) * 0.72f;
    finalUniforms[25] = -sunElevation;
    finalUniforms[26] = 0.42f;
    finalUniforms[27] = g.visual.timeOfDay;
    finalUniforms[1] = g.visual.dlssSharpness;
    finalUniforms[2] = dlssFrameActive ? 1.0f : 0.0f;
    finalUniforms[3] = g.visual.saturation;
    finalUniforms[28] = static_cast<float>(weatherMode);
    finalUniforms[29] = weatherIntensity;
    finalUniforms[30] = g.visual.windStrength;
    finalUniforms[31] = frameAnimationSeconds;
    float passthroughUniforms[32]{};
    float skyUniforms[32]{};
    std::memcpy(passthroughUniforms, finalUniforms, sizeof(finalUniforms));
    std::memcpy(skyUniforms, finalUniforms, sizeof(finalUniforms));
    passthroughUniforms[0] = 1.0f;
    skyUniforms[0] = 2.0f;
    wgpuQueueWriteBuffer(g.queue, g.finalToneMapUniformBuffer, 0,
                         finalUniforms, sizeof(finalUniforms));
    wgpuQueueWriteBuffer(g.queue, g.finalPassthroughUniformBuffer, 0,
                         passthroughUniforms, sizeof(passthroughUniforms));
    wgpuQueueWriteBuffer(g.queue, g.finalSkyUniformBuffer, 0,
                         skyUniforms, sizeof(skyUniforms));

    WGPUSurfaceTexture surfaceTexture{};
    wgpuSurfaceGetCurrentTexture(g.surface, &surfaceTexture);
    if ((surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessOptimal &&
         surfaceTexture.status != WGPUSurfaceGetCurrentTextureStatus_SuccessSuboptimal) ||
        !surfaceTexture.texture) {
        setError("Unable to acquire the WebGPU swapchain texture for the scene");
        return 0;
    }
    WGPUTextureView surfaceView = wgpuTextureCreateView(surfaceTexture.texture, nullptr);
    if (!surfaceView) {
        const bool deviceHealthy = processDeviceEventsAndCheckForLoss();
        if (deviceHealthy) {
            wgpuTextureRelease(surfaceTexture.texture);
            setError("Unable to create the WebGPU swapchain texture view");
        }
        return 0;
    }

    auto releaseSurfaceResources = [&]() {
        if (surfaceView) {
            wgpuTextureViewRelease(surfaceView);
            surfaceView = nullptr;
        }
        if (surfaceTexture.texture) {
            wgpuTextureRelease(surfaceTexture.texture);
            surfaceTexture.texture = nullptr;
        }
    };
    auto failFrame = [&](const char* fallbackMessage) -> int {
        // Once wgpu-native has reported a device/validation failure, even
        // releasing frame-local WebGPU handles crosses the same invalid native
        // device. Leave those opaque references for process teardown and make
        // no further GPU calls from this frame.
        if (g.deviceLost.load(std::memory_order_acquire) ||
            g.uncapturedDeviceError.load(std::memory_order_acquire)) {
            stopForDeviceLoss();
            return 0;
        }
        const bool deviceHealthy = processDeviceEventsAndCheckForLoss();
        if (!deviceHealthy) return 0;
        if (fallbackMessage) setError(fallbackMessage);
        releaseSurfaceResources();
        return 0;
    };
    auto failEncoder = [&](WGPUCommandEncoder& frameEncoder,
                           const char* fallbackMessage) -> int {
        if (g.deviceLost.load(std::memory_order_acquire) ||
            g.uncapturedDeviceError.load(std::memory_order_acquire)) {
            frameEncoder = nullptr;
            stopForDeviceLoss();
            return 0;
        }
        if (!processDeviceEventsAndCheckForLoss()) {
            frameEncoder = nullptr;
            return 0;
        }
        if (frameEncoder) wgpuCommandEncoderRelease(frameEncoder);
        frameEncoder = nullptr;
        if (fallbackMessage) setError(fallbackMessage);
        releaseSurfaceResources();
        return 0;
    };
    auto submitEncoder = [&](WGPUCommandEncoder& frameEncoder,
                             const char* fallbackMessage) -> bool {
        if (!frameEncoder) {
            const bool deviceHealthy = processDeviceEventsAndCheckForLoss();
            if (deviceHealthy && fallbackMessage) setError(fallbackMessage);
            return false;
        }
        WGPUCommandBuffer frameCommand =
            wgpuCommandEncoderFinish(frameEncoder, nullptr);
        if (!frameCommand) {
            const bool deviceHealthy = processDeviceEventsAndCheckForLoss();
            if (deviceHealthy) {
                wgpuCommandEncoderRelease(frameEncoder);
                if (fallbackMessage) setError(fallbackMessage);
            }
            frameEncoder = nullptr;
            return false;
        }
        wgpuQueueSubmit(g.queue, 1, &frameCommand);
        if (!processDeviceEventsAndCheckForLoss()) {
            frameEncoder = nullptr;
            return false;
        }
        wgpuCommandBufferRelease(frameCommand);
        wgpuCommandEncoderRelease(frameEncoder);
        frameEncoder = nullptr;
        return true;
    };
    if (streamlineFrameActive) {
        streamlineFrameBegin(static_cast<uint32_t>(g.frameIndex));
        streamlineSimulationEnd();
        streamlineRenderSubmitBegin();
    }
    // wgpu-native intentionally forbids mixing WebGPU commands with its raw
    // Vulkan callback in one encoder. Keep AS construction, rasterization,
    // RTX and final composition in ordered queue submissions.  RTX shadows do
    // not enter Streamline's frame/present lifecycle.
    const bool rayTracingActive = g.rtxShadowsEnabled || g.rtxReflectionsEnabled;
    bool rayFrameReady = !rayTracingActive;
    if (rayTracingActive) {
        WGPUCommandEncoder buildEncoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
        if (!buildEncoder)
            return failFrame("Unable to create the RTX scene-build command encoder");
        const float* scenePositions = g.currentScenePositions.empty()
            ? nullptr
            : g.currentScenePositions.data();
        // The Java scene buffer is already bounded to the renderer's visible
        // draw distance.  Feed that complete visible static prefix to the TLAS;
        // truncating it made only the first portion of the screen participate
        // in RTX shadowing.
        uint32_t rayStaticVertexCount = staticVertexCount;
        rayStaticVertexCount -= rayStaticVertexCount % 3u;
        const bool staticRayReady = updateRayQueryScene(
            buildEncoder, vertices, scenePositions, rayStaticVertexCount,
            staticRevision);
        bool dynamicRayReady = true;
        if (staticRayReady && g.rtxDynamicShadowsEnabled) {
            dynamicRayReady = updateDynamicRayQueryScene(
                buildEncoder, vertices, scenePositions,
                staticVertexCount, vertexCount);
        } else if (staticRayReady && g.dynamicRayMeshActive) {
            // Remove a previously active dynamic BLAS while retaining the
            // static-world RTX scene.
            dynamicRayReady = updateDynamicRayQueryScene(
                buildEncoder, vertices, scenePositions,
                staticVertexCount, staticVertexCount);
        }
        const bool lightsReady = staticRayReady && dynamicRayReady &&
            updateRayQueryLights(buildEncoder, lightRecords, lightCount);
        rayFrameReady = staticRayReady && dynamicRayReady && lightsReady;
        if (!rayFrameReady && g.pendingRaySceneRetirementTicket == 0u)
            setError("RTX scene update failed; presenting the raster frame without "
                     "ray-query lighting or reflections");
        if (!submitEncoder(buildEncoder,
                           "Unable to submit the RTX scene-build command buffer"))
            return failFrame(nullptr);
    }

    WGPUCommandEncoder encoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
    if (!encoder)
        return failFrame("Unable to create the WebGPU scene command encoder");

    const WGPUColor skyColor{
        srgbToLinear(static_cast<double>((clearColor >> 16u) & 0xffu) / 255.0),
        srgbToLinear(static_cast<double>((clearColor >> 8u) & 0xffu) / 255.0),
        srgbToLinear(static_cast<double>(clearColor & 0xffu) / 255.0),
        0.0};

    // Render a real camera-relative sky into the HDR scene before geometry.
    // Doing this upstream means RTX reflections, DLSS Super Resolution and
    // Neural Rendering all see the same time-of-day environment instead of a
    // black clear color added only after upscaling.
    WGPURenderPassColorAttachment skyAttachment{};
    skyAttachment.view = g.hdrColorView;
    skyAttachment.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
    skyAttachment.loadOp = WGPULoadOp_Clear;
    skyAttachment.storeOp = WGPUStoreOp_Store;
    skyAttachment.clearValue = WGPUColor{0.0, 0.0, 0.0, 0.0};
    WGPURenderPassDescriptor skyPassDescriptor{};
    skyPassDescriptor.colorAttachmentCount = 1;
    skyPassDescriptor.colorAttachments = &skyAttachment;
    WGPURenderPassEncoder skyPass =
        wgpuCommandEncoderBeginRenderPass(encoder, &skyPassDescriptor);
    if (!skyPass)
        return failEncoder(encoder, "Unable to begin the procedural sky render pass");
    wgpuRenderPassEncoderSetViewport(
        skyPass,
        static_cast<float>(renderViewport.left),
        static_cast<float>(renderViewport.top),
        static_cast<float>(renderViewport.width),
        static_cast<float>(renderViewport.height), 0.0f, 1.0f);
    wgpuRenderPassEncoderSetPipeline(skyPass, g.neuralInputPipeline);
    wgpuRenderPassEncoderSetBindGroup(skyPass, 0, g.skyBindGroup, 0, nullptr);
    wgpuRenderPassEncoderDraw(skyPass, 3, 1, 0, 0);
    wgpuRenderPassEncoderEnd(skyPass);
    wgpuRenderPassEncoderRelease(skyPass);

    WGPURenderPassColorAttachment colorAttachments[5]{};
    colorAttachments[0].view = g.hdrColorView;
    colorAttachments[1].view = g.motionView;
    colorAttachments[2].view = g.normalRoughnessView;
    colorAttachments[3].view = g.diffuseAlbedoView;
    colorAttachments[4].view = g.specularAlbedoView;
    for (auto& color : colorAttachments) {
        color.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
        color.loadOp = WGPULoadOp_Clear;
        color.storeOp = WGPUStoreOp_Store;
        color.clearValue = WGPUColor{0.0, 0.0, 0.0, 0.0};
    }
    colorAttachments[0].loadOp = WGPULoadOp_Load;
    colorAttachments[2].clearValue = WGPUColor{0.0, 1.0, 0.0, 1.0};
    WGPURenderPassDepthStencilAttachment depth{};
    depth.view = g.depthView;
    depth.depthLoadOp = WGPULoadOp_Clear;
    depth.depthStoreOp = WGPUStoreOp_Store;
    depth.depthClearValue = 0.0f;
    depth.depthReadOnly = WGPU_FALSE;
    depth.stencilLoadOp = WGPULoadOp_Undefined;
    depth.stencilStoreOp = WGPUStoreOp_Undefined;
    depth.stencilReadOnly = WGPU_TRUE;
    WGPURenderPassDescriptor passDescriptor{};
    passDescriptor.colorAttachmentCount = 5;
    passDescriptor.colorAttachments = colorAttachments;
    passDescriptor.depthStencilAttachment = &depth;
    WGPURenderPassEncoder pass = wgpuCommandEncoderBeginRenderPass(encoder, &passDescriptor);
    if (!pass)
        return failEncoder(encoder, "Unable to begin the WebGPU scene render pass");

    if (vertexCount > 0) {
        wgpuRenderPassEncoderSetViewport(
            pass,
            static_cast<float>(renderViewport.left),
            static_cast<float>(renderViewport.top),
            static_cast<float>(renderViewport.width),
            static_cast<float>(renderViewport.height),
            0.0f,
            1.0f);
        wgpuRenderPassEncoderSetPipeline(pass, g.scenePipeline);
        wgpuRenderPassEncoderSetBindGroup(pass, 0, g.sceneBindGroup, 0, nullptr);
        wgpuRenderPassEncoderSetVertexBuffer(pass, 0, g.sceneVertexBuffer, 0, vertexBytes);
        // Complete opaque geometry across ALL resident sectors first. Drawing
        // tile water immediately after its own seabed lets it depth-reject a
        // neighboring tile's seabed before that color can show through water.
        // Cached index partitions preserve the append-only vertex/RTX layout;
        // dry sectors still use a single ordinary draw with no index storage.
        for (const bool water : {false, true}) {
            if (g.staticSectorManifestEnabled) {
                for (const auto& range : g.staticSectorManifest) {
                    if (!range.active ||
                        range.firstVertex + range.vertexCount > staticVertexCount)
                        continue;
                    drawSceneRange(pass, g.staticDrawRanges.at(range.firstVertex), water);
                }
            } else if (staticVertexCount > 0u) {
                drawSceneRange(pass, g.staticDrawRanges.at(0u), water);
            }
            drawSceneRange(pass, g.dynamicDrawRange, water);
        }
    }

    wgpuRenderPassEncoderEnd(pass);
    wgpuRenderPassEncoderRelease(pass);

    // DLSS output is display-sized while all temporal inputs are rendered at
    // the selected internal resolution. It must not share a WebGPU render pass
    // with the smaller reflection guides because attachment extents must match.
    // Streamline writes the complete DLSS output whenever evaluation succeeds.
    WGPURenderPassColorAttachment temporalAttachments[3]{};
    temporalAttachments[0].view = g.reflectionView;
    temporalAttachments[1].view = g.specularHitDistanceView;
    temporalAttachments[2].view = g.neuralRenderingView;
    for (auto& color : temporalAttachments) {
        color.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
        color.loadOp = WGPULoadOp_Clear;
        color.storeOp = WGPUStoreOp_Store;
        color.clearValue = WGPUColor{0.0, 0.0, 0.0, 0.0};
    }
    temporalAttachments[0].clearValue = skyColor;
    WGPURenderPassDescriptor temporalPassDescriptor{};
    temporalPassDescriptor.colorAttachmentCount = 3;
    temporalPassDescriptor.colorAttachments = temporalAttachments;
    WGPURenderPassEncoder temporalPass =
        wgpuCommandEncoderBeginRenderPass(encoder, &temporalPassDescriptor);
    if (!temporalPass)
        return failEncoder(encoder, "Unable to begin the temporal-guide render pass");
    wgpuRenderPassEncoderEnd(temporalPass);
    wgpuRenderPassEncoderRelease(temporalPass);

    // Streamline is handed COLOR_ATTACHMENT_OPTIMAL as the native state of
    // the display-sized output.  Initialize it in an independent pass so the
    // first DLSS/RR evaluation never receives a newly-created UNDEFINED image,
    // and do not mix its display extent with the render-sized G-buffer.
    if (dlssFrameActive) {
        WGPURenderPassColorAttachment dlssOutputAttachment{};
        dlssOutputAttachment.view = g.dlssOutputView;
        dlssOutputAttachment.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
        dlssOutputAttachment.loadOp = WGPULoadOp_Clear;
        dlssOutputAttachment.storeOp = WGPUStoreOp_Store;
        dlssOutputAttachment.clearValue = skyColor;
        WGPURenderPassDescriptor dlssOutputPassDescriptor{};
        dlssOutputPassDescriptor.colorAttachmentCount = 1;
        dlssOutputPassDescriptor.colorAttachments = &dlssOutputAttachment;
        WGPURenderPassEncoder dlssOutputPass =
            wgpuCommandEncoderBeginRenderPass(encoder, &dlssOutputPassDescriptor);
        if (!dlssOutputPass)
            return failEncoder(encoder,
                               "Unable to begin the DLSS output initialization pass");
        wgpuRenderPassEncoderEnd(dlssOutputPass);
        wgpuRenderPassEncoderRelease(dlssOutputPass);
    }

    if (!submitEncoder(encoder, "Unable to submit the WebGPU scene command buffer"))
        return failFrame(nullptr);

    // The Vulkan work is recorded on the same WebGPU device/queue. Hardware
    // rays complete first, then DLSS 5 Neural Rendering enhances the
    // render-sized HDR scene, and Super Resolution optionally upscales it.
    bool reflectionsEvaluated = false;
    if (rayTracingActive && rayFrameReady) {
        WGPUCommandEncoder nativeEncoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
        if (!nativeEncoder)
            return failFrame("Unable to create the RTX lighting command encoder");
        bool rayEvaluationReady = true;
        if (g.rtxShadowsEnabled) {
            rayEvaluationReady = evaluateRayQueryLighting(
                nativeEncoder, rasterViewProjection, cameraPosition,
                viewportX, viewportY, viewportWidth, viewportHeight,
                interfaceWidth, interfaceHeight);
        }
        if (rayEvaluationReady && g.rtxReflectionsEnabled)
            reflectionsEvaluated =
                evaluateRayQueryReflections(
                    nativeEncoder, rasterViewProjection, cameraPosition,
                    viewportX, viewportY, viewportWidth, viewportHeight,
                    interfaceWidth, interfaceHeight);
        if (!rayEvaluationReady ||
            (g.rtxReflectionsEnabled && !reflectionsEvaluated)) {
            setError("RTX evaluation failed; presenting the raster frame without "
                     "ray-query reflections");
            reflectionsEvaluated = false;
        }
        if (!submitEncoder(nativeEncoder,
                           "Unable to submit the RTX lighting command buffer"))
            return failFrame(nullptr);
    }

    // DLSS Neural Rendering feature 1004 consumes display-referred color.
    // Bake the same ACES/exposure/gamma transform used by the swapchain into
    // a distinct render-sized FP16 image. Keeping this separate from the HDR
    // G-buffer preserves correct RTX lighting and prevents a second tone map
    // after Neural Rendering returns its display-referred output.
    if (neuralRenderingFrameActive) {
        WGPUCommandEncoder neuralInputEncoder =
            wgpuDeviceCreateCommandEncoder(g.device, nullptr);
        if (!neuralInputEncoder)
            return failFrame("Unable to create the Neural Rendering input encoder");
        WGPURenderPassColorAttachment neuralInputColor{};
        neuralInputColor.view = g.neuralInputView;
        neuralInputColor.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
        neuralInputColor.loadOp = WGPULoadOp_Clear;
        neuralInputColor.storeOp = WGPUStoreOp_Store;
        neuralInputColor.clearValue = WGPUColor{0.0, 0.0, 0.0, 1.0};
        WGPURenderPassDescriptor neuralInputPassDescriptor{};
        neuralInputPassDescriptor.colorAttachmentCount = 1;
        neuralInputPassDescriptor.colorAttachments = &neuralInputColor;
        WGPURenderPassEncoder neuralInputPass = wgpuCommandEncoderBeginRenderPass(
            neuralInputEncoder, &neuralInputPassDescriptor);
        if (!neuralInputPass)
            return failEncoder(neuralInputEncoder,
                               "Unable to begin the Neural Rendering input pass");
        wgpuRenderPassEncoderSetViewport(
            neuralInputPass, 0.0f, 0.0f,
            static_cast<float>(sceneWidth()),
            static_cast<float>(sceneHeight()), 0.0f, 1.0f);
        wgpuRenderPassEncoderSetPipeline(neuralInputPass, g.neuralInputPipeline);
        wgpuRenderPassEncoderSetBindGroup(
            neuralInputPass, 0,
            reflectionsEvaluated
                ? g.neuralInputReflectionBindGroup
                : g.neuralInputHdrBindGroup,
            0, nullptr);
        wgpuRenderPassEncoderDraw(neuralInputPass, 3, 1, 0, 0);
        wgpuRenderPassEncoderEnd(neuralInputPass);
        wgpuRenderPassEncoderRelease(neuralInputPass);
        if (!submitEncoder(neuralInputEncoder,
                           "Unable to submit the Neural Rendering input buffer"))
            return failFrame(nullptr);
    }

    bool neuralOutputEvaluated = false;
    bool dlssOutputEvaluated = false;
    if (streamlineFrameActive) {
        WGPUCommandEncoder streamlineEncoder =
            wgpuDeviceCreateCommandEncoder(g.device, nullptr);
        if (!streamlineEncoder)
            return failFrame("Unable to create the DLSS command encoder");
        if (g.dlssNeuralRenderingReleasePending) {
            // Cleanup is deferred to a fresh Streamline frame so the failed
            // frame's queued command buffer is no longer being recorded. The
            // NR-only off path frees feature 1004 without releasing SR state.
            StreamlineDLSSNRFrame off{};
            off.viewport = 0;
            off.options.enabled = false;
            streamlineDLSSNREvaluate(off);
            g.dlssNeuralRenderingReleasePending = false;
        }
        WGPUTexture enhancedInput = reflectionsEvaluated
            ? g.reflectionTexture
            : g.hdrColorTexture;
        if (neuralRenderingFrameActive) {
            neuralOutputEvaluated = evaluateDlssNeuralRendering(
                streamlineEncoder, g.neuralInputTexture, viewProjection,
                cameraViewToClip, cameraPosition, renderViewport, frameJitter);
            if (neuralOutputEvaluated) {
                enhancedInput = g.neuralRenderingTexture;
            } else {
                // A preview NGX failure is not safe to hammer every frame. Latch
                // it off for this native runtime while allowing raster, RTX and
                // Super Resolution to finish and present normally.
                const StreamlineFeatureState failedState = streamlineFeatureState();
                g.dlssNeuralRenderingRequested = false;
                g.dlssNeuralRenderingFailedThisSession = true;
                g.dlssNeuralRenderingReleasePending = true;
                setError(failedState.neuralRenderingReason.empty()
                    ? "DLSS 5 Neural Rendering failed and was disabled for this session"
                    : failedState.neuralRenderingReason);
            }
        }
        if (g.dlssRayReconstructionRequested && reflectionsEvaluated) {
            dlssOutputEvaluated = evaluateRayReconstruction(
                streamlineEncoder, viewProjection, cameraViewToClip,
                cameraPosition, renderViewport, frameJitter);
        } else if (dlssFrameActive) {
            // If the reflection preview is unavailable for this frame, SR can
            // still safely upscale the raster scene; RR is never fed fabricated
            // or stale reflection inputs.
            dlssOutputEvaluated = evaluateDlss(
                streamlineEncoder, enhancedInput, viewProjection,
                cameraViewToClip, cameraPosition, renderViewport, frameJitter);
        }
        if (!submitEncoder(streamlineEncoder,
                           "Unable to submit the DLSS command buffer"))
            return failFrame(nullptr);
    }

    encoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
    if (!encoder)
        return failFrame("Unable to create the final composition command encoder");

    WGPURenderPassColorAttachment finalColor{};
    finalColor.view = surfaceView;
    finalColor.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
    finalColor.loadOp = WGPULoadOp_Clear;
    finalColor.storeOp = WGPUStoreOp_Store;
    finalColor.clearValue = skyColor;
    WGPURenderPassDescriptor finalPassDescriptor{};
    finalPassDescriptor.colorAttachmentCount = 1;
    finalPassDescriptor.colorAttachments = &finalColor;
    WGPURenderPassEncoder finalPass =
        wgpuCommandEncoderBeginRenderPass(encoder, &finalPassDescriptor);
    if (!finalPass)
        return failEncoder(encoder, "Unable to begin the final composition pass");
    wgpuRenderPassEncoderSetViewport(finalPass, 0, 0, static_cast<float>(g.width),
                                     static_cast<float>(g.height), 0.0f, 1.0f);
    wgpuRenderPassEncoderSetPipeline(finalPass, g.finalPipeline);
    WGPUBindGroup finalBindGroup = dlssOutputEvaluated
        ? g.finalDlssBindGroup
        : neuralOutputEvaluated
            ? g.finalNeuralRenderingBindGroup
        : reflectionsEvaluated
            ? g.finalReflectionBindGroup
            : g.finalHdrBindGroup;
    wgpuRenderPassEncoderSetBindGroup(finalPass, 0, finalBindGroup, 0, nullptr);
    wgpuRenderPassEncoderDraw(finalPass, 3, 1, 0, 0);
    wgpuRenderPassEncoderEnd(finalPass);
    wgpuRenderPassEncoderRelease(finalPass);

    // UI is deliberately composed after the scene/G-buffer pass so DLSS,
    // Frame Generation and RTX never receive RuneLite widgets as scene data.
    WGPURenderPassColorAttachment uiColor{};
    uiColor.view = surfaceView;
    uiColor.depthSlice = WGPU_DEPTH_SLICE_UNDEFINED;
    uiColor.loadOp = WGPULoadOp_Load;
    uiColor.storeOp = WGPUStoreOp_Store;
    WGPURenderPassDescriptor uiPassDescriptor{};
    uiPassDescriptor.colorAttachmentCount = 1;
    uiPassDescriptor.colorAttachments = &uiColor;
    WGPURenderPassEncoder uiPass =
        wgpuCommandEncoderBeginRenderPass(encoder, &uiPassDescriptor);
    if (!uiPass)
        return failEncoder(encoder, "Unable to begin the RuneLite interface pass");
    wgpuRenderPassEncoderSetViewport(uiPass, 0, 0, static_cast<float>(g.width),
                                     static_cast<float>(g.height), 0.0f, 1.0f);
    wgpuRenderPassEncoderSetPipeline(uiPass, g.uiBlendPipeline);
    wgpuRenderPassEncoderSetBindGroup(uiPass, 0, g.uiBlendBindGroup, 0, nullptr);
    wgpuRenderPassEncoderDraw(uiPass, 3, 1, 0, 0);
    wgpuRenderPassEncoderEnd(uiPass);
    wgpuRenderPassEncoderRelease(uiPass);

    if (!submitEncoder(encoder,
                       "Unable to submit the final WebGPU composition buffer"))
        return failFrame(nullptr);
    if (streamlineFrameActive) {
        streamlineRenderSubmitEnd();
        streamlinePresentBegin();
    }
    wgpuSurfacePresent(g.surface);
    if (streamlineFrameActive) streamlinePresentEnd();
    if (!processDeviceEventsAndCheckForLoss()) return 0;
    releaseSurfaceResources();
    std::memcpy(g.previousViewProjection, rasterViewProjection, sizeof(float) * 16u);
    std::memcpy(g.previousStreamlineViewProjection, viewProjection, sizeof(float) * 16u);
    const uint32_t retainedDynamicVertexCount = vertexCount - staticVertexCount;
    if (retainedDynamicVertexCount > 0u) {
        const float* dynamicPositions = g.currentScenePositions.data() +
            static_cast<size_t>(staticVertexCount) * 3u;
        g.previousDynamicPositions.assign(
            dynamicPositions,
            dynamicPositions + static_cast<size_t>(retainedDynamicVertexCount) * 3u);
    } else {
        g.previousDynamicPositions.clear();
    }
    g.previousDynamicVertexCount = retainedDynamicVertexCount;
    g.hasPreviousViewProjection = true;
    g.temporalStaticRevision = staticRevision;
    g.staticSectorManifestChanged = false;
    std::memcpy(g.previousCameraPosition, cameraPosition, sizeof(g.previousCameraPosition));
    g.hasPreviousCameraPosition = true;
    const bool requestedTemporalStagesSucceeded =
        (!neuralRenderingFrameActive || neuralOutputEvaluated) &&
        (!dlssFrameActive || dlssOutputEvaluated);
    if (streamlineFrameActive && requestedTemporalStagesSucceeded) {
        g.temporalResetRequested = false;
        if (dlssFrameActive) ++g.temporalJitterIndex;
    } else if (streamlineFrameActive) {
        // A failed evaluation creates a gap in DLSS history. Restart both the
        // accumulation and deterministic jitter sequence on the next frame.
        g.temporalResetRequested = true;
        g.temporalJitterIndex = 0;
    }
    ++g.frameIndex;
    return 1;
}

int rlw_read_scene_rgba16(int stage, uint16_t* pixels, uint64_t capacityBytes,
                         uint32_t* width, uint32_t* height) {
    std::lock_guard<std::mutex> lock(g.mutex);
    if (!width || !height || stopForDeviceLoss()) return 0;
    WGPUTexture source = stage == 0 ? g.hdrColorTexture
        : stage == 1 ? g.neuralRenderingTexture
        : stage == 2 && g.rtxReflectionsEnabled ? g.reflectionTexture : nullptr;
    if (!source || (stage == 1 &&
        !streamlineFeatureState().neuralRenderingActive)) return 0;
    *width = sceneWidth();
    *height = sceneHeight();
    const uint64_t rowBytes = static_cast<uint64_t>(*width) * 8u;
    if (!pixels) return 1;
    if (capacityBytes < rowBytes * *height) return 0;
    const uint64_t paddedRowBytes = (rowBytes + 255u) & ~255ull;
    const uint64_t bufferBytes = paddedRowBytes * *height;
    WGPUBufferDescriptor descriptor{};
    descriptor.label = stringView("RuneLite diagnostic color readback");
    descriptor.size = bufferBytes;
    descriptor.usage = WGPUBufferUsage_CopyDst | WGPUBufferUsage_MapRead;
    WGPUBuffer buffer = wgpuDeviceCreateBuffer(g.device, &descriptor);
    WGPUCommandEncoder encoder = wgpuDeviceCreateCommandEncoder(g.device, nullptr);
    if (!buffer || !encoder) {
        if (buffer) wgpuBufferRelease(buffer);
        if (encoder) wgpuCommandEncoderRelease(encoder);
        return 0;
    }
    WGPUTexelCopyTextureInfo sourceInfo{};
    sourceInfo.texture = source;
    sourceInfo.aspect = WGPUTextureAspect_All;
    WGPUTexelCopyBufferInfo destination{};
    destination.buffer = buffer;
    destination.layout.bytesPerRow = static_cast<uint32_t>(paddedRowBytes);
    destination.layout.rowsPerImage = *height;
    const WGPUExtent3D extent{*width, *height, 1};
    wgpuCommandEncoderCopyTextureToBuffer(encoder, &sourceInfo, &destination, &extent);
    WGPUCommandBuffer command = wgpuCommandEncoderFinish(encoder, nullptr);
    if (!command) {
        wgpuCommandEncoderRelease(encoder);
        wgpuBufferRelease(buffer);
        return 0;
    }
    wgpuQueueSubmit(g.queue, 1, &command);
    wgpuCommandBufferRelease(command);
    wgpuCommandEncoderRelease(encoder);
    auto mapStatus = std::make_shared<std::atomic<WGPUMapAsyncStatus>>(
        static_cast<WGPUMapAsyncStatus>(0));
    WGPUBufferMapCallbackInfo callback{};
    callback.mode = WGPUCallbackMode_AllowProcessEvents;
    callback.userdata1 = new decltype(mapStatus)(mapStatus);
    callback.callback = [](WGPUMapAsyncStatus status, WGPUStringView, void* data, void*) {
        auto* state = static_cast<decltype(mapStatus)*>(data);
        (*state)->store(status, std::memory_order_release);
        delete state;
    };
    wgpuBufferMapAsync(buffer, WGPUMapMode_Read, 0, bufferBytes, callback);
    // wgpu-native drives buffer mapping through DevicePoll; WaitAny alone
    // does not guarantee that the map completion queue has been serviced.
    for (int attempt = 0; attempt < 3000 &&
            mapStatus->load(std::memory_order_acquire) == 0; ++attempt) {
        wgpuDevicePoll(g.device, false, nullptr);
        wgpuInstanceProcessEvents(g.instance);
        if (mapStatus->load(std::memory_order_acquire) == 0) Sleep(1);
    }
    const bool ready = mapStatus->load(std::memory_order_acquire) ==
        WGPUMapAsyncStatus_Success;
    const auto* mapped = ready ? static_cast<const uint8_t*>(
        wgpuBufferGetConstMappedRange(buffer, 0, bufferBytes)) : nullptr;
    if (mapped) {
        for (uint32_t row = 0u; row < *height; ++row)
            std::memcpy(reinterpret_cast<uint8_t*>(pixels) + row * rowBytes,
                        mapped + row * paddedRowBytes, rowBytes);
    }
    wgpuBufferUnmap(buffer);
    wgpuBufferRelease(buffer);
    if (!mapped) setError("Diagnostic color readback did not map successfully");
    return mapped != nullptr;
}

void rlw_destroy(void) {
    std::lock_guard<std::mutex> lock(g.mutex);
    releaseRuntime();
}

const char* rlw_last_error(void) {
    thread_local std::string result;
    std::lock_guard<std::mutex> lock(g.mutex);
    result = g.error;
    return result.c_str();
}

const char* rlw_backend_name(void) {
    thread_local std::string result;
    std::lock_guard<std::mutex> lock(g.mutex);
    result = g.backendName;
    return result.c_str();
}

int rlw_get_capabilities(RLWCapabilities* capabilities) {
    if (!capabilities || capabilities->struct_size < sizeof(RLWCapabilities)) return 0;
    std::lock_guard<std::mutex> lock(g.mutex);
    RLWCapabilities result{};
    result.struct_size = sizeof(result);
    result.vendor_id = g.vendorId;
    result.device_id = g.deviceId;
    result.is_rtx = g.isRtx ? 1 : 0;
    const StreamlineCapabilities streamline = streamlineCapabilities();
    const RayQueryBridgeCapabilities rayQuery = rayQueryBridgeCapabilities();
    result.streamline_present = streamline.runtimePresent ? 1 : 0;
    result.streamline_initialized = streamline.initialized ? 1 : 0;
    result.vulkan_attached = streamline.vulkanAttached ? 1 : 0;
    result.dlss_super_resolution = streamline.dlssSuperResolution ? 1 : 0;
    constexpr bool frameGenerationPresentHook = false;
    result.dlss_frame_generation_hardware =
        streamline.dlssFrameGeneration ? 1 : 0;
    result.dlss_frame_generation =
        streamline.dlssFrameGeneration && frameGenerationPresentHook ? 1 : 0;
    result.dlss_ray_reconstruction = streamline.dlssRayReconstruction ? 1 : 0;
    result.reflex = streamline.reflex ? 1 : 0;
    result.native_ray_tracing = rayQuery.accelerationStructureSupported ? 1 : 0;
    result.ray_query =
        rayQuery.rayQuerySupported && rayQuery.pipelineReady ? 1 : 0;
    result.dlss_neural_rendering = streamline.dlssNeuralRendering ? 1 : 0;
    result.dlss_neural_rendering_api_loaded =
        streamline.dlssNeuralRenderingFunctionsLoaded ? 1 : 0;
    result.dlss_neural_rendering_dll_loaded =
        streamline.dlssNeuralRenderingDllLoaded ? 1 : 0;
    result.dlss_neural_rendering_direct_supported =
        streamline.dlssNeuralRenderingDirectSupported ? 1 : 0;
    result.dlss_neural_rendering_ngx_mapping_patched =
        streamline.dlssNeuralRenderingNgxMappingPatched ? 1 : 0;
    // Hardware/plugin support is not application support. The required
    // intercepted Vulkan Present path and resource lifetime contract do not
    // exist yet, so the UI must keep Frame Generation disabled.
    result.dlss_frame_generation_present_hook =
        frameGenerationPresentHook ? 1 : 0;
    // The renderer supplies noisy water reflections plus depth, dense motion,
    // diffuse/specular albedo, packed normal/roughness and hit-distance guides.
    result.dlss_ray_reconstruction_guides =
        g.device && rayQuery.rayQuerySupported && rayQuery.pipelineReady ? 1 : 0;
    std::snprintf(result.adapter_name, sizeof(result.adapter_name), "%s", g.adapterName.c_str());
    std::string status = !streamline.status.empty()
        ? streamline.status
        : (g.device ? "Standalone Vulkan WebGPU bridge initialized"
                    : "WebGPU bridge is not initialized");
    const StreamlineFeatureState featureState = streamlineFeatureState();
    if (g.device) {
        status += " · DLSS " + std::to_string(featureState.dlssEvaluationCount) +
                  "/" + std::to_string(featureState.dlssFailureCount);
        status += " · RR " + std::to_string(featureState.rayReconstructionEvaluationCount) +
                  "/" + std::to_string(featureState.rayReconstructionFailureCount);
        status += " · NR " + std::to_string(featureState.neuralRenderingEvaluationCount) +
                  "/" + std::to_string(featureState.neuralRenderingFailureCount);
        status += " · FG " + std::to_string(featureState.frameGenerationRequested ? 1 : 0) +
                  std::to_string(featureState.frameGenerationConfigured ? 1 : 0) +
                  std::to_string(featureState.frameGenerationActive ? 1 : 0) + ":" +
                  std::to_string(featureState.frameGenerationPresentedFrameCount) + "/" +
                  std::to_string(featureState.frameGenerationFailureCount);
        status += " · RTX " + std::to_string(rayQuery.evaluationCount) +
                  "/" + std::to_string(rayQuery.failureCount) +
                  " T" + std::to_string(rayQuery.triangleCount) +
                  " B" + std::to_string(rayQuery.buildCount);
    }
    std::snprintf(result.status, sizeof(result.status), "%s", status.c_str());
    *capabilities = result;
    return 1;
}

int rlw_get_dlss_runtime_status(RLWDlssRuntimeStatus* status) {
    if (!status || status->struct_size < sizeof(RLWDlssRuntimeStatus)) return 0;
    std::lock_guard<std::mutex> lock(g.mutex);
    const StreamlineFeatureState state = streamlineFeatureState();
    RLWDlssRuntimeStatus result{};
    result.struct_size = sizeof(result);
    result.requested_mode = static_cast<uint32_t>(g.dlssRequest.mode);
    result.super_resolution_requested = state.dlssRequested ? 1 : 0;
    result.super_resolution_configured = state.dlssConfigured ? 1 : 0;
    result.super_resolution_active = state.dlssActive ? 1 : 0;
    result.ray_reconstruction_requested =
        g.dlssRayReconstructionRequested ? 1 : 0;
    result.ray_reconstruction_configured =
        state.rayReconstructionConfigured ? 1 : 0;
    result.ray_reconstruction_active = state.rayReconstructionActive ? 1 : 0;
    result.neural_rendering_requested = g.dlssNeuralRenderingRequested ? 1 : 0;
    result.neural_rendering_configured = state.neuralRenderingConfigured ? 1 : 0;
    result.neural_rendering_active = state.neuralRenderingActive ? 1 : 0;
    result.render_width = state.renderWidth;
    result.render_height = state.renderHeight;
    result.output_width = state.outputWidth;
    result.output_height = state.outputHeight;
    result.super_resolution_evaluations = state.dlssEvaluationCount;
    result.super_resolution_failures = state.dlssFailureCount;
    result.ray_reconstruction_evaluations =
        state.rayReconstructionEvaluationCount;
    result.ray_reconstruction_failures =
        state.rayReconstructionFailureCount;
    result.neural_rendering_evaluations = state.neuralRenderingEvaluationCount;
    result.neural_rendering_failures = state.neuralRenderingFailureCount;
    result.super_resolution_last_result = state.dlssLastResult;
    result.ray_reconstruction_last_result = state.rayReconstructionLastResult;
    result.neural_rendering_last_result = state.neuralRenderingLastResult;
    std::snprintf(result.super_resolution_reason,
                  sizeof(result.super_resolution_reason), "%s",
                  state.dlssReason.c_str());
    std::snprintf(result.ray_reconstruction_reason,
                  sizeof(result.ray_reconstruction_reason), "%s",
                  state.rayReconstructionReason.c_str());
    std::snprintf(result.neural_rendering_reason,
                  sizeof(result.neural_rendering_reason), "%s",
                  state.neuralRenderingReason.c_str());
    *status = result;
    return 1;
}

int rlw_request_features(const RLWFeatureRequest* request) {
    if (!request || request->struct_size < sizeof(RLWFeatureRequest)) return 0;
    std::lock_guard<std::mutex> lock(g.mutex);
    if (stopForDeviceLoss()) return 0;
    if (request->dlss_mode > RLW_DLSS_DLAA) {
        setError("Invalid DLSS mode");
        return 0;
    }
    if (request->frame_generation != 0) {
        setError("DLSS Frame Generation is unavailable until the Vulkan present hook, "
                 "display-sized HUD-less color and DPI-correct UI tags are validated");
        return 0;
    }
    const bool dlssRequested = request->dlss_mode != RLW_DLSS_OFF;
    const bool rayReconstructionRequested = request->ray_reconstruction != 0;
    const bool neuralRenderingRequested = request->neural_rendering != 0;
    if (neuralRenderingRequested && dlssRequested) {
        setError("DLSS 5 Neural Rendering preview cannot be chained with DLSS "
                 "Super Resolution until NVIDIA publishes a supported contract");
        return 0;
    }
    if (neuralRenderingRequested && g.dlssNeuralRenderingFailedThisSession) {
        setError("DLSS 5 Neural Rendering was disabled after a native evaluation "
                 "failure; restart RuneLite before retrying it");
        return 0;
    }
    if (rayReconstructionRequested && !dlssRequested) {
        setError("DLSS Ray Reconstruction requires an enabled DLSS quality mode");
        return 0;
    }
    if (dlssRequested &&
        (request->output_width != g.width || request->output_height != g.height)) {
        setError("DLSS output dimensions must match the physical RuneLite surface");
        return 0;
    }
    const StreamlineCapabilities capabilities = streamlineCapabilities();
    if (dlssRequested && !capabilities.dlssSuperResolution) {
        setError("DLSS Super Resolution is unavailable on the active Vulkan adapter");
        return 0;
    }
    if (rayReconstructionRequested && !capabilities.dlssRayReconstruction) {
        setError("DLSS Ray Reconstruction is unavailable on the active Vulkan adapter");
        return 0;
    }
    if (rayReconstructionRequested) {
        const RayQueryBridgeCapabilities rayQuery = rayQueryBridgeCapabilities();
        if (!rayQuery.rayQuerySupported || !rayQuery.pipelineReady) {
            setError("DLSS Ray Reconstruction requires a ready ray-query "
                     "reflection pipeline and valid guide generation");
            return 0;
        }
    }
    if (neuralRenderingRequested && !capabilities.dlssNeuralRendering) {
        const StreamlineFeatureState state = streamlineFeatureState();
        setError(state.neuralRenderingReason.empty()
            ? "DLSS 5 Neural Rendering is unavailable on the active Vulkan adapter"
            : state.neuralRenderingReason);
        return 0;
    }
    if (rayReconstructionRequested && !g.rtxReflectionsEnabled) {
        setError("DLSS Ray Reconstruction requires the ray-traced reflections input");
        return 0;
    }
    StreamlineDLSSOptions options{};
    options.mode = static_cast<StreamlineDLSSMode>(request->dlss_mode);
    options.outputWidth = request->output_width;
    options.outputHeight = request->output_height;
    options.preExposure = request->pre_exposure > 0.0f ? request->pre_exposure : 1.0f;
    options.exposureScale = request->exposure_scale > 0.0f ? request->exposure_scale : 1.0f;
    options.colorBuffersHDR = request->color_buffers_hdr != 0;
    // RuneLite currently applies its exposure in the scene shader. Auto
    // exposure avoids tagging a stale or doubly-applied 1x1 exposure value.
    options.useAutoExposure = true;
    options.alphaUpscaling = request->alpha_upscaling != 0;

    const bool configurationChanged =
        options.mode != g.dlssRequest.mode ||
        options.outputWidth != g.dlssRequest.outputWidth ||
        options.outputHeight != g.dlssRequest.outputHeight ||
        options.colorBuffersHDR != g.dlssRequest.colorBuffersHDR ||
        rayReconstructionRequested != g.dlssRayReconstructionRequested ||
        neuralRenderingRequested != g.dlssNeuralRenderingRequested;
    if (configurationChanged &&
        (g.dlssRequest.mode != StreamlineDLSSMode::Off ||
         g.dlssNeuralRenderingRequested) &&
        !streamlineReleaseViewport(0)) {
        const StreamlineFeatureState state = streamlineFeatureState();
        setError(!state.neuralRenderingReason.empty()
            ? state.neuralRenderingReason
            : "Streamline could not flush the active viewport safely");
        return 0;
    }
    if (!streamlineRequestFeatures(
            options, false, rayReconstructionRequested)) {
        const StreamlineFeatureState state = streamlineFeatureState();
        const std::string reason = dlssRequested && !state.dlssReason.empty()
            ? state.dlssReason
            : rayReconstructionRequested && !state.rayReconstructionReason.empty()
                ? state.rayReconstructionReason
                : "Streamline rejected the requested NVIDIA features";
        const bool safeToReleaseTextures = disableDlssConfiguration();
        if (g.device && safeToReleaseTextures) releaseDepthResources();
        setError(reason);
        return 0;
    }
    const StreamlineFeatureState state = streamlineFeatureState();
    if (dlssRequested &&
        (!state.dlssConfigured || !state.renderWidth || !state.renderHeight ||
         state.outputWidth != g.width || state.outputHeight != g.height)) {
        const std::string reason = state.dlssReason.empty()
            ? "Streamline returned an invalid DLSS render configuration"
            : state.dlssReason;
        const bool safeToReleaseTextures = disableDlssConfiguration();
        if (g.device && safeToReleaseTextures) releaseDepthResources();
        setError(reason);
        return 0;
    }
    const uint32_t oldRenderWidth = g.dlssRenderWidth;
    const uint32_t oldRenderHeight = g.dlssRenderHeight;
    const uint32_t oldOutputWidth = g.dlssOutputWidth;
    const uint32_t oldOutputHeight = g.dlssOutputHeight;
    g.dlssRequest = options;
    g.dlssRayReconstructionRequested = rayReconstructionRequested;
    g.dlssNeuralRenderingRequested = neuralRenderingRequested;
    g.dlssRenderWidth = dlssRequested ? state.renderWidth : 0;
    g.dlssRenderHeight = dlssRequested ? state.renderHeight : 0;
    g.dlssOutputWidth = dlssRequested ? state.outputWidth : 0;
    g.dlssOutputHeight = dlssRequested ? state.outputHeight : 0;
    g.temporalResetRequested = true;
    if (g.device &&
        (configurationChanged || oldRenderWidth != g.dlssRenderWidth ||
         oldRenderHeight != g.dlssRenderHeight ||
         oldOutputWidth != g.dlssOutputWidth || oldOutputHeight != g.dlssOutputHeight)) {
        releaseDepthResources();
    }
    return 1;
}

uint64_t rlw_last_dynamic_hash(void) {
    std::lock_guard<std::mutex> lock(g.mutex);
    return g.lastDynamicInputHash;
}

} // extern "C"
