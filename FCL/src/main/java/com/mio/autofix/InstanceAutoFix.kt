package com.mio.autofix

import com.mio.JavaManager
import com.mio.data.Renderer
import com.mio.manager.RendererManager
import com.tungsten.fcl.game.FCLGameRepository
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fcl.setting.VersionSetting
import com.tungsten.fclcore.game.JavaVersion
import com.tungsten.fclcore.game.Version
import com.tungsten.fclcore.util.Logging
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import java.util.logging.Level

/**
 * 对游戏实例做一次幂等自检与修正。**两个触发点**：
 *
 * - [applyAll]：**每次打开启动器时**，后台协程扫全部实例；
 * - [applyToVersion]：**每次启动游戏前**，只处理被点的那一个
 *   （`LauncherHelper.launch0()` 任务链里的 `launch.state.instance_fix` 一栏）。
 *
 * 两处都是幂等的，重复跑不会有副作用。
 *
 * 修正四件事：
 *
 * 1. **启用游戏特定设置（`isUsesGlobal = false`）。** 这是前置条件——第 2~3 步写的都是
 *    版本自己的设置，而 `getVersionSetting()` 在 `isUsesGlobal` 为真时会返回全局对象，
 *    写进去等于没写。
 *
 * 2. **版本隔离常开。** 本 app 的既定行为就是游戏目录落在 `versions/<id>/`，
 *    所以这里是**强制打开**而非"设默认值"——用户改过也会在下次启动时被改回来。
 *
 * 3. **渲染器按 MC 版本三档强制映射。** 见 [rendererFor]：**1.17.1 及更早**（含
 *    Pre-Classic / Classic / Indev / Infdev / Alpha / Beta）钉死 Krypton Wrapper；
 *    **1.18 ~ 26.2** 用 MobileGlues；**26.3 及以上**换成 MobileGL
 *    （MobileGlues 在该区间过 Mojang logo 后黑屏）。三档都覆盖用户手选。
 *
 * 4. **`-XX:UseSVE=0` 与老 JDK 不兼容。** 这个参数是 aarch64 专有的，JDK 17 才引入；
 *    Java 8 见到它会以 `Unrecognized VM option 'UseSVE'` 直接退出。而
 *    [VersionSetting.DEFAULT_JAVA_ARGS] 是无条件带上的，所以老版本实例开箱必崩。
 *    判定依据是**实际会被用到的那个 Java**，与 `LauncherHelper` 启动时的选择逻辑保持一致。
 *
 * 整个过程只改**版本自己的设置**，全局 Java 虚拟机参数不动。
 *
 * MC 26.2 起的图形后端**不在这里处理**。它落的是 `options.txt` 而不是实例设置，而且每次
 * 启动前都得重新确认一次，所以由 `LauncherHelper` 在启动路径上写——判定条件见
 * [maySelectVulkan]，那里也说明了为什么不用"塞模组 / 塞 JVM 参数"这两条老路。
 */
object InstanceAutoFix {

    private const val TAG = "InstanceAutoFix"

    /** 只有 aarch64 的 JDK 17+ 认识它；更老的 JDK 会以 `Unrecognized VM option` 直接退出 */
    private const val USE_SVE = "-XX:UseSVE=0"

    /**
     * 认识 `-XX:UseSVE` 的最低 Java 主版本。
     *
     * ⚠️ **这个开关不是"优化"，是"能不能启动"**：Java 8 见到 `-XX:UseSVE=0` 会在
     * 初始化阶段就报 `Unrecognized VM option 'UseSVE'` 退出。而
     * [VersionSetting.DEFAULT_JAVA_ARGS] 是无条件带这个参数的，所以**任何用 Java 8 的实例
     * 开箱必崩**——这正是远古版本（它们的 version.json 没有 `javaVersion` 字段，
     * 落到 [JavaManager.getSuitableJavaVersion] 就是 Java 8）启动不了的根因。
     */
    private const val MIN_JAVA_FOR_SVE = 17

