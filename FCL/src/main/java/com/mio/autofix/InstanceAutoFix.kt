package com.mio.autofix

import com.mio.JavaManager
import com.mio.data.Renderer
import com.tungsten.fcl.game.FCLGameRepository
import com.tungsten.fcl.setting.Profile
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fcl.setting.VersionSetting
import com.tungsten.fclcore.download.LibraryAnalyzer
import com.tungsten.fclcore.game.Version
import com.tungsten.fclcore.util.Logging
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level

/**
 * 启动器每次打开时，对所有游戏实例做一次幂等自检与修正。
 *
 * 修正五件事：
 *
 * 1. **启用游戏特定设置（`isUsesGlobal = false`）。** 这是前置条件——第 2~4 步写的都是
 *    版本自己的设置，而 `getVersionSetting()` 在 `isUsesGlobal` 为真时会返回全局对象，
 *    写进去等于没写。
 *
 * 2. **版本隔离常开。** 本 app 的既定行为就是游戏目录落在 `versions/<id>/`，
 *    所以这里是**强制打开**而非"设默认值"——用户改过也会在下次启动时被改回来。
 *
 * 3. **渲染器按 MC 版本二选一。** 见 [rendererFor]：低于 1.17 钉死 Krypton Wrapper，
 *    1.17 及以上只纠正"被留成 Krypton Wrapper"的情况，用户手选的其它渲染器保留。
 *
 * 4. **`-XX:UseSVE=0` 与老 JDK 不兼容。** 这个参数是 aarch64 专有的，JDK 17 才引入；
 *    Java 8 见到它会以 `Unrecognized VM option 'UseSVE'` 直接退出。而
 *    [VersionSetting.DEFAULT_JAVA_ARGS] 是无条件带上的，所以老版本实例开箱必崩。
 *    判定依据是**实际会被用到的那个 Java**，与 `LauncherHelper` 启动时的选择逻辑保持一致。
 *
 * 5. **MC 26.2 起游戏可能自己挑到 Vulkan 渲染后端。** 麒麟 Maleoon 的 Vulkan 能力不足
 *    （这正是本项目"绝不调用 Vulkan"硬约束的由来），选到就崩。对策分两条路：
 *    - 有加载器（fabric/quilt/forge）且 PreferOpenGL 模组支持该版本 → 把模组放进 `mods/`
 *    - 其余（原版、NeoForge、模组尚未支持的版本）→ 往该实例的 JVM 参数追加
 *      `-Dminecraft.forceOpenGL=true`
 *
 * 整个过程只改**版本自己的设置**，全局 Java 虚拟机参数不动。
 */
object InstanceAutoFix {

    private const val TAG = "InstanceAutoFix"

    /** 只有 aarch64 的 JDK 17+ 认识它；更老的 JDK 会拒绝启动 */
    private const val USE_SVE = "-XX:UseSVE=0"

    /** 未知的 `-D` 属性 JVM 会静默忽略，所以这条最坏情况是"不生效"，不会导致启动失败 */
    private const val FORCE_OPENGL = "-Dminecraft.forceOpenGL=true"

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
     * 入口。挂在 `SplashActivity.enterLauncher()` 里 `ConfigHolder.init()` 之后，
     * 用独立的 IO 协程跑、**不 await**——这里会碰网络和磁盘，不能拖慢启动。
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
     * 处理单个实例。返回是否发生了实际改动。
     *
     * @throws com.tungsten.fclcore.game.VersionNotFoundException 版本已被删掉
     */
    private fun applyToVersion(repository: FCLGameRepository, id: String): Boolean {
        // 读实例 jar 里的 version.json——加载器实例也能借此拿到真实的 MC 版本，
        // 而不是 fabric-loader 的版本号。jar 还没下载完时拿不到，跳过即可。
        val gameVersion = repository.getGameVersion(id).orElse(null) ?: return false

        val resolved = repository.getResolvedPreservingPatchesVersion(id)
        val loader = detectLoader(LibraryAnalyzer.analyze(resolved, gameVersion))

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

        // 渲染器按 MC 版本二选一，见 rendererFor
        val wantRenderer = rendererFor(gameVersion, vs.renderer)
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

        // 26.2 起的渲染后端：有加载器就上模组，否则退回 JVM 参数
        if (PreferOpenGLFetcher.maySelectVulkan(gameVersion)) {
            val jar = if (loader != null) PreferOpenGLFetcher.resolve(loader, gameVersion) else null
            if (jar != null) {
                if (installMod(repository.getModsDirectory(id), jar)) {
                    changed = true
                    Logging.LOG.log(Level.INFO, "[$TAG] $id 放入 ${jar.name}（$loader / $gameVersion）")
                }
                // 模组就位后，之前给同一个版本加的那条 JVM 参数就多余了。
                // 清掉它，让实例的最终状态只取决于 (加载器, MC 版本)，
                // 而不是"第一次联网扫描和第一次离线扫描谁先跑"。
                if (removeToken(vs, FORCE_OPENGL)) {
                    changed = true
                    Logging.LOG.log(Level.INFO, "[$TAG] $id 已由模组接管，移除 $FORCE_OPENGL")
                }
            } else if (addToken(vs, FORCE_OPENGL)) {
                changed = true
                Logging.LOG.log(
                    Level.INFO,
                    "[$TAG] $id 追加 $FORCE_OPENGL（${loader ?: "原版"} / $gameVersion 无可用模组）"
                )
            }
        }

        return changed
    }

