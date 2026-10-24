package com.mio.autofix

import com.mio.data.Renderer
import com.tungsten.fclcore.util.versioning.GameVersionNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器三档强制映射（1.3.4.0 起）：
 *
 * | MC 版本 | 渲染器 |
 * | --- | --- |
 * | 1.17.1 及更早（Pre-Classic / Classic / Indev / Infdev / Alpha / Beta / 1.0–1.17.1） | **Krypton Wrapper** |
 * | 1.18 ~ 26.2（含） | **MobileGlues** |
 * | 26.3 及以上 | **MobileGL** |
 *
 * 三档都**覆盖用户手选**。判错的代价不对称：
 *   - 远古版本留 MobileGlues → `checkRenderer` 每次启动弹不可取消对话框，点取消中止启动；
 *   - 26.3+ 留 MobileGlues → 过 Mojang logo 后必然黑屏（OpenGL 路径改 ShaderC）。
 *
 * ⚠️ 还有一个更隐蔽的失败方式必须钉住：**版本名认不出来**。
 * `GameVersionNumber` 对 `0.0.14a` / `Beta 1.7.3` / `Cave game tech test` / `Java版2.0`
 * 这类名字既不抛异常也不报错：它造一个 `Special` 且 `prev == null`，这种
 * `Special.isUnknown()` 在比较时**直接返回 1**（"比谁都新"），于是被判成 ≥ 26.3 →
 * 选到 MobileGL。所以判定顺序必须是「先按形态自己认，认不出按远古兜底」。
 */
class InstanceAutoFixTest {

    /** 用户手选的"非默认"渲染器，代表一切可能出现的既有取值 */
    private val userPick = Renderer.ID_ZINK

