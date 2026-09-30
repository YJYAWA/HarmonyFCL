package com.mio.autofix

import com.mio.data.Renderer
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器这一支按 MC 版本分三段，判错的代价各不相同：
 *   低于 1.17   → 必须钉死 Krypton Wrapper。MobileGlues 的 minMCver 是 1.17，
 *                 `checkRenderer` 每次启动弹一个不可取消的对话框，点取消直接中止启动；
 *   26.3 及以上 → 必须换成 MobileGL，且**刻意覆盖用户手选**。留在 MobileGlues 上是
 *                 必然黑屏（26.3 的 OpenGL 路径改用 ShaderC 编译 shader），不是偏好问题；
 *   1.17 ~ 26.2 → 只做纠正。用户刻意选的 Zink / Virgl / GL4ES 一律原样返回，
 *                 被悄悄改掉的话他无从察觉。
 * 既有"必须钉死"的支，也有"绝不能多管"的支，所以三段都逐个钉，边界值单独钉。
 */
class InstanceAutoFixTest {

    /** 用户手选的"非默认"渲染器，代表一切不该被自动改动的选择 */
    private val userPick = Renderer.ID_ZINK

    @Test
    fun `低于 1_17 一律 Krypton Wrapper`() {
        for (v in listOf("1.7.10", "1.12.2", "1.16", "1.16.5")) {
            assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, Renderer.ID_MOBILEGLUES))
            assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, userPick))
            assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, Renderer.ID_NGGL4ES))
        }
    }

    /** 1.17 及以上：被留成 Krypton Wrapper 的要纠正回 MobileGlues */
    @Test
    fun `1_17 及以上纠正掉 Krypton Wrapper`() {
        for (v in listOf("1.17", "1.17.1", "1.20.1", "1.21.4", "26.2")) {
            assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, Renderer.ID_NGGL4ES))
        }
    }

    /**
     * 1.17 及以上：用户手选的其它渲染器原样返回。
     * 全局默认本来就是 MobileGlues，所以"什么都不做"就已经是需求要的结果；
     * 反过来每次启动强行写回，等于剥夺用户的选择权。
     */
    @Test
    fun `1_17 及以上保留用户手选的渲染器`() {
        for (v in listOf("1.17", "1.20.1", "26.2")) {
            assertEquals(userPick, InstanceAutoFix.rendererFor(v, userPick))
            assertEquals(Renderer.ID_GL4ES, InstanceAutoFix.rendererFor(v, Renderer.ID_GL4ES))
            assertEquals(Renderer.ID_VIRGL, InstanceAutoFix.rendererFor(v, Renderer.ID_VIRGL))
            // MobileGlues 本身就是默认值，再赋一次等于没改
            assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, Renderer.ID_MOBILEGLUES))
        }
    }

    /**
     * 边界本身：1.17 是 MobileGlues 的 minMCver，含在 MobileGlues 一侧。
     * 与 `LauncherHelper.checkRenderer` 的 `compare(version, minMCver) < 0` 对齐。
     */
    @Test
    fun `1_17 恰好是分界点`() {
        assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor("1.16.5", Renderer.ID_MOBILEGLUES))
        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("1.17", Renderer.ID_NGGL4ES))
    }

    /**
     * 金丝雀：证明 GameVersionNumber 真的能在单测环境里初始化。
     *
     * 它读 `/assets/game/versions.txt`，读不到时静态初始化抛 ExceptionInInitializerError；
     * 而 [InstanceAutoFix.maySelectVulkan] 外面包着 runCatching，会把那个异常吞掉、
     * 悄悄跑到正则回落分支上去——上面那些断言照样是绿的，却什么都没验到。
     * 所以这条故意不包 try：资源一旦没挂上就立刻红，不会再次误导。
     */
    @Test
    fun `版本解析器真的可用`() {
        assertTrue(GameVersionNumber.compare("1.17", "1.16.5") > 0)
        assertTrue(GameVersionNumber.compare("1.16.5", "1.17") < 0)
        assertTrue(GameVersionNumber.compare("26.2", "1.17") > 0)
    }

    // ---- 26.3 起换成 MobileGL ----

    /**
     * 26.3 是分界点：26.2 仍归 MobileGlues，26.3 起才是 MobileGL。
     * 判错的代价不对称——
     *   判早 → 26.2 实例被换到一个没必要换的渲染器；
     *   判晚 → 26.3 实例留在 MobileGlues 上，过 Mojang logo 后黑屏。
     */
    @Test
    fun `26_3 恰好是换 MobileGL 的分界点`() {
        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("26.2", Renderer.ID_MOBILEGLUES))
        assertEquals(Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor("26.3", Renderer.ID_MOBILEGLUES))
    }

    /**
     * 26.3 及以上一律换成 MobileGL —— **包括用户手选了别的渲染器的情况**。
     * 这一支和其他支不同：它刻意无视用户选择，因为留在 MobileGlues 上是必然黑屏，
     * 不是偏好问题。所以逐个组合钉死，防止将来有人"顺手"把 userPick 的保留逻辑套上来。
     */
    @Test
    fun `26_3 及以上一律换成 MobileGL`() {
        val versions = listOf("26.3", "26.3.1", "26.4", "27.0")
        val currents = listOf(
            Renderer.ID_MOBILEGLUES, Renderer.ID_NGGL4ES, Renderer.ID_MOBILEGL,
            userPick, Renderer.ID_GL4ES, Renderer.ID_VIRGL
        )
        for (v in versions) {
            for (cur in currents) {
                assertEquals("MC=$v 当前=$cur", Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor(v, cur))
            }
        }
    }

    /**
     * 26.3 的**快照**不算 ≥ 26.3 —— `ReleaseType` 的序数是
     * `UNKNOWN < SNAPSHOT < PRE_RELEASE < RC < GA`，所以 `26.3-snapshot-3` < `26.3`。
     *
     * 这是刻意的取舍，不是漏判：`minMCver` 同时是 `checkRenderer` 的越界判据**和
     * `RendererSelectItemAdapter` 里显示给用户的 `>=26.3`**。要连快照一起收进来，就得把
     * 那个值写成 `26.3-snapshot-1`，用户会在渲染器列表里读到一串他自己都没见过的版本号；
     * 而究竟是哪个快照开始黑屏，并没有查实（26.3 的快照 2 加 OIT、快照 4 换 SDL3、
     * 快照 5 才把 shader 编译切到 ShaderC），凭猜选定一个边界比划一条粗但明确的线更糟。
     *
     * 代价是 26.3 快照的用户会卡在两头：MobileGlues 黑屏，手选 MobileGL 虽能绕过这里，
     * 但 `checkRenderer` 每次启动都会弹一次提示，得点「继续」。这条已写进
     * README「已知限制」，要改先改那里。
     */
    @Test
    fun `26_3 的快照排在正式版之前`() {
        // 断言这个前提本身，而不是只断言结果——哪天序数变了，这里要红
        assertTrue(GameVersionNumber.compare("26.3-snapshot-3", "26.3") < 0)
        assertTrue(GameVersionNumber.compare("26.3-rc1", "26.3") < 0)

        // 因此 26.3 快照落在 1.17~26.2 那一支：只纠正 Krypton Wrapper，其余原样
        for (v in listOf("26.3-snapshot-3", "26.3-rc1")) {
            assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, Renderer.ID_MOBILEGLUES))
            assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, Renderer.ID_NGGL4ES))
        }
    }

    /**
     * 32 位包（armeabi-v7a）里没有 libMobileGL.so，选中它会在启动时加载失败。
     * 这种情况 26.3 只能留在 MobileGlues —— 没有更好的选择，但绝不能指向一个
     * 不存在的渲染器。判据由 [com.mio.manager.RendererManager.canUseMobileGL] 提供
     * （它查的是本包里有没有那个 .so，不是设备 ABI）。
     */
    @Test
    fun `没有 MobileGL 的 ABI 上不会选中它`() {
        // 已经选着 MobileGlues 的：不动
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("26.3", Renderer.ID_MOBILEGLUES, mobileGLUsable = false)
        )
        // 被留成 Krypton Wrapper 的：照旧纠正成 MobileGlues
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("26.3", Renderer.ID_NGGL4ES, mobileGLUsable = false)
        )
        // 低于 1.17 那一支不受 ABI 影响
        assertEquals(
            Renderer.ID_NGGL4ES,
            InstanceAutoFix.rendererFor("1.12.2", Renderer.ID_MOBILEGLUES, mobileGLUsable = false)
        )
        // 1.17~26.2 那一支也不受影响
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("1.20.1", Renderer.ID_NGGL4ES, mobileGLUsable = false)
        )
    }

    // ---- MC 26.2 的图形后端判定（`LauncherHelper` 据此把 options.txt 的
    //      preferredGraphicsBackend 钉成 opengl）----

    @Test
    fun `26_2 之前的版本不改用户的图形后端设置`() {
        for (v in listOf("1.7.10", "1.12.2", "1.16.5", "1.21.4", "26.1")) {
            assertFalse(InstanceAutoFix.maySelectVulkan(v))
        }
    }

    @Test
    fun `26_2 起的正式版与快照都要写`() {
        assertTrue(InstanceAutoFix.maySelectVulkan("26.2"))
        assertTrue(InstanceAutoFix.maySelectVulkan("26.3"))
        // 26.2 的首个快照
        assertTrue(InstanceAutoFix.maySelectVulkan("26w14a"))
    }

    /**
     * 26.2 前后最常见的实际形态其实是带后缀的开发版。
     * 这几个若被判成 false，对应实例就漏掉了强制 OpenGL——代价不对称，所以逐个钉。
     */
    @Test
    fun `带后缀的开发版不能漏掉`() {
        for (v in listOf("26.2-snapshot-1", "26.2-pre1", "26.2-rc1", "26.3-snapshot-3")) {
            assertTrue(InstanceAutoFix.maySelectVulkan(v))
        }
    }
}