    /**
     * 「这个实例要用哪个 Java 判不出来」的哨兵值。
     *
     * 取 `-1`：它比任何真实 Java 主版本都小，所以在 [needsSveRemoval] 的
     * `major < MIN_JAVA_FOR_SVE` 里天然落到"要删"那一支——这正是我们要的"不认识就删"。
     * 不要改成 `Int.MAX_VALUE` 或别的正值，那会把策略反过来（变成"不认识就留"，
     * 也就是现在这个 BUG 的行为）。
     */
    private const val UNKNOWN_JAVA_MAJOR = -1

    // 渲染器三个档位的两个分界点：**1.18** 和 **26.3**。
    //
    // 这两个数字在别处还有两份，改动时必须一起改（见 [rendererFor] 与 [classify] 的说明）：
    //   - `RendererManager` 里 MobileGlues 的 `minMCver = "1.18"`、MobileGL 的 `minMCver = "26.3"`；
    //   - `LauncherHelper.checkRenderer()` 拿这两个值和实际 MC 版本比，**超出范围就弹一个
    //     `setCancelable(false)` 的对话框**，点"取消"直接中止启动。
    // 所以档位判错不是"渲染器选得不好"，是"每次启动被拦一次"或"过 Mojang logo 后黑屏"。

    /**
     * 全量入口：挂在 `SplashActivity.enterLauncher()` 里 `ConfigHolder.init()` 之后，
     * 用独立的 IO 协程跑、**不 await**——这里会碰磁盘，不能拖慢启动。
     *
     * ⚠️ **这不是唯一的入口。** 同一个自检还会在**每次启动游戏前**对**该实例**再跑一次
     * （`LauncherHelper.launch0()` 任务链里的 `launch.state.instance_fix` 一栏，见
     * [applyToVersion] 的说明）。两边都是幂等的，所以重复跑不会互相打架：
     * 第一次改完第二次就没得改，直接返回 `false`。
     *
     * 任何单个实例出错都只记日志并继续，绝不向外抛。
     */
    @JvmStatic
    fun applyAll() {
        Logging.LOG.log(Level.INFO, "[$TAG] 开始实例自检")
        var changed = 0
        var failed = 0

        Profiles.profiles.forEach { profile ->
            runCatching {
                profile.repository.versions.forEach { version ->
                    runCatching { applyToVersion(profile.repository, version.id) }
                        .onSuccess { if (it) changed++ }
                        .onFailure {
                            failed++
                            Logging.LOG.log(Level.FINE, "[$TAG] 跳过 ${version.id}", it)
                        }
                }
            }.onFailure {
                failed++
                Logging.LOG.log(Level.WARNING, "[$TAG] 配置自检失败: ${profile.repository}", it)
            }
        }

        Logging.LOG.log(
            Level.INFO,
            "[$TAG] 实例自检结束：修正 $changed 个实例，跳过 $failed 个"
        )
    }

