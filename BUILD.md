# 构建与验证

本文件覆盖：环境要求 → 构建 MobileGlues → 构建 FCL → 无 Vulkan 的可复现验证 →
签名密钥 → 安装/授权/回滚 → 配置入口 → 自定义默认键位 → 已知限制与实机验证清单。

所有路径都相对**仓库根目录**。

---

## 1. 环境

| 工具 | 版本 |
| --- | --- |
| JDK | **17**（`gradle.properties` 里没有 toolchain 覆盖，AGP 依赖 JAVA_HOME） |
| Android SDK | `platforms;android-35`、`build-tools;35.0.0` |
| Android NDK | **27.x**（`FCL/build.gradle.kts` 里写的是 `27.3.13750724`；换成别的 27.x 就同步改那一行） |
| CMake / Ninja | NDK 自带的即可，或独立安装（构建 MobileGlues 用） |
| Gradle | 由 wrapper 指定 **8.14.4**，不需要自己装 |

`local.properties`（**不入库**，需要自己建）：

```properties
sdk.dir=/path/to/android-sdk
# 可选：CurseForge 整合包下载 / 微软登录功能需要
# curse.api.key=...
# oauth.api.key=...
```

网络说明：本仓库的 `settings.gradle.kts` 把阿里云镜像放在 `google()` / `mavenCentral()` 之前，
wrapper 指向腾讯云镜像 —— 这是为国内网络准备的（Maven Central 上有一部分构件，例如
`org.jetbrains.kotlin:kotlin-compiler-embeddable`，会 301 跳到 github.com 的 release 资源）。
镜像里没有的构件会返回 404，Gradle 自动回落，所以海外网络也不受影响。

---

## 2. （可选）重新构建渲染器库

**这一步不是必需的。** 仓库已经内置了 `libmobileglues.so`（两个 ABI，合计约 11MB）和
`libMobileGL.so`（**只有 arm64-v8a**，约 14MB），直接执行第 3 节就能出包。

下面只在两种情况下需要：你想**自己验证**这两个库确实不含 Vulkan，或者你想改它们的代码。

### 2.1 MobileGlues → `libmobileglues.so`

源码在 `third_party/MobileGlues/`，**已经打好「去 Vulkan」补丁**（子模块内容也已 vendored，
不需要额外初始化子模块）。

```bash
cd third_party/MobileGlues/MobileGlues-cpp

NDK=/path/to/android-ndk          # 例如 $ANDROID_HOME/ndk/27.3.13750724

for ABI in arm64-v8a armeabi-v7a; do
  cmake -B build-$ABI \
    -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -G Ninja
  cmake --build build-$ABI --target mobileglues -j 8
done
```

`ANDROID_PLATFORM=android-26` 与 FCL 的 `minSdk 26` 对齐。

产物是 `build-<abi>/libmobileglues.so`（**未 strip，约 57MB**）。strip 之后约 6MB，
再拷进 FCL 的两个 jniLibs 目录：

```bash
STRIP=$NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-strip   # Windows 下 host=windows-x86_64

$STRIP --strip-unneeded build-arm64-v8a/libmobileglues.so
$STRIP --strip-unneeded build-armeabi-v7a/libmobileglues.so

cp build-arm64-v8a/libmobileglues.so      ../../FCL/src/main/jniLibs/arm64-v8a/
cp build-armeabi-v7a/libmobileglues.so    ../../FCL/src/main/jniLibs/armeabi-v7a/
```

> 仓库里预置的那两个 `.so` 就是按上面步骤构建、strip 后拷进去的，SHA-256 为
> （供对拍；**不是**构建可复现性承诺 —— 编译路径会写进二进制，你自己构建的哈希大概率不同）：
>
> | ABI | SHA-256 |
> | --- | --- |
> | `arm64-v8a` | `b6423f84b978a80d04b56f2c4dd7be684a5d2bdded1b82da246279f6946ac373` |
> | `armeabi-v7a` | `cf60514a25d18a73cac3c6ffda675abea71e5253a94d39f99b3c5f767718e624` |
>
> 自己构建的 `libmobileglues.so` 会与它们功能等价（第 4 节的三条验证命令都应通过），
> 但字节不一定相同，所以不要拿哈希当构建是否成功的判据 —— **拿 Vulkan 断言当判据**。