    /**
     * 远古版本全量清单（用户指定）。分两个变体：
     *  - 官方发布名（FCL 实例 id 实际长这样：`rd-132211`、`c0.30_01c`、`in-20100124-2310` …）
     *  - 中文资料里的**展示名**（`Beta 1.7.3`、`0.0.14a`、`Cave game tech test` …），
     *    用户自己改名或从别处导入时就会是这些，解析器**认不出**，必须靠兜底也不炸。
     */
    private val ancientVersions: List<String> = listOf(
        // ---- Pre-Classic & Classic（2009）----
        "rd-132211", "rd-132328", "Cave game tech test", "rd-20090515",
        "rd-160052", "rd-161348",
        "mc-161607", "mc-161616", "mc-161625", "mc-161648",
        "c0.0.1a", "c0.0.2a", "c0.0.3a", "c0.0.9a", "c0.0.10a", "c0.0.11a",
        "c0.0.12a", "c0.0.12a_01", "c0.0.12a_02", "c0.0.12a_03",
        "c0.0.13a", "c0.0.13a_03",
        "c0.0.14a", "c0.0.14a_01", "c0.0.14a_02", "c0.0.14a_03",
        "c0.0.14a_04", "c0.0.14a_05", "c0.0.14a_06", "c0.0.14a_07", "c0.0.14a_08",
        "c0.0.15a", "c0.0.16a", "c0.0.16a_01", "c0.0.16a_02",
        "c0.0.17a", "c0.0.18a", "c0.0.18a_01", "c0.0.18a_02",
        "c0.0.19a", "c0.0.19a_01", "c0.0.19a_02", "c0.0.19a_03",
        "c0.0.19a_04", "c0.0.19a_05", "c0.0.19a_06",
        "c0.0.20a", "c0.0.20a_01", "c0.0.21a",
        "c0.0.22a", "c0.0.22a_01", "c0.0.22a_02", "c0.0.22a_03",
        "c0.0.22a_04", "c0.0.22a_05",
        "c0.0.23a", "c0.0.23a_01",
        "c0.24_SURVIVAL_TEST", "c0.24_SURVIVAL_TEST_01", "c0.24_SURVIVAL_TEST_02",
        "c0.24_SURVIVAL_TEST_03",
        "c0.25_SURVIVAL_TEST", "c0.25_SURVIVAL_TEST_01", "c0.25_SURVIVAL_TEST_02",
        "c0.25_SURVIVAL_TEST_03", "c0.25_SURVIVAL_TEST_04", "c0.25_SURVIVAL_TEST_05",
        "c0.26_SURVIVAL_TEST", "c0.27_SURVIVAL_TEST",
        "c0.28", "c0.28_01", "c0.29", "c0.29_01", "c0.29_02",
        "c0.30", "c0.30_01c",
        // 展示名（解析器认不出，靠兜底）
        "0.0.1a", "0.0.14a", "0.0.30", "0.24_SURVIVAL_TEST", "0.30_01c",

        // ---- Indev（2009.12 – 2010.02）----
        "in-20091223-1", "in-20091223-2", "in-20091231-1", "in-20091231-2",
        "in-20100104", "in-20100106", "in-20100107", "in-20100109", "in-20100110",
        "in-20100111", "in-20100112", "in-20100113", "in-20100114", "in-20100115",
        "in-20100116", "in-20100117", "in-20100118", "in-20100119", "in-20100120",
        "in-20100121", "in-20100122", "in-20100123", "in-20100124", "in-20100125",
        "in-20100126", "in-20100127", "in-20100128", "in-20100129", "in-20100130",
        "in-20100131", "in-20100201-1", "in-20100201-2", "in-20100201-3",
        "in-20100203", "in-20100204", "in-20100205", "in-20100206", "in-20100207-1",
        "in-20100207-2", "in-20100212-1", "in-20100212-2", "in-20100214-1",
        "in-20100214-2", "in-20100218", "in-20100219", "in-20100223",
        "0.31（20091223-1）", "0.31（20100223）",

        // ---- Infdev（2010.02 – 2010.06）----
        "inf-20100227-1", "inf-20100227-2", "inf-20100313", "inf-20100316",
        "inf-20100320", "inf-20100321", "inf-20100325", "inf-20100327",
        "inf-20100330", "inf-20100413", "inf-20100415", "inf-20100420",
        "inf-20100607", "inf-20100608", "inf-20100611", "inf-20100616",
        "inf-20100617-1", "inf-20100617-2", "inf-20100618", "inf-20100624",
        "inf-20100625-1", "inf-20100625-2", "inf-20100627", "inf-20100629",
        "inf-20100630",
        "20100227-1", "20100630",

        // ---- Alpha（2010.06 – 2010.12）----
        "a1.0.0", "a1.0.1", "a1.0.2", "a1.0.4", "a1.0.5", "a1.0.6", "a1.0.7",
        "a1.0.8", "a1.0.9", "a1.0.10", "a1.0.11", "a1.0.12", "a1.0.13", "a1.0.14",
        "a1.0.15", "a1.0.16", "a1.0.17", "a1.1.0", "a1.1.2", "a1.1.2_01",
        "a1.2.0", "a1.2.1", "a1.2.2", "a1.2.3", "a1.2.4", "a1.2.5", "a1.2.6",
        "Alpha 1.0.0", "Alpha 1.2.6", "1.4",

        // ---- Beta（2010.12 – 2011.11）----
        "b1.0", "b1.0_01", "b1.0.2", "b1.1", "b1.1_01", "b1.1_02",
        "b1.2", "b1.2_01", "b1.2_02", "b1.3", "b1.3_01", "b1.4", "b1.4_01",
        "b1.5", "b1.5_01", "b1.5_02", "b1.6", "b1.6.1", "b1.6.2", "b1.6.3",
        "b1.6.4", "b1.6.5", "b1.6.6", "b1.7", "b1.7_01", "b1.7.2", "b1.7.3",
        "b1.8", "b1.8.1",
        "Beta 1.0", "Beta 1.7.3", "Beta 1.8.1",

        // ---- 正式版 1.0 – 1.17.1 ----
        "1.0", "1.0.0", "1.0.1", "1.1", "1.2.1", "1.2.2", "1.2.3", "1.2.4", "1.2.5",
        "1.3.1", "1.3.2", "1.4.2", "1.4.4", "1.4.5", "1.4.6", "1.4.7",
        "1.5", "1.5.1", "1.5.2", "1.6.1", "1.6.2", "1.6.4",
        "1.7.2", "1.7.10",
        "1.8", "1.8.1", "1.8.2", "1.8.3", "1.8.4", "1.8.5", "1.8.6", "1.8.7", "1.8.8", "1.8.9",
        "1.9", "1.9.1", "1.9.2", "1.9.3", "1.9.4",
        "1.10", "1.10.1", "1.10.2",
        "1.11", "1.11.1", "1.11.2",
        "1.12", "1.12.1", "1.12.2",
        "1.13", "1.13.1", "1.13.2",
        "1.14", "1.14.1", "1.14.2", "1.14.3", "1.14.4",
        "1.15", "1.15.1", "1.15.2",
        "1.16", "1.16.1", "1.16.2", "1.16.3", "1.16.4", "1.16.5",
        "1.17", "1.17.1",
        // 老快照 / 预发布 / 愚人节
        "13w01a", "13w02a", "13w12~", "14w02a", "14w34a",
        "15w14a", "16w32a", "16w44a", "17w18b", "18w22c",
        "1.16-pre1", "1.16-pre8", "1.15-pre1", "1.14.4-pre7",
        "1.0.0-rc1", "1.RV-Pre1", "3D Shareware v1.34", "Java版2.0", "20w14∞"
    )

