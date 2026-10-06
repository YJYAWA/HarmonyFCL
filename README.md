<div align="center">
    <img width="75" src="/FCL/src/main/res/drawable/img_app.png"></img>
</div>

<h1 align="center">Harmony FCL</h1>

<div align="center">

把 [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) 渲染器内嵌进
[FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)，并**源码级移除全部 Vulkan 调用路径**。

面向 **华为鸿蒙 5.0+（纯血鸿蒙）的卓易通容器 + 麒麟 SoC**。

</div>

> **非官方修改版**，与 FoldCraftLauncher 官方团队、MobileGlues 官方团队均无关系。
> 本仓库是 GPL-3.0 的衍生作品，许可证见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

---

## 它解决什么问题

卓易通能让 FCL 在纯血鸿蒙上跑起来，但默认路径有四个坎：

1. **麒麟 Maleoon 驱动的 Vulkan 能力不足。** 鸿蒙对 Vulkan 的支持不完整（例如缺少
   `VK_KHR_dynamic_rendering`），而 FCL 默认的渲染路径会走到 Vulkan 上 —— 表现是渲染异常，
   或者干脆起不来。
2. **MobileGlues 原本要单独装一个插件 APK**，还要给它授一次「所有文件访问」，
   FCL 才检测得到、才肯调用。