### 2.2 MobileGL → `libMobileGL.so`

**只编 `arm64-v8a`。** 上游 MobileGL 不支持 `armeabi-v7a`，所以 32 位包里没有这个 `.so` ——
`RendererManager.canUseMobileGL()` 会据此把 26.3 的实例留在 MobileGlues 上。
上游**不发任何预编译产物**（Releases 与 Tags 都是空的），只能自己编。

| 项 | 值 |
| --- | --- |
| 上游 | <https://github.com/MobileGL-Dev/MobileGL> |
| 打补丁的基线提交 | `08124c99f12ab2283cc15e4dc64ea972ecbd49c1`（2026-09-30） |
| 补丁 | `patches/mobilegl-no-vulkan.patch`（4 个文件，内容见第 4 节） |
| 许可证 | **LGPL-3.0**（见第 10 节与 [NOTICE.md](NOTICE.md)） |

源码用仓库里 vendored 的那份（`third_party/MobileGL/`，已含补丁与全部子模块内容），
**不需要 clone、不需要初始化子模块**：

```bash
cd third_party/MobileGL

NDK=/path/to/android-ndk              # 27.3.13750724 实测通过
CMAKE=/path/to/cmake/bin/cmake        # 3.22.1 + ninja 实测通过；需要支持 C++23 的工具链

$CMAKE -S . -B build-arm64-v8a -G Ninja \
  -DCMAKE_MAKE_PROGRAM=/path/to/cmake/bin/ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release
$CMAKE --build build-arm64-v8a --target MobileGL -j 8
```

> 构建目录一律用 `build-<abi>`，别用 `build/` —— vendored 树里 `3rdparty/xxHash/build/`
> 是上游自己的源码目录，撞名会让 `.gitignore` 与清理都变麻烦。

想验证「vendored 的那份确实等于上游 + 补丁」，再走一遍从上游还原的路：

```bash
git clone https://github.com/MobileGL-Dev/MobileGL && cd MobileGL
git checkout 08124c99f12ab2283cc15e4dc64ea972ecbd49c1
git apply /path/to/patches/mobilegl-no-vulkan.patch

# 子模块。DirectVulkan 摘掉之后 Vulkan-Headers 仍是**编译期**依赖：
# MobileGL/Includes.h 无条件 #include <vulkan/vulkan.h>，`vulkan` 那个链接项才是
# DT_NEEDED 的来源。所以别顺手把 Vulkan-Headers 也删了 —— 会编不过。
git submodule update --init --recursive --depth 1
```

> 子模块克隆卡住时（`git submodule update` 一次要拉 9 个仓库，很容易超时），可以按
> `.gitmodules` 里记的提交逐个浅克隆：`git init` + `git fetch --depth 1 origin <sha>`
> + `git checkout FETCH_HEAD`。glslang 自己还带两个**嵌套**子模块
> （`External/spirv-tools`、`External/spirv-tools/external/spirv-headers`），
> 那两层 `git submodule update` 不会替你拉，而 `CMakeLists.txt` 要链接
> `SPIRV-Tools-opt`，缺了编不过。

**编出来的 `libMobileGL.so` 约 265MB，必须 strip。** 即使 `CMAKE_BUILD_TYPE=Release`，
glslang 那一侧的 CMake 仍然带调试信息（光 `.debug_info` 一节就 97MB；真正的 `.text` 只有 9MB）：

```bash
STRIP=$NDK/toolchains/llvm/prebuilt/<host>/bin/llvm-strip   # Windows 下 host=windows-x86_64
$STRIP --strip-unneeded build-arm64-v8a/libMobileGL.so      # 265MB → 约 14MB

mkdir -p ../../FCL/src/main/jniLibs/arm64-v8a
cp build-arm64-v8a/libMobileGL.so ../../FCL/src/main/jniLibs/arm64-v8a/
```

