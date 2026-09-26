# 第三方组件与许可证

本仓库是 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）的衍生作品，
因此整体以 **GPL-3.0** 分发，见 [LICENSE](LICENSE)。

下面是随本仓库或随构建产物一同分发的第三方组件。**本 fork 只修改了 MobileGlues 与
FoldCraftLauncher 自身的源码**，其余组件均为原样使用。

---

## 一、本 fork 直接修改的两个上游项目

### FoldCraftLauncher

- 上游：<https://github.com/FCL-Team/FoldCraftLauncher>
- 许可证：**GPL-3.0**（[LICENSE](LICENSE)）
- 本仓库基线提交：`72e156685d59ac3d338a7616f76f7094b57ac34e`
- 本 fork 的改动：见 [`patches/fcl-embed-mobileglues.patch`](patches/fcl-embed-mobileglues.patch)
  （内置 MobileGlues 渲染器、改默认渲染器与默认 JVM 参数、改包名与应用名、`-Darch` 支持架构列表）

### MobileGlues

- 上游：<https://github.com/MobileGL-Dev/MobileGlues>
- 许可证：**LGPL-2.1-only**（原文见 [`third_party/MobileGlues/LICENSE`](third_party/MobileGlues/LICENSE)）
- 本仓库内置版本：`2.0.0`（`version.h` 为 `VERSION_RELEASE`）
- 本 fork 的改动：见 [`patches/mobileglues-no-vulkan.patch`](patches/mobileglues-no-vulkan.patch)
  （源码级移除全部 Vulkan 调用路径 + 强制关闭 ANGLE）

**完整修改版源码在 [`third_party/MobileGlues/`](third_party/MobileGlues/)**，
包含构建所需的四个子模块内容（已 vendored，无需额外克隆）。
任何接收本构建产物的人都可以用它自行重建、替换 `libmobileglues.so`。

分发方式与 LGPL 合规说明：

`libmobileglues.so` 以**独立的共享库文件**随 APK 分发（路径 `lib/<abi>/libmobileglues.so`），
既不是静态链接、也没有被改写进 FCL 自己的二进制里（`useLegacyPackaging = true`，
运行时解压到 `nativeLibraryDir`，替换该文件即可替换渲染器）。
配合上方的完整修改版源码，接收者具备自行重建与替换该库的全部条件。

> GPL-3.0 与 LGPL-2.1-only 之间的兼容性在自由软件社区一直有争议（两者条款方向不同）。
> 本项目采取的规避方式就是上面这一条：**动态链接 + 提供完整对应源码**。
> 如果你是下游再分发者，请自行确认这个安排是否满足你的使用场景。

---

## 二、MobileGlues 内置的第三方代码（vendored 在 `third_party/MobileGlues/` 下）

上游通过 `.gitmodules` 引用的四个子模块（内容已 vendored，去掉各自的 `.git`）：

| 组件 | 上游 | 固定提交 | 许可证 |
| --- | --- | --- | --- |
| glslang | <https://github.com/KhronosGroup/glslang> | `f5f664dee8146676b04a332a7233959fc3ce9681` | BSD-3-Clause / Apache-2.0 / MIT 等混合，见其 `LICENSE.txt` |
| SPIRV-Cross | <https://github.com/KhronosGroup/SPIRV-Cross> | `a0fba56c34a6700f1724bf9b751da5b488a3775c` | Apache-2.0 |
| xxhash | <https://github.com/stbrumme/xxhash> | `c2866db364b6ea3a11933e62235ddc166ba18565` | MIT（Copyright (c) 2018 Stephan Brumme）。注意：这是 Stephan Brumme 的 xxhash 实现，**不是** Yann Collet 的同名项目 |
| flat_hash_map（在树里位于 `include/ska`） | <https://github.com/MobileGL-Dev/flat_hash_map> | `21c1cec9abee1beef827e4a7c95f692875d9594` | Boost Software License 1.0（作者 Malte Skarupke） |

另外两项**直接放在 MobileGlues 源码树里**（不是子模块）：

| 组件 | 位置 | 上游 | 许可证 |
| --- | --- | --- | --- |
| cJSON | `MobileGlues-cpp/config/cJSON.c` / `cJSON.h` | <https://github.com/DaveGamble/cJSON> | MIT |
| FidelityFX-FSR（FSR1） | `MobileGlues-cpp/gl/FSR1/` | <https://github.com/GPUOpen-Effects/FidelityFX-FSR> | MIT |

上游 README 还署明了 **Perfetto**（Google，Apache-2.0）：本仓库**未 vendored** 它，因为
`MobileGlues-cpp/3rdparty/perfetto/` 只在开启性能追踪时才用到，构建 `libmobileglues.so` 不需要，
上游仓库里该子模块目录本身也是空的。

