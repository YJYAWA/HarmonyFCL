# 第三方组件与许可证

本仓库是 [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)（GPL-3.0）的衍生作品，
因此整体以 **GPL-3.0** 分发，见 [LICENSE](LICENSE)。

下面是随本仓库或随构建产物一同分发的第三方组件。**本 fork 只修改了 MobileGlues、MobileGL
与 FoldCraftLauncher 自身的源码**，其余组件均为原样使用。

> **本次修改（2026-10-01）**：补齐随 APK 分发的许可证文本与 `assets/NOTICE.txt`（见**第四节**），
> 并补上 Terracotta / EasyTier / Rust 依赖的声明（见**第五节**）与
> 缺许可证文本组件的清单（见**第六节**）。本次**只改文档与新增许可证文本文件，
> 未改动任何源码或构建产物**。

> **许可证文本随 APK 分发。** 除了本仓库里的许可证文件，构建产物自身也携带合规信息：
> `FCL/src/main/assets/licenses/` 下的 `GPL-3.0.txt`、`LGPL-2.1.txt`、`LGPL-3.0.txt`、
> `AGPL-3.0.txt`、`EasyTier-LGPL-3.0.txt`，以及 `FCL/src/main/assets/NOTICE.txt`
> （本应用的许可证声明与完整第三方组件清单）。这些文件会随 APK 的 `assets/` 一并分发，
> 因此**只拿到 APK 的接收者也能看到许可证全文与源码获取方式**。
> 核对命令：`unzip -l HarmonyFCL-<版本>-<abi>.apk | grep -E 'assets/(licenses/|NOTICE)'`。

---

## 一、本 fork 直接修改的三个上游项目

### FoldCraftLauncher

- 上游：<https://github.com/FCL-Team/FoldCraftLauncher>
- 许可证：**GPL-3.0**（[LICENSE](LICENSE)）
- 本仓库基线版本：tag **`1.3.3.6`**
  （该 tag 的树内容与提交 `72e156685d59ac3d338a7616f76f7094b57ac34e` 之间的差异已全部并入本仓库；
  由于本 fork 是以单个压缩提交发布的、与上游**没有共同祖先**，同步方式是逐文件套用上游 diff，
  而非 `git merge`）
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

### MobileGL

- 上游：<https://github.com/MobileGL-Dev/MobileGL>
- 许可证：**LGPL-3.0**（`LICENSE` 原文写明 *GNU Lesser General Public License v3.0*，
  另有 `COPYING` 与 `COPYING.LESSER`）
