#pragma once

#include <cstdint>
#include <vector>

namespace rlw {

// Keep this in sync with the terrain classification in the scene shader.
inline bool isWaterSurface(int32_t textureId, uint32_t flags) {
    const bool terrain = (flags & 2u) != 0u;
    const bool blend = (flags & 0x80000000u) != 0u && textureId != -2;
    return terrain && (textureId == -2 ||
        (!blend && (flags & 0x40000000u) != 0u));
}

// Partition only indices: vertex order and all static-sector/RTX offsets stay
// unchanged. Opaque and water triangle order are each stable. Homogeneous
// ranges need no index buffer and can use the original direct draw.
template <typename Vertex>
uint32_t partitionWaterTriangles(const Vertex* vertices, uint32_t first,
                                uint32_t count, std::vector<uint32_t>& indices) {
    count -= count % 3u;
    uint32_t opaqueCount = 0u;
    for (uint32_t offset = 0u; offset < count; offset += 3u) {
        const auto& vertex = vertices[first + offset];
        if (!isWaterSurface(vertex.textureId, vertex.sourceFlags)) opaqueCount += 3u;
    }
    indices.clear();
    if (opaqueCount == 0u || opaqueCount == count) return opaqueCount;
    indices.resize(count);
    uint32_t opaque = 0u;
    uint32_t water = opaqueCount;
    for (uint32_t offset = 0u; offset < count; offset += 3u) {
        const auto& vertex = vertices[first + offset];
        uint32_t& cursor = isWaterSurface(vertex.textureId, vertex.sourceFlags)
            ? water : opaque;
        for (uint32_t corner = 0u; corner < 3u; ++corner)
            indices[cursor++] = first + offset + corner;
    }
    return opaqueCount;
}

} // namespace rlw
