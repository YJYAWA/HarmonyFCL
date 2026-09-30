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

## 2. （可选）重新构建 MobileGlues → `libmobileglues.so`

**这一步不是必需的。** 仓库已经内置了两个 ABI 的 `libmobileglues.so`
（`FCL/src/main/jniLibs/<abi>/`，合计约 11MB），直接执行第 3 节就能出包。

下面只在两种情况下需要：你想**自己验证**这个库确实不含 Vulkan，或者你想改 MobileGlues 的代码。

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
- `.so` 的页对齐是 4KB（`0x1000`），与 FCL 上游自带的 `libgl4es_114.so` 等一致。卓易通/鸿蒙
  目前是 4KB 页，因此不构成问题；若将来系统切到 16KB 页，需要给 MobileGlues 与这些上游库
  一起加 `-Wl,-z,max-page-size=16384` 重新构建。
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

---

## 10. 许可证

本 fork 是 FoldCraftLauncher 的衍生作品，**GPL-3.0**（见 `LICENSE`）。
MobileGlues 是 **LGPL-2.1-only**，以独立共享库（`lib/<abi>/libmobileglues.so`）形式随 APK 分发，
其**完整修改版源码**在 `third_party/MobileGlues/`。逐项的许可证与来源见 **[NOTICE.md](NOTICE.md)**。