    /**
     * 最关键的一条：上面**每一个**远古版本都必须判成 Krypton Wrapper，且**绝不抛异常**。
     *
     * 抛异常 = `applyAll` 静默跳过该实例 = 用户看到"远古版本没自动切渲染器"。
     * 这条把"不抛"和"判对"一起钉住。
     */
    @Test
    fun `远古版本全量清单都必须判成 Krypton Wrapper 且不抛异常`() {
        val failures = mutableListOf<String>()
        for (v in ancientVersions) {
            for (cur in listOf(Renderer.ID_MOBILEGLUES, Renderer.ID_MOBILEGL, userPick)) {
                try {
                    val got = InstanceAutoFix.rendererFor(v, cur)
                    if (got != Renderer.ID_NGGL4ES) failures += "$v / 当前=$cur -> $got"
                } catch (t: Throwable) {
                    failures += "$v / 当前=$cur -> 抛异常 ${t::class.java.simpleName}: ${t.message}"
                }
            }
        }
        assertEquals(
            "以下远古版本没有判成 Krypton Wrapper（会被 applyAll 静默跳过）：\n" +
                failures.joinToString("\n"),
            0, failures.size
        )
    }

    /** 1.0 – 1.17.1 全部 Krypton Wrapper（含用户手选过的） */
    @Test
    fun `1_0 到 1_17_1 一律 Krypton Wrapper`() {
        val versions = listOf(
            "1.0", "1.0.0", "1.1", "1.2.5", "1.4.7", "1.7.10", "1.8.9",
            "1.9.4", "1.10.2", "1.11.2", "1.12.2", "1.13.2", "1.14.4",
            "1.15.2", "1.16.5", "1.17", "1.17.1"
        )
        for (v in versions) {
            for (cur in listOf(Renderer.ID_MOBILEGLUES, Renderer.ID_MOBILEGL, userPick, Renderer.ID_NGGL4ES)) {
                assertEquals("MC=$v 当前=$cur", Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, cur))
            }
        }
    }

    /** 1.18 ~ 26.2 一律 MobileGlues，**覆盖用户手选** */
    @Test
    fun `1_18 到 26_2 一律 MobileGlues`() {
        val versions = listOf(
            "1.18", "1.18.1", "1.18.2", "1.19", "1.19.4",
            "1.20", "1.20.1", "1.20.4", "1.20.5", "1.20.6",
            "1.21", "1.21.1", "1.21.4", "1.21.10",
            "26.1", "26.2"
        )
        for (v in versions) {
            for (cur in listOf(Renderer.ID_NGGL4ES, Renderer.ID_MOBILEGL, userPick)) {
                assertEquals("MC=$v 当前=$cur", Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, cur))
            }
        }
    }

    /** 26.3 及以上一律 MobileGL，**覆盖用户手选** */
    @Test
    fun `26_3 及以上一律 MobileGL`() {
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
     * 两个分界点都要精确：
     *   1.17.1 → Krypton Wrapper，1.18 → MobileGlues
     *   26.2   → MobileGlues，  26.3 → MobileGL
     */
    @Test
    fun `两个分界点`() {
        assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor("1.17.1", Renderer.ID_MOBILEGLUES))
        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("1.18", Renderer.ID_NGGL4ES))

        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("26.2", Renderer.ID_MOBILEGLUES))
        assertEquals(Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor("26.3", Renderer.ID_MOBILEGLUES))
    }

    /**
     * 按周命名的快照要跟**它所属的正式版**同档，不能只看年份。
     *
     * 最容易错的是这一批：
     *   20w14a / 20w14∞ 是 **1.16** 的快照（愚人节版）→ Krypton；
     *   21w06a 是 **1.17** 的快照 → Krypton；
     *   21w37a 起才是 **1.18** 的快照 → MobileGlues。
     * 只按 "year >= 18" 一刀切会把前两批全判成 MobileGlues。
     */
    @Test
    fun `按周命名的快照跟着所属正式版走`() {
        val krypton = listOf("13w01a", "14w02a", "16w32a", "17w18b", "18w22c",
            "19w03a", "20w14a", "20w14∞", "20w45a", "21w03a", "21w06a", "21w20a")
        val glues = listOf("21w37a", "21w44a", "22w03a", "23w31a", "24w14a", "25w14craftmine", "26w14a")
        val mobileGL = listOf("27w01a", "28w03a")

        for (v in krypton) assertEquals("MC=$v", Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, userPick))
        for (v in glues) assertEquals("MC=$v", Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, userPick))
        for (v in mobileGL) assertEquals("MC=$v", Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor(v, userPick))
    }

    /**
     * 正式版的快照 / pre / rc 排在正式版**之前**，所以压在档位起点的那一版要退回上一档：
     *   1.18-pre1  < 1.18 → Krypton（MobileGlues 的 minMCver 正是 1.18，留着会弹窗）
     *   26.3 的快照 < 26.3 → MobileGlues
     *   27.0 的快照 < 27.0 → MobileGlues
     * 26.4 / 27.1 的快照在档内，不退回。
     */
    @Test
    fun `压在档位起点的开发版要退回上一档`() {
        assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor("1.18-pre1", userPick))
        assertEquals(Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor("1.18-rc1", userPick))
        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("1.18.1-pre1", userPick))

        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("26.3-pre-1", userPick))
        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("26.3-rc-1", userPick))
        assertEquals(Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor("26.4-snapshot-1", userPick))

        assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor("27.0-snapshot-1", userPick))
        assertEquals(Renderer.ID_MOBILEGL, InstanceAutoFix.rendererFor("27.1-snapshot-1", userPick))
    }

    /**
     * 完全认不出的名字（自造实例 id、愚人节版、资料里的中文展示名）必须落到
     * **Krypton Wrapper**，不能落到 MobileGL —— 这是 1.3.4.0 修掉的那个坑。
     */
    @Test
    fun `认不出的名字一律按远古兜底`() {
        for (v in listOf("Java版2.0", "3D Shareware v1.34", "1.RV-Pre1", "我的整合包",
            "Cave game tech test", "20100227-1", "20100630", "", "   ")) {
            assertEquals("MC=$v", Renderer.ID_NGGL4ES, InstanceAutoFix.rendererFor(v, userPick))
        }
    }

    /**
     * 26.3 的**快照**不算 ≥ 26.3 —— `ReleaseType` 的序数是
     * `UNKNOWN < SNAPSHOT < PRE_RELEASE < RC < GA`，所以 `26.3-snapshot-3` < `26.3`，
     * 落在 1.18~26.2 那档（MobileGlues）。
     *
     * 这是刻意的取舍，不是漏判：`minMCver` 同时是 `checkRenderer` 的越界判据**和**
     * `RendererSelectItemAdapter` 里显示的 `>=26.3`。要连快照一起收进来就得写成
     * `26.3-snapshot-1`，用户会在渲染器列表里读到一串没见过的版本号。已写进 README「已知限制」。
     */
    @Test
    fun `26_3 的快照排在正式版之前`() {
        assertTrue(GameVersionNumber.compare("26.3-snapshot-3", "26.3") < 0)
        assertTrue(GameVersionNumber.compare("26.3-rc1", "26.3") < 0)
        for (v in listOf("26.3-snapshot-3", "26.3-rc1")) {
            assertEquals(Renderer.ID_MOBILEGLUES, InstanceAutoFix.rendererFor(v, userPick))
        }
    }

    /**
     * 32 位包（armeabi-v7a）里没有 libMobileGL.so，选中它会在启动时加载失败。
     * 这种情况 26.3 回落 MobileGlues —— 没有更好的选择，但绝不能指向不存在的渲染器。
     */
    @Test
    fun `没有 MobileGL 的 ABI 上不会选中它`() {
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("26.3", Renderer.ID_MOBILEGLUES, mobileGLUsable = false)
        )
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("26.3", Renderer.ID_NGGL4ES, mobileGLUsable = false)
        )
        // 远古版本与 1.18~26.2 都不受 ABI 影响
        assertEquals(
            Renderer.ID_NGGL4ES,
            InstanceAutoFix.rendererFor("1.12.2", Renderer.ID_MOBILEGLUES, mobileGLUsable = false)
        )
        assertEquals(
            Renderer.ID_MOBILEGLUES,
            InstanceAutoFix.rendererFor("1.20.1", Renderer.ID_NGGL4ES, mobileGLUsable = false)
        )
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
        assertTrue(GameVersionNumber.compare("1.18", "1.17.1") > 0)
        assertTrue(GameVersionNumber.compare("1.17.1", "1.18") < 0)
        assertTrue(GameVersionNumber.compare("26.2", "1.18") > 0)
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
        assertTrue(InstanceAutoFix.maySelectVulkan("26w14a"))
    }

    @Test
    fun `带后缀的开发版不能漏掉`() {
        for (v in listOf("26.2-snapshot-1", "26.2-pre1", "26.2-rc1", "26.3-snapshot-3")) {
            assertTrue(InstanceAutoFix.maySelectVulkan(v))
        }
    }
}
