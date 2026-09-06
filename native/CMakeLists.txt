cmake_minimum_required(VERSION 3.24)
project(runelite_webgpu_native VERSION 0.1.0 LANGUAGES C CXX)

if (NOT WIN32)
    message(FATAL_ERROR "The RuneLite WebGPU bridge currently targets Windows")
endif ()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(RLW_ENABLE_NVIDIA_FEATURES "Build Streamline/DLSS and RTX ray-query integration" ON)
option(RLW_ENABLE_DLSS5_NEURAL_RENDERING
    "Build the locally provisioned, pre-release DLSS 5 Neural Rendering bridge" OFF)

# This pinned wgpu-native build is copied into the standalone project. It is
# based on v29.0.1.1 and adds Vulkan context/image/command-buffer accessors;
# RuneLite never loads or links to ThreeBrowserNVNGX itself.
set(_wgpu_root "${CMAKE_CURRENT_SOURCE_DIR}/third_party/wgpu-native")
set(_wgpu_include_dir "${_wgpu_root}/include/webgpu")
set(_wgpu_dll_file "${_wgpu_root}/lib/wgpu_native.dll")
set(_wgpu_link_file "${_wgpu_root}/lib/libwgpu_native.dll.a")
if (NOT EXISTS "${_wgpu_include_dir}/webgpu.h" OR
    NOT EXISTS "${_wgpu_include_dir}/wgpu.h" OR
    NOT EXISTS "${_wgpu_dll_file}" OR
    NOT EXISTS "${_wgpu_link_file}")
    message(FATAL_ERROR "The standalone patched wgpu-native package is incomplete")
endif ()

if (RLW_ENABLE_NVIDIA_FEATURES)
    find_package(Vulkan REQUIRED)
    set(RLW_STREAMLINE_VERSION "2.12.0" CACHE STRING "NVIDIA Streamline SDK version")
    set(_streamline_root "${CMAKE_BINARY_DIR}/_deps/streamline-sdk-v${RLW_STREAMLINE_VERSION}")
    set(_streamline_zip "${CMAKE_BINARY_DIR}/_deps/streamline-sdk-v${RLW_STREAMLINE_VERSION}.zip")
    if (NOT EXISTS "${_streamline_root}/include/sl.h")
        file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/_deps" "${_streamline_root}")
        file(DOWNLOAD
            "https://github.com/NVIDIAGameWorks/Streamline/releases/download/v${RLW_STREAMLINE_VERSION}/streamline-sdk-v${RLW_STREAMLINE_VERSION}.zip"
            "${_streamline_zip}"
            EXPECTED_HASH "SHA256=F5C0A3D870707DDDC3570FB4BCD3655CF48A8A68C3A9D342910CFA21B77DCF48"
            TLS_VERIFY ON SHOW_PROGRESS STATUS _streamline_download)
        list(GET _streamline_download 0 _streamline_code)
        if (NOT _streamline_code EQUAL 0)
            list(GET _streamline_download 1 _streamline_message)
            message(FATAL_ERROR "Unable to download NVIDIA Streamline: ${_streamline_message}")
        endif ()
        file(ARCHIVE_EXTRACT INPUT "${_streamline_zip}" DESTINATION "${_streamline_root}")
    endif ()
    if (NOT EXISTS "${_streamline_root}/bin/x64/sl.interposer.dll")
        message(FATAL_ERROR "The pinned Streamline SDK has an unexpected layout")
    endif ()

    # DLSS 5 Neural Rendering has not yet been published in the public
    # Streamline SDK.  This machine already has the NVIDIA-signed 2.13 preview
    # runtime copied into the standalone RuneLite repository.  Pin every file
    # by SHA-256 so a different or partially-updated local runtime can never be
    # loaded accidentally.  Nothing is read from the ThreeBrowser checkout.
    set(RLW_DLSS5_RUNTIME_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../.runtime/webgpu" CACHE PATH
        "Directory containing the locally provisioned NVIDIA DLSS 5 preview runtime")
    if (RLW_ENABLE_DLSS5_NEURAL_RENDERING)
        set(_dlss5_runtime_files
            "sl.interposer.dll|27B2190057994C0B287C2C5716953BF1586F6499AC12FBBB2092B9AAF8396570"
            "sl.common.dll|A4B2B5ACBE49FBC6D44DD432CAC19CD53218F698B2539DC7ED0FB268C72CFC8D"
            "sl.dlss.dll|1EB5FB3D6F01D340FE086D981CC2DE4F18AA6D05EE276E5CF28ECD54818DCC8B"
            "sl.dlss_g.dll|B8B5EFFD7DEBDB750ABD216DE43385FB653261712BC315D85EBA68811FB3EE02"
            "sl.dlss_nr.dll|9F6672E5E0170DC118A3188D21BDA187E1FC1AA3502895B21AB846D23165C11D"
            "sl.pcl.dll|12AA4E76C28A27C735E4ECB3072F44D09428ACB107B70AC38E4BD48DDB05F88D"
            "sl.reflex.dll|ECF12973CDCEC2FFCED2EA77B1C7E45F4D387E7C864DDB5531B66A6F947EFFB3"
            "nvngx_dlss.dll|C85F971CE023C9F3492FC7455F0B01A24BA18EA39636407A846902C4360B0B7E"
            "nvngx_dlssg.dll|5D5CBF14D2727D47F93FD10BF77BD91708AE122482A6F86FD564971641EBD47B"
            "nvngx_dlssnr.dll|E16BCF15E16E13F527491CDF7845B2FE6521A738D8F7C9C721866A8496E1FC8E")
        foreach (_dlss5_entry IN LISTS _dlss5_runtime_files)
            string(REPLACE "|" ";" _dlss5_parts "${_dlss5_entry}")
            list(GET _dlss5_parts 0 _dlss5_name)
            list(GET _dlss5_parts 1 _dlss5_expected_hash)
            set(_dlss5_path "${RLW_DLSS5_RUNTIME_DIR}/${_dlss5_name}")
            if (NOT EXISTS "${_dlss5_path}")
                message(FATAL_ERROR
                    "DLSS 5 Neural Rendering was requested, but ${_dlss5_name} is missing from ${RLW_DLSS5_RUNTIME_DIR}")
            endif ()
            file(SHA256 "${_dlss5_path}" _dlss5_actual_hash)
            string(TOUPPER "${_dlss5_actual_hash}" _dlss5_actual_hash)
            if (NOT _dlss5_actual_hash STREQUAL _dlss5_expected_hash)
                message(FATAL_ERROR
                    "Refusing unpinned DLSS 5 runtime ${_dlss5_name}: ${_dlss5_actual_hash}")
            endif ()
        endforeach ()
    endif ()

    find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslang-validator
        HINTS "$ENV{VULKAN_SDK}/Bin" "C:/VulkanSDK/1.4.357.0/Bin" REQUIRED)

    function(rlw_compile_vulkan_shader target shader_src var_name variant)
        get_filename_component(_shader_name "${shader_src}" NAME)
        set(_generated_dir "${CMAKE_BINARY_DIR}/generated/threepp/renderers/vulkan/shaders")
        file(MAKE_DIRECTORY "${_generated_dir}")
        if (variant)
            set(_header "${_generated_dir}/${_shader_name}.${variant}.spv.h")
        else ()
            set(_header "${_generated_dir}/${_shader_name}.spv.h")
        endif ()
        set(_defines ${ARGN})
        list(TRANSFORM _defines PREPEND "-D")
        add_custom_command(OUTPUT "${_header}"
            COMMAND "${GLSLANG_VALIDATOR}" -V --target-env vulkan1.3 -Os
                "-I${CMAKE_CURRENT_SOURCE_DIR}/third_party/nvidia-bridge/shaders"
                ${_defines} --vn "${var_name}" "${shader_src}" -o "${_header}"
            DEPENDS "${shader_src}" VERBATIM)
        target_sources(${target} PRIVATE "${_header}")
        target_include_directories(${target} PRIVATE "${CMAKE_BINARY_DIR}/generated")
    endfunction()
