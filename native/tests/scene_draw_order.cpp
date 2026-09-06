#include "scene_draw_order.h"

#include <cstdlib>
#include <iostream>
#include <vector>

struct Vertex {
    int32_t textureId;
    uint32_t sourceFlags;
};

void check(bool result, const char* message) {
    if (!result) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

int main() {
    check(rlw::isWaterSurface(-2, 2u), "sentinel terrain is water");
    check(rlw::isWaterSurface(7, 0x40000002u), "tagged terrain is water");
    check(!rlw::isWaterSurface(7, 0xc0000002u), "blend metadata is not water");
    check(rlw::isWaterSurface(-2, 0xc0000002u), "sentinel overrides blend tag");
    check(!rlw::isWaterSurface(-2, 0x40000001u), "model effects are not water");
    check(!rlw::isWaterSurface(7, 0x20000002u), "seabed is opaque");

    std::vector<Vertex> vertices;
    auto triangle = [&](int32_t texture, uint32_t flags) {
        for (int i = 0; i < 3; ++i) vertices.push_back({texture, flags});
    };
    triangle(0, 1u);               // Previous sector, excluded from this range.
    triangle(-2, 0x40000002u);     // Water comes first in the input.
    triangle(7, 0x20000002u);      // Its opaque seabed must render first.
    triangle(-2, 0x40000002u);
    triangle(0, 1u);               // Another opaque object under the water.
    std::vector<uint32_t> indices;
    const auto original = vertices;
    check(rlw::partitionWaterTriangles(vertices.data(), 3u, 12u, indices) == 6u,
          "mixed range has six opaque indices");
    check(indices == std::vector<uint32_t>({6, 7, 8, 12, 13, 14, 3, 4, 5, 9, 10, 11}),
          "opaque precedes water, preserving order and absolute vertex offsets");
    for (size_t i = 0; i < vertices.size(); ++i)
        check(vertices[i].textureId == original[i].textureId &&
              vertices[i].sourceFlags == original[i].sourceFlags,
              "partition must not mutate the vertex/RTX stream");
    check(rlw::partitionWaterTriangles(vertices.data(), 3u, 3u, indices) == 0u &&
          indices.empty(), "all-water range uses a direct draw");
    check(rlw::partitionWaterTriangles(vertices.data(), 6u, 3u, indices) == 3u &&
          indices.empty(), "dry range uses a direct draw");
    check(rlw::partitionWaterTriangles<Vertex>(nullptr, 0u, 0u, indices) == 0u &&
          indices.empty(), "empty scene is valid");
    std::cout << "Scene draw order checks passed\n";
}
