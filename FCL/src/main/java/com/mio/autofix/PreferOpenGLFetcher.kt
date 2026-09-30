package com.mio.autofix

import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.mod.ModLoaderType
import com.tungsten.fclcore.mod.RemoteMod
import com.tungsten.fclcore.mod.modrinth.ModrinthRemoteModRepository
import com.tungsten.fclcore.util.Logging
import com.tungsten.fclcore.util.io.NetworkUtils
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import java.io.File
import java.util.logging.Level

/**
 * PreferOpenGL 模组的解析、下载与缓存。
 *
 * 这个模组做的事是改写 options.txt 里的 `graphicsApiPreference:prefer_opengl`，
 * 把 MC 26.2+ 的渲染后端钉死在 OpenGL——麒麟 Maleoon 的 Vulkan 能力不足，
 * 游戏自动选到 Vulkan 会直接崩溃。
 *
 * **为什么不打包进 APK**：该模组的许可证是 `LicenseRef-All-Rights-Reserved`（保留所有权利），
 * 而本仓库已开源到公开仓库，把它随 APK 一起分发属于再分发受版权保护的作品。
 * 所以改成首次运行时联网下载到应用私有目录缓存，之后离线复用。
 *
 * 解析顺序：先查 Modrinth API（能自动吃到作者后续新增的 MC 版本支持），
 * API 不可达时回落到 [FALLBACK_URLS] 里写死的文件，保证有一条确定的路。
 */
object PreferOpenGLFetcher {

    private const val TAG = "PreferOpenGL"

    private const val PROJECT_ID = "rmumVsNS"

    /**
     * 模组支持的加载器。NeoForge 不在其中（模组说明里标注仍在开发中），
     * 因此 NeoForge 实例走 JVM 参数那条路。
     */
    private val SUPPORTED_LOADERS = setOf("fabric", "quilt", "forge")

    /** 模组支持的首个 MC 版本；低于它不需要处理 */
    const val MIN_SUPPORTED_MC = "26.2"

    /** [MIN_SUPPORTED_MC] 对应的首个快照，供 [GameVersionNumber.isAtLeast] 快速比较用 */
    private const val MIN_SUPPORTED_SNAPSHOT = "26w14a"

    /**
     * API 不可达时的兜底版本上界。
     * 正常情况下这个范围由 API 返回的 `gameVersions` 决定，这里只是离线兜底。
     */
    const val FALLBACK_MAX_MC = "26.3"

    /**
     * 写死的回落地址（2026-09-30 取自 Modrinth API）。
     * fabric 与 quilt 共用同一个 jar，forge 是独立构建。
     */
    private val FALLBACK_URLS = mapOf(
        "fabric" to "https://cdn.modrinth.com/data/rmumVsNS/versions/sMcPi6uS/preferopengl-1.0.0.jar",
        "quilt" to "https://cdn.modrinth.com/data/rmumVsNS/versions/sMcPi6uS/preferopengl-1.0.0.jar",
        "forge" to "https://cdn.modrinth.com/data/rmumVsNS/versions/3x35PJ6n/preferopengl-forge-1.0.0.jar",
    )

    private fun cacheDir(): File = File(FCLPath.FILES_DIR, "preferopengl")

    fun isSupportedLoader(loader: String): Boolean = loader in SUPPORTED_LOADERS

    /**
     * 这个 MC 版本是否属于"游戏会自己挑渲染后端、可能挑到 Vulkan"的范围。
     *
     * 26.2 起 MC 才引入图形后端选择；麒麟 Maleoon 的 Vulkan 能力不足，
     * 一旦游戏选到 Vulkan 就是直接崩溃，所以在启动前必须把后端钉死。
     * 版本比较走 [GameVersionNumber]，解析不了（自造版本号之类）时回落到粗糙的正则。
     */
    @JvmStatic
    fun maySelectVulkan(mcVersion: String): Boolean = runCatching {
        GameVersionNumber.asGameVersion(mcVersion)
            .isAtLeast(MIN_SUPPORTED_MC, MIN_SUPPORTED_SNAPSHOT)
    }.getOrElse {
        mcVersionForCompare(mcVersion)?.let { it >= MC_26_2 } ?: false
    }