    /**
     * 处理**单个**实例，返回是否发生了实际改动。
     *
     * 两个调用方：
     *
     * 1. [applyAll]（每次打开启动器时，扫全部实例）；
     * 2. `LauncherHelper.launch0()` 的 `launch.state.instance_fix` 一栏
     *    （**每次启动游戏前**，只处理被点的那一个）。
     *
     * 第 2 条是必需的，不能只靠第 1 条：第 1 条是**不 await 的后台协程**，
     * 用户完全可能在它跑完之前就点下启动；而且启动器开着的时候用户还能在设置里
     * 手改渲染器 / JVM 参数，那之后只有再点一次启动器才会被纠正。
     *
     * ⚠️ **调用方在自检之后必须重新取一次版本设置。**
     *
     * 这个函数会在实例开着「使用全局设置」时把它切成游戏特定设置
     * （`isUsesGlobal = false`，见下面第 2 步）。切换之前调用方手里那个
     * `VersionSetting` 是**全局对象**，已经不是启动时该用的设置了 ——
     * 继续读它就会得到「自检跑了但没生效」的假象。
     * `LauncherHelper.launch0()` 因此写成「自检 → `setting = profile.getVersionSetting(id)`」，
     * 那两行不能分开。
     *
     * 这里写的一律是**本实例自己的设置**：开了「使用全局设置」的实例会先被切换到
     * 游戏特定设置（`isUsesGlobal = false`），再写值，所以不会污染全局。
     *
     * @throws com.tungsten.fclcore.game.VersionNotFoundException 版本已被删掉
     */
    @JvmStatic
    fun applyToVersion(repository: FCLGameRepository, id: String): Boolean {
        // 读实例 jar 里的 version.json——加载器实例也能借此拿到真实的 MC 版本，
        // 而不是 fabric-loader 的版本号。jar 还没下载完时拿不到，跳过即可。
        val gameVersion = repository.getGameVersion(id).orElse(null) ?: return false

        val resolved = repository.getResolvedPreservingPatchesVersion(id)

        // ⚠️ 必须用 getLocalVersionSetting / createLocalVersionSetting。
        // getVersionSetting(id) 在 isUsesGlobal 为真时会**返回全局对象**，
        // 往它上面写参数会污染所有版本。
        val vs = repository.getLocalVersionSetting(id)
            ?: repository.createLocalVersionSetting(id)
            ?: return false

        var changed = false

        // 前置条件：启用游戏特定设置。否则即使我们写了版本自己的 javaArgs，
        // 启动时 getVersionSetting() 依然会解析成全局对象，写进去的值等于没写。
        if (vs.isUsesGlobal) {
            vs.isUsesGlobal = false
            changed = true
            Logging.LOG.log(Level.INFO, "[$TAG] $id 启用游戏特定设置")
        }

        // 版本隔离：本 app 的既定行为就是常开（游戏目录落在 versions/<id>/），
        // 所以这里是强制打开而不是"设默认值"——用户改过也会在下次启动时被改回来。
        if (!vs.isIsolateGameDir) {
            vs.isIsolateGameDir = true
            changed = true
            Logging.LOG.log(Level.INFO, "[$TAG] $id 开启版本隔离")
        }

        // 渲染器按 MC 版本三选一，见 rendererFor。能不能用 MobileGL 从 RendererManager 取：
        // 它按"本包里到底有没有 libMobileGL.so"判，32 位包（armeabi-v7a）里没有。
        val wantRenderer = rendererFor(gameVersion, vs.renderer, RendererManager.canUseMobileGL())
        if (vs.renderer != wantRenderer) {
            vs.renderer = wantRenderer
            changed = true
            Logging.LOG.log(Level.INFO, "[$TAG] $id 渲染器设为 $wantRenderer（MC $gameVersion）")
        }

        // UseSVE：只按"这个实例版本要求的 Java"判断，不按"当前装了哪个 Java"（见 javaMajorOf）
        if (needsSveRemoval(vs, resolved)) {
            if (removeToken(vs, USE_SVE)) {
                changed = true
                Logging.LOG.log(Level.INFO, "[$TAG] $id 移除 $USE_SVE（Java 不支持该参数）")
            }
        }

        return changed
    }

    /**
     * MC 版本 + 当前取值 → 该实例应有的渲染器 ID。
     *
     * **三档强制映射，覆盖用户手选**（1.3.4.0 起，按用户指定的分段改掉旧逻辑）：
     *
     * | MC 版本 | 渲染器 |
     * | --- | --- |
     * | 1.17.1 及更早（含 Pre-Classic / Classic / Indev / Infdev / Alpha / Beta / 1.0–1.17.1） | **Krypton Wrapper** |
     * | 1.18 ~ 26.2（含） | **MobileGlues** |
     * | 26.3 及以上 | **MobileGL** |
     *
     * 为什么远古版本要钉死 Krypton Wrapper：MobileGlues 的 `minMCver` 是 1.18，
     * 留着它会让 `checkRenderer` 每次启动弹一个 `setCancelable(false)` 的对话框，
     * 点"取消"直接中止启动。
     *
     * 为什么 26.3 起要换 MobileGL：26.3 的 OpenGL 路径改用 ShaderC 编译 shader
     * （与 Vulkan 同一套），MobileGlues 在这个区间过 Mojang logo 后黑屏。
     *
     * ⚠️ **判据必须能容忍"认不出的版本名"，而且兜底方向必须是远古。**
     * `GameVersionNumber` 对 `0.0.14a` / `Beta 1.7.3` / `Cave game tech test` / `Java版2.0`
     * 这类名字既不抛异常也不报错，而是造一个 `Special` 且 `prev == null`；这种
     * `Special.isUnknown()` 在 `compareToReleaseOrSnapshot` 里**直接返回 1**，即
     * "比任何版本都新"。实测后果是它们被判成 ≥ 26.3 → 选到 MobileGL。
     * 所以判定顺序必须是「先按形态自己认，认得就返回；认不出按远古处理」，
     * **绝不能把认不出的交给官方比较器**，反过来一定错。
     *
     * [mobileGLUsable] 由调用方传入——MobileGL 只编了 arm64-v8a，32 位包里没有那个 .so，
     * 选中它会在启动时加载失败；不可用时 26.3+ 回落 MobileGlues。
     * 默认 `true` 只是为了让纯函数在单测里能判满分支，真正的取值来自
     * [RendererManager.canUseMobileGL]。
     *
     * [current] **已不参与判定**（保留在签名里只是为了不改调用方与既有测试）。
     */
    @JvmStatic
    fun rendererFor(gameVersion: String, current: String, mobileGLUsable: Boolean = true): String =
        when (tierOf(gameVersion, mobileGLUsable)) {
            RendererTier.KRYPTON -> Renderer.ID_NGGL4ES
            RendererTier.MOBILEGLUES -> Renderer.ID_MOBILEGLUES
            RendererTier.MOBILEGL -> Renderer.ID_MOBILEGL
        }

    /** 内置渲染器的三档 */
    private enum class RendererTier { KRYPTON, MOBILEGLUES, MOBILEGL }

    /**
     * 三档归类。纯字符串判定，**不查版本库、不调官方比较器、永不抛异常**。
     *
     * 顺序不能反过来。`GameVersionNumber` 对认不出的名字不抛异常，而是造一个
     * `Special` 且 `prev == null`，而 `Special.isUnknown()` 在比较时**直接返回 1**
     * （"比谁都新"）——实测 `0.0.14a` / `Cave game tech test` / `mc-161607` /
     * `0.31（20091223-1）` / `20100227-1` / `Java版2.0` 全被判成 ≥ 26.3 → MobileGL。
     * 所以只要自己能确定形态，就必须在问官方之前返回；认不出的**按远古**兜底。
     *
     * 兜底给 Krypton Wrapper 而不是 MobileGlues：Krypton Wrapper 的 `minMCver` 是空串，
     * 永远不会触发 `checkRenderer` 那个 `setCancelable(false)` 的越界弹窗。猜错的最坏
     * 结果是渲染慢一点，而不是每次启动都被拦一次。
     */
    private fun tierOf(gameVersion: String, mobileGLUsable: Boolean): RendererTier {
        val tier = classify(gameVersion)
        // 32 位包没有 libMobileGL.so，选中就是加载失败 —— 回落 MobileGlues
        return if (tier == RendererTier.MOBILEGL && !mobileGLUsable) RendererTier.MOBILEGLUES else tier
    }

    /** `26.3-snapshot-3` / `1.18-pre1` / `26.2-rc-1`：排在对应正式版**之前**的开发版 */
    private val PREVIEW_SUFFIX = Regex("-(snapshot|pre|rc)(-|\\d|$)", RegexOption.IGNORE_CASE)

