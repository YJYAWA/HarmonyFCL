package com.mio.manager

import android.content.Context
import com.mio.data.Renderer
import com.mio.plugin.DriverPlugin
import com.mio.plugin.RendererPlugin
import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.R
import java.io.File

object RendererManager {
    /** MobileGL 的 .so 名。[RENDERER_MOBILEGL] 与 [canUseMobileGL] 共用，避免两处写歪。 */
    private const val MOBILEGL_LIB = "libMobileGL.so"

    lateinit var RENDERER_GL4ES: Renderer
    lateinit var RENDERER_VIRGL: Renderer
    lateinit var RENDERER_VGPU: Renderer
    lateinit var RENDERER_ZINK: Renderer
    lateinit var RENDERER_FREEDRENO: Renderer
    lateinit var RENDERER_NGGL4ES: Renderer
    lateinit var RENDERER_MOBILEGLUES: Renderer
    lateinit var RENDERER_MOBILEGL: Renderer
    private var isInit = false

    @JvmStatic
    val rendererList: MutableList<Renderer> = mutableListOf()
        get() {
            if (!isInit) {
                init(FCLApp.getAppContext())
            }
            return field
        }

    fun init(context: Context) {
        if (isInit) return
        isInit = true
        rendererList.clear()
        RENDERER_GL4ES = Renderer(
            "Holy-GL4ES",
            context.getString(R.string.settings_fcl_renderer_gl4es),
            "libgl4es_114.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_GL4ES,
            "",
            "1.21.4"
        )

        RENDERER_VIRGL = Renderer(
            "VirGLRenderer",
            context.getString(R.string.settings_fcl_renderer_virgl),
            "libOSMesa_81.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_VIRGL,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_VGPU = Renderer(
            "VGPU",
            context.getString(R.string.settings_fcl_renderer_vgpu),
            "libvgpu.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_VGPU,
            "",
            "1.16.5"
        )

        RENDERER_ZINK = Renderer(
            "Zink",
            context.getString(R.string.settings_fcl_renderer_zink),
            "libglxshim.so",
            "libEGL_mesa.so",
            "",
            null,
            null,
            Renderer.ID_ZINK,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_FREEDRENO = Renderer(
            "Freedreno",
            context.getString(R.string.settings_fcl_renderer_freedreno),
            "libOSMesa_8.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_FREEDRENO,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_NGGL4ES = Renderer(
            "Krypton Wrapper",
            context.getString(R.string.settings_fcl_renderer_nggl4es),
            "libng_gl4es.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_NGGL4ES,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_MOBILEGLUES = Renderer(
            "MobileGlues",
            context.getString(R.string.settings_fcl_renderer_mobileglues),
            "libmobileglues.so",
            "libmobileglues.so",
            "",
            null,
            null,
            Renderer.ID_MOBILEGLUES,
            "1.17",
            ""
        )

        // MobileGL：26.3 起顶掉 MobileGlues —— 26.3 的 OpenGL 路径改用 ShaderC 编译
        // shader，MobileGlues 在这个区间会过 Mojang logo 后黑屏。
        // minMCver 是 26.3：`LauncherHelper.checkRenderer` 拿它做上下界判断，
        // 低于它的版本留着这个渲染器会**每次启动弹一个不可取消的对话框**，
        // 所以 `InstanceAutoFix` 必须保证 <26.3 的实例不会留着 MobileGL。
        // 没有上界：26.4 起 MC 默认走 Vulkan，本项目另有 options.txt 兜底。
        RENDERER_MOBILEGL = Renderer(
            "MobileGL",
            context.getString(R.string.settings_fcl_renderer_mobilegl),
            MOBILEGL_LIB,
            MOBILEGL_LIB,
            "",
            null,
            null,
            Renderer.ID_MOBILEGL,
            "26.3",
            ""
        )

        RendererPlugin.init(context)
        addRenderer()
        DriverPlugin.init(context)
    }

    private fun addRenderer() {
        rendererList.add(RENDERER_MOBILEGLUES)
        // 本包里没有 libMobileGL.so 时（32 位包）不注册：列出来只会让用户选到一个
        // 加载不起来的渲染器。不注册的话 getRenderer 会静默回落 Krypton Wrapper。
        if (canUseMobileGL()) rendererList.add(RENDERER_MOBILEGL)
        rendererList.add(RENDERER_NGGL4ES)
        rendererList.add(RENDERER_GL4ES)
        rendererList.add(RENDERER_VIRGL)
        rendererList.add(RENDERER_VGPU)
        rendererList.add(RENDERER_ZINK)
        rendererList.add(RENDERER_FREEDRENO)
        rendererList.addAll(RendererPlugin.rendererList)
    }

    fun refresh(context: Context) {
        RendererPlugin.refresh(context)
        rendererList.clear()
        addRenderer()
    }

    /** 原位替换同 id 的插件渲染器实例（v2 环境变量配置变化后调用），已初始化时才生效 */
    fun replaceRenderer(renderer: Renderer) {
        if (!isInit) return
        rendererList.removeIf { it.id == renderer.id }
        rendererList.add(renderer)
    }

    /**
     * 本进程能不能用 MobileGL —— 也就是"这个 APK 里到底有没有那个 .so"。
     *
     * ⚠️ **不要改用 `Build.SUPPORTED_ABIS` 判。** 那是**设备级**的：64 位设备上装 32 位包
     * （`-Darch=arm64,arm` 出的那个 arm 包），它照样含 `arm64-v8a`，可包里没有
     * libMobileGL.so —— 选中它照样是加载失败。这里要回答的是包的问题，不是设备的问题。
     *
     * `nativeLibraryDir` 是**本进程**实际加载 .so 的目录。本工程
     * `useLegacyPackaging = true`（`FCL/build.gradle.kts:218`）意味着安装时 .so 会被解到
     * 那里，所以查文件存不存在是准的。若将来改成 `extractNativeLibs = false`，.so 会留在
     * APK 里、这个目录下什么都没有，本判据会**退化成永远 false**（表现是 26.3 实例静默留在
     * MobileGlues 上黑屏）—— 那时得换别的办法，但仍然不能退回按 ABI 判。
     *
     * 单测环境里拿不到 Context，`getAppContext()` 会失败，按不可用处理。
     */
    @JvmStatic
    fun canUseMobileGL(): Boolean = runCatching {
        File(FCLApp.getAppContext().applicationInfo.nativeLibraryDir, MOBILEGL_LIB).exists()
    }.getOrDefault(false)

    @JvmStatic
    fun getRenderer(id: String): Renderer {
        return rendererList.find { it.id == id } ?: RENDERER_NGGL4ES
    }

}