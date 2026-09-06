#pragma once
#include "material_mips.h"

// The Java atlas remains the transfer format. Each GPU layer contains exactly
// one material, including its entire mip pyramid, so filtering cannot bleed.
inline bool uploadMaterialLayers(WGPUDevice device, WGPUQueue queue,
    const uint32_t* atlas, uint32_t width, uint32_t tileSize, uint32_t count,
    uint32_t mipCount, bool normals, WGPUTexture& target, WGPUTextureView& view) {
    WGPUTextureDescriptor texture{};
    texture.dimension = WGPUTextureDimension_2D;
    texture.size = WGPUExtent3D{tileSize, tileSize, count};
    texture.format = normals ? WGPUTextureFormat_RGBA8Unorm : WGPUTextureFormat_RGBA8UnormSrgb;
    texture.mipLevelCount = mipCount;
    texture.sampleCount = 1;
    texture.usage = WGPUTextureUsage_TextureBinding | WGPUTextureUsage_CopyDst;
    target = wgpuDeviceCreateTexture(device, &texture);
    if (!target) return false;
    WGPUTextureViewDescriptor descriptor{};
    descriptor.dimension = WGPUTextureViewDimension_2DArray;
    descriptor.mipLevelCount = mipCount;
    descriptor.arrayLayerCount = count;
    view = wgpuTextureCreateView(target, &descriptor);
    if (!view) return false;
    for (uint32_t layer = 0; layer < count; ++layer) {
        uint32_t ox = (layer % (width / tileSize)) * tileSize;
        uint32_t oy = (layer / (width / tileSize)) * tileSize;
        std::vector<uint32_t> pixels(size_t(tileSize) * tileSize);
        for (uint32_t y = 0; y < tileSize; ++y)
            std::copy_n(atlas + size_t(oy + y) * width + ox, tileSize,
                        pixels.data() + size_t(y) * tileSize);
        uint32_t size = tileSize;
        for (uint32_t mip = 0; mip < mipCount; ++mip) {
            WGPUTexelCopyTextureInfo destination{};
            destination.texture = target;
            destination.mipLevel = mip;
            destination.origin.z = layer;
            WGPUTexelCopyBufferLayout layout{};
            layout.bytesPerRow = size * sizeof(uint32_t);
            layout.rowsPerImage = size;
            WGPUExtent3D extent{size, size, 1};
            wgpuQueueWriteTexture(queue, &destination, pixels.data(),
                pixels.size() * sizeof(uint32_t), &layout, &extent);
            if (size > 1) pixels = rlw::downsampleMaterial(pixels, size, normals);
            size = std::max(1u, size / 2u);
        }
    }
    return true;
}