    /**
     * 按**版本名形态**归类，认不出按远古（Krypton Wrapper）处理。
     *
     * 规则从"最古老的命名风格"排到"最新的命名风格"，命中即返回：
     *
     * 1. 远古前缀 / 单词：`rd-` `mc-` `c0.` `in-` `inf-` `a1.` `b1.`、`Beta 1.7.3`、`Cave game`
     * 2. Infdev 的纯日期展示名：`20100227-1` / `20100630`
     * 3. 按周命名：`13w01a` / `21w37a` / `26w14a`
     * 4. 年份编号：`26.1` / `26.3` / `27.0`
     * 5. `1.x`
     * 6. `0.x`
     */
    private fun classify(raw: String): RendererTier {
        val s = raw.trim()
        if (s.isEmpty()) return RendererTier.KRYPTON

        // 1) Pre-Classic / Classic / Indev / Infdev / Alpha / Beta
        //    官方发布名（rd- / mc- / c0. / in- / inf- / a1. / b1.）之外，还要覆盖
        //    中文资料里的展示名（`Beta 1.7.3` / `Alpha 1.2.6` / `Cave game tech test`）
        if (Regex("^(rd-|mc-|c\\d|c?0\\.|in-|inf-|a\\d|b\\d)", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            return RendererTier.KRYPTON
        }
        if (Regex("^(beta|alpha|cave|classic|indev|infdev)", RegexOption.IGNORE_CASE).containsMatchIn(s)) {
            return RendererTier.KRYPTON
        }

        // 2) Infdev 的纯日期展示名：20100227-1 / 20100630（官方发布名是 inf- 前缀，
        //    这两个是资料里常见的写法，解析器认不出，只能靠这里认）
        if (Regex("^\\d{8}(-\\d+)?$").containsMatchIn(s)) return RendererTier.KRYPTON

        // 3) 按周命名的开发版：13w01a / 21w37a / 26w14a / 20w14∞
        //    年份与归属正式版的对应关系（按真实发布历史）：
        //      11w–20w → 1.0 ~ 1.16（20w14∞ 这类愚人节版也在其中）→ Krypton
        //      21w    → 1.17 是 21w03a–21w20a，**21w37a 起才是 1.18** → 以第 37 周为界
        //      22w–25w → 1.19 ~ 1.21.x → MobileGlues
        //      26w    → 26.1 / 26.2 时代 → MobileGlues
        //      27w+   → MobileGL
        Regex("^(\\d{2})w(\\d+)").find(s)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val week = m.groupValues[2].toIntOrNull() ?: 0
            return when {
                year >= 27 -> RendererTier.MOBILEGL
                year >= 22 -> RendererTier.MOBILEGLUES
                year == 21 -> if (week >= 37) RendererTier.MOBILEGLUES else RendererTier.KRYPTON
                else -> RendererTier.KRYPTON
            }
        }

        // 4) 年份编号：26.1 / 26.2 / 26.3 / 27.0
        Regex("^(\\d{2})\\.(\\d+)(?:\\.(\\d+))?").find(s)?.let { m ->
            val major = m.groupValues[1].toIntOrNull() ?: return@let
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            val patch = m.groupValues[3].toIntOrNull() ?: 0
            val preview = PREVIEW_SUFFIX.containsMatchIn(s)
            return when {
                // 档位起点的快照 / rc 排在正式版**之前**（ReleaseType 序数是
                // UNKNOWN < SNAPSHOT < PRE_RELEASE < RC < GA），退回上一档；
                // 但带 patch 的（26.3.1-pre-1）已经在正式版之后，不退回
                major == 27 && minor == 0 && patch == 0 && preview -> RendererTier.MOBILEGLUES
                major >= 27 -> RendererTier.MOBILEGL
                major == 26 -> when {
                    minor >= 4 -> RendererTier.MOBILEGL
                    minor == 3 -> if (preview && patch == 0) RendererTier.MOBILEGLUES else RendererTier.MOBILEGL
                    else -> RendererTier.MOBILEGLUES
                }
                else -> RendererTier.KRYPTON
            }
        }

        // 5) 1.x：1.17.1 及更早 → Krypton；1.18 起 → MobileGlues
        //    1.18 的快照 / pre 同理排在 1.18 之前，而 MobileGlues 的 minMCver 正是 1.18 —
        //    留着它照样会触发越界弹窗，所以这些也归 Krypton（1.18.1-pre1 这种带 patch 的不算）
        Regex("^1\\.(\\d+)(?:\\.(\\d+))?").find(s)?.let { m ->
            val minor = m.groupValues[1].toIntOrNull() ?: return@let
            val patch = m.groupValues[2].toIntOrNull() ?: 0
            val preview = PREVIEW_SUFFIX.containsMatchIn(s)
            return when {
                minor > 18 -> RendererTier.MOBILEGLUES
                minor == 18 -> if (preview && patch == 0) RendererTier.KRYPTON else RendererTier.MOBILEGLUES
                else -> RendererTier.KRYPTON
            }
        }

        // 6) 0.x 开头都是 2009–2010 的 Classic / Indev / Infdev
        //    （0.31 / 0.30_01c / 0.24_SURVIVAL_TEST / 0.0.14a）
        if (s.startsWith("0.")) return RendererTier.KRYPTON

        // 认不出：`1.RV-Pre1` / `3D Shareware v1.34` / `Java版2.0` / 用户自造的实例 id …
        // **一律当远古**——理由见 [tierOf] 的 KDoc，宁可渲染慢也不能每次启动被弹窗拦。
        return RendererTier.KRYPTON
    }

