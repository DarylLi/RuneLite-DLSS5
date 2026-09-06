#include "runelite_webgpu.h"
#include "material_mips.h"
#define WIN32_LEAN_AND_MEAN
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <vector>
#include <windows.h>

struct Vertex {
  float position[3];
  uint32_t color;
  float uv[2];
  int32_t texture;
  uint32_t flags;
  uint32_t vanilla;
};
LRESULT CALLBACK windowProc(HWND h, UINT m, WPARAM w, LPARAM l) {
  return DefWindowProcW(h, m, w, l);
}
float half(uint16_t bits) {
  const float sign = (bits & 0x8000u) ? -1.0f : 1.0f;
  const unsigned exponent = (bits >> 10u) & 31u;
  const unsigned mantissa = bits & 1023u;
  return sign * (exponent == 0u ? std::ldexp(float(mantissa), -24)
                                : std::ldexp(1.0f + float(mantissa) / 1024.0f,
                                             int(exponent) - 15));
}
int main() {
  std::cout << std::unitbuf;
  WNDCLASSW wc{};
  wc.lpfnWndProc = windowProc;
  wc.hInstance = GetModuleHandleW(nullptr);
  wc.lpszClassName = L"RuneLite.ShadowPixels";
  RegisterClassW(&wc);
  HWND hwnd = CreateWindowW(wc.lpszClassName, L"Shadow pixel regression",
                            WS_OVERLAPPEDWINDOW, 0, 0, 640, 480, nullptr,
                            nullptr, wc.hInstance, nullptr);
  if (!rlw_create(hwnd, 640, 480)) {
    std::cerr << rlw_last_error() << '\n';
    return 1;
  }
  RLWCapabilities caps{};
  caps.struct_size = sizeof(caps);
  rlw_get_capabilities(&caps);
  if (!caps.ray_query) {
    std::cout << "SKIP: no hardware ray query\n";
    rlw_destroy();
    return 0;
  }
  RLWVisualSettings settings{};
  settings.struct_size = sizeof(settings);
  settings.render_scale = 1;
  settings.normal_strength = 1;
  settings.saturation = 1;
  settings.contrast = 1;
  settings.ambient_intensity = .42f;
  settings.exposure = 1;
  settings.time_of_day = 12;
  settings.rtx_shadow_strength = 1;
  if (!rlw_set_visual_settings(&settings))
    return 2;
  const float positions[][3] = {
      {-96, 0, 128},  {96, 0, 128},    {96, 0, 384},    {-96, 0, 128},
      {96, 0, 384},   {-96, 0, 384},   {-24, -64, 224}, {24, -64, 224},
      {24, -64, 288}, {-24, -64, 224}, {24, -64, 288},  {-24, -64, 288}};
  Vertex vertices[12]{};
  for (unsigned i = 0; i < 12; ++i) {
    std::copy_n(positions[i], 3, vertices[i].position);
    vertices[i].color = vertices[i].vanilla = 90;
    vertices[i].texture = -1;
    vertices[i].flags = i < 6 ? 2 : 1;
  }
  const uint64_t sectorIds[] = {1, 2};
  const uint32_t sectorFirst[] = {0, 6}, sectorCounts[] = {6, 6};
  uint32_t sectorFlags[] = {1, 1};
  if (!rlw_set_static_sector_manifest(sectorIds, sectorFirst, sectorCounts,
                                      sectorFlags, 2))
    return 2;
  const float vp[] = {1.0f / 128, 0,           0, 0, 0,    -1.0f / 128, 0, 0, 0,
                      1.0f / 512, -1.0f / 512, 0, 0, -.5f, .9f,         1};
  const float identity[] = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  const float camera[] = {0, -256, 0};
  std::vector<uint32_t> ui(640u * 480u, 0u);
  uint64_t revision = 1;
  auto render = [&](bool shadows, int frames) {
    rlw_set_rtx_enabled(shadows);
    for (int f = 0; f < frames; ++f)
      if (!rlw_present_scene(vertices, 12, 12, revision, nullptr, 0, vp,
                             identity, camera, 0, 0, 640, 480, 0, ui.data(),
                             640, 480))
        return false;
    return true;
  };
  auto read = [&](int stage, std::vector<float> &output) {
    uint32_t width{}, height{};
    if (!rlw_read_scene_rgba16(stage, nullptr, 0, &width, &height))
      return false;
    std::vector<uint16_t> pixels(size_t(width) * height * 4);
    if (!rlw_read_scene_rgba16(stage, pixels.data(), pixels.size() * 2, &width,
                               &height))
      return false;
    output.resize(size_t(width) * height);
    for (size_t i = 0; i < output.size(); ++i)
      output[i] = (half(pixels[i * 4]) + half(pixels[i * 4 + 1]) +
                   half(pixels[i * 4 + 2])) /
                  3;
    return true;
  };
  std::vector<float> lit, shadowed;
  if (!render(false, 2) || !read(0, lit) || !render(true, 2) ||
      !read(0, shadowed)) {
    std::cerr << "HDR readback failed: " << rlw_last_error() << '\n';
    return 3;
  }
  std::vector<size_t> shadowPixels;
  double ratioSum = 0;
  for (size_t i = 0; i < lit.size(); ++i)
    if (lit[i] > .04f && lit[i] - shadowed[i] > .03f) {
      shadowPixels.push_back(i);
      ratioSum += shadowed[i] / lit[i];
    }
  const double meanRatio =
      shadowPixels.empty() ? 1 : ratioSum / shadowPixels.size();
  std::cout << "HDR shadow pixels=" << shadowPixels.size()
            << " mean shadow/lit=" << meanRatio << '\n';
  if (shadowPixels.size() < 100 || meanRatio > .85) {
    std::cerr << "Ray evaluation did not produce visible shadows\n";
    return 4;
  }
  if (caps.dlss_neural_rendering) {
    RLWFeatureRequest request{};
    request.struct_size = sizeof(request);
    request.neural_rendering = 1;
    request.output_width = 640;
    request.output_height = 480;
    request.pre_exposure = 1;
    request.exposure_scale = 1;
    request.color_buffers_hdr = 1;
    if (!rlw_request_features(&request)) {
      std::cerr << "NR request failed: " << rlw_last_error() << '\n';
      return 5;
    }
    std::vector<float> neuralLit, neuralShadow;
    if (!render(false, 8) || !read(1, neuralLit) || !render(true, 8) ||
        !read(1, neuralShadow)) {
      std::cerr << "NR readback failed: " << rlw_last_error() << '\n';
      return 6;
    }
    double litSum = 0, shadowSum = 0;
    for (size_t pixel : shadowPixels) {
      litSum += neuralLit[pixel];
      shadowSum += neuralShadow[pixel];
    }
    std::cout << "NR mean shadow/lit=" << shadowSum / std::max(litSum, 1e-9)
              << '\n';
    if (shadowSum >= litSum * .97) {
      std::cerr << "NR removed the visible shadow contrast\n";
      return 7;
    }
  }
  // Reuse the receiver as calm water. Compare the HDR reflection composition
  // against its source from the SAME frame to exclude sky/time variation.
  for (unsigned i = 0; i < 6; ++i) {
    vertices[i].texture = -2;
    vertices[i].flags = 0x40000002u;
    vertices[i].color = 0x6430u;
    vertices[i].vanilla = 90;
  }
  revision = (uint64_t(1) << 32u) | 1u;
  settings.water_quality = 1;
  settings.water_clarity = .6f;
  settings.rtx_reflection_strength = 1;
  if (!rlw_set_visual_settings(&settings))
    return 8;
  RLWFeatureRequest waterRequest{};
  waterRequest.struct_size = sizeof(waterRequest);
  waterRequest.output_width = 640;
  waterRequest.output_height = 480;
  if (!rlw_request_features(&waterRequest))
    return 17;
  std::vector<float> waterWithoutTextures, waterWithTextures;
  if (!render(false, 2) || !read(0, waterWithoutTextures))
    return 18;
  settings.textures_enabled = 1;
  if (!rlw_set_visual_settings(&settings) || !render(false, 2) ||
      !read(0, waterWithTextures))
    return 19;
  float maxWaterTextureDifference = 0;
  for (unsigned y = 180; y < 300; ++y)
    for (unsigned x = 80; x < 560; ++x) {
      size_t pixel = size_t(y) * 640 + x;
      maxWaterTextureDifference = std::max(
          maxWaterTextureDifference,
          std::abs(waterWithTextures[pixel] - waterWithoutTextures[pixel]));
    }
  std::cout << "Water texture-toggle maximum difference="
            << maxWaterTextureDifference << '\n';
  if (maxWaterTextureDifference > .001f) {
    std::cerr
        << "Texture toggle replaced water color with vanilla brightness\n";
    return 20;
  }
  settings.textures_enabled = 0;
  if (!rlw_set_visual_settings(&settings))
    return 21;
  if (caps.dlss_neural_rendering) {
    waterRequest.neural_rendering = 1;
    if (!rlw_request_features(&waterRequest))
      return 22;
  }
  std::vector<float> neuralNoReflection, neuralReflection;
  rlw_set_rtx_reflections(0);
  if (!render(false, 8) ||
      (caps.dlss_neural_rendering && !read(1, neuralNoReflection)))
    return 9;
  rlw_set_rtx_reflections(1);
  std::vector<float> waterSource, waterReflection;
  rlw_get_capabilities(&caps);
  unsigned long long previousEvaluations = 0, previousFailures = 0;
  const char *previousCounters = std::strstr(caps.status, "RTX ");
  if (!previousCounters ||
      std::sscanf(previousCounters, "RTX %llu/%llu", &previousEvaluations,
                  &previousFailures) != 2)
    return 23;
  if (!render(false, 12))
    return 10;
  rlw_get_capabilities(&caps);
  std::cout << "Reflection before readback: " << caps.status
            << " error=" << rlw_last_error() << '\n';
  unsigned long long evaluations = 0, failures = 0;
  const char *counters = std::strstr(caps.status, "RTX ");
  if (!counters ||
      std::sscanf(counters, "RTX %llu/%llu", &evaluations, &failures) != 2 ||
      evaluations <= previousEvaluations || failures != previousFailures) {
    std::cerr
        << "RTX failed to recover after static epoch reset and off/on toggle\n";
    return 24;
  }
  if (!read(0, waterSource) || !read(2, waterReflection)) {
    std::cerr << "Reflection readback failed\n";
    return 10;
  }
  if (!render(false, 8) || !read(0, waterSource) || !read(2, waterReflection))
    return 10;
  rlw_get_capabilities(&caps);
  std::cout << "Reflection after readback: " << caps.status
            << " error=" << rlw_last_error() << '\n';
  std::vector<size_t> reflectionPixels;
  double reflectionDelta = 0;
  for (size_t i = 0; i < waterSource.size(); ++i)
    if (std::abs(waterReflection[i] - waterSource[i]) > .003f) {
      reflectionPixels.push_back(i);
      reflectionDelta += std::abs(waterReflection[i] - waterSource[i]);
    }
  std::cout << "HDR reflection pixels=" << reflectionPixels.size()
            << " mean absolute delta="
            << reflectionDelta / std::max(size_t(1), reflectionPixels.size())
            << '\n';
  if (reflectionPixels.size() < 100 || reflectionPixels.size() > 50000) {
    std::cerr << "Reflection evaluation did not isolate water pixels\n";
    return 11;
  }
  if (caps.dlss_neural_rendering) {
    if (!read(1, neuralReflection))
      return 12;
    double delta = 0;
    for (size_t pixel : reflectionPixels)
      delta += std::abs(neuralReflection[pixel] - neuralNoReflection[pixel]);
    std::cout << "NR reflection mean absolute delta="
              << delta / reflectionPixels.size() << '\n';
    if (delta / reflectionPixels.size() < .001) {
      std::cerr << "NR removed the water reflection change\n";
      return 13;
    }
  }
  // Remove the elevated object from the TLAS. A true object reflection must
  // change the water, not just add a constant sky brightness. Compare only
  // the receiver's screen rectangle, where the object itself is never drawn.
  sectorFlags[1] = 0;
  if (!rlw_set_static_sector_manifest(sectorIds, sectorFirst, sectorCounts,
                                      sectorFlags, 2))
    return 14;
  std::vector<float> missSource, missReflection;
  if (!render(false, 8) || !read(0, missSource) || !read(2, missReflection))
    return 15;
  size_t geometryPixels = 0;
  double geometryDelta = 0;
  for (unsigned y = 180; y < 300; ++y)
    for (unsigned x = 80; x < 560; ++x) {
      size_t pixel = size_t(y) * 640 + x;
      double difference =
          std::abs((waterReflection[pixel] - waterSource[pixel]) -
                   (missReflection[pixel] - missSource[pixel]));
      if (difference > .001) {
        ++geometryPixels;
        geometryDelta += difference;
      }
    }
  std::cout << "Reflected object pixels=" << geometryPixels
            << " mean hit/miss delta="
            << geometryDelta / std::max(size_t(1), geometryPixels) << '\n';
  if (geometryPixels < 100) {
    std::cerr << "Water reflections did not respond to the scene object\n";
    return 16;
  }
  // A high-frequency checker must converge to linear 50% grey, not alias
  // between black and white or average to 21% grey in sRGB space.
  const auto grey = rlw::downsampleMaterial({0xff000000u, 0xffffffffu,
      0xffffffffu, 0xff000000u}, 2, false);
  if ((grey[0] & 255u) < 187 || (grey[0] & 255u) > 189) return 25;
  const auto coverage = rlw::downsampleMaterial({0x00000000u, 0xffffffffu,
      0x00000000u, 0xffffffffu}, 2, false);
  if ((coverage[0] & 0xffffffu) != 0xffffffu || (coverage[0] >> 24u) != 128u) return 26;
  const auto normalMip = rlw::downsampleMaterial(
      std::vector<uint32_t>(4, 0xffff8080u), 2, true);
  if ((normalMip[0] & 0xffffffu) != 0xff8080u) return 27;
  waterRequest.neural_rendering = 0;
  if (!rlw_request_features(&waterRequest)) return 28;
  rlw_set_rtx_reflections(0);
  const unsigned tile = 64, atlasWidth = tile * 2;
  std::vector<uint32_t> atlas(atlasWidth * tile), normals(atlas.size(), 0xffff8080u);
  for (unsigned y = 0; y < tile; ++y) for (unsigned x = 0; x < tile; ++x) {
    atlas[y * atlasWidth + x] = ((x + y) & 1) ? 0xffffffffu : 0xff000000u;
    atlas[y * atlasWidth + tile + x] = 0xff0000ffu;
  }
  // Material 0 is unlit with an absolute diffuse color; layer 1 is red.
  const float materials[] = {1, .82f, 0, 3, 1, .82f, 0, 3};
  if (!rlw_upload_texture_atlas(atlas.data(), atlas.data(), normals.data(), materials,
      atlasWidth, tile, tile, 2)) return 29;
  for (unsigned i = 0; i < 6; ++i) {
    vertices[i].texture = 0;
    vertices[i].flags = 2;
    vertices[i].uv[0] = (vertices[i].position[0] + 96) / 192 * 128;
    vertices[i].uv[1] = (vertices[i].position[2] - 128) / 256 * 128;
  }
  revision = (uint64_t(2) << 32u) | 1u;
  settings.textures_enabled = 1;
  if (!rlw_set_visual_settings(&settings)) return 30;
  std::vector<float> checker, unlitShadow, red;
  if (!render(false, 3) || !read(0, checker)) return 31;
  float maxCheckerError = 0;
  for (unsigned y = 200; y < 280; ++y) for (unsigned x = 120; x < 520; ++x)
    maxCheckerError = std::max(maxCheckerError, std::abs(checker[y * 640 + x] - .5f));
  std::cout << "Mipmapped checker maximum linear-grey error=" << maxCheckerError << '\n';
  if (maxCheckerError > .02f) return 32;
  sectorFlags[1] = 1;
  if (!rlw_set_static_sector_manifest(sectorIds, sectorFirst, sectorCounts, sectorFlags, 2) ||
      !render(true, 4) || !read(0, unlitShadow)) return 33;
  float maxUnlitError = 0;
  for (unsigned y = 200; y < 280; ++y) for (unsigned x = 120; x < 520; ++x)
    maxUnlitError = std::max(maxUnlitError, std::abs(checker[y * 640 + x] - unlitShadow[y * 640 + x]));
  std::cout << "Unlit material shadow difference=" << maxUnlitError << '\n';
  if (maxUnlitError > .001f) return 34;
  for (unsigned i = 0; i < 6; ++i) vertices[i].texture = 1;
  revision = (uint64_t(3) << 32u) | 1u;
  if (!render(false, 4) || !read(0, red)) return 35;
  float maxLayerError = 0;
  for (unsigned y = 200; y < 280; ++y) for (unsigned x = 120; x < 520; ++x)
    maxLayerError = std::max(maxLayerError, std::abs(red[y * 640 + x] - 1.f / 3));
  std::cout << "Isolated material-layer maximum error=" << maxLayerError << '\n';
  if (maxLayerError > .002f) return 36;
  // A visible reflected object must retain its texture, not just its packed
  // vertex color. Change only its material layer, outside the water rectangle.
  for (unsigned i = 0; i < 6; ++i) {
    vertices[i].texture = -2;
    vertices[i].flags = 0x40000002u;
  }
  for (unsigned i = 6; i < 12; ++i) vertices[i].texture = 1;
  settings.rtx_reflection_quality = 2;
  if (!rlw_set_visual_settings(&settings)) return 37;
  revision = (uint64_t(4) << 32u) | 1u;
  rlw_set_rtx_reflections(1);
  std::vector<float> redReflection, greyReflection;
  if (!render(false, 8) || !read(2, redReflection)) return 38;
  for (unsigned i = 6; i < 12; ++i) vertices[i].texture = 0;
  revision = (uint64_t(5) << 32u) | 1u;
  if (!render(false, 8) || !read(2, greyReflection)) return 39;
  size_t texturedHitPixels = 0;
  for (unsigned y = 180; y < 300; ++y) for (unsigned x = 80; x < 560; ++x) {
    const size_t pixel = y * 640 + x;
    if (std::abs(redReflection[pixel] - greyReflection[pixel]) > .0001f) ++texturedHitPixels;
  }
  std::cout << "Textured reflected-hit pixels=" << texturedHitPixels << '\n';
  if (texturedHitPixels < 100) return 40;
  rlw_destroy();
  DestroyWindow(hwnd);
  return 0;
}
