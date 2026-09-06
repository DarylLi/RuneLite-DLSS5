#include <cstdint>

// NVIDIA's preview feature snippet checks that its immediate caller lives in
// a module whose path contains the case-sensitive suffix "nvngx.dll". Keep
// this tiny call proxy in its own RuneLite-owned DLL so capability discovery
// reaches the signed snippet without loading code from ThreeBrowser or
// modifying NVIDIA files on disk.
using GetFeatureRequirements = uint32_t(
    void* instance, void* physicalDevice, const void* discovery,
    void* requirement);

extern "C" __declspec(dllexport) __declspec(noinline)
uint32_t runelite_nvngx_get_feature_requirements(
    void* target, void* instance, void* physicalDevice, const void* discovery,
    void* requirement) {
    const auto function = reinterpret_cast<GetFeatureRequirements*>(target);
    if (!function) return 0xbad00005u;

    // Force a real CALL and retain work after it. A tail jump would expose the
    // main renderer DLL as the return-address owner and re-trigger the guard.
    volatile uint32_t result =
        function(instance, physicalDevice, discovery, requirement);
    return result;
}