> 仓库里预置的那份 strip 后 SHA-256 是
> `c35a2e5b955c1090d34ac3eff8f74bd3dc5cd38e0f808c2ee56469130dd0ffd9`。
> 与上面一样，**别拿哈希当构建是否成功的判据**，拿第 4 节的 Vulkan 断言当判据。
>
> `--strip-unneeded` 会保留 `.dynsym`（那份 .so 里有 12000+ 个默认可见导出），
> FCL 靠 `eglGetProcAddress` 动态取 GL 入口，所以**不要**改用别的更激进的裁剪方式。

---

## 3. 构建 FCL

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :FCL:assembleRelease -Darch=arm64,arm
```

`-Darch` 接受单个架构（`arm` / `arm64` / `x86` / `x86_64`）、逗号分隔的列表、或 `all`。
给列表时 AGP 的 ABI splits 会为每个 ABI 各出一个 APK；同时 JRE / LWJGL 资产会按目标架构裁剪，
所以**分别构建两个 ABI 比一次构建两个 ABI 的包更小**：

```bash
./gradlew :FCL:assembleRelease -Darch=arm64     # 只出 arm64
./gradlew :FCL:assembleRelease -Darch=arm      # 只出 v7a
```

产物：`FCL/build/outputs/apk/release/HarmonyFCL-1.3.3.6-<abi>.apk`（每个约 190–200MB，
其中 JRE 资产占大头）。

---

## 4. 关于「绝不调用 Vulkan」

Vulkan 的处理是**源码级**的，不是配置级开关。

MobileGlues 原版里唯一会碰 Vulkan 的运行时路径是 `config/gpu_utils.cpp` 的 `hasVulkan12()`：
它 `dlopen("libvulkan.so")`、`vkCreateInstance()`、枚举物理设备、看 `apiVersion >= 1.2`。
而 `config/settings.cpp` 在 ANGLE 判定**之前无条件**调用它 —— 也就是说即便把 `enableANGLE`
写成 2（ForceDisable），原版仍然会创建一个 `VkInstance`。麒麟 Maleoon 驱动的 Vulkan 能力不足，
这正是要避免的；ANGLE 本身也是 GLES→Vulkan 的翻译层。

补丁（见 `patches/mobileglues-no-vulkan.patch`）做了三件事：

1. `gpu_utils.cpp`：删掉 `#include "vulkan/vulkan.h"` 与 `vk_lib` 数组；`hasVulkan12()` 整个
   实现体替换为 `return 0;`；`checkIfANGLESupported()` 直接 `return false;`。
2. `settings.cpp`：删掉 `hasVulkan12()` 的调用与相关日志；读取配置后**无条件**覆盖
   `angleConfig = ForceDisable`、`angleDepthClearFixMode = Disabled`。
3. 结果：MobileGlues 的**所有 `.c` / `.cpp` 编译单元里**不再有任何 `libvulkan` /
   `vkCreateInstance` / `VkInstance` 的引用（只剩补丁注释）。

> 需要说明一处：`MobileGlues-cpp/include/vulkan/` 下的**头文件仍在**（`vulkan_core.h` 等约 20 个）。
> 全树没有任何代码 include 它们（除头文件之间互相 include），因此不会被编译进 `libmobileglues.so`，
> 也不构成任何 Vulkan 调用。保留是为了让这一处补丁与上游的差异保持在最小范围。
> 真正的判据是产物级断言（下面的第 2、3 条），那两条在任何情况下都必须无输出。
> 如果你希望连头文件也一并清掉，删掉该目录即可，构建不受影响。

MobileGL 侧的补丁（`patches/mobilegl-no-vulkan.patch`，4 个文件）做四件事：

