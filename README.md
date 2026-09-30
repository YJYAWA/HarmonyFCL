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

卓易通能让 FCL 在纯血鸿蒙上跑起来，但默认路径有三个坎：

1. **麒麟 Maleoon 驱动的 Vulkan 能力不足。** 鸿蒙对 Vulkan 的支持不完整（例如缺少
   `VK_KHR_dynamic_rendering`），而 FCL 默认的渲染路径会走到 Vulkan 上 —— 表现是渲染异常，
   或者干脆起不来。
2. **MobileGlues 原本要单独装一个插件 APK**，还要给它授一次「所有文件访问」，
   FCL 才检测得到、才肯调用。
3. **FCL 默认下载的那份 Java 在卓易通容器里会启动异常** —— 麒麟 SoC 的 SVE 向量化是原因。

## 本 fork 改了什么

除了下面的七项，其余代码与上游 FoldCraftLauncher 一致。逐文件的改动见
[`patches/fcl-embed-mobileglues.patch`](patches/fcl-embed-mobileglues.patch)。

| # | 改动 | 说明 |
| --- | --- | --- |
| 1 | **内嵌 MobileGlues，并设为默认渲染器** | `libmobileglues.so` 随 APK 分发，不再需要插件 APK |
| 2 | **源码级移除全部 Vulkan 调用路径** | 见 [它**不会**做什么](#它不会做什么) |
| 3 | **新建版本的默认 JVM 参数改为 `-XX:+UnlockExperimentalVMOptions -XX:UseSVE=0`** | 见 [默认 JVM 参数](#默认-jvm-参数) |
| 4 | **改包名与应用名**：`com.harmony.fcl` / **Harmony FCL** | 与官方 FCL 共存，数据目录独立 |
| 5 | **`-Darch` 支持架构列表**（`arm64,arm` 这样写） | 方便一次出多个 ABI 的包 |
| 6 | **按 MC 版本自动匹配渲染器** | < 1.17 → Krypton Wrapper；≥ 1.17 → MobileGlues |
| 7 | **每次打开启动器时做一次实例自检** | 见 [每次启动的实例自检](#每次启动的实例自检) |

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

**这是本 fork 对 FCL 的第 3 项改动。** 新建版本时，「JVM 参数」这一栏的默认值从**空白**
改成了：

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

## 每次启动的实例自检

**这是本 fork 对 FCL 的第 6、7 项改动。** 每次打开启动器时，会在后台（不阻塞启动）对所有
游戏实例扫一遍并修正。全过程幂等，**只改实例自己的设置，不动全局**。修正四件事：

| 项 | 行为 |
| --- | --- |
| **版本隔离** | **强制开启**（游戏目录落在 `.minecraft/versions/<id>/`）。这是刻意的——你在实例设置里关掉，下次打开启动器会被改回来。 |
| **渲染器** | MC < 1.17 强制 Krypton Wrapper；MC ≥ 1.17 只在它被留成 Krypton Wrapper 时改回 MobileGlues，**你手选的 Zink / Virgl / GL4ES 一律保留**。 |
| **`-XX:UseSVE=0`** | 该实例实际会用的 Java 主版本 < 17 时，从它的 `javaArgs` 里删掉这一项。 |
| **MC ≥ 26.2 的图形后端** | 见下。 |

**为什么渲染器要按版本切**：MobileGlues 的 `minMCver` 是 `1.17`，给更老的实例配它，启动时
`checkRenderer` 会弹一个**不可取消**的对话框（点「取消」直接中止启动），每次启动都弹一次。
Krypton Wrapper 则是无下界的，两者正好无缝覆盖全部版本。

**为什么 MC ≥ 26.2 要额外处理**：26.2 起游戏会自己挑图形后端，可能选到 Vulkan——而麒麟
Maleoon 的 Vulkan 能力不足（这正是本项目「绝不调用 Vulkan」的由来），选到就崩。对策分两条：

- **有加载器**（fabric / quilt / forge）：自动把
  [PreferOpenGL](https://modrinth.com/mod/preferopengl) 模组下载到实例的 `mods/` 目录，
  由它把后端钉死在 OpenGL。
- **其余**（原版 / NeoForge / 模组尚未支持的版本）：往该实例的 JVM 参数追加
  `-Dminecraft.forceOpenGL=true`。

> ⚠️ PreferOpenGL 的许可证是 `LicenseRef-All-Rights-Reserved`（保留所有权利），而本仓库是
> 公开仓库 —— **它的 jar 不会被编译进 APK**，而是首次联网时下载到应用私有目录
> `Android/data/com.harmony.fcl/files/preferopengl/` 缓存，之后离线复用。
> **纯离线的首次运行拿不到模组**，那条路会退回 JVM 参数。

## 它**不会**做什么

- **默认路径不碰 Vulkan。** MobileGlues 侧的 `hasVulkan12()` 整个实现体已换成 `return 0;`，
  ANGLE 与它的深度清除修正被硬编码关闭；FCL 侧新增的渲染器分支只写
  `POJAV_RENDERER=opengles3`（命中 `egl_bridge.c` 的 `opengles` 前缀分支 → GL4ES 桥接表）。
  可复现的验证命令见 [BUILD.md](BUILD.md#关于绝不调用-vulkan)。
- **不覆盖 FCL 自带的其它渲染器。** Nggl4es / GL4ES / VirGL / VGPU / Zink / Freedreno 都还在，
  可以手动选。

  >  但**手选 Zink / Freedreno 会加载 Vulkan** —— 那是 FCL 上游本来的行为，本 fork 没有改动它。
  > 在麒麟上大概率不能用，这就是为什么内置的默认渲染器是 MobileGlues。
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

`libmobileglues.so` 已经**预置**在 `FCL/src/main/jniLibs/<abi>/`，不需要先编译它。
产物落在 `FCL/build/outputs/apk/release/`。完整环境要求、参数、验证步骤见 **[BUILD.md](BUILD.md)**。

**没有签名密钥也能构建** —— 会自动回落到仓库自带的调试密钥（产物是调试签名，
不能覆盖安装正式包）。要长期发布就自己生成一份，见 BUILD.md「签名密钥」。

想自己重建渲染器库（改 MobileGlues 代码，或者验证它确实不含 Vulkan）：
源码在 [`third_party/MobileGlues/`](third_party/MobileGlues/)，构建命令见 BUILD.md 第 2 节。

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
- `.so` 的页对齐是 4KB。与 FCL 上游自带的 `libgl4es_114.so` 等一致；若将来系统切到 16KB 页，
  需要给它们一起加 `-Wl,-z,max-page-size=16384` 重新构建。
- `CurseForge` / `OAuth` 的 API key 拿不到，对应功能（整合包下载、微软登录）不可用。
  要启用就在 `local.properties` 里填 `curse.api.key` / `oauth.api.key`。

## 许可证与致谢

| 组件 | 许可证 |
| --- | --- |
| 本 fork（FoldCraftLauncher 衍生） | **GPL-3.0**，见 [LICENSE](LICENSE) |
| [MobileGlues](https://github.com/MobileGL-Dev/MobileGlues) | **LGPL-2.1-only**，以独立共享库分发，完整修改版源码在 [`third_party/MobileGlues/`](third_party/MobileGlues/) |
| glslang / SPIRV-Cross / xxhash / ska 等 | 见 [NOTICE.md](NOTICE.md) |

感谢 **FoldCraftLauncher** 与 **MobileGlues** 两个上游项目，以及 MBE 键位的作者。
如果这个 fork 对你有用，也请去给上游点 star。
