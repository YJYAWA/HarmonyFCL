# MobileGL（本仓库内置的修改版）

这是 [MobileGL](https://github.com/MobileGL-Dev/MobileGL) 的**源码副本**，被
[HarmonyFCL](../..) 用作内置渲染器之一（MC 26.3 及以上的实例会自动切到它）。放在这里而不是
以预编译产物或插件的形式分发，是为了：**任何人 clone 本仓库都能自行重建 `libMobileGL.so`**
（LGPL-3.0 合规，见 [NOTICE.md](../../NOTICE.md)）。

> 这是本仓库的 vendored 副本，**不要**把它当成上游仓库使用 —— 上游在
> <https://github.com/MobileGL-Dev/MobileGL>。

## 版本与来源

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/MobileGL-Dev/MobileGL> |
| 打补丁的基线提交 | `08124c99f12ab2283cc15e4dc64ea972ecbd49c1`（2026-09-30） |
| 许可证 | **LGPL-3.0**，见 [LICENSE](LICENSE)、[COPYING](COPYING)、[COPYING.LESSER](COPYING.LESSER) |

上游**不发任何预编译产物**（Releases 与 Tags 都是空的），仓库里那份 `libMobileGL.so`
就是这个提交加上下面的补丁编出来的。

## 本 fork 对它的改动

只有一处，见 [`../../patches/mobilegl-no-vulkan.patch`](../../patches/mobilegl-no-vulkan.patch)，
共 4 个文件：

- `CMakeLists.txt`：去掉 `vulkan` 链接项与 DirectVulkan 源文件
- `MobileGL/ConfigLoader.cpp`：硬锁 `MOBILEGL_BACKEND_TYPE=DirectGLES`，环境变量改不动
- `MobileGL/MG_Backend/BackendObjects.h`、`MobileGL/MG_Backend/Init.cpp`：摘掉 DirectVulkan
  后端与 `DriverPost` 诊断入口

即**源码级移除全部 Vulkan 调用路径**，不是配置级开关。理由与可复现的验证命令见
[BUILD.md 第 4 节](../../BUILD.md#关于绝不调用-vulkan)。

也可以拿上游原始源码 + 应用该补丁得到同样的结果：

```bash
git clone https://github.com/MobileGL-Dev/MobileGL
cd MobileGL
git checkout 08124c99f12ab2283cc15e4dc64ea972ecbd49c1
git apply -p1 ../HarmonyFCL/patches/mobilegl-no-vulkan.patch
```

## 子模块（已 vendored）

上游 `.gitmodules` 声明了 **12 个**子模块。本仓库把其中 **9 个有内容的**直接放了进来
（去掉了各自的 `.git` 目录），所以克隆后不需要初始化子模块就能构建：

| 路径 | 上游 | 固定提交 |
| --- | --- | --- |
| `3rdparty/glslang` | <https://github.com/MobileGL-Dev/glslang> | `d89cf443bcd220e9205defa0cc4c4b8d8f75dfa2` |
| `3rdparty/SPIRV-Cross` | <https://github.com/KhronosGroup/SPIRV-Cross> | `072444287f4e139c178d6d8fe32e04a0d2c34e8b` |
| `3rdparty/SPIRV-Reflect` | <https://github.com/KhronosGroup/SPIRV-Reflect> | `10b4f09a24d7ac1603071e767c089551dc6a3949` |
| `3rdparty/xxHash` | <https://github.com/Cyan4973/xxHash> | `1d7b2a9d21bf9a740ead540aa93f4bc4caf11ae0` |
| `3rdparty/asio` | <https://github.com/chriskohlhoff/asio> | `8806a6803cde7054c3049d3666d3ec36786568c5` |
| `3rdparty/Vulkan-Headers` | <https://github.com/KhronosGroup/Vulkan-Headers> | `ad9ce1235e88dc09287e19171dfac384db8ec32c` |
| `3rdparty/Vulkan-Utility-Libraries` | <https://github.com/KhronosGroup/Vulkan-Utility-Libraries> | `738ec97a3f659dd6469bff3c4078ef981b0a343f` |
| `3rdparty/VulkanMemoryAllocator` | <https://github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator> | `e722e57c891a8fbe3cc73ca56c19dd76be242759` |
| `include/ska` | <https://github.com/MobileGL-Dev/flat_hash_map> | `21c1cec95abee1beef827e4a7c95f692875d9594` |

`3rdparty/glslang` 自己还带两个**嵌套**子模块（上游的 `.gitmodules` 不会替你拉它们，
但 `glslang/CMakeLists.txt` 要用到，所以一并 vendored 了）：

| 路径 | 上游 | 固定提交 |
| --- | --- | --- |
| `3rdparty/glslang/External/spirv-tools` | <https://github.com/KhronosGroup/SPIRV-Tools> | `33e02568181e3312f49a3cf33df470bf96ef293a` |
| `3rdparty/glslang/External/spirv-tools/external/spirv-headers` | <https://github.com/KhronosGroup/SPIRV-Headers> | `2a611a970fdbc41ac2e3e328802aed9985352dca` |

**下面 3 个是空的，没有 vendored**（上游在这三个目录里也没放东西，构建用不到）：
`3rdparty/DiligentCore`（`f36e6388…`）、`3rdparty/tracy`（`e6b9ea46…`）、
`3rdparty/apitrace`（`c8036190…`）。

> ⚠️ **`Vulkan-Headers` 不能因为"本项目不用 Vulkan"就删掉。** `MobileGL/Includes.h`
> **无条件** `#include <vulkan/vulkan.h>`，它是**编译期**依赖；真正产生 Vulkan 依赖的是
> `CMakeLists.txt` 里那个 `vulkan` 链接项，那个被补丁去掉了。删了头文件会直接编不过。
>
> `Vulkan-Utility-Libraries` 与 `VulkanMemoryAllocator` 的情况类似：它们在树里、但实测
> **一个 `.o` 都没编出来**（三个 Vulkan 相关目录各 0 个），所以既不入产物也不计入分发。

## 为了让这份副本真的能编过，改动了它自己的 `.gitignore`

上游把 `External/spirv-tools` 等当子模块，因此各自的 `.gitignore` 里都有排除规则。
本仓库既然把它们的内容 vendored 进来了，就必须把那些规则反向放行 —— 否则 **clone 本仓库
拿到的是一个编不过的源码树**，而且失败现场离原因很远（CMake 报找不到 SPIRV-Tools-opt 目标，
不会提到 `.gitignore`）。共 3 处，都带 `HarmonyFCL vendored 副本追加` 注释：

| 文件 | 追加的规则 | 不追加会怎样 |
| --- | --- | --- |
| `3rdparty/glslang/.gitignore` | `!External/spirv-tools`<br>`!External/spirv-tools/**` | 少 1956 个文件，`SPIRV-Tools-opt` 目标不存在 |
| `3rdparty/glslang/External/spirv-tools/.gitignore` | `!/external/spirv-headers`<br>`!/build_overrides/` | 少 110 个 SPIRV 头文件 |
| `3rdparty/SPIRV-Cross/.gitignore` | `!tests-other/*.spv` | 少 6 个测试输入（`*.spv` 那条本想只忽略跑测试时落在**根目录**的产物） |
| `3rdparty/asio/src/tests/unit/ip/.gitignore` | `!address_v4*.cpp`、`!address_v6*.cpp` 共 6 条 | 少 6 个上游跟踪的测试源文件（`address_v4*` 那条本想只忽略同名的**可执行文件**） |
| 仓库根 `.gitignore` | `!/third_party/MobileGL/3rdparty/xxHash/build/**` | 少 7 个 xxHash 的 cmake/make 配置 —— 而 `MobileGL/CMakeLists.txt:212` 恰恰 `add_subdirectory(3rdparty/xxHash/build/cmake)`，**configure 直接失败** |

第二条里 `!External/spirv-tools/**` 那行是必需的（不能只写目录本身）：`glslang/.gitignore`
第 21~23 行的 `third_party/`、`buildtools/`、`tools/` 都**没有前导斜杠**，在任意深度都匹配，
会连带吞掉 `spirv-tools/tools/`（48 个文件，而 `spirv-tools/CMakeLists.txt:352` 是无条件
`add_subdirectory(tools)`）。

## 行尾（拿本副本与上游对哈希前请先看这条）

本仓库存的是 **CRLF，连 blob 都是**（`.gitattributes` 里虽然写着 `* text=auto`，
但实测归一化并没有发生 —— 从初始提交起这个仓库就是 CRLF），MobileGlues 那份 vendored
副本也是同样情况。

而上游 MobileGL 的换行是**混的**：它自身的文件存 CRLF，子模块里的文件多数存 LF
（`3rdparty/glslang/.gitattributes` 甚至明写 `* -text`，注释说测试文件本来就是 lf/crlf
混用、别去动它）。

所以拿本副本与上游逐字节比对时，**子模块那部分会因行尾不同而全线报差异** ——
先两边都归一化成 LF 再比。这不影响构建：C++ 编译器不在意行尾 —— 用本副本编出来的
`libMobileGL.so` 与仓库里预置的那份导出符号数一致（都是 12038 个），
见下「[验证这份副本是完整的](#验证这份副本是完整的)」一节。

## 没有 vendored 的部分

- **`tools/trace_replay/fixtures/`**：上游用 **Git LFS** 存这些 trace 回放素材（`.tgz` 单个
  最大 13.5 MB，`.png` 是对拍基准图）。它们只服务于 `tools/trace_replay` 这个开发者工具，
  构建 `libMobileGL.so` 用不到；而且不装 LFS 时 clone 下来的本来也只是指针文件。
- **6 个非源码文件**：spirv-tools 的 `utils/Table/__pycache__/`（5 个 Python 字节码缓存）
  与 xxHash 的 `tests/bench/.clang_complete`（编辑器配置）。原样放在工作树里时存在，
  但它们不是任何东西的输入，不入库。

**入库的文件是 12858 个**（工作树里连缓存一共 12864 个）。若将来发现少了什么，
用这一句对着 vendored 副本核：`git status --ignored --short third_party/MobileGL`。

## 验证这份副本是完整的

`cmake` 配置能过就说明所有 `add_subdirectory` 的目标目录都在（少了任何一个都会在这里报错，
而不是在编译到一半时）：

```bash
cd third_party/MobileGL
$CMAKE -S . -B build-verify -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/path/to/cmake/bin/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
```

配置输出里应出现 `-- optimizer enabled`（表示找到了 SPIRV-Tools）。

再真编一次：

```bash
$CMAKE --build build-verify --target MobileGL -j4
```

本副本入库前跑过一遍：420/420 个目标、0 error，产物未 strip 267.4 MB。对新编出的 `.so`
跑 BUILD.md 第 4 节那四条 Vulkan 断言，全部通过（`DT_NEEDED` 只有
`libandroid/liblog/libm/libdl/libc`、未定义 `vk*`/`Vk*` 符号 0 个、6 个关键 EGL 入口齐全、
`VK_KHR` 字符串 1 处且是已知的 glslang 诊断消息），导出符号 12038 个，与仓库里预置的
那份一致。

> 以上只验证到「本副本能编出符号一致的库」。**它能不能在麒麟设备上真的跑起来，还没有
> 实机确认过** —— 见 [BUILD.md 第 9 节](../../BUILD.md#9-已知限制与实机验证清单)。

## 关于「OpenGL 4.6」还是「4.2」

上游 `README.md`（见文末原文）三处都写 **OpenGL 4.2 (Core Profile)**，但那是**过时文档**：
实际编出来的库报的是 **4.6**。依据是
`MobileGL/MG_Backend/DirectGLES/BackendObject_DirectGLES.cpp:769` 的
`.TargetGLVersion = {4, 6, 0}` —— `glGetString(GL_VERSION)` 报的就是它。
本仓库界面上的渲染器名写「OpenGL 4.6」是按这个来的，**不是笔误，不要照着上游 README 改回去**。

## 构建

见 [BUILD.md 2.2 节](../../BUILD.md#22-mobilegl--libmobileglso)。
产物 `build-arm64-v8a/libMobileGL.so` 未 strip 前约 265 MB，**必须 strip** 后
（→ 约 14 MB）再拷进 `FCL/src/main/jniLibs/arm64-v8a/`。

完整的第三方组件与许可证清单见 [NOTICE.md](../../NOTICE.md)。

---

# 以下是上游 MobileGL 仓库的原始 README（原样保留）

<h1 align="center">MobileGL</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Language-C%2B%2B-00599c?style=flat&logo=c%2B%2B" alt="C++">
  <img src="https://img.shields.io/badge/License-GNU%20LGPL%203.0-00399c?style=flat" alt="GNU LGPL 3.0">
  <img src="https://img.shields.io/badge/Status-Development-0078d7?style=flat" alt="Development">
</p>

<p align="center"><em>
A desktop OpenGL implementation
</em></p>

MobileGL is a *free* and *open-source* project that implements a desktop **OpenGL** API. The goal is to provide a complete desktop OpenGL implementation with a state management layer and multi-backend support.

> [!NOTE]
>
> **Status:** In development. Parts of the codebase are incomplete. Current short-term target: **OpenGL 4.2 (Core Profile)**.

## Project positioning

MobileGL is an implementation of a desktop OpenGL library. It aims to provide:

* Full OpenGL state management.
* A front-end that exposes OpenGL functions.
* Multiple independent backend implementations, where each backend targets a specific graphics API and remains fully isolated from others.

This project is intended as an implementation/translation layer.

## Key components

The repository is organized into following top-level modules:

1. **MG_State** — state tracking and management logic for Graphics APIs.
2. **MG_Impl** — front-end implementations of Graphics APIs that interact with `MG_State` and `MG_Backend`.
3. **MG_Backend** — per-backend translation layer that maps front-end Graphics APIs' semantics and state into concrete backend API calls (e.g. OpenGL ES, Vulkan).
4. **MG_Util** and other utility modules.

## Third-party components

MobileGL reuses several open-source projects:

* **SPIRV-Cross** by **KhronosGroup** - [Apache License 2.0](https://github.com/KhronosGroup/SPIRV-Cross/blob/master/LICENSE): [github](https://github.com/KhronosGroup/SPIRV-Cross)
* **glslang** by **KhronosGroup** - [Various Licenses](https://github.com/KhronosGroup/glslang/blob/main/LICENSE.txt): [github](https://github.com/KhronosGroup/glslang)
* **DiligentCore** by **Diligent Graphics** - [Apache License 2.0](https://github.com/DiligentGraphics/DiligentCore/blob/master/License.txt): [github](https://github.com/DiligentGraphics/DiligentCore)
* **flat_hash_map** by **Malte Skarupke** - [Boost Software License 1.0](https://github.com/MobileGL-Dev/flat_hash_map/blob/master/LICENSE): [github](https://github.com/MobileGL-Dev/flat_hash_map)

Refer to each component's repository for exact license texts. Any bundled third-party code in this repository is included under the upstream project's license.

## Compatibility & target

* **Short-term target:** `OpenGL 4.2 (Core Profile)`.
* **Current development focus:**
  * Performance improvement
  * `MG_State` and `MG_Impl` for `OpenGL 4.2 (Core Profile)`
  * `Direct (Vulkan)` backend
  * `Direct (OpenGL ES)` backend

## Build Instructions

We currently provide **no releases** and **no precompiled binaries**.  
If you want to try the project right now, you’ll need to build it yourself:

1. Clone the repository:

   ```sh
   git clone https://github.com/MobileGL-Dev/MobileGL.git
   ```

2. Initialize and update all submodules recursively:

   ```sh
   git submodule update --init --recursive
   ```

3. Follow glslang’s own documentation for its required initiation.

4. Configure and build the project with CMake:

   ```sh
   cmake -B build
   cmake --build build
   ```
   
   or do it in a modern way:
   
   ```sh
   cmake -S . -B build -G Ninja -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++
   cmake --build build
   ```
   
   Alternatively, you can use platform-specific build commands as needed.

### Build For macOS

On macOS, MobileGL can be built as a dylib that exposes the normal OpenGL/CGL/NSOpenGL entry points and routes them to the `DirectVulkan` backend. This is useful for running applications such as Minecraft through their stock GLFW/LWJGL OpenGL path while MobileGL is injected before context creation.

Prerequisites:

* macOS with Clang and Ninja.
* Vulkan loader and MoltenVK installed. With Homebrew, the MoltenVK ICD is commonly located at `/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json`.

Configure and build:

```sh
cmake -S . -B build-macos-magma \
  -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DMOBILEGL_BACKEND_TYPE=DirectVulkan \
  -DMOBILEGL_BUILD_TEST=OFF \
  -DMOBILEGL_BUILD_BENCHMARK=OFF

cmake --build build-macos-magma --target MobileGL -j8
```

The dylib will be generated at:

```sh
build-macos-magma/libMobileGL.dylib
```

To run Minecraft by MobileGL from a launcher like PrismLauncher, keep the stock LWJGL/GLFW natives and add a wrapper command to the instance settings:

```sh
env DYLD_INSERT_LIBRARIES=/absolute/path/to/MobileGL/build-macos-magma/libMobileGL.dylib MOBILEGL_BACKEND_TYPE=DirectVulkan VK_ICD_FILENAMES=/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json
```

Also make sure the JVM arguments include:

```sh
-XstartOnFirstThread
```

`DYLD_INSERT_LIBRARIES` must be active before GLFW creates its OpenGL context. After startup, the Minecraft F3 screen should report MobileGL and the `Direct (Vulkan)` backend if the injection worked.

## Build Options

| Option                       | Description                                           | Default |
|------------------------------| ----------------------------------------------------- | ------- |
| `MOBILEGL_BUILD_TEST`        | Build MobileGL tests (requires Clang)                 | ON      |
| `MOBILEGL_BUILD_BENCHMARK`   | Build MobileGL benchmarks (requires Clang)            | ON      |
| `MOBILEGL_FORCE_RELEASE_OPT` | Enable O3 and LTO in Debug build                      | ON      |
| `MOBILEGL_ENABLE_TRACY`      | Enable Tracy profiler for performance analysis        | OFF     |

   **Notes:**

* The project requires C++23.
* `MG_Test` and `MG_Benchmark` can only be built with Clang, not GCC. To enforce Clang, add `-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++` to your command.
* On Android, tests and benchmarks are always disabled.

## Environment Variables

MobileGL supports runtime configuration via environment variables.

### Supported Keys

| Variable                | Description                                      | Allowed Values                       | Default        |
|-------------------------|--------------------------------------------------|--------------------------------------|----------------|
| `MOBILEGL_BACKEND_TYPE` | Select active backend implementation at startup. | `DirectGLES`, `DirectVulkan`         | `DirectGLES`   |
| `MOBILEGL_DISABLE_TIMERQUERY` | Disable GPU timer-query exposure and use. | `0`, `1` | `0` |
| `MOBILEGL_ESPRYT_USE_ANGLE` | Load ANGLE EGL/GLES libraries. | `0`, `1` | `0` |
| `MOBILEGL_MAGMA_DISABLE_SUBGROUP` | Disable Vulkan shader subgroup support. | `0`, `1` | `0` |
| `MOBILEGL_ADVERTISE_FP64` | Advertise `GL_ARB_gpu_shader_fp64`. GLSL `double`/`dvec`/`dmat` compile and run either way - they are narrowed to 32 bits - so this only changes whether an application is told it has 64-bit precision, which it does not. | `0`, `1` | `0` |
| `MOBILEGL_MAGMA_R11G11B10F_FALLBACK` | Use Magma's R11G11B10F format fallback. | `0`, `1` | `0` |
| `MOBILEGL_MAGMA_FRAMESINFLIGHT` | Set Magma frames in flight. | Integer `1`–`64` | `3` |
| `MOBILEGL_MAGMA_MAX_DRAWS_PER_COMMAND_BUFFER` | Draws and dispatches Magma records into one command buffer before it submits the buffer mid-frame and continues on a fresh one. `0` never splits. | Integer `0`–`16777216` | `16384` |
| `MOBILEGL_MAGMA_DESCRIPTOR_TRIM_FRAMES` | Frames a frame slot's descriptor pools must stay under a quarter full before Magma frees the slot's cached descriptor sets and grown pools. `0` never trims. | Integer `0`–`1048576` | `120` |
| `MOBILEGL_ESPRYT_AVOID_SAMPLER_MIPMAP_MIN_FILTER` | Avoid sampler mipmap minification filters. | `0`, `1` | `0` |
| `MOBILEGL_COHERENT_AS_FLUSH` | Treat persistent `GL_MAP_FLUSH_EXPLICIT_BIT` maps as coherent (app-compat for engines like Flywheel that never flush them). | `0`, `1` | `0` |
| `MOBILEGL_ESPRYT_FORCE_DS_READBACK_EMULATION` | Always emulate depth/stencil `glReadPixels`/`glGetTexImage` by shader sampling on Espryt, instead of using the driver's own depth/stencil readback where it has one. | `0`, `1` | `0` |
| `VK_ICD_FILENAMES`      | Select the Vulkan ICD used by the Vulkan loader. | Path to an ICD JSON file             | Loader default |

## License

This project is distributed under **GNU LGPL v3.0**. See the `LICENSE` file in the repository for detailed information.