| 改动 | 文件 | 为什么 |
| --- | --- | --- |
| 摘掉 `DirectVulkan` 全部源码（19 个 .cpp） | `CMakeLists.txt` | 不编就不会被选到 |
| 去掉 Android 的 `vulkan` 链接项 | `CMakeLists.txt` | 这是 `DT_NEEDED` 里 `libvulkan.so` 的**唯一**来源。`DT_NEEDED` 是**加载期**解析——容器里没有这个文件的话 `dlopen` 整个失败，与选哪个后端无关 |
| 去掉 `BackendLoaders/Vulkan/Loader.cpp` 与 `SelfTest/DriverPost*.cpp` | `CMakeLists.txt` | 前者 `dlopen("libvulkan.so")`；后者的诊断入口运行期会建真的 `VkInstance`。DirectGLES 不引用 `DriverPost::` 的任何符号，所以摘掉是自洽的 |
| 摘掉 `#include "DirectVulkan/BackendObject_DirectVulkan.h"` | `MG_Backend/BackendObjects.h` | 上面已经没这个文件了 |
| `DirectVulkan` 分支改成明确的失败出口 | `MG_Backend/Init.cpp` | 否则是链接期报未定义符号，而不是运行时一条日志 |
| `InitBackendType()` 硬锁 `DirectGLES` | `ConfigLoader.cpp` | 上游把后端暴露成 `MOBILEGL_BACKEND_TYPE` 环境变量（插件里甚至做成用户可切换的开关），这里改成环境变量说了不算 |

> **必须连源码一起摘，不能只删那行链接。** DirectVulkan 自己的源文件直接调 `vk*`，
> 只删链接项会立刻链接失败。
>
> 摘掉之后 `Vulkan-Headers` 仍然要留着：`MobileGL/Includes.h` 无条件
> `#include <vulkan/vulkan.h>`，它是**编译期**依赖，但只出头文件、不产生链接依赖
> （摘掉源码后 `CMakeLists.txt` 里也没有任何 Vulkan 静态库被链接进来）。

FCL 侧新加的渲染器分支只写 `POJAV_RENDERER=opengles3`。这个值命中 `egl_bridge.c` 里的
`strncmp(..., "opengles", 8)` 分支 → `RENDERER_GL4ES` + `set_gl_bridge_tbl()`，**不会**
`load_vulkan()`。（FCL 里会加载 Vulkan 的取值是 `opengles3_desktopgl_zink_kopper`、`vulkan_zink`、
`gallium_freedreno`、`custom_gallium` —— 本 fork 一个都没有用到，也没有改动它们；
用户显式手选 Zink / Freedreno 时仍是 FCL 原有行为。）

### 可复现的验证命令

```bash
NDK=/path/to/android-ndk
BIN=$NDK/toolchains/llvm/prebuilt/<host>/bin

# 1) 源码里不再有 Vulkan 运行时引用（只应剩补丁注释与 SPIR-V 头文件）
grep -rn "libvulkan\|vkCreateInstance\|VkInstance" \
  third_party/MobileGlues/MobileGlues-cpp --include=*.c --include=*.cpp \
  | grep -v 3rdparty          # → 只剩补丁注释

# 2) 产物 .so 里没有任何 Vulkan 字符串（关键断言，必须无输出）
$BIN/llvm-strings third_party/MobileGlues/MobileGlues-cpp/build-arm64-v8a/libmobileglues.so \
  | grep -i "libvulkan\|vkCreateInstance\|VkInstance"

# 3) 产物 .so 的依赖里没有被动态链接的 Vulkan
$BIN/llvm-readelf -d third_party/MobileGlues/MobileGlues-cpp/build-arm64-v8a/libmobileglues.so \
  | grep NEEDED              # → libandroid.so liblog.so libm.so libdl.so libc.so（无 libvulkan.so）
```

MobileGL 侧多一条更硬的判据。前三条对 libMobileGL.so 同样适用
（把路径换成 `FCL/src/main/jniLibs/arm64-v8a/libMobileGL.so`），另外：