    /**
     * MC 版本 + 当前取值 → 该实例应有的渲染器 ID。
     *
     * 两侧不对称，因为"用错"的代价不一样：
     *
     * - **低于 1.17 一律钉死 Krypton Wrapper。** MobileGlues 的 `minMCver` 是 1.17，留着它会让
     *   `checkRenderer` 每次启动都弹一个 `setCancelable(false)` 的对话框，点"取消"直接中止启动。
     * - **1.17 及以上只纠正"被留成 Krypton Wrapper"这一种情况。** 全局默认本来就是 MobileGlues，
     *   正常实例无需干预；用户刻意选的 Zink / Virgl / GL4ES 一律**原样返回**，
     *   不能每次启动都给人改回去。
     *
     * 边界值取自 MobileGlues 的 `minMCver`，与 `LauncherHelper.checkRenderer` 的
     * `compare(version, minMCver) < 0` 逐字对齐（相等时不算越界）。
     * Krypton Wrapper 的 `minMCver` 是空串、MobileGlues 的 `maxMCver` 是空串，
     * 所以这两支无缝覆盖全部版本，老到 1.0 也不会缺渲染器。
     *
     * 抽成纯函数是为了能单测：判错的后果对用户是"每次启动被弹窗拦一次"。
     *
     * @throws IllegalArgumentException MC 版本号解析不了时（`GameVersionNumber.compare` 抛），
     *         调用方会跳过该实例——宁可不动，也好过按错误的版本给渲染器。
     */
    @JvmStatic
    fun rendererFor(gameVersion: String, current: String): String =
        if (GameVersionNumber.compare(gameVersion, MOBILEGLUES_MIN_MC) >= 0) {
            if (current == Renderer.ID_NGGL4ES) Renderer.ID_MOBILEGLUES else current
        } else {
            Renderer.ID_NGGL4ES
        }

    /**
     * 从 `LibraryAnalyzer` 的结果里认出加载器。
     *
     * 只返回 PreferOpenGL 支持的三种；NeoForge 与其它加载器返回 null，走 JVM 参数那条路。
     * Quilt 先于 Fabric 判断——Quilt 的安装文件里也可能带 fabric 相关条目。
     */
    private fun detectLoader(analyzer: LibraryAnalyzer): String? {
        fun has(type: LibraryAnalyzer.LibraryType) = analyzer.getLibrary(type).isPresent
        return when {
            // Quilt 先判：这里一并排除了 LEGACY_FABRIC（更老的版本，不在 PreferOpenGL 支持范围内）
            has(LibraryAnalyzer.LibraryType.QUILT) -> "quilt"
            has(LibraryAnalyzer.LibraryType.FABRIC) -> "fabric"
            has(LibraryAnalyzer.LibraryType.FORGE) -> "forge"
            else -> null
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

    /** 追加 token，已存在则不重复添加（幂等）。 */
    private fun addToken(vs: VersionSetting, token: String): Boolean {
        val tokens = vs.javaArgs.split(' ').filter { it.isNotEmpty() }
        if (token in tokens) return false
        vs.javaArgs = (tokens + token).joinToString(" ")
        return true
    }

    /**
     * 把缓存里的模组拷进实例的 `mods/`。
     * 同名同大小视为已就位——避免每次启动都重写一遍文件。
     */
    private fun installMod(modsDir: Path, jar: File): Boolean {
        Files.createDirectories(modsDir)
        val target = modsDir.resolve(jar.name)
        if (Files.isRegularFile(target) && Files.size(target) == jar.length()) return false
        Files.copy(jar.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}
