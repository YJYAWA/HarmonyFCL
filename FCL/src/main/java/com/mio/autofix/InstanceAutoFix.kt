package com.mio.autofix

import com.mio.JavaManager
import com.mio.data.Renderer
import com.mio.manager.RendererManager
import com.tungsten.fcl.game.FCLGameRepository
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fcl.setting.VersionSetting
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
 * 3. **渲染器按 MC 版本三选一。** 见 [rendererFor]：低于 1.17 钉死 Krypton Wrapper；
 *    26.3 及以上换成 MobileGL（MobileGlues 在该区间过 Mojang logo 后黑屏）；
 *    中间的 1.17~26.2 只纠正"被留成 Krypton Wrapper"的情况，用户手选的其它渲染器保留。
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

    /** 只有 aarch64 的 JDK 17+ 认识它；更老的 JDK 会拒绝启动 */
    private const val USE_SVE = "-XX:UseSVE=0"

    private const val MIN_JAVA_FOR_SVE = 17

    /**
     * MobileGlues 支持的最低 MC 版本，与 `RendererManager` 里那个渲染器的 `minMCver` 必须一致。
     *
     * 这个值不是"仅显示"：`LauncherHelper.checkRenderer()` 拿它和实际 MC 版本比，
     * **超出范围就弹一个 `setCancelable(false)` 的对话框**——每次启动都弹一次，
     * 点"取消"直接中止启动。所以低于 1.17 的实例绝不能留着 MobileGlues。
     */
    private const val MOBILEGLUES_MIN_MC = "1.17"

    /**
     * MobileGL 支持的最低 MC 版本，与 `RendererManager` 里那个渲染器的 `minMCver` 必须一致。
     *
     * 26.3 起 MC 的 OpenGL 路径改用 ShaderC 编译 shader，MobileGlues 在这个区间会过
     * Mojang logo 后黑屏。和上面同一个道理：这个值不是"仅显示"，`checkRenderer` 拿它
     * 做上下界判断，**超出范围就弹一个 `setCancelable(false)` 的对话框**，
     * 所以低于 26.3 的实例绝不能留着 MobileGL。
     *
     * ⚠️ **26.3 的快照 / rc 不算"≥ 26.3"。** `GameVersionNumber.Release` 的 `ReleaseType`
     * 序数是 `UNKNOWN < SNAPSHOT < PRE_RELEASE < RC < GA`，所以 `26.3-snapshot-3` 排在
     * `26.3` **之前**——这些实例会走 1.17~26.2 那一支，留在 MobileGlues 上。
     *
     * 这是刻意划粗线：`minMCver` 除了当判据，还会被 `RendererSelectItemAdapter` 原样显示成
     * `>=26.3`，要连快照一起收进来就得写成 `26.3-snapshot-1`，让用户读到一串他没见过的
     * 版本号；而究竟从哪个快照开始黑屏并没有查实（快照 2 加 OIT、快照 4 换 SDL3、
     * 快照 5 才把 shader 编译切到 ShaderC），凭猜定边界不如划一条粗但明确的线。
     *
     * 副作用见 README「已知限制」：26.3 快照的用户留在 MobileGlues 上是黑屏，手选 MobileGL
     * 能绕过这里，但 `checkRenderer` 每次启动都会弹一次提示，得点「继续」。要改先改 README。
     */
    private const val MOBILEGL_MIN_MC = "26.3"

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

        // UseSVE：按实际会被用到的 Java 主版本判断
        if (javaMajorOf(vs, resolved) < MIN_JAVA_FOR_SVE) {
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
     * 三支，判错的代价不一样：
     *
     * - **低于 1.17 一律钉死 Krypton Wrapper。** MobileGlues 的 `minMCver` 是 1.17，留着它会让
     *   `checkRenderer` 每次启动都弹一个 `setCancelable(false)` 的对话框，点"取消"直接中止启动。
     * - **26.3 及以上一律换成 MobileGL。** 26.3 起 MC 的 OpenGL 路径改用 ShaderC 编译 shader
     *   （与 Vulkan 同一套），MobileGlues 在这个区间过 Mojang logo 后黑屏。这一支**刻意覆盖
     *   用户的手选**：留在 MobileGlues 上是必然黑屏，不是偏好问题。
     *   注意 26.3 的**快照 / rc 排序在正式版之前**，走的是下面那一支，见 [MOBILEGL_MIN_MC]。
     * - **1.17 ~ 26.2 只纠正"被留成 Krypton Wrapper"这一种情况。** 全局默认本来就是 MobileGlues，
     *   正常实例无需干预；用户刻意选的 Zink / Virgl / GL4ES 一律**原样返回**，
     *   不能每次启动都给人改回去。
     *
     * 边界值逐字对齐 `LauncherHelper.checkRenderer` 的 `compare(version, minMCver) < 0`
     * 与 `compare(version, maxMCver) > 0`（相等都不算越界）。Krypton Wrapper 的 `minMCver`
     * 是空串、MobileGlues 与 MobileGL 的 `maxMCver` 都是空串，所以三支无缝覆盖全部版本，
     * 老到 1.0 也不会缺渲染器。
     *
     * [mobileGLUsable] 由调用方传入——MobileGL 只编了 arm64-v8a，32 位包里没有那个 .so，
     * 选中它会在启动时加载失败。默认 `true` 只是为了让纯函数在单测里能判满分支，
     * 真正的取值来自 [RendererManager.canUseMobileGL]。
     *
     * 抽成纯函数是为了能单测：判错的后果对用户是"每次启动被弹窗拦一次"。
     *
     * @throws IllegalArgumentException MC 版本号解析不了时（`GameVersionNumber.compare` 抛），
     *         调用方会跳过该实例——宁可不动，也好过按错误的版本给渲染器。
     */
    @JvmStatic
    fun rendererFor(gameVersion: String, current: String, mobileGLUsable: Boolean = true): String = when {
        GameVersionNumber.compare(gameVersion, MOBILEGLUES_MIN_MC) < 0 -> Renderer.ID_NGGL4ES
        mobileGLUsable && GameVersionNumber.compare(gameVersion, MOBILEGL_MIN_MC) >= 0 ->
            Renderer.ID_MOBILEGL
        current == Renderer.ID_NGGL4ES -> Renderer.ID_MOBILEGLUES
        else -> current
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
     * 实际会被用到的 Java 主版本。
     *
     * 与 `LauncherHelper` 的选择逻辑对齐：实例显式指定了 Java（[VersionSetting.java] 不为 `"Auto"`）
     * 就用它；否则用 `JavaManager` 推荐的那个。
     */
    private fun javaMajorOf(vs: VersionSetting, version: Version?): Int {
        val explicit = vs.java
        if (explicit != "Auto") {
            JavaManager.javaList.find { it.name == explicit }?.let { return it.getVersion() }
        }
        return JavaManager.getSuitableJavaVersion(version).getVersion()
    }

    /** 按 token 移除，保留用户其它参数与原有顺序；本就不在则返回 false（幂等）。 */
    private fun removeToken(vs: VersionSetting, token: String): Boolean {
        val tokens = vs.javaArgs.split(' ').filter { it.isNotEmpty() }
        if (token !in tokens) return false
        vs.javaArgs = tokens.filter { it != token }.joinToString(" ")
        return true
    }
}