```bash
MG=FCL/src/main/jniLibs/arm64-v8a/libMobileGL.so

# 4) 没有任何**未定义的 Vulkan 符号**（关键断言，必须输出 0）
#    这一条比第 2、3 条都硬：DT_NEEDED 干净只说明没有动态依赖库，
#    静态链进来的 Vulkan 代码要靠这个才查得出来。
$BIN/llvm-readelf --dyn-syms $MG | awk '$7=="UND" {print $8}' | grep -cE "^vk|^Vk"

# 5) 这个 .so 必须是 AArch64，且 .dynsym 还在（FCL 靠 eglGetProcAddress 动态取 GL 入口）
$BIN/llvm-readelf -h $MG | grep Machine          # → AArch64
$BIN/llvm-readelf --dyn-syms $MG | grep -c "FUNC.*DEFAULT"   # → 12000 上下
```

> 第 2 条在 libMobileGL.so 上会命中**一条**：`VK_KHR_relaxed_block_layout extension`。
> 那是 glslang 的**诊断消息文本**（它在报错时会念出 Vulkan 扩展名），是个字符串常量、
> 不产生任何调用，属于 glslang 的一部分，不是残留的 Vulkan 路径。别把这条当成失败。
> 判据以第 4 条为准 —— 未定义符号必须是 0。

出包之后还可以在 **APK 本体**上再验一次（推荐，这才是真正发出去的东西）：

```bash
unzip -o -j HarmonyFCL-1.3.3.6-arm64-v8a.apk 'lib/arm64-v8a/libmobileglues.so' -d /tmp/apkcheck
$BIN/llvm-strings /tmp/apkcheck/libmobileglues.so | grep -i "libvulkan\|vkCreateInstance\|VkInstance"
$BIN/llvm-readelf -d /tmp/apkcheck/libmobileglues.so | grep NEEDED

# 包名 / versionCode / ABI / 桌面名
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging HarmonyFCL-1.3.3.6-arm64-v8a.apk
#   → package: name='com.harmony.fcl' versionCode='1337' versionName='1.3.3.6'
#     application-label:'Harmony FCL'   native-code: 'arm64-v8a'
```

---

## 5. 签名密钥

**密钥不在仓库里。** 构建时按以下顺序查找，取第一个存在的：

1. Gradle 属性 `-PharmonyFclKeystore=<path>`
2. 环境变量 `HARMONYFCL_KEYSTORE`
3. 仓库根目录下的 `harmony-fcl.jks`
4. 仓库上一级目录下的 `harmony-fcl.jks`

口令与别名同理可覆盖：`-PharmonyFclStorePassword` / `HARMONYFCL_STORE_PASSWORD`（默认 `HarmonyFCL`）、
`-PharmonyFclKeyAlias` / `HARMONYFCL_KEY_ALIAS`（默认 `harmonyfcl`）。

一个都找不到时**不会失败**：回落到仓库自带的 `debug-key.jks`（上游的调试密钥，口令 `FCL-Debug`），
并在构建日志里打一条 warning。产物带的是 `CN=FCL-Debug` 调试签名，**与正式发布包签名不同，
不能覆盖安装正式包**。

自签名密钥的生成方式（10000 天有效期）：

```bash
keytool -genkeypair -keystore harmony-fcl.jks -storetype JKS -alias harmonyfcl \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
  -storepass HarmonyFCL -keypass HarmonyFCL \
  -dname "CN=Harmony FCL, OU=Harmony FCL for HarmonyOS, O=Harmony FCL, L=Unknown, ST=Unknown, C=CN"
```

> ⚠️ **换密钥等于换签名**，覆盖安装会被系统拒绝。要长期发布就把这个文件固定保存好。
> 也**不要**把它提交进任何公开仓库：拿到它的人可以伪造出「同包名 + 同签名」的 APK，
> 让已经装了本应用的用户被系统当成合法升级装上。