    /**
     * 取得适用于 [loader] + [mcVersion] 的模组 jar；不支持时返回 null。
     *
     * 返回的文件已确保落在本地缓存目录里，可直接拷进实例的 mods 目录。
     * 任何网络失败都只记日志并返回 null，绝不抛出——调用方在启动路径上。
     */
    @JvmStatic
    fun resolve(loader: String, mcVersion: String): File? {
        if (!isSupportedLoader(loader)) return null

        // 1) 先问 API：能同时确认"这个 MC 版本"和"这个加载器"真的被支持
        val apiAnswer = runCatching { resolveFromApi(loader, mcVersion) }
        if (apiAnswer.isSuccess) {
            // API 答了。此时 null 的意思是"确实不支持这个组合"——**不能再回落**。
            // 把模组塞进它不支持的版本会让游戏崩，那比退回 JVM 参数更糟。
            // （API 返回空列表也走这里，方向偏保守，不会崩。）
            return apiAnswer.getOrNull()
        }

        // 2) 只有"API 连不上"才用写死地址兜底，保证离线/接口挂掉时仍有一条确定的路
        Logging.LOG.log(Level.WARNING, "[$TAG] Modrinth API 不可达，改用离线兜底", apiAnswer.exceptionOrNull())
        if (!withinFallbackRange(mcVersion)) {
            Logging.LOG.log(Level.INFO, "[$TAG] $mcVersion 不在模组支持范围（兜底判断），改走 JVM 参数")
            return null
        }
        val url = FALLBACK_URLS[loader] ?: return null
        val target = File(cacheDir(), url.substringAfterLast('/'))
        if (target.isFile && target.length() > 0) return target
        return if (download(url, target)) target else null
    }

    /**
     * 查 Modrinth，挑出同时匹配加载器与游戏版本的那个文件。
     * 没有匹配（例如 NeoForge、或超出模组支持范围）时返回 null。
     */
    private fun resolveFromApi(loader: String, mcVersion: String): File? {
        val wantLoader = when (loader) {
            "fabric" -> ModLoaderType.FABRIC
            "quilt" -> ModLoaderType.QUILT
            "forge" -> ModLoaderType.FORGE
            else -> return null
        }

        val stream = ModrinthRemoteModRepository.MODS.getRemoteVersionsById(PROJECT_ID)
        val matched: RemoteMod.Version? = try {
            stream.filter { v ->
                mcVersion in v.gameVersions() && wantLoader in v.loaders()
            }.findFirst().orElse(null)
        } finally {
            stream.close()
        }

        if (matched == null) {
            Logging.LOG.log(Level.INFO, "[$TAG] 模组不支持 $loader / $mcVersion，改走 JVM 参数")
            return null
        }

        val remote = matched.file()
        // 缓存文件名用远端文件名，作者发新版时自然换名、不会命中旧缓存
        val target = File(cacheDir(), remote.filename())
        if (target.isFile && target.length() > 0) return target
        return if (download(remote.url(), target)) target else null
    }

    /**
     * API 不可达时的兜底范围判断。只做粗判，宁可多试一次下载也不要漏掉该处理的版本。
     */
    private fun withinFallbackRange(mcVersion: String): Boolean = runCatching {
        val v = mcVersionForCompare(mcVersion) ?: return@runCatching false
        v >= MC_26_2 && v <= MC_26_3
    }.getOrDefault(false)

    private val MC_26_2 = 2602
    private val MC_26_3 = 2603

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

    /** 下载到 [target]，失败时清掉半截文件。 */
    private fun download(url: String, target: File): Boolean {
        return runCatching {
            target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) return false }
            val conn = NetworkUtils.resolveConnection(
                NetworkUtils.createHttpConnection(NetworkUtils.toURL(url))
            )
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.length() > 0
        }.getOrElse {
            target.delete()
            Logging.LOG.log(Level.WARNING, "[$TAG] 下载失败: $url", it)
            false
        }.also {
            if (it) Logging.LOG.log(Level.INFO, "[$TAG] 已缓存模组: ${target.name}")
        }
    }
}