3. **FCL 默认下载的那份 Java 在卓易通容器里会启动异常** —— 麒麟 SoC 的 SVE 向量化是原因。
4. **远古版本（1.16 及更早）启动不了的坑。** 默认 JVM 参数里的 `-XX:UseSVE=0` 只有
   Java 17+ 认识，而远古版本实际用 Java 8，一启动就报 `Unrecognized VM option 'UseSVE'` 退出。
   **这个已在 1.3.3.7 修好**：实例自检会按"这个版本要求的 Java"判断并自动删掉该参数，
   不再需要手动删（见[默认 JVM 参数](#默认-jvm-参数)的 7.2 节）。

## 本 fork 改了什么

除了下面的十三项，其余代码与上游 FoldCraftLauncher 一致。逐文件的改动见
[`patches/fcl-embed-mobileglues.patch`](patches/fcl-embed-mobileglues.patch)。
MobileGL 那一侧的裁剪另有一份
[`patches/mobilegl-no-vulkan.patch`](patches/mobilegl-no-vulkan.patch)（打在上游 MobileGL 上）。

「版本」一列是该项改动**首次进入本 fork 的版本**。三次版本分界：
`1.3.3.5`（tag [`v.1.3.3.5`](https://github.com/YJYAWA/HarmonyFCL/releases/tag/v.1.3.3.5)）
是内嵌 MobileGlues 的第一版；`1.3.3.6` 起有第 6~11 项；**当前版本 `1.3.3.7`**
（`versionCode` 1338 = 上游 1337 + 1）同步了上游 1.3.3.7（31 个提交 / 82 个文件）。

| # | 改动 | 版本 | 说明 |
| --- | --- | --- | --- |
| 1 | **内嵌 MobileGlues 与 MobileGL** | 1.3.3.5 / 1.3.3.6 | 两个 `.so` 都随 APK 分发，不再需要插件 APK；全局默认仍是 MobileGlues。MobileGlues 自 1.3.3.5 起，MobileGL 自 1.3.3.6 起 |
| 2 | **源码级移除全部 Vulkan 调用路径** | 1.3.3.5 | 见 [它**不会**做什么](#它不会做什么) |
| 3 | **新建版本的默认 JVM 参数改为 `-XX:+UnlockExperimentalVMOptions -XX:UseSVE=0`** | 1.3.3.5 | 见 [默认 JVM 参数](#默认-jvm-参数) |
| 4 | **改包名与应用名**：`com.harmony.fcl` / **Harmony FCL** | 1.3.3.5 | 与官方 FCL 共存，数据目录独立 |
| 5 | **`-Darch` 支持架构列表**（`arm64,arm` 这样写） | 1.3.3.5 | 方便一次出多个 ABI 的包 |
| 6 | **按 MC 版本自动匹配渲染器** | 1.3.3.6 | < 1.17 → Krypton Wrapper；1.17~26.2 → MobileGlues；≥ 26.3 → MobileGL |
| 7 | **每次打开启动器时做一次实例自检** | 1.3.3.6 | 见 [实例自检](#实例自检开启动器时--每次启动游戏前) |
| 8 | **MC 26.2 起把图形后端钉死在 OpenGL** | 1.3.3.6 | 见 [MC 26.2 起的图形后端](#mc-262-起的图形后端) |
| 9 | **MC 26.3 起换用 MobileGL** | 1.3.3.6 | 见 [MC 26.3 起的渲染器](#mc-263-起的渲染器) |
| 10 | **修复模组源（CurseForge / Modrinth）在国内的可用性** | 1.3.3.6 | 搜索之外的接口也走镜像；详情页不再一片空白。见[模组源的镜像](#模组源的镜像) |
| 11 | **更换全部图标** | 1.3.3.6 | 桌面图标与应用内图标都换成镐子，和官方 FCL 区分开 |
| 12 | **启动游戏前也跑一次实例自检** | 1.3.3.7 | 见 [实例自检](#实例自检开启动器时--每次启动游戏前) |
| 13 | **修复远古版本（1.16 及更早）启动不了** | 1.3.3.7 | `-XX:UseSVE=0` 的判据原来依赖"jre8 装没装"，见 [默认 JVM 参数](#默认-jvm-参数) 的 7.2 节 |

**另外有一项是「上游加了、本 fork 刻意不用」**：上游 1.3.3.7 的 Vulkan 设备能力检测
（`com.mio.device.*`，含一个会真的创建 `VkInstance` 的
`vulkan_checker.c`）。本 fork **把它的自动检测从启动路径上摘掉了**，只保留实例设置里
手动点的那一次 —— 理由见 [它**不会**做什么](#它不会做什么)。

> 默认键位**没有**改动，用的就是 FCL 上游自带的 `Default` 布局，见[默认键位](#默认键位)。

## 本 fork 的应用身份

| 项 | 值 |
| --- | --- |
| 包名 | `com.harmony.fcl` |
| 桌面显示名 | **Harmony FCL** |
| 版本 | `1.3.3.6`（`versionCode` 1337 = 上游 + 1） |
| 产物名 | `HarmonyFCL-<版本>-<abi>.apk`，每个 ABI 一个包 |
| 签名 | 自签名，**密钥不随仓库分发**（见[签名密钥](#签名密钥)） |
| 上游基线 | FoldCraftLauncher tag `1.3.3.6` / MobileGlues `2.0.0` |

包名与官方 FCL（`com.tungsten.fcl`）**不同**，所以**不需要先卸载官方版**：两者可以共存，
数据目录也各自独立（`Android/data/com.harmony.fcl`）。

## 默认 JVM 参数

**这是本 fork 对 FCL 的第 3 项改动，1.3.3.5 起。** 新建版本时，「JVM 参数」这一栏的默认值从
**空白**改成了：

```
-XX:+UnlockExperimentalVMOptions -XX:UseSVE=0
```

**为什么要加**：aarch64 上 JVM 默认会启用 SVE 向量化（`-XX:UseSVE` 默认值等价于 `1`）。
卓易通容器里 FCL 自己下载的那份 Java，在麒麟 SoC 上开着 SVE 会**启动异常**。
`-XX:UseSVE=0` 把它关掉。

**这两个参数的行为边界**（都实测过，别想当然）：

- **全局参数不做任何过滤，但每个实例的这项参数会被启动器按需修正。**「全局 Java 虚拟机参数」
  原样传给 JVM、启动器不碰；而**每个实例自己的** `javaArgs`，在**该实例实际会用到的那份 Java
  主版本 < 17** 时，`-XX:UseSVE=0` 会被自动删掉 —— Java 8 见到它会以
  `Unrecognized VM option 'UseSVE'` 直接退出。判定依据是该实例的 Java 设置（`Auto` 时用
  启动器推荐的那份），所以老版本实例开箱即用，不需要你手动去删。
  见[每次启动的实例自检](#每次启动的实例自检)。
- **只影响新建的版本。** 已经存在的版本，存档 JSON 里原本写的是什么就还是什么 ——
  反序列化时以存档里的值为准，缺失的键才回落新默认值。所以升级不会改掉你已有的配置。
- **如果某个 Java 不认这两个选项**（日志里报 `Unrecognized VM option`），
  把设置里那个输入框的内容**删空**即可，删了不会被自动加回。
  （上面那条自动修正只针对 `-XX:UseSVE=0`；`-XX:+UnlockExperimentalVMOptions` 不会被自动处理。）
- 为什么是"两个一起"：`-XX:+UnlockExperimentalVMOptions` 本身不改变 JVM 行为，它只是放开
  实验性选项的开关。不同的 JDK 发行版对 `UseSVE` 的归类不一致（产品选项 / 实验性选项），
  带上它才能保证各家构建都收得下。**要缩到只剩一个**的话，先确认你的 Java 不报
  `Unrecognized VM option 'UseSVE'` 再删掉前者。

## 实例自检：开启动器时 + 每次启动游戏前

**这是本 fork 对 FCL 的第 6、7 项改动，1.3.3.6 起。** 同一套幂等自检有**两个触发点**，
**只改实例自己的设置，全局不动**：

| 触发点 | 范围 | 时机 |
| --- | --- | --- |
| **打开启动器时** | 全部实例 | 后台协程，**不阻塞**启动（`InstanceAutoFix.applyAll()`） |
| **每次启动游戏前** | 只处理被点的那一个 | **挡在启动路径上**，在启动进度里显示为「检查实例设置」 |

第二个触发点是必需的，不能只靠第一个：

1. 第一个是**不 await 的后台协程**，你完全可能没等它跑完就点下启动；
2. 启动器开着的时候，你还能在设置里手改渲染器 / JVM 参数 —— 那不重启启动器就不会被纠正。

两个触发点跑的是同一个函数，所以彼此幂等：先跑的那个改完，后跑的那个直接没有可改的。
**顺序上有个硬约束**：它必须早于 `LauncherHelper` 抓取版本设置的那一刻，否则改的对象不是
启动时真正用的那个 —— 代码里用「自检后重新 `getVersionSetting()`」来保证这点。

修正三件事：

| 项 | 行为 |
| --- | --- |
| **版本隔离** | **强制开启**（游戏目录落在 `.minecraft/versions/<id>/`）。这是刻意的——你在实例设置里关掉，下次打开启动器会被改回来。 |
| **渲染器** | MC < 1.17 强制 Krypton Wrapper；MC ≥ 26.3 强制 MobileGL；1.17 ~ 26.2 只在它被留成 Krypton Wrapper 时改回 MobileGlues，**你手选的 Zink / Virgl / GL4ES 一律保留**。 |
| **`-XX:UseSVE=0`** | 该实例实际会用的 Java 主版本 < 17 时，从它的 `javaArgs` 里删掉这一项。 |

**为什么渲染器要按版本切**：这几个渲染器的 `minMCver` 不是"仅显示"，启动时
`checkRenderer` 会拿它和实际 MC 版本比，**超出范围就弹一个不可取消的对话框**
（点「取消」直接中止启动），每次启动都弹一次。MobileGlues 的 `minMCver` 是 `1.17`，
MobileGL 的是 `26.3`；Krypton Wrapper 没有下界、MobileGlues 与 MobileGL 都没有上界，
所以三支正好无缝覆盖全部版本——连 1.0 都不会缺渲染器。

**26.3 那一支刻意无视你的手选**，因为留在 MobileGlues 上是必然黑屏，不是偏好问题。
32 位包（`armeabi-v7a`）里没有 `libMobileGL.so`，所以那种包不会切到 MobileGL：
`RendererManager.canUseMobileGL()` 查的是**本进程实际加载 `.so` 的目录里有没有这个文件**，
判不通过就维持 MobileGlues。

> 这里**不能**用 `Build.SUPPORTED_ABIS` 判——那是设备级的：64 位设备上装 32 位包，
> 它照样含 `arm64-v8a`，可包里根本没有那个 `.so`。

## MC 26.2 起的图形后端

**这是本 fork 对 FCL 的第 8 项改动，1.3.3.6 起。** 26.2 起游戏会自己挑图形后端，可能选到 Vulkan——
而麒麟 Maleoon 的 Vulkan 能力不足（这正是本项目「绝不调用 Vulkan」的由来），选到就是
**黑屏或者直接退出**。

对策只有一条：**每次启动前，把该实例 `options.txt` 里的 `preferredGraphicsBackend`
写成 `opengl`**，判定条件是 MC ≥ 26.2（含 `26w14a` 起的快照）。这一步在 `LauncherHelper`
的启动路径上，每次启动都落一次盘；低于 26.2 的版本不认识这个键，保持你在设置里选的值。

**必须是 `opengl` 而不是 `default`。** 官方 26.2 更新日志写明：设成 `default` 时，游戏在
启动阶段**仍然会去探测 Vulkan**；只有明确写 `opengl` 才是"完全不与 Vulkan 交互"。

选这条路的理由是它**不依赖联网、不依赖模组、不依赖加载器**，原版和 NeoForge 一样管用。

> 曾经试过把 [PreferOpenGL](https://modrinth.com/mod/preferopengl) 模组下进实例的 `mods/`，
> 或者给实例追加 `-Dminecraft.forceOpenGL=true`。**两条都已废弃**：前者的许可证是
> `LicenseRef-All-Rights-Reserved`（保留所有权利），公开仓库不能分发它，而且它做的本来
> 就是写同一个 `options.txt` 键——多一层联网依赖换不来任何确定性；后者这个 JVM 属性从未
> 被证实存在。

## MC 26.3 起的渲染器

**这是本 fork 对 FCL 的第 9 项改动，1.3.3.6 起。** 26.3 起 MC 的 **OpenGL 路径也改用 ShaderC 编译
shader**（与 Vulkan 同一套）。MobileGlues 是架在宿主 GLES 驱动之上的薄转译层，应用的桌面
GLSL 会直接喂给宿主驱动，在这个区间表现为**过 Mojang logo 之后黑屏**；26.3 还引入了 OIT
（34 个 `oit_*` shader）和 SDL3 窗口层，进一步加重。

对策是换用 [MobileGL](https://github.com/MobileGL-Dev/MobileGL)：它自建完整 GL 状态机，
shader 链路是 `GLSL → glslang → SPIR-V → SPIRV-Cross → ESSL`，**宿主 ES 驱动从头到尾看不到
应用的桌面 GLSL**。判定条件是 MC ≥ 26.3，同样在启动器启动时做（见上一节）。

> **26.3 的快照与 rc 不算"≥ 26.3"** —— 版本比较器里
> `UNKNOWN < SNAPSHOT < PRE_RELEASE < RC < GA`，所以 `26.3-snapshot-3` 排在 `26.3`
> 之前，那些实例留在 MobileGlues 上。这是刻意的：`minMCver` 会原样显示成渲染器列表里的
> `>=26.3`，要连快照一起收进来就得写成 `26.3-snapshot-1`。代价见[已知限制](#已知限制)。

### 内置的 MobileGL 是裁剪过的

上游 MobileGL 有两条后端：`DirectGLES`（默认，纯 GLES）和 `DirectVulkan`。本 fork 的硬约束
是**任何情况下都不调用 Vulkan**，所以内置的 `.so` 是在上游源码上打过
[`patches/mobilegl-no-vulkan.patch`](patches/mobilegl-no-vulkan.patch) 之后编出来的。
那块补丁做四件事：

| 裁剪 | 为什么 |
| --- | --- |
| 摘除 `DirectVulkan` 全部源码 | 不编就不会被选到。只删下面那行 `vulkan` 链接会直接链接失败，因为 DirectVulkan 自己就在调 `vk*`。 |
| 去掉 Android 的 `vulkan` 链接项 | 这是 `libMobileGL.so` 的 `DT_NEEDED` 里 `libvulkan.so` 的**唯一**来源。`DT_NEEDED` 是**加载期**解析——容器里没有这个文件的话 `dlopen` 整个失败，与选哪个后端无关。 |
| 硬锁后端为 `DirectGLES` | 上游把后端暴露成 `MOBILEGL_BACKEND_TYPE` 环境变量（插件里甚至做成用户可切换的开关），这里改成环境变量说了不算。 |
| 摘除 `DriverPost` | 上游的诊断入口会在运行期 `dlopen("libvulkan.so")` 并真的建 `VkInstance` 去探测。FCL 从不调用它，但按硬约束不该留在包里。 |

> ⚠️ **MobileGL 只编了 `arm64-v8a`**（上游就不支持 `armeabi-v7a`）。所以
> `-Darch=arm64,arm` 出的那个 **arm 包里没有 MobileGL**，32 位设备上 26.3 实例会留在
> MobileGlues。
>
> 构建它需要 NDK `27.3.13750724` + C++23 + 全部 submodule——上游**不发预编译产物**
> （Releases 与 Tags 都是空的），只能自己编，并接受 LGPL-3.0 的条款。

## 模组源的镜像

**这是本 fork 对 FCL 的第 10 项改动（1.3.3.6 起）。** 症状：在国内网络下打开「下载 → 模组」，
**能搜出结果、能进详情页、宣传图也能显示，但「选择下载版本」那一片是空白**。
（拿官方 FCL 试也是同样的症状——所以这不是本 fork 引入的。）

原因是 `RemoteModRepository` 这个接口的设计：

| 接口 | 有没有 `DownloadProvider` 参数 |
| --- | --- |
| `search(DownloadProvider, …)` | **有** → 搜索知道要去镜像 |
| `getModById` / `getRemoteVersionsById` / `getCategories` | **没有** → 一律硬编码直连 `api.curseforge.com` / `api.modrinth.com` |

也就是说**只有搜索**享受到了下载源里的镜像改写，详情、版本列表、分类全在撞墙；版本列表
请求失败 → 页面收尾时布局又被恢复成"已加载"的样子 → 用户看到的就是那一片空白。
本 fork 加了一个 `RemoteModHttp`，把启动器当前选定的 `DownloadProvider` 装进去
（装载点是 `DownloadProviders.init()`），让那些没有参数可拿的接口也走同一套候选规则；
同时修掉了失败路径上这个"把空列表渲染成正常页面"的收尾顺序。

**只有 API 被墙，CDN 没有。** 这一点决定了镜像该怎么排：

| 类别 | 地址 | 排序 | 为什么 |
| --- | --- | --- | --- |
| **API** | `api.modrinth.com`、`api.curseforge.com` | **镜像在前**，官方兜底 | 镜像（[MCIM](https://github.com/mcmod-info-mirror/mcim-rust-api)）是**真代理**，它替你把请求转出去。官方地址排前面的国内每次都要先吃一次超时 |
| **文件 CDN** | `cdn.modrinth.com`、`edge.forgecdn.net` | **官方在前**，镜像兜底 | CDN 本身在国内是通的；镜像对文件请求只是 **302 回原站**，实测 `mod.mcimirror.top/files/9019/497/x.jar` 与 `edge.forgecdn.net/files/9019/497/x.jar` 的落点**完全相同**（都是 `mediafilez.forgecdn.net/files/9019/497/x.jar`）。排前面等于每次下载都白绕一跳 |

顺带解决的一件事：**CurseForge 的浏览与下载不再需要 API key**。官方
`api.curseforge.com` 无 key 是 403，而镜像无 key 直接给 200（搜索与文件列表都实测过）。
仍需要 key 的只剩**整合包导出时反查远端文件**那一步（`CurseForgeRemoteModRepository.isAvailable()`
只在那里被判断），没有 key 时导出会退化成"所有文件塞进 `overrides/`"，不影响游戏。

> 非国内用户不受影响：`AutoDownloadProvider` 的候选链里 Mojang 排在 BMCLAPI 之前，
> 那边给出的是原始地址，本来就先试；CDN 也是官方优先。

## 它**不会**做什么

- **默认路径不碰 Vulkan。** MobileGlues 侧的 `hasVulkan12()` 整个实现体已换成 `return 0;`，
  ANGLE 与它的深度清除修正被硬编码关闭；FCL 侧新增的渲染器分支只写
  `POJAV_RENDERER=opengles3`（命中 `egl_bridge.c` 的 `opengles` 前缀分支 → GL4ES 桥接表）。
  可复现的验证命令见 [BUILD.md](BUILD.md#关于绝不调用-vulkan)。
- **内置的 MobileGL 是源码级裁掉 Vulkan 的版本。** DirectVulkan 后端整个不编、
  `libvulkan.so` 不进 `DT_NEEDED`（容器里有没有这个文件都不影响加载）、后端硬锁
  `DirectGLES`、诊断入口 `DriverPost` 一并摘除。见
  [MC 26.3 起的渲染器](#mc-263-起的渲染器)。
- **启动路径上不做 Vulkan 检测。** 上游 1.3.3.7 新增了一套 Vulkan 设备能力检测
  （`com.mio.device.*`），并且把它**接进了启动流程**：MC 26.2+ 的实例每次启动前，
  `MainActivity.checkVulkanThenLaunch` 会调 `VulkanCheckManager.ensureSupported`，
  必要时还会执行一次真实检测 —— 那个检测会 `dlopen("libvulkan.so")`、
  **创建 `VkInstance`**、枚举物理设备。本 fork **把这一段从启动路径上摘掉了**
  （`MainActivity` 里那四个函数已删除，直接走 `doLaunchVersion`），
  因为本项目的硬约束是**任何情况下都不调用 Vulkan**。
  检测功能本身完整保留：实例设置里的「检测 Vulkan」一行照旧可以手动触发，
  `libvulkan_check.so` 也照常随包分发，`vulkan_check_launcher_tip` / 缺失依赖提示等 UI 都在。
  与上游行为唯一的分歧就是「不自动跑」。
- **不覆盖 FCL 自带的其它渲染器。** Nggl4es / GL4ES / VirGL / VGPU / Zink / Freedreno 都还在，
  可以手动选。

  >  但**手选 Zink / Freedreno 会加载 Vulkan** —— 那是 FCL 上游本来的行为，本 fork 没有改动它。
  > 在麒麟上大概率不能用。这就是为什么内置的默认渲染器是 MobileGlues：**只有 MobileGlues
  > 和 MobileGL 这两条路是被改造过的**——前者的 Vulkan 探测被换成 `return 0;`，
  > 后者干脆没编 Vulkan 后端。
- **不再需要 MobileGlues 插件 APK。** `libmobileglues.so` 直接随本 APK 分发。

## 只需要授一次权

装好后正常给 FCL 授「所有文件访问」即可（读写 `.minecraft` 需要）。
**整个应用只剩这一次授权** —— 不再需要给 MobileGlues 插件单独授权。

原理：MobileGlues 把 `config.json` / `latest.log` / `glsl_cache.tmp` / `stats.json` 都放在
`MG_DIR_PATH` 指向的目录，默认是 `/sdcard/MG`（独立插件 APK 的路径，属于「所有文件访问」范围）。
内嵌后 `MG_DIR_PATH` 被指向应用自己的外部私有目录
`Android/data/com.harmony.fcl/files/mobileglues` —— 游戏进程与启动器同 uid，读写不需要任何权限声明。

改配置就改这个文件（**只在缺失时写入，之后永不覆盖**，手改即时生效、改完重启游戏进程）：

```
/sdcard/Android/data/com.harmony.fcl/files/mobileglues/config.json
```

> 其中 `enableANGLE` 与 `angleDepthClearFixMode` 两个键**改了也不生效**：`settings.cpp` 在读取之后
> 无条件覆盖它们。这是刻意的，见 BUILD.md。

## 构建

```bash
git clone https://github.com/YJYAWA/HarmonyFCL && cd HarmonyFCL

export JAVA_HOME=/path/to/jdk-17        # JDK 17
./gradlew :FCL:assembleRelease -Darch=arm64,arm    # 每个 ABI 出一个包
./gradlew :FCL:assembleRelease -Darch=arm64        # 只出 arm64，包更小
```

`libmobileglues.so` 与 `libMobileGL.so`（仅 `arm64-v8a`）都已经**预置**在
`FCL/src/main/jniLibs/<abi>/`，不需要先编译它们。
产物落在 `FCL/build/outputs/apk/release/`。完整环境要求、参数、验证步骤见 **[BUILD.md](BUILD.md)**。

**没有签名密钥也能构建** —— 会自动回落到仓库自带的调试密钥（产物是调试签名，
不能覆盖安装正式包）。要长期发布就自己生成一份，见 BUILD.md「签名密钥」。

想自己重建渲染器库（改渲染器代码，或者验证它们确实不含 Vulkan）：两份源码都在
[`third_party/`](third_party/) 下，**已含全部子模块内容，克隆后不需要初始化子模块**
—— MobileGlues 在 [`third_party/MobileGlues/`](third_party/MobileGlues/)、
MobileGL 在 [`third_party/MobileGL/`](third_party/MobileGL/)，构建命令见 BUILD.md 第 2 节。

## 默认键位

本仓库内置的是 **FCL 上游自带的 `Default` 布局**。

> 上游的 `Default` 键位比较简陋，制作方曾用过一套**第三方作者「不想取名字的阿peng」制作的
> MBE 键位**（参照基岩版操作界面风格，127 个布局组 / 1448 个按键）。那份键位的版权属于作者，
> **未随本仓库分发**，因此本仓库构建出的包默认键位是 `Default`。
> 想换成自己的布局（包括 MBE 键位）：见 BUILD.md「自定义默认键位」。

## 签名密钥

`harmony-fcl.jks` **不在仓库里**（已在 `.gitignore` 中）。原因是拿到它的人可以伪造出
「同包名 `com.harmony.fcl` + 同签名」的 APK，让已经装了本应用的用户被系统当成合法升级装上。

构建时按以下顺序查找，取第一个存在的：

1. Gradle 属性 `-PharmonyFclKeystore=<path>`
2. 环境变量 `HARMONYFCL_KEYSTORE`
3. 仓库根目录下的 `harmony-fcl.jks`
4. 仓库上一级目录下的 `harmony-fcl.jks`

生成命令见 BUILD.md「签名密钥」。**换密钥等于换签名，覆盖安装会被系统拒绝**，要长期发布就
把生成的这份文件妥善保存。

## 已知限制

- **仅在华为LRT-W30(harmony os 6.1.0)上做过验证** 就是说麒麟9030及其套壳型号可以正常使用release内的apk
- **MobileGL 只有 `arm64-v8a`。** 32 位包（`armeabi-v7a`）里没有 `libMobileGL.so`，那种包上
  26.3 的实例会留在 MobileGlues 上（也就是黑屏）。这是上游的限制，不是裁剪造成的。
- **MobileGL 这条路径还没有上过真机。** 已完成的是静态验证：它满足「绝不调用 Vulkan」的
  全部断言（`DT_NEEDED` 无 `libvulkan.so`、无 `libvulkan` 字符串、**无未定义的 `vk*` 符号**），
  且导出的核心 GL 符号与 MobileGlues 逐一对齐（两边各 1383 个核心名）。**「26.3 到底还黑不黑屏」
  只有实机能回答** —— 这正是整个改动的目的，装包后请优先验这一条。
- **26.3 的快照 / rc 版本不会被自动切到 MobileGL。** 它们排序在 `26.3` 正式版之前
  （见 [MC 26.3 起的渲染器](#mc-263-起的渲染器)），所以留在 MobileGlues 上——那个区间就是
  黑屏。手动去渲染器列表里选 MobileGL 能绕过自动判定，但 `checkRenderer` 每次启动都会
  弹一次提示（MobileGL 的 `minMCver` 是 `26.3`），点「继续」才进得去游戏。
  这是划粗线的代价，不是漏判。要收进来就把 `RendererManager.RENDERER_MOBILEGL` 的
  `minMCver`、`InstanceAutoFix.MOBILEGL_MIN_MC` 和 `Renderer.kt` 的注释一起改成
  `26.3-snapshot-1`，测试里的 `26_3 的快照排在正式版之前` 也要一并改。
- `.so` 的页对齐是 4KB。与 FCL 上游自带的 `libgl4es_114.so` 等一致；若将来系统切到 16KB 页，
  需要给它们一起加 `-Wl,-z,max-page-size=16384` 重新构建。
- **文件 CDN 没法镜像。** 镜像只代理 API；对文件请求它做的只是 302 回原站
  （见[模组源的镜像](#模组源的镜像)），所以 `cdn.modrinth.com` / `edge.forgecdn.net`
  要是真的不通，本 fork 也救不了——镜像留作兜底并不等于它能替你把字节取回来。
- **CurseForge 的浏览与下载已不需要 API key**（走镜像），但**整合包导出**里"反查远端文件"
  那一步仍要 key，没有时全部文件会落进 `overrides/`（不影响游戏）。**微软登录（OAuth）**
  的 key 依旧拿不到，该功能不可用。要启用就在 `local.properties` 里填
  `curse.api.key` / `oauth.api.key`。

## 许可证与致谢

| 组件 | 许可证 |
| --- | --- |
| 本 fork（FoldCraftLauncher 衍生） | **GPL-3.0**，见 [LICENSE](LICENSE) |
| [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) | **LGPL-2.1-only**，以独立共享库分发，完整修改版源码在 [`third_party/MobileGlues/`](third_party/MobileGlues/) |
| [MobileGL](https://github.com/MobileGL-Dev/MobileGL) | **LGPL-3.0**，以独立共享库分发，完整修改版源码在 [`third_party/MobileGL/`](third_party/MobileGL/) |
| glslang / SPIRV-Cross / SPIRV-Tools / xxhash / ska 等 | 见 [NOTICE.md](NOTICE.md) |

> 两个渲染器都用「动态链接 + 完整对应源码」的安排：`.so` 作为独立共享库随 APK 分发
> （不是静态链接、也没被改写进 FCL 自己的二进制），源码整棵在 `third_party/` 下，
> 因此接收者具备自行重建与替换的全部条件。逐项说明见 [NOTICE.md](NOTICE.md)。

**许可证文本随 APK 分发。** 构建产物自身携带 `assets/licenses/`（GPL-3.0、LGPL-2.1、
LGPL-3.0、AGPL-3.0、EasyTier-LGPL-3.0 全文）与 `assets/NOTICE.txt`，所以只拿到 APK 的人
也能看到许可证全文与源码获取方式。核对：

```bash
unzip -l HarmonyFCL-<版本>-<abi>.apk | grep -E 'assets/(licenses/|NOTICE)'
```

---

## 相关项目

以下与上游 FoldCraftLauncher 的 README 一致，本 fork 沿用同一套上游依赖。

- [FoldCraftLauncher](https://github.com/FCL-Team/FoldCraftLauncher)：本 fork 的上游（GPL-3.0）
- [HMCL](https://github.com/HMCL-dev/HMCL)：核心功能来源（fclcore 移植自 `org.jackhuang.hmcl`）
- [Boat 及其相关项目](https://github.com/AOF-Dev/Boat)
- [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)（PojavLauncher Android fork）：JVM 启动与渲染后端
- [authlib-injector](https://github.com/yushijinhun/authlib-injector)
- [EasyTier](https://github.com/EasyTier/EasyTier)：局域网联机组网底层（内嵌在 Terracotta 模块中）
- [Terracotta](https://github.com/burningtnt/Terracotta)：基于 EasyTier 的联机方案（Terracotta 模块 JNI 封装）
- [TouchController](https://github.com/TouchController/TouchController)：触摸控制器依赖
- [NG-GL4ES](https://github.com/ShirosakiMio/NG-GL4ES)：gl4es fork 渲染器（构建产物以 aar 随 FCL 发布）
- [FCLRendererPlugin](https://github.com/ShirosakiMio/FCLRendererPlugin)：渲染器插件扩展
- [FCLDriverPlugin](https://github.com/FCL-Team/FCLDriverPlugin)：驱动（Turnip 等）插件扩展
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)（**本 fork 新增**）：内嵌为默认渲染器，见 [NOTICE.md 第一节](NOTICE.md)
- [MobileGL](https://github.com/MobileGL-Dev/MobileGL)（**本 fork 新增**）：26.3 起的渲染器，见 [NOTICE.md 第一节](NOTICE.md)

## 依赖

同样与上游 FoldCraftLauncher 的 README 一致；Android 平台第三方组件的逐项核对见
[NOTICE.md](NOTICE.md)。

- [Amethyst-Android](https://github.com/AngelAuraMC/Amethyst-Android)（PojavLauncher Android fork）: [GPL-3.0]
- Android Support Libraries: [Apache License 2.0](https://android.googlesource.com/platform/prebuilts/maven_repo/android/+/master/NOTICE.txt)
- [GL4ES](https://github.com/ptitSeb/gl4es): [MIT License](https://github.com/ptitSeb/gl4es/blob/master/LICENSE)
- [NG-GL4ES](https://github.com/ShirosakiMio/NG-GL4ES)（gl4es fork，Krypton Wrapper 衍生，FCL 以 aar 形式使用预构建产物）
- [ANGLE](https://chromium.googlesource.com/angle/angle): [BSD-3 License](https://chromium.googlesource.com/angle/angle/+/refs/heads/main/LICENSE)
- [OpenJDK](https://github.com/AngelAuraMC/openjdk-multiarch-jdk8u): [GNU GPLv2 License](https://openjdk.java.net/legal/gplv2+ce.html)（运行时由 FCL-Team 自建并随版本发布）
- [LWJGL3](https://github.com/LWJGL/lwjgl3)（官方 jar + Android 源码补丁）: [BSD-3 License](https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md)
- [LWJGLX](https://github.com/AngelAuraMC/lwjglx) (LWJGL2 API compatibility layer for LWJGL3): unknown license
- [Mesa 3D Graphics Library](https://gitlab.freedesktop.org/mesa/mesa): [MIT License](https://docs.mesa3d.org/license.html)
- [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross)（SPIR-V 反射/转换，natives 以 aar 打包）: [Apache License 2.0](https://github.com/KhronosGroup/SPIRV-Cross/blob/master/LICENSE)
- [bhook](https://github.com/bytedance/bhook) (Used for exit code trapping): [MIT license](https://github.com/bytedance/bhook/blob/main/LICENSE)
- [libepoxy](https://github.com/anholt/libepoxy): [MIT License](https://github.com/anholt/libepoxy/blob/master/COPYING)
- [virglrenderer](https://github.com/AngelAuraMC/virglrenderer): [MIT License](https://gitlab.freedesktop.org/virgl/virglrenderer/-/blob/master/COPYING)
- [OpenAL-Soft](https://github.com/kcat/openal-soft): [GNU LGPLv2.1](https://github.com/kcat/openal-soft/blob/master/COPYING)
  - [oboe](https://github.com/google/oboe): [Apache License 2.0](https://github.com/google/oboe/blob/main/LICENSE)
  - [pffft](https://bitbucket.org/jpommier/pffft/src/master/): [ARR]
- [EasyTier](https://github.com/EasyTier/EasyTier)（Terracotta 模块内嵌组网底层）: [LGPL-3.0](https://github.com/EasyTier/EasyTier/blob/main/LICENSE)
- [Terracotta](https://github.com/burningtnt/Terracotta)（`net.burningtnt.terracotta` JNI 封装）: [AGPL-3.0](https://github.com/burningtnt/Terracotta/blob/main/LICENSE)
- [TouchController](https://github.com/TouchController/TouchController)（触摸控制器）: [LGPL-3.0](https://github.com/TouchController/TouchController/blob/main/LICENSE)
- [discord-rpc](https://github.com/discord/discord-rpc)（`libdiscord-rpc.so`）: [MIT License](https://github.com/discord/discord-rpc/blob/master/LICENSE)
- [control-converter](https://github.com/NingZeStudio/control-converter)（FCL↔ZL2 控制布局转换，以 cc.py 为语义基准的内置纯 Kotlin 实现）: [MIT License](https://opensource.org/licenses/MIT)
- [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues)（**本 fork 新增**，内嵌为默认渲染器）: [LGPL-2.1-only](third_party/MobileGlues/LICENSE)
- [MobileGL](https://github.com/MobileGL-Dev/MobileGL)（**本 fork 新增**，26.3 起的渲染器）: [LGPL-3.0](third_party/MobileGL/COPYING.LESSER)
- glslang / SPIRV-Tools / SPIRV-Reflect / xxHash / asio / flat_hash_map（**随上述两个渲染器静态链接**）: 见 [NOTICE.md 第二节](NOTICE.md)

---

感谢 **FoldCraftLauncher**、**HMCL**、**MobileGlues** 与 **MobileGL** 等上游项目。
如果这个 fork 对你有用，也请去给上游点 star。

本分支（Harmony FCL，包名 `com.harmony.fcl`）是社区修改版，与 FCL-Team 官方团队无隶属关系。