    /** 26.2 是 MC 开始自己挑图形后端的版本；对应首个快照 26w14a */
    private const val MIN_MULTI_BACKEND_MC = "26.2"
    private const val MIN_MULTI_BACKEND_SNAPSHOT = "26w14a"

    /**
     * 这个 MC 版本是否属于"游戏会自己挑渲染后端、可能挑到 Vulkan"的范围。
     *
     * 26.2 起 MC 才引入图形后端选择。麒麟 Maleoon 的 Vulkan 能力不足，游戏一旦选到
     * Vulkan 就是黑屏或者直接退出——这正是本项目"绝不调用 Vulkan"硬约束的由来。
     *
     * **对策只有一条：启动前把 `options.txt` 的 `preferredGraphicsBackend` 写成 `opengl`**
     * （不是 `default` —— 官方 26.2 日志写明 `default` 在启动阶段**仍会去探测 Vulkan**），
     * 由 `LauncherHelper` 在启动路径上每次都落一次盘。这里只负责判定。
     *
     * 曾经试过另外两条路，都已废弃，别再捡回来：
     * - **塞 PreferOpenGL 模组**：要联网下载、许可证还是"保留所有权利"（公开仓库不能分发），
     *   而且它只是替你写同一个 `options.txt` 键——多一层依赖换不来任何确定性。
     * - **塞 `-Dminecraft.forceOpenGL=true`**：这个 JVM 属性从未被证实存在，属于猜测。
     *
     * 版本比较走 [GameVersionNumber]，解析不了（自造版本号之类）时回落到粗糙的正则，
     * 与上面那个判定共用同一套兜底。判错的代价不对称：判漏 → 麒麟上游戏选到 Vulkan 崩掉；
     * 判多 → 只是给老版本白写一个它不认识的键。
     */
    @JvmStatic
    fun maySelectVulkan(mcVersion: String): Boolean = runCatching {
        GameVersionNumber.asGameVersion(mcVersion)
            .isAtLeast(MIN_MULTI_BACKEND_MC, MIN_MULTI_BACKEND_SNAPSHOT)
    }.getOrElse {
        // GameVersionNumber 初始化失败（读不到 /assets/game/versions.txt）或版本号是自造的，
        // 走到这里。宁可多写一个键，也不要漏掉 26.2+ 的实例。
        mcVersionForCompare(mcVersion)?.let { it >= 2602 } ?: false
    }

    /**
     * 把 "26.2" / "26.3-snapshot-3" / "26w14a" 这类版本号压成一个可比较的整数。
     * 只用于兜底判断，不需要精确到快照序号。
     */
    private fun mcVersionForCompare(raw: String): Int? {
        val m = Regex("^26(?:w(\\d+)|[.-](\\d+))").find(raw) ?: return null
        return if (m.groupValues[1].isNotEmpty()) {
            // 26w14a 之类：开发版一律视为 26.2 之后
            2602
        } else {
            2600 + (m.groupValues[2].toIntOrNull() ?: return null)
        }
    }