- 打补丁的基线提交：**`08124c99f12ab2283cc15e4dc64ea972ecbd49c1`**（2026-09-30）
- 本 fork 的改动：见 [`patches/mobilegl-no-vulkan.patch`](patches/mobilegl-no-vulkan.patch)
  （摘除 `DirectVulkan` 后端与 `DriverPost` 诊断入口、去掉 `vulkan` 链接项、硬锁 `DirectGLES`；
  逐项说明见 [BUILD.md 第 4 节](BUILD.md#关于绝不调用-vulkan)）

**完整修改版源码在 [`third_party/MobileGL/`](third_party/MobileGL/)**，与 MobileGlues 那一侧
同样是把整棵树 vendored 进来（含构建所需的 9 个子模块与 glslang 的 2 个嵌套子模块，均已
去掉各自的 `.git`，克隆后无需初始化子模块）。任何接收本构建产物的人都可以用它自行重建、
替换 `libMobileGL.so`。

分发方式与 LGPL 合规说明：

`libMobileGL.so` 以**独立的共享库文件**随 APK 分发（路径 `lib/arm64-v8a/libMobileGL.so`），
既不是静态链接、也没有被改写进 FCL 自己的二进制里（`useLegacyPackaging = true`，
运行时解压到 `nativeLibraryDir`，替换该文件即可替换渲染器）。
配合上方的完整修改版源码，接收者具备自行重建与替换该库的全部条件。

> 📌 与 MobileGlues 那一侧的一处差别：MobileGL 的 vendored 副本**未包含
> `tools/trace_replay/fixtures/`**（上游用 Git LFS 存的 trace 回放素材，构建用不到），
> 以及 18 个被各自 `.gitignore` 排除的第三方测试/缓存文件。逐项清单见
> [`third_party/MobileGL/README.md`](third_party/MobileGL/README.md)。这些都不影响
> `libMobileGL.so` 的重建。

---

## 二、两个渲染器库内置的第三方代码

### MobileGlues 侧（vendored 在 `third_party/MobileGlues/` 下）

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

### MobileGL 侧（vendored 在 `third_party/MobileGL/` 下）

以下组件会被**静态链接进 `libMobileGL.so`**，因此属于随产物分发的部分：

| 组件 | 上游 | 固定提交 | 许可证 |
| --- | --- | --- | --- |
| glslang（MobileGL-Dev fork） | <https://github.com/MobileGL-Dev/glslang> | `d89cf443bcd220e9205defa0cc4c4b8d8f75dfa2` | MIT / Apache-2.0 / BSD-3-Clause 等混合，见其 `LICENSE.txt` |
| SPIRV-Tools（glslang 的嵌套子模块） | <https://github.com/KhronosGroup/SPIRV-Tools> | `33e02568181e3312f49a3cf33df470bf96ef293a` | Apache-2.0 |
| SPIRV-Headers（spirv-tools 的嵌套子模块） | <https://github.com/KhronosGroup/SPIRV-Headers> | `2a611a970fdbc41ac2e3e328802aed9985352dca` | MIT（Khronos） |
| SPIRV-Cross | <https://github.com/KhronosGroup/SPIRV-Cross> | `072444287f4e139c178d6d8fe32e04a0d2c34e8b` | Apache-2.0 |
| SPIRV-Reflect | <https://github.com/KhronosGroup/SPIRV-Reflect> | `10b4f09a24d7ac1603071e767c089551dc6a3949` | Apache-2.0 |
| xxHash | <https://github.com/Cyan4973/xxHash> | `1d7b2a9d21bf9a740ead540aa93f4bc4caf11ae0` | BSD-2-Clause（Copyright (c) 2012-2021 Yann Collet）。注意：这是 **Yann Collet** 的 xxhash，与上面 MobileGlues 用的 Stephan Brumme 那个**同名不同项目** |
| asio | <https://github.com/chriskohlhoff/asio> | `8806a6803cde7054c3049d3666d3ec36786568c5` | Boost Software License 1.0（Christopher M. Kohlhoff） |
| flat_hash_map（在树里位于 `include/ska`） | <https://github.com/MobileGL-Dev/flat_hash_map> | `21c1cec95abee1beef827e4a7c95f692875d9594` | Boost Software License 1.0（作者 Malte Skarupke） |

**静态库产物只有下面这些**（构建树里实测）：`libspirv-cross-{c,core,glsl,reflect}.a`、
`libspirv-reflect-static.a`、`libSPIRV-Tools.a`、`libSPIRV-Tools-opt.a`、`libglslang.a`、
`libxxhash.a`。asio 与 flat_hash_map 是头文件库，以**内联代码**形式进入 MobileGL 自己的目标文件。

上游 `.gitmodules` 声明了 12 个子模块。另有下面三个 Vulkan 相关目录**也在树里**，
但**一个都没有被编进 `libMobileGL.so`** —— 实测这三个目录下各 0 个 `.o`：

| 组件 | 上游 | 固定提交 | 许可证 |
| --- | --- | --- | --- |
| Vulkan-Headers | <https://github.com/KhronosGroup/Vulkan-Headers> | `ad9ce1235e88dc09287e19171dfac384db8ec32c` | Apache-2.0 |
| Vulkan-Utility-Libraries | <https://github.com/KhronosGroup/Vulkan-Utility-Libraries> | `738ec97a3f659dd6469bff3c4078ef981b0a343f` | Apache-2.0 |
| VulkanMemoryAllocator | <https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator> | `e722e57c891a8fbe3cc73ca56c19dd76be242759` | MIT |

`Vulkan-Headers` 只是个**编译期**依赖（`MobileGL/Includes.h` 无条件 include `vulkan/vulkan.h`），
只出头文件、不产生链接产物，因此不计入分发；删掉它反而会编不过。

还有三个子模块**是空目录，本仓库没有 vendored**（上游在这三个目录里也没放东西，构建用不到）：
`3rdparty/DiligentCore`（`f36e6388…`）、`3rdparty/tracy`（`e6b9ea46…`）、
`3rdparty/apitrace`（`c8036190…`）。

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
| Terracotta（`libterracotta.so`） | `Terracotta/` | **AGPL-3.0** —— 见下方**第五节** | 随 APK 的 `assets/licenses/AGPL-3.0.txt` |
| discord-rpc / bhook / unpack200 等原生库 | `FCL/src/main/jniLibs/*` | MIT / GPL-2.0-only + Classpath Exception | 见下方**第六节** |


> 上表的许可证名称按其附带的许可证文本判定。**权威文本以各组件自身的许可证文件为准**；
> 本仓库只原样携带，未做任何修改。表内 aar 路径可以用
> `unzip -l FCL/libs/<名字>.aar | grep licenses` 自行核对。

> 关于 `FCL/src/main/jniLibs/*/libvulkan_freedreno.so`、`libVkLayer_khronos_timeline_semaphore.so`：
> 它们是 FCL 上游为 **手动选择**的 Zink / Freedreno 渲染器准备的，**不在本 fork 的默认路径上**。
> 本 fork 新增的 MobileGlues 渲染器分支不会加载它们，构建产物里的 `libmobileglues.so`
> 也不含任何 Vulkan 引用（验证命令见 [BUILD.md](BUILD.md#关于绝不调用-vulkan)）。
>
> 另：上表 Mesa 那一行把 `libVkLayer_khronos_timeline_semaphore.so` 一并归入是不准确的，
> 它实际来自 [KhronosGroup/Vulkan-ExtensionLayer](https://github.com/KhronosGroup/Vulkan-ExtensionLayer)
> （Apache-2.0）。

---

## 四、许可证随构建产物分发（本次新增）

在此之前，本项目的合规材料只存在于**仓库**里（`LICENSE`、`NOTICE.md`、
`third_party/*/LICENSE`），而 APK 自身只带上游 FCL 原有的 19 份第三方许可证
（LWJGL / Mesa / OpenAL Soft / SDL / ANGLE 等），**没有** GPL-3.0、LGPL-2.1、LGPL-3.0 的全文，
也没有源码获取指引。而 APK 是本项目的主要分发物，只拿到 APK 的接收者看不到任何合规信息。

本次补齐，新增以下随 APK 分发的文件（源文件都在 `FCL/src/main/assets/` 下）：

| 文件 | 内容 |
| --- | --- |
| `assets/licenses/GPL-3.0.txt` | GPL-3.0 全文（本应用自身，与仓库根 `LICENSE` 相同） |
| `assets/licenses/LGPL-2.1.txt` | LGPL-2.1 全文（MobileGlues） |
| `assets/licenses/LGPL-3.0.txt` | LGPL-3.0 全文（MobileGL） |
| `assets/licenses/AGPL-3.0.txt` | AGPL-3.0 全文（Terracotta） |
| `assets/licenses/EasyTier-LGPL-3.0.txt` | LGPL-3.0 全文（EasyTier，供 Terracotta 一节引用） |
| `assets/NOTICE.txt` | 许可证声明：本应用身份与上游关系、各组件许可证与对应源码位置、Terracotta 的完整依赖清单、已知缺口 |

行尾与上游 `assets/licenses/` 内的文件一致（LF）。核对命令：

```bash
unzip -l HarmonyFCL-<版本>-<abi>.apk | grep -E 'assets/(licenses/|NOTICE)'
```

> 说明：`FCL/src/main/assets/licenses/` 在**上游 FCL 源码树里并不存在** —— APK 里原有的
> 那些许可证是构建时从各 `aar` 依赖（`kopper-zink`、`lwjgl-*`、`openal-soft`、`SDL`）
> 合并进 `assets/licenses/` 的。本 fork 现在在源码里补上同名目录，会按同样的机制进入 APK。

---

## 五、Terracotta —— `libterracotta.so`（上游预编译产物）

这一节对应上面第三节的那条 Terracotta。**它是本仓库里合规状况最差的一块**，所以单独列出。

### 5.1 基本情况

- 上游：[burningtnt/Terracotta](https://github.com/burningtnt/Terracotta)
- 许可证：**AGPL-3.0**（上游仓库默认分支 `master`，其 `LICENSE` 为 GNU Affero GPL v3 全文）
- 许可证文本：**随 APK 分发**于 `assets/licenses/AGPL-3.0.txt`
- 引入方式：`FCL/build.gradle.kts` 的 `implementation(project(":Terracotta"))`

### 5.2 本仓库里有什么

`Terracotta/` 模块**只有预编译产物，没有 Rust 源码**：

```
Terracotta/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/libterracotta.so
Terracotta/src/main/java/net/burningtnt/terracotta/TerracottaAndroidAPI.java
Terracotta/build.gradle.kts
```

**没有** `Cargo.toml`、**没有** `Cargo.lock`、**没有** `LICENSE`、**没有**任何 Rust 源码。
`TerracottaAndroidAPI.java` 本身也没有版权头。

### 5.3 这个 `.so` 里静态链接了什么

`libterracotta.so` 内部静态链接了 EasyTier 及大量 Rust 依赖。以下清单是从**产物自身内嵌的
源码路径字符串**（`.cargo/registry/src/index.crates.io-<hash>/<crate>-<version>/` 与
`.cargo/git/checkouts/<repo>-<hash>/<rev>/`）中提取的，因此版本号是可核验的：

**以 git 依赖形式引入（附完整提交哈希）：**

| 组件 | 提交 | 上游 |
| --- | --- | --- |
| EasyTier | `66c82d0a99ae6733463e37eaa3b8c91f584c4689` | <https://github.com/EasyTier/EasyTier> |
| http_req | `b10aa9fc0db3067cc3d2174683a87250b80a1ea9` | <https://github.com/jayjamesjay/http_req> |
| kcp-sys | `71eff18c573a4a71bf99c7fabc6a8b9f211c84c1` | <https://github.com/EasyTier/kcp-sys> |
| rust-tun | `12378839e7985283df0e4fb536b7137230356db5` | <https://github.com/ssrlive/rust-tun> |
| smoltcp | `0a926767a68bc88d5512afefa7529c5ecdade4ea` | <https://github.com/smoltcp-rs/smoltcp> |

**EasyTier 的许可证是 LGPL-3.0**（不是 AGPL）—— 已用 GitHub API 核实其仓库
`license.spdx_id = LGPL-3.0`，与上游 FCL README 的记载一致。
其许可证原文同样随 APK 分发于 `assets/licenses/EasyTier-LGPL-3.0.txt`。

**来自 crates.io 的依赖共 113 项**，精确名称与版本：

```
aead 0.5.2, android_system_properties 0.1.5, anyhow 1.0.102, arc-swap 1.8.2,
async-ringbuf 0.3.5, base64 0.22.1, blake2 0.10.6, block-buffer 0.10.4,
boringtun-easytier 0.6.1, bytecodec 0.4.15, byteorder 1.5.0, bytes 1.11.1, cesu8 1.1.0,
chacha20 0.10.0, chacha20 0.9.1, chacha20poly1305 0.10.1, chrono 0.4.44, cidr 0.3.2,
cipher 0.4.4, crossbeam-channel 0.5.15, crossbeam-epoch 0.9.18, curve25519-dalek 4.1.3,
dashmap 6.1.0, data-encoding 2.10.0, fastbloom 0.14.1, fixedbitset 0.5.7, flume 0.12.0,
form_urlencoded 1.2.2, futures-channel 0.3.32, futures-core 0.3.32, futures-util 0.3.32,
generic-array 0.14.7, getrandom 0.3.4, hashbrown 0.14.5, hashbrown 0.15.5, hashbrown 0.16.1,
heapless 0.9.2, hickory-proto 0.25.2, hickory-resolver 0.25.2, hickory-server 0.25.2,
hmac 0.12.1, http 1.4.0, httparse 1.10.1, iana-time-zone 0.1.65, icu_normalizer 2.1.1,
idna 1.1.0, indexmap 2.13.0, itoa 1.0.17, jni 0.21.1, lazy_static 1.5.0, lru-slab 0.1.2,
mio 1.1.1, moka 0.12.14, once_cell 1.21.3, parking_lot 0.12.5, parking_lot_core 0.9.12,
percent-encoding 2.3.2, pnet_datalink 0.35.0, pnet_packet 0.35.0, pnet_sys 0.35.0,
ppv-lite86 0.2.21, prefix-trie 0.7.0, prost 0.13.5, prost-reflect 0.14.7, prost-types 0.13.5,
quinn 0.11.9, quinn-proto 0.11.13, quinn-udp 0.5.14, rand 0.10.0, rand 0.8.5, rand 0.9.2,
rand_core 0.6.4, rand_core 0.9.5, rcgen 0.12.1, resolv-conf 0.7.6, ring 0.17.14,
ringbuf 0.4.8, rustc-demangle 0.1.27, rustc-hash 2.1.1, rustls 0.23.37, rustls-pki-types 1.14.0,
rustls-webpki 0.103.9, serde_core 1.0.228, serde_json 1.0.149, sha2 0.10.9,
signal-hook-registry 1.4.8, slab 0.4.12, smallvec 1.15.1, socket2 0.5.10, socket2 0.6.3,
stun_codec 0.3.5, tagptr 0.2.0, timedmap 1.0.1, tinyvec 1.10.0, tokio 1.50.0,
tokio-rustls 0.26.4, tokio-util 0.7.18, tokio-websockets 0.8.3, toml 0.8.23,
toml_datetime 0.6.11, toml_edit 0.22.27, toml_write 0.1.2, tracing-core 0.1.36,
universal-hash 0.5.1, untrusted 0.9.0, url 2.5.8, utf8_iter 1.0.4, uuid 1.22.0,
wildmatch 2.6.1, winnow 0.7.15, yasna 0.5.2, zerocopy 0.7.35, zstd-safe 7.2.4
```

这些 crate 各自适用其自身许可证（以 MIT / Apache-2.0 / BSD / ISC / MPL-2.0 等宽松许可为主，
其中 `ring`、`rustls`、`webpki` 等为 ISC/Apache-2.0/BSD 系），版权归各作者所有。
`libterracotta.so` 内**没有嵌入任何这些组件的版权声明或许可证文本**（实测：二进制里
`AGPL` / `LGPL` / `MIT License` / `Apache License` / `BSD` 均 0 命中，唯一的 `Copyright`
字符串是 protobuf 的 `Copyright 2008 Google Inc.`）。

### 5.4 已知缺口（如实声明）

1. `libterracotta.so` 是**上游预编译产物**，构建时剥离了除源码路径以外的信息。上列 crate
   版本由产物内嵌路径提取，**个别条目可能与实际链接版本有出入**；本仓库内没有
   `Cargo.lock` 可供交叉验证。
2. **AGPL-3.0 / LGPL-3.0 的对应源码没有随本仓库提供。** 本 fork 虽然随 APK 分发了
   AGPL-3.0 与 LGPL-3.0 的许可证全文、并列出了上游地址与提交哈希，但接收者若要行使
   "修改并重新链接该库" 的权利，需要自行从上游克隆 EasyTier 与 Terracotta 并重新构建。
   本仓库**没有** `libterracotta.so` 的构建脚本。
3. 约 100 项 Rust 依赖的**许可证原文既不在本仓库、也不在 APK 内**，只有上列的名称与
   版本作为线索。

**要做完全合规的分发，可选两条路**：(a) 从 <https://github.com/burningtnt/Terracotta>
取对应提交的源码，把 `Cargo.lock` 与该提交一并放进 `Terracotta/`，并在 `BUILD.md` 里补出
重建命令；(b) 从本构建中移除该模块（`FCL/build.gradle.kts` 去掉
`implementation(project(":Terracotta"))`，并删除 `settings.gradle.kts` 的 `include(":Terracotta")`），
代价是失去联机功能。

---

## 六、没有随附许可证文本的组件

以下组件的许可证原文**既不在本仓库、也不在 APK 内**。本节如实列出其来源与许可证类型，
完整文本请见各自上游。这些**全部来自上游 FoldCraftLauncher 的依赖，本 fork 未改动**：

| 组件 | 许可证 | 上游 | 说明 |
| --- | --- | --- | --- |
| `junrar`（Java 依赖，`gradle/libs.versions.toml`） | **UnRar License**（非自由许可） | <https://github.com/junrar/junrar> | 由上游 `libs.versions.toml` 引入；UnRAR 许可与 GPL-3.0 存在兼容性争议，上游同样如此 |
| TouchController（`top.fifthlight.touchcontroller:proxy-client-android`） | LGPL-3.0 | <https://github.com/TouchController/TouchController> | 合并进 dex，无法走「独立共享库」路线 |
| android_gamepad_remapper | LGPL-3.0 | <https://github.com/Mathias-Boulay/android_gamepad_remapper> | 同上 |
| bhook（`libbytehook.so` / `liblinkerhook.so`） | MIT | <https://github.com/bytedance/bhook> | |
| discord-rpc（`libdiscord-rpc.so`） | MIT | <https://github.com/discord/discord-rpc> | |
| control-converter（内置纯 Kotlin 移植） | MIT | <https://github.com/NingZeStudio/control-converter> | 上游 README 有致谢，本 fork 的补丁删除了上游 `README_EN.md`/`README_RU.md` 时连带丢失，此处补回 |
| `libvgpu.so` / `libng_gl4es.so` | 未确证（gl4es 派生，通常为 MIT） | 上游 FCL 携带 | 本 fork 未改动 |
| AndroidX / Kotlin / jsoup / Glide / Gson / Okio / xz / lz4 / commons-compress / nanohttpd / javassist 等约 40 个 Java 依赖 | 各自宽松许可（Apache-2.0 为主） | 见上游 FCL 的 `gradle/libs.versions.toml` | 上游 FCL 亦未在 README 中逐项列出 |

> 本节的目的是**不把"未逐项列出"伪装成"已清点完毕"**。上表之外若还有遗漏，
> 属能力所限，欢迎提 issue 指出。

---

## 七、上游跟踪的密钥材料

`key-store.jks`、`debug-key.jks`、`private_key.pepk` 都是 **FoldCraftLauncher 上游仓库本身就跟踪**
的文件（`debug-key.jks` 的口令 `FCL-Debug` 写在上游的 `build.gradle.kts` 里），本 fork 原样保留，
仅用于「没有自签名密钥时的构建回落」。

本 fork **自己的**签名密钥 `harmony-fcl.jks` **不在本仓库中**（见 `.gitignore`），
生成方式见 [BUILD.md](BUILD.md#签名密钥)。
