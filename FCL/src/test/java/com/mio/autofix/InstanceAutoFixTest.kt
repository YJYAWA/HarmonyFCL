package com.mio.autofix

import com.mio.data.Renderer
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器这一支两侧不对称：低于 1.17 必须钉死，1.17 及以上只做纠正。
 * 判错的代价也不对称——
 *   漏掉低版本 → MobileGlues 的 minMCver 是 1.17，`checkRenderer` 每次启动弹一个
 *                不可取消的对话框，点取消直接中止启动；
 *   误改高版本 → 用户刻意选的 Zink / Virgl / GL4ES 被悄悄改掉，且他无从察觉。
 * 所以两支都逐个钉死。
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
     * 而 `PreferOpenGLFetcher.maySelectVulkan` 外面包着 runCatching，会把那个异常吞掉、
     * 悄悄跑到正则回落分支上去——上面那些断言照样是绿的，却什么都没验到。
     * 所以这条故意不包 try：资源一旦没挂上就立刻红，不会再次误导。
     */
    @Test
    fun `版本解析器真的可用`() {
        assertTrue(GameVersionNumber.compare("1.17", "1.16.5") > 0)
        assertTrue(GameVersionNumber.compare("1.16.5", "1.17") < 0)
        assertTrue(GameVersionNumber.compare("26.2", "1.17") > 0)
    }
}