    /**
     * 这个实例**一定会用**的 Java 主版本；判不出来时给 [UNKNOWN_JAVA_MAJOR]。
     *
     * ⚠️ **这里绝不能用 `JavaManager.getSuitableJavaVersion` 的结果。** 那个函数会在
     * 目标版本没装时**回落到已装的第一个 Java**，于是「远古版本（应为 Java 8）+ 只装了
     * jre17+」这个组合会报出 17 —— 判成"支持 UseSVE"、参数被留下，而实际启动
     * 要么用 Java 17 跑不起来、要么用户装了 Java 8 后直接崩在 `Unrecognized VM option`。
     * 这个坑正是"远古版本启动不了"的根因，**改动前先看这一段**。
     *
     * 正确的语义是**版本要求的 Java**，与装没装无关：
     *
     * - `version.javaVersion.majorVersion` 非空 → MC 自己声明的（1.17+ 的 version.json 都有）；
     * - 为空 → 1.16 及更早的古老版本，一律按 `JavaVersion.JAVA_VERSION_8` 算。
     *   `version.json` 里没有 `javaVersion` 就等价于"为 Java 8 构建"，与
     *   `LauncherHelper.checkGameState()` 里 `getSuitableJavaVersion(null)` → `jre8` 一致。
     *
     * 显式指定了 Java 的实例直接看那个 Java 的版本；名字查不到（理论上不该发生）也算未知。
     */
    private fun javaMajorOf(vs: VersionSetting, version: Version?): Int =
        effectiveJavaMajor(version?.javaVersion?.majorVersion, vs.java)

    /**
     * [javaMajorOf] 的纯函数内核，抽出来是为了能单测——这个判据一开始就是错的
     * （依赖"jre8 装没装"），而它判错的后果是**远古版本点启动直接崩**，
     * 不能只靠肉眼看代码。
     *
     * @param declaredMajor MC 在 `version.json` 里声明的 Java 主版本；
     *        1.16 及更早的古老版本**没有这个字段**，传 `null`。
     * @param explicitJavaName 实例设置里选的 Java（`VersionSetting.java`），`"Auto"` 表示自动。
     * @return 会用到的 Java 主版本；判不出来时 [UNKNOWN_JAVA_MAJOR]。
     *
     * 两条规则：
     *
     * 1. **显式指定优先**，且只看**内置的四套 JRE 名字**——不查 `JavaManager.javaList`。
     *    故意的：查已装列表会把"用户选的 Java 没装"变成"未知"，而未知按下面
     *    [needsSveRemoval] 的策略是要删参数的；名字认不出来就不该动用户的参数。
     * 2. 自动选择时用**版本声明的** Java；没有声明就是 Java 8（古老版本）。
     *    **绝不能**回落到 `JavaManager.getSuitableJavaVersion` —— 它会在 jre8 没装时
     *    返回已装的第一个 Java（例如 17），从而把"该删的参数"判成"该留"。
     */
    @JvmStatic
    fun effectiveJavaMajor(declaredMajor: Int?, explicitJavaName: String): Int {
        if (explicitJavaName != "Auto") {
            return when (explicitJavaName) {
                "jre8" -> JavaVersion.JAVA_VERSION_8
                "jre17" -> JavaVersion.JAVA_VERSION_17
                "jre21" -> JavaVersion.JAVA_VERSION_21
                "jre25" -> JavaVersion.JAVA_VERSION_25
                else -> UNKNOWN_JAVA_MAJOR
            }
        }
        val declared = declaredMajor ?: JavaVersion.JAVA_VERSION_8
        return if (declared > 0) declared else UNKNOWN_JAVA_MAJOR
    }

    /**
     * 这个实例实际会不会跑在 **不认识 `-XX:UseSVE`** 的 Java 上。
     *
     * 判不出来时**返回 true（=要删）**，故意的：这个判据判错的两种代价完全不对称——
     *
     * - 多删一次：只是少一个 Java 17+ 上的 SVE 优化（SVE 在麒麟上本来就是明确要关掉的，
     *   而且它只是性能选项，不影响正确性）；
     * - 漏删：**Java 8 直接启动失败**，用户看到的是"点了启动没反应/闪退"。
     *
     * 所以宁可不认识就删。这条策略让"远古版本启动不了"不会再依赖"jre8 装没装"这种
     * 与版本本身无关的外部状态。
     */
    private fun needsSveRemoval(vs: VersionSetting, version: Version?): Boolean =
        javaMajorOf(vs, version) < MIN_JAVA_FOR_SVE

    /** 按 token 移除，保留用户其它参数与原有顺序；本就不在则返回 false（幂等）。 */
    private fun removeToken(vs: VersionSetting, token: String): Boolean {
        val tokens = vs.javaArgs.split(' ').filter { it.isNotEmpty() }
        if (token !in tokens) return false
        vs.javaArgs = tokens.filter { it != token }.joinToString(" ")
        return true
    }
}
