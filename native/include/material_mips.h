#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace rlw {
// Filter colors in linear light and weight them by coverage to avoid dark
// fringes around leaves. Normal maps are vectors, never sRGB colors.
inline std::vector<uint32_t> downsampleMaterial(
    const std::vector<uint32_t>& source, uint32_t size, bool normals) {
    const uint32_t next = std::max(1u, size / 2u);
    std::vector<uint32_t> result(size_t(next) * next);
    auto decode = [](float v) {
        return v <= .04045f ? v / 12.92f : std::pow((v + .055f) / 1.055f, 2.4f);
    };
    auto encode = [](float v) {
        return v <= .0031308f ? v * 12.92f : 1.055f * std::pow(v, 1.f / 2.4f) - .055f;
    };
    for (uint32_t y = 0; y < next; ++y) for (uint32_t x = 0; x < next; ++x) {
        float rgb[3]{}, alpha = 0;
        for (uint32_t dy = 0; dy < 2; ++dy) for (uint32_t dx = 0; dx < 2; ++dx) {
            uint32_t p = source[size_t(std::min(y * 2 + dy, size - 1)) * size +
                                std::min(x * 2 + dx, size - 1)];
            float a = float(p >> 24) / 255.f;
            alpha += a;
            for (unsigned c = 0; c < 3; ++c) {
                float v = float((p >> (c * 8)) & 255u) / 255.f;
                rgb[c] += normals ? v * 2.f - 1.f : decode(v) * a;
            }
        }
        float length = std::sqrt(rgb[0]*rgb[0] + rgb[1]*rgb[1] + rgb[2]*rgb[2]);
        uint32_t packed = uint32_t(std::lround(alpha * .25f * 255.f)) << 24;
        for (unsigned c = 0; c < 3; ++c) {
            float v = normals ? (length > 1e-6f ? rgb[c] / length : (c == 2 ? 1.f : 0.f)) * .5f + .5f
                              : encode(rgb[c] / std::max(alpha, 1e-6f));
            packed |= uint32_t(std::lround(std::clamp(v, 0.f, 1.f) * 255.f)) << (c * 8);
        }
        result[size_t(y) * next + x] = packed;
    }
    return result;
}
}