endif ()

add_library(runelite_webgpu SHARED
    src/runelite_webgpu.cpp)
if (RLW_ENABLE_NVIDIA_FEATURES)
    if (RLW_ENABLE_DLSS5_NEURAL_RENDERING)
        add_library(runelite_nvngx SHARED
            third_party/nvidia-bridge/dlss5_probe_proxy.cpp)
        set_target_properties(runelite_nvngx PROPERTIES
            PREFIX ""
            OUTPUT_NAME "runelite_nvngx"
            RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
            LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
        if (MINGW)
            target_link_options(runelite_nvngx PRIVATE
                -static -static-libgcc -static-libstdc++)
        endif ()
        add_dependencies(runelite_webgpu runelite_nvngx)
    endif ()
    target_sources(runelite_webgpu PRIVATE
        third_party/nvidia-bridge/streamline_bridge.cpp
        third_party/nvidia-bridge/ray_query_bridge.cpp
        third_party/nvidia-bridge/shader_compiler.cpp)
    target_compile_definitions(runelite_webgpu PRIVATE
        THREEBROWSER_STREAMLINE=1 THREEBROWSER_RAY_QUERY=1)
    if (RLW_ENABLE_DLSS5_NEURAL_RENDERING)
        target_compile_definitions(runelite_webgpu PRIVATE
            THREEBROWSER_EXPERIMENTAL_DLSS_NR=1)
    endif ()
    target_include_directories(runelite_webgpu PRIVATE
        "${_streamline_root}/include" "${Vulkan_INCLUDE_DIRS}"
        "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nvidia-bridge")
    set(_shader_root "${CMAKE_CURRENT_SOURCE_DIR}/third_party/nvidia-bridge/shaders")
    rlw_compile_vulkan_shader(runelite_webgpu
        "${_shader_root}/ray_query_lighting.comp" kRayQueryLightingCompSpv "")
    rlw_compile_vulkan_shader(runelite_webgpu
        "${_shader_root}/ray_query_reflections.comp" kRayQueryReflectionsCompSpv "")
    rlw_compile_vulkan_shader(runelite_webgpu
        "${_shader_root}/ray_query_reflections.comp" kRayQueryReflectionsV2R16CompSpv
        "reflections_v2_r16" THREEBROWSER_REFLECTIONS_V2=1)
    rlw_compile_vulkan_shader(runelite_webgpu
        "${_shader_root}/ray_query_reflections.comp" kRayQueryReflectionsV2R32CompSpv
        "reflections_v2_r32" THREEBROWSER_REFLECTIONS_V2=1 THREEBROWSER_HIT_DISTANCE_R32=1)
endif ()
target_compile_definitions(runelite_webgpu PRIVATE
    RLW_BUILD_DLL=1
    WGPU_SHARED_LIBRARY=1)
target_include_directories(runelite_webgpu
    PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/include"
    PRIVATE "${_wgpu_include_dir}")
target_link_libraries(runelite_webgpu PRIVATE
    "${_wgpu_link_file}"
    user32 gdi32 bcrypt advapi32)
set_target_properties(runelite_webgpu PROPERTIES
    PREFIX ""
    OUTPUT_NAME "runelite_webgpu"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
if (MINGW)
    target_link_options(runelite_webgpu PRIVATE -static -static-libgcc -static-libstdc++)
endif ()

add_custom_command(TARGET runelite_webgpu POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${_wgpu_dll_file}" "$<TARGET_FILE_DIR:runelite_webgpu>/wgpu_native.dll")
if (RLW_ENABLE_NVIDIA_FEATURES)
    set(_streamline_runtimes
        sl.interposer.dll sl.common.dll sl.dlss.dll sl.dlss_g.dll sl.dlss_d.dll
        sl.reflex.dll sl.pcl.dll NvLowLatencyVk.dll nvngx_dlss.dll nvngx_dlssg.dll
        nvngx_dlssd.dll)
    foreach (_runtime IN LISTS _streamline_runtimes)
        if (EXISTS "${_streamline_root}/bin/x64/${_runtime}")
            add_custom_command(TARGET runelite_webgpu POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "${_streamline_root}/bin/x64/${_runtime}"
                    "$<TARGET_FILE_DIR:runelite_webgpu>/${_runtime}")
        endif ()
    endforeach ()
    if (RLW_ENABLE_DLSS5_NEURAL_RENDERING)
        # Copy the complete matching 2.13 feature subset after the public 2.12
        # runtime. Files not supplied by the preview package (notably the
        # DLSS-RR plug-in) remain present for non-preview builds, but the
        # preview bridge deliberately does not request or expose them.
        foreach (_dlss5_entry IN LISTS _dlss5_runtime_files)
            string(REPLACE "|" ";" _dlss5_parts "${_dlss5_entry}")
            list(GET _dlss5_parts 0 _dlss5_name)
            add_custom_command(TARGET runelite_webgpu POST_BUILD
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                    "${RLW_DLSS5_RUNTIME_DIR}/${_dlss5_name}"
                    "$<TARGET_FILE_DIR:runelite_webgpu>/${_dlss5_name}")
        endforeach ()
    endif ()
endif ()

add_executable(runelite_webgpu_smoke tests/smoke.cpp)
target_link_libraries(runelite_webgpu_smoke PRIVATE runelite_webgpu user32)
set_target_properties(runelite_webgpu_smoke PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
if (MINGW)
    target_link_options(runelite_webgpu_smoke PRIVATE -static -static-libgcc -static-libstdc++)
endif ()

enable_testing()
add_executable(runelite_scene_draw_order_test tests/scene_draw_order.cpp)
target_include_directories(runelite_scene_draw_order_test PRIVATE include)
if (MINGW)
    target_link_options(runelite_scene_draw_order_test PRIVATE -static -static-libgcc -static-libstdc++)
endif ()
add_test(NAME scene_draw_order COMMAND runelite_scene_draw_order_test)

add_executable(runelite_shadow_pixels_test tests/shadow_pixels.cpp)
target_link_libraries(runelite_shadow_pixels_test PRIVATE runelite_webgpu user32)
set_target_properties(runelite_shadow_pixels_test PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin")
if (MINGW)
    target_link_options(runelite_shadow_pixels_test PRIVATE -static -static-libgcc -static-libstdc++)
endif ()
add_test(NAME shadow_reflection_pixels COMMAND runelite_shadow_pixels_test)
set_tests_properties(shadow_reflection_pixels PROPERTIES TIMEOUT 45 LABELS "gpu")

add_test(NAME dlss_offset_viewport_dlaa COMMAND runelite_webgpu_smoke)
add_test(NAME dlss_offset_viewport_quality COMMAND runelite_webgpu_smoke --quality)
set_tests_properties(dlss_offset_viewport_dlaa dlss_offset_viewport_quality
    PROPERTIES TIMEOUT 45 LABELS "gpu" RUN_SERIAL TRUE)
set_tests_properties(shadow_reflection_pixels PROPERTIES RUN_SERIAL TRUE)
