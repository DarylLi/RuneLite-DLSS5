# Third-party notices

Maintainer: [SamGCoder](https://github.com/SamG-Coder).
The root BSD-2-Clause licence applies to original project contributions and
RuneLite-derived code where applicable. It does not override component licences,
asset-specific conditions, vendor runtime terms, or existing copyright headers.

| Component | Source / credit | Licence or terms retained |
| --- | --- | --- |
| RuneLite | https://github.com/runelite/runelite | `LICENSES/RuneLite-BSD-2-Clause.txt`; existing per-file notices |
| 117 HD | https://github.com/117HD/RLHD at `3f77aea983c862ca362e0c857a1d7dfa4d84ca86` | `plugins/117-hd/LICENSE`, `LICENSES/117HD-BSD-2-Clause.txt` |
| 117 HD textures | 117 and contributors/providers listed in the asset notice | `LICENSES/117HD-texture-attributions.txt` and original `000_licenses.txt`; includes CC0, BSD, Unsplash, permission-based assets, and Jagex policy exceptions |
| ThreeBrowser / threepp-derived bridge | SamGCoder; Lars Ivar Hatledal and threepp contributors; https://github.com/markaren/threepp | `native/third_party/nvidia-bridge/LICENSE`, `LICENSES/ThreeBrowser-threepp-MIT.txt` |
| wgpu-native | https://github.com/gfx-rs/wgpu-native at `6aed50955d934ac36049ba8d002034841633ae02` | `LICENSES/wgpu-native-MIT.txt`, `LICENSES/wgpu-native-Apache-2.0.txt` |
| wgpu | https://github.com/gfx-rs/wgpu at `923b896955655600c38ac93d6e9ae26845682f80` | MIT OR Apache-2.0; native library is supplied separately |
| WebGPU C headers | https://github.com/webgpu-native/webgpu-headers | `LICENSES/WebGPU-Headers-BSD-3-Clause.txt`; original header notices |
| Streamline source SDK | https://github.com/NVIDIA-RTX/Streamline | `LICENSES/Streamline-source-MIT.txt`; fetched SDK carries additional third-party notices |
| NVIDIA DLSS/NGX/Reflex runtime components | https://developer.nvidia.com/rtx/streamline | Separately supplied vendor components, not covered by this repository's BSD licence; consult the exact SDK/runtime package's terms |
| Gradle wrapper | https://github.com/gradle/gradle | Apache-2.0; wrapper licence headers retained |

Java dependencies (including RuneLite client/jshell, JNA, Lombok, JUnit, Mockito,
and their transitive dependencies) are resolved through Gradle rather than
published as vendor JARs here. A binary distributor must preserve the notices for
the exact resolved dependencies. No public binary release is provided by this
source publication.

The original 117 HD texture notice explicitly excludes certain Jagex-derived
textures from BSD and points to the [Jagex Fan Content Policy](https://legal.jagex.com/docs/policies/fan-content-policy).
We preserve that distinction and do not grant independent rights to game IP.
The source asset notice is authoritative where a summary here is less specific.

The patched wgpu-native prebuilt library, NVIDIA runtime DLLs, extracted game
cache, user configuration, accounts, and session data are excluded from this
public repository. No general redistribution grant for those files is implied.