各目录内保留了其原始许可证文件。

---

## 三、随 APK 分发的其他组件（均来自 FoldCraftLauncher 上游，本 fork 未改动）

| 组件 | 位置 | 许可证 | 许可证文本位置 |
| --- | --- | --- | --- |
| GL4ES | `FCL/src/main/jniLibs/*/libgl4es_114.so`、`FCL/libs/NG-GL4ES-release.aar` | MIT | 上游仓库 |
| ANGLE | `FCL/src/main/jniLibs/*/libEGL_angle.so`、`libGLESv2_angle.so` | BSD-3-Clause | 上游仓库 |
| Mesa（OSMesa / freedreno / virgl / koppper-zink） | `FCL/src/main/jniLibs/*/libOSMesa_*.so`、`libvulkan_freedreno.so`、`libvirgl*`、`FCL/libs/kopper-zink-release.aar` | MIT 系（Mesa 的 License and Copyright 声明） | `kopper-zink-release.aar` 内 `assets/licenses/mesa-licenses.rst` |
| LWJGL | `FCL/libs/lwjgl-*-natives-release.aar`、`LWJGL/` | BSD-3-Clause | 同 aar 内 `assets/licenses/lwjgl_license.txt`，另有 12 份随库依赖的许可证（glfw / freetype / libffi / liburing / nanosvg / nanovg / openal_soft / shaderc / tinyfd / vma / blendish / khronos） |
| OpenAL Soft | `FCL/libs/openal-soft-release.aar`、`lwjgl-*-natives-release.aar` 内 | LGPL-2.0-or-later | 同 aar 内 `assets/licenses/openal_soft_license.txt`（原文为 *GNU Library General Public License Version 2*；`openal-soft-release.aar` 里那份文件名写作 `OPENAL-SOFT_GPL2`，但内容同样是 LGPL-2.0） |
| SDL2 / sdl2-compat | `FCL/libs/SDL-release.aar` | Zlib | 同 aar 内 `assets/licenses/sdl3-zlib-license.txt`、`sdl2-compat-zlib-license.txt` |
| SPIRV-Cross（Android 原生库） | `FCL/libs/spirv-cross-natives.aar` | Apache-2.0 | 上游仓库 |
| JNA | `FCL/src/main/assets/app_runtime/jna/` | Apache-2.0 / LGPL-2.1（双许可） | 上游仓库 |
| Caciocavallo | `FCL/src/main/assets/app_runtime/caciocavallo*/` | GPL-2.0-with-Classpath-Exception | 上游仓库 |
| Java 运行时（jre8 / jre17 / jre21 / jre25） | `FCL/src/main/jreAssets/app_runtime/java/` | GPL-2.0-with-Classpath-Exception（OpenJDK 标准布局） | 各资产内 `legal/` 目录逐模块给出 |
| ZipFileSystem（`jdk.nio.zipfs`） | `ZipFileSystem/` | Oracle 的 BSD-3-Clause 风格许可 | `ZipFileSystem/src/main/resources/LICENSE` |
| androidnsbypass | `FCL/src/main/jni/androidnsbypass/` | MIT | 同目录 `LICENSE`、`LICENSE-BSD-2-Clause` |
| Terracotta、discord-rpc、unpack200 等 | `Terracotta/`、`FCL/src/main/jniLibs/*` | 见各自上游仓库 | 同左 |

> 上表的许可证名称按其附带的许可证文本判定。**权威文本以各组件自身的许可证文件为准**；
> 本仓库只原样携带，未做任何修改。表内 aar 路径可以用
> `unzip -l FCL/libs/<名字>.aar | grep licenses` 自行核对。

> 关于 `FCL/src/main/jniLibs/*/libvulkan_freedreno.so`、`libVkLayer_khronos_timeline_semaphore.so`：
> 它们是 FCL 上游为 **手动选择**的 Zink / Freedreno 渲染器准备的，**不在本 fork 的默认路径上**。
> 本 fork 新增的 MobileGlues 渲染器分支不会加载它们，构建产物里的 `libmobileglues.so`
> 也不含任何 Vulkan 引用（验证命令见 [BUILD.md](BUILD.md#关于绝不调用-vulkan)）。

## 四、上游跟踪的密钥材料

`key-store.jks`、`debug-key.jks`、`private_key.pepk` 都是 **FoldCraftLauncher 上游仓库本身就跟踪**
的文件（`debug-key.jks` 的口令 `FCL-Debug` 写在上游的 `build.gradle.kts` 里），本 fork 原样保留，
仅用于「没有自签名密钥时的构建回落」。

本 fork **自己的**签名密钥 `harmony-fcl.jks` **不在本仓库中**（见 `.gitignore`），
生成方式见 [BUILD.md](BUILD.md#签名密钥)。