验证签名：

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs HarmonyFCL-1.3.3.6-arm64-v8a.apk
keytool -list -v -keystore harmony-fcl.jks
```

---

## 6. 安装 / 授权 / 回滚

### 安装

按机型选一个 APK 装（桌面图标名都是 **Harmony FCL**）：

| 文件 | 包名 | 用途 |
| --- | --- | --- |
| `HarmonyFCL-1.3.3.6-arm64-v8a.apk` | `com.harmony.fcl` | 麒麟机型（64 位）—— 推荐 |
| `HarmonyFCL-1.3.3.6-armeabi-v7a.apk` | `com.harmony.fcl` | 32 位环境 |

包名与官方 FCL（`com.tungsten.fcl`）不同，所以**不需要先卸载官方版**，两者可以共存，
数据目录各自独立（`Android/data/com.harmony.fcl`）。

### 授权

正常给 FCL 授「所有文件访问」即可（读写 `.minecraft` 需要）。**不要**再装 MobileGlues 的独立插件 APK。

### 回滚

卸载本 APK、装回官方 FCL 即可（`Android/data/com.harmony.fcl` 会被一并清掉，
如需保留先备份 `.minecraft`）。本 fork 没有改动任何存储格式：`VersionSetting` 新增的默认值只在
**新建**版本时写入（渲染器 = MobileGlues、JVM 参数 = 那两个参数、键位 = `00000000`），
已有版本的 JSON 反序列化时仍以存档里的值为准（缺失的键才回落新默认值）。

---

## 7. 默认 JVM 参数

新建版本时「JVM 参数」的默认值由空白变成：

```
-XX:+UnlockExperimentalVMOptions -XX:UseSVE=0
```

`-XX:UseSVE=0` 关掉 aarch64 上的 SVE 向量化 —— 卓易通容器里麒麟 SoC 上默认下载的那份 Java
开着 SVE 会启动异常。

这两个参数**原样传给 JVM，启动器不做任何按架构 / Java 版本的过滤**。如果某个 Java 不认这个选项
（报 `Unrecognized VM option`），把输入框里的内容删掉即可，删了不会被加回；已有版本的存档里
原本是什么就还是什么，只有新建版本才会带上新默认值。

### 7.1 实例自检：两个触发点

`com.mio.autofix.InstanceAutoFix` 是一套**幂等**的实例修正（版本隔离、渲染器、`-XX:UseSVE=0`），
挂**两个**触发点：

| 触发点 | 代码位置 | 范围 | 是否阻塞 |
| --- | --- | --- | --- |
| 打开启动器时 | `SplashActivity.enterLauncher()` → `InstanceAutoFix.applyAll()` | 全部实例 | 否（独立 IO 协程，**不 await**） |
| **启动游戏前** | `LauncherHelper.launch0()` 任务链 → `InstanceAutoFix.applyToVersion(repo, id)` | 只处理被点的那一个 | 是（挡在启动路径上） |

**为什么需要第二个**：第一个是后台协程，用户可能没等它跑完就点启动；而且启动器开着时用户还能
手改渲染器 / JVM 参数，那不重启启动器就不会被纠正。

**为什么两处不会互相打架**：同一个函数，幂等 —— 先跑的那个改完，后跑的那个没有可改的，返回 `false`。

**改动这里时必须守住的一条时序约束**：

`LauncherHelper` 的构造函数会把当前 `VersionSetting` 抓成字段，渲染器与 `javaArgs` 之后都从它读。
而自检会把**开着「使用全局设置」的实例**切到游戏特定设置（`isUsesGlobal = false`）—— 切换之后，
构造函数里抓到的那个对象（可能是全局设置）就不再是启动时该用的设置了。所以：

- `setting` 字段**刻意不是 `final`**（`LauncherHelper.java`）；
- 自检那一步跑完**紧接着**执行 `setting = profile.getVersionSetting(selectedVersion)` 重新取一次；
  **这两行不能分开**，中间也不要插入别的东西；
- 自检 stage（`launch.state.instance_fix`，界面文案「检查实例设置」）排在
  `launch.state.mods` 之后、`checkGameState` 之前；渲染器与 `javaArgs` 是更靠后
  构造 `launchOptions` / `FCLGameLauncher` 时才读的，所以这个位置是安全的。

删掉那次重新取值，或把自检挪到构造 `launchOptions` / `FCLGameLauncher` 之后，
都会造成「改了但启动时读不到」这类**静默失效**（不报错、只是不生效），排查时先看这里。

---

## 8. 自定义默认键位

内置的默认键位就是 `FCL/src/main/assets/controllers/00000000.json`（本仓库里是 FCL 上游自带的
`Default` 布局）。想换成自己的一套：

1. 把导出的布局 JSON 覆盖这个文件；
2. **把顶层 `"id"` 改写成 `00000000`**，其余字节不用动；
3. 重新构建。

**为什么必须改写 id**：FCL 的默认值 `VersionSetting.controller = "00000000"`、内置资产的**文件名**、
以及 `Controller.getFileName()`（= `id + ".json"`）三者必须是同一个值，否则每次读版本设置都会走
「找不到 → 回落到列表第一个」的兜底分支（`findControllerById()` 里会打一条 warning）。
id 改写后三处天然一致，代码里的默认值一个字都不用改。

**只影响全新安装**：`Controllers.checkControllers()` 只在「磁盘上一个布局都没有」时才写入内置布局，
所以已经用过的设备不会被覆盖（用户改过的键位、导入过的布局都还在）。已有设备想用上某个布局，
把 JSON 拷到 `/sdcard/Android/data/com.harmony.fcl/files/controller/`（FCL 的导入格式就是这个 JSON），
游戏内「键位」菜单里选它即可。

> 本项目历史上用过一套第三方作者制作的 **MBE 键位**（参照基岩版操作界面风格，
> 127 个布局组 / 1448 个按键）。那份键位的版权属于作者，**未随本仓库分发**，
> 所以这里的内置布局是 `Default`；想用就去取得作者许可或自己导出一套。

`Controllers.checkControllers()` 的写盘路径是「把 assets 里的原始字节直接落盘 + 轻量解析元数据」，
与布局体积无关 —— 换成上千个按键的大布局也不会把按键写空。

---

## 9. 已知限制与实机验证清单

### 已知限制

- **没有条件做真机回归。** 已完成的验证是源码级断言与产物级检查（见第 4 节），
  以及包名 / ABI / 版本号 / 桌面名 / 内置资产 / FileProvider authority 的核对。
  **MobileGL 这条路径尤其如此** —— 它连"能不能在麒麟上跑起来"都还没有实机确认过，
  只确认了它满足第 4 节的四条断言、且导出的核心 GL 符号与 MobileGlues 逐一对齐（1383 个核心名）。
- **MobileGL 只有 `arm64-v8a`。** 32 位包（`armeabi-v7a`）里没有 `libMobileGL.so`，
  那种包上的 26.3 实例会留在 MobileGlues 上（即黑屏）。见 2.2 节。
- **26.3 的快照 / rc 不会被切到 MobileGL。** 版本比较器里 `SNAPSHOT < RC < GA`，
  所以 `26.3-snapshot-3` 排在 `26.3` 之前，落在 1.17~26.2 那一支。理由与代价见
  [README 的「已知限制」](README.md#已知限制)。
- `.so` 的页对齐是 4KB（`0x1000`），与 FCL 上游自带的 `libgl4es_114.so` 等一致。卓易通/鸿蒙
  目前是 4KB 页，因此不构成问题；若将来系统切到 16KB 页，需要给 MobileGlues、MobileGL 与这些
  上游库一起加 `-Wl,-z,max-page-size=16384` 重新构建。
- **CurseForge / OAuth 的 API key 拿不到**，构建产物里对应的 `resValue` 是空串。不影响启动游戏，
  只影响「CurseForge 整合包下载」与「微软登录」。
- **独立 MobileGlues 插件 APK 没有做去重**：如果设备上也装了它，渲染器列表里会同时出现
  「MobileGlues」（内置）和插件提供的那一项，两个都能选。
- **手选 Zink / Freedreno 会加载 Vulkan**（FCL 上游行为，未改动），在麒麟上大概率不能用。

### 实机验证清单（建议在麒麟 + 卓易通上逐条确认）

1. 只装本 APK，不给任何额外权限，新建一个版本 → 渲染器默认已是 **MobileGlues**。
2. 启动一个 MC 1.17+ 的版本，进游戏后看
   `Android/data/com.harmony.fcl/files/mobileglues/latest.log`：
   - 有 `Final ANGLE setting: 0`（= Disabled）；
   - **没有** `Has Vulkan 1.2?` 这一行，也没有任何 `libvulkan` 相关日志；
   - GPU 串是 Maleoon（说明确实走系统 GLES，不是任何 Vulkan 后端）。
3. 游戏内 `glGetString(GL_VERSION)` 报告 4.0 级别（MobileGlues 的桌面上报）。
4. 极端回归：往 `config.json` 里写 `{"enableANGLE": 3}`，再启动 → ANGLE 仍然必须是 disabled。
5. 授权只弹了一次（FCL 自己那次）。
6. **渲染器分段**：新建一个 MC 26.2 的实例 → 渲染器是 **MobileGlues**；再新建一个 26.3 的
   → 自动变成 **MobileGL**，且渲染器列表里那一项显示 `>=26.3`。把 26.3 实例的渲染器手动改成
   列表里的第一项（MobileGlues）再重开启动器 → 应当被**改回** MobileGL（这一支刻意覆盖手选）。
7. **启动游戏前的自检（7.1 节的第二个触发点）**，这四条是新增功能的回归：
   a. 启动游戏时，任务弹窗里应当能看到**「检查实例设置」**这一栏；
   b. **不重启启动器**的情况下，在实例设置里把 26.3 实例的渲染器改成列表第一项（MobileGlues），
      然后直接点启动 → 启动时应被改回 MobileGL（重新打开实例设置能确认）；
   c. 把一个实例的 Java 改成 **jre8**（或任何 Java 8），它的 `javaArgs` 里若带着
      `-XX:UseSVE=0` → 启动前应被删掉（否则 Java 8 会以 `Unrecognized VM option 'UseSVE'` 直接退出）；
   d. **不能出现"改了但没生效"**：上面 b/c 两条的判定必须看**游戏实际用的**渲染器 /
      启动指令里的 JVM 参数，而不是只看设置界面显示的值 —— 这两者不一致就说明
      `LauncherHelper` 的 `setting` 重新取值那一步被破坏了，见 7.1 节的时序约束。
8. **26.3 实机能进游戏**（这是 MobileGL 唯一真正要证的命题）：启动 26.3 实例，确认过了
   Mojang logo 之后**不再黑屏**。出问题先看是不是根本没加载到：`libMobileGL.so` 加载失败
   会在 logcat 里留 `dlopen failed`。
9. **32 位包回归**：装 `armeabi-v7a` 那个包（在 64 位设备上装也有效）→ 渲染器列表里
   **不应该出现 MobileGL**，26.3 实例会留在 MobileGlues 上。
   这一条同时也是 `canUseMobileGL()` 那个修正的回归：若换成按 `Build.SUPPORTED_ABIS` 判，
   64 位设备会报告 `arm64-v8a` 而永远为真，于是选中一个**包里根本不存在**的 `.so`。

---

## 10. 许可证

本 fork 是 FoldCraftLauncher 的衍生作品，**GPL-3.0**（见 `LICENSE`）。

- **MobileGlues** 是 **LGPL-2.1-only**，以独立共享库（`lib/<abi>/libmobileglues.so`）形式随 APK
  分发，其**完整修改版源码**在 `third_party/MobileGlues/`。
- **MobileGL** 是 **LGPL-3.0**，以独立共享库（`lib/arm64-v8a/libMobileGL.so`）形式随 APK 分发，
  其**完整修改版源码**在 `third_party/MobileGL/`（含构建所需的 11 个子模块与嵌套子模块，
  均已去掉各自的 `.git`）。它静态链进去的第三方（glslang / SPIRV-Cross / SPIRV-Reflect /
  xxHash / asio / flat_hash_map 等）逐项列在 NOTICE.md。

逐项的许可证与来源见 **[NOTICE.md](NOTICE.md)**。
