# MobileGlues（本仓库内置的修改版）

这是 [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) 的**源码副本**，被
[HarmonyFCL](../..) 用作内置渲染器。放在这里而不是以插件 APK 的形式分发，是为了：
**任何人 clone 本仓库都能自行重建 `libmobileglues.so`**（LGPL-2.1 合规，见 [NOTICE.md](../../NOTICE.md)）。

> 这是本仓库的 vendored 副本，**不要**把它当成上游仓库使用 —— 上游在
> <https://github.com/MobileGL-Dev/MobileGlues>。

## 版本与来源

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/MobileGL-Dev/MobileGlues> |
| 对应版本 | `2.0.0`（`MobileGlues-cpp/version.h` 中 `VERSION_TYPE = VERSION_RELEASE`） |
| 源码快照 | 上游 `main` 分支，2026-09-23 拉取 |
| 许可证 | **LGPL-2.1-only**，见 [LICENSE](LICENSE) |

## 本 fork 对它的改动

只有一处，见 [`../../patches/mobileglues-no-vulkan.patch`](../../patches/mobileglues-no-vulkan.patch)：

- `MobileGlues-cpp/config/gpu_utils.cpp`：删掉 `#include "vulkan/vulkan.h"` 与 `vk_lib` 数组；
  `hasVulkan12()` 整个实现体替换为 `return 0;`；`checkIfANGLESupported()` 直接 `return false;`
- `MobileGlues-cpp/config/settings.cpp`：删掉 `hasVulkan12()` 调用与相关日志；读取配置后
  **无条件**覆盖 `angleConfig = ForceDisable`、`angleDepthClearFixMode = Disabled`

即**源码级移除全部 Vulkan 调用路径**，不是配置级开关。理由与可复现的验证命令见
[BUILD.md](../../BUILD.md#关于绝不调用-vulkan)。

这一处改动以本仓库的补丁形式记录，所以也可以拿上游原始源码 + 应用该补丁得到同样的结果：

```bash
git clone https://github.com/MobileGL-Dev/MobileGlues
cd MobileGlues
git apply -p1 ../HarmonyFCL/patches/mobileglues-no-vulkan.patch
```

## 子模块（已 vendored）

上游通过 `.gitmodules` 引用四个子模块。本仓库把它们的**内容**直接放了进来（去掉了各自的
`.git` 目录），所以克隆后不需要初始化子模块就能构建：

| 路径 | 上游 | 固定提交 |
| --- | --- | --- |
| `MobileGlues-cpp/3rdparty/glslang` | <https://github.com/KhronosGroup/glslang> | `f5f664dee8146676b04a332a7233959fc3ce9681` |
| `MobileGlues-cpp/3rdparty/SPIRV-Cross` | <https://github.com/KhronosGroup/SPIRV-Cross> | `a0fba56c34a6700f1724bf9b751da5b488a3775c` |
| `MobileGlues-cpp/3rdparty/xxhash` | <https://github.com/stbrumme/xxhash> | `c2866db364b6ea3a11933e62235ddc166ba18565` |
| `MobileGlues-cpp/include/ska` | <https://github.com/MobileGL-Dev/flat_hash_map> | `21c1cec9abee1beef827e4a7c95f692875d9594` |

`MobileGlues-cpp/3rdparty/perfetto` 是空目录：上游只在开启性能追踪时才需要它，
构建 `libmobileglues.so` 用不到，所以没有 vendored。

此外还有两项第三方代码**直接位于 MobileGlues 源码树内**（不是子模块）：
cJSON（`MobileGlues-cpp/config/cJSON.*`，MIT）与 FidelityFX-FSR1（`MobileGlues-cpp/gl/FSR1/`，MIT）。
完整的第三方组件与许可证清单见 [NOTICE.md](../../NOTICE.md)。

## 构建

见 [BUILD.md](../../BUILD.md#2-构建-mobileglues--libmobilegluesso)。
产物 `build-<abi>/libmobileglues.so` 需要 strip 后拷进 `FCL/src/main/jniLibs/<abi>/`。

---

# 以下是上游 MobileGlues 仓库的原始 README（原样保留）

# MobileGlues

**MobileGlues**, which stands for "(on) Mobile, GL uses ES", is a GL implementation running on top of host OpenGL ES 3.x (best on 3.2, minimum 3.0), with running Minecraft: Java Edition in mind.

# For Shader Developers

1. MobileGlues automatically:
   - Converts desktop GLSL → GLSL ES
   - Removes `layout(binding)` syntax
   - Handles version directives
   - Always declare precision explicitly:
     ```glsl
     precision highp float;
     precision highp int;
     ```

2. MobileGlues (since V1.2.6) injects these macros into your shaders:
   ```glsl
   #define MG_MOBILEGLUES                   // Indicates MobileGlues environment
   #define MG_MOBILEGLUES_VERSION 1260      // Version number (e.g. 1260 = V1.2.6)
   ```

   Use these macros for platform-specific logic:
   ```glsl
   #ifdef MG_MOBILEGLUES
       #if MG_MOBILEGLUES_VERSION >= 1270
           // Logic for MobileGlues (version >= V1.2.7)
       #else
           // Logic for MobileGlues (version < V1.2.7)
       #endif
   #else
       // ...
   #endif
   ```

3. If encountering issues:
   - Enable `Ignore shader/program error`, and check the logs (located at `/sdcard/MG/latest.log`).

# License

MobileGlues is licensed under **GNU LGPL-2.1 License**.

Please see [LICENSE](https://github.com/MobileGL-Dev/MobileGlues/blob/main/LICENSE).

# Third-party components

**SPIRV-Cross** by **KhronosGroup** - [Apache License 2.0](https://github.com/KhronosGroup/SPIRV-Cross/blob/master/LICENSE): [github](https://github.com/KhronosGroup/SPIRV-Cross)

**glslang** by **KhronosGroup** - [Various Licenses](https://github.com/KhronosGroup/glslang/blob/main/LICENSE.txt): [github](https://github.com/KhronosGroup/glslang)

**cJSON** by **DaveGamble** - [MIT License](https://github.com/DaveGamble/cJSON/blob/master/LICENSE): [github](https://github.com/DaveGamble/cJSON)

**FidelityFX-FSR** by **AMD** - [MIT License](https://github.com/GPUOpen-Effects/FidelityFX-FSR/blob/master/license.txt): [github](https://github.com/GPUOpen-Effects/FidelityFX-FSR) 

**Perfetto** by **Google** - [Apache License 2.0](https://github.com/google/perfetto/blob/main/LICENSE): [github](https://github.com/google/perfetto)

**xxHash** by **Yann Collet** - [BSD 2-Clause License](https://github.com/Cyan4973/xxHash/blob/dev/LICENSE): [github](https://github.com/Cyan4973/xxHash)

**flat_hash_map** by **Malte Skarupke** - [Boost Software License 1.0](https://github.com/MobileGL-Dev/flat_hash_map/blob/master/LICENSE): [github](https://github.com/MobileGL-Dev/flat_hash_map) (fork of [skarupke/flat_hash_map](https://github.com/skarupke/flat_hash_map), carrying a fix that lets the header be included on 32-bit targets)
