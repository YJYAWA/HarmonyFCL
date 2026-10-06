package com.mio.autofix

import com.tungsten.fclcore.game.JavaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `-XX:UseSVE=0` 该不该从实例的 `javaArgs` 里删掉。
 *
 * 这个判据一开始是错的，症状是**远古版本（1.16 及更早）点启动直接退出**，
 * 报 `Unrecognized VM option 'UseSVE'`。
 *
 * 错的根因很隐蔽，所以这里把它钉死：原实现走
 * `JavaManager.getSuitableJavaVersion(version)`，而那个函数在目标 Java **没装**时
 * 会回落到「已装的第一个 Java」。远古版本的 `version.json` 里没有 `javaVersion` 字段，
 * 于是：
 *
 *   `getSuitableJavaVersion(null)` → `getJavaFromVersionName("jre8")`
 *   → jre8 没装时返回 `javaList.first()`（例如 jre17）→ 报出主版本 17
 *   → 判成"支持 UseSVE" → 参数被留下 → 实际用 Java 8 启动就崩。
 *
 * 也就是说：**判据不能依赖"jre8 装没装"这种与版本本身无关的外部状态。**
 * 正确语义是「这个版本要求的 Java」，而它只由 `version.json` 的 `javaVersion` 决定。
 *
 * 这里全部是纯函数测试，不碰 Android 环境。
 */
class EffectiveJavaMajorTest {

    // ---------- 自动选择（explicit = "Auto"）----------

    /**
     * 最关键的一条：**远古版本没有 javaVersion 字段（declared = null），必须按 Java 8 算。**
     *
     * 代表版本：1.0 / 1.2.5 / 1.6.4 / 1.7.10 / 1.12.2 / 1.16.5 —— 它们的
     * `version.json` 都没有 `javaVersion`，在 `Version.getJavaVersion()` 里都是 `null`。
     *
     * 这个用例如果哪天变红，说明又有人把判据接回 `JavaManager` 的"已装列表"上了，
     * 远古版本会重新变成启动不了。
     */
    @Test
    fun `没有 javaVersion 字段的远古版本按 Java8 算`() {
        assertEquals(
            "declared=null（1.16 及更早）必须按 Java 8 判，否则 UseSVE 参数会被留下",
            JavaVersion.JAVA_VERSION_8,
            InstanceAutoFix.effectiveJavaMajor(null, "Auto")
        )
    }

    /** 1.17+ 的 version.json 声明了 javaVersion，按声明值算 */
    @Test
    fun `声明的 javaVersion 被如实采用`() {
        assertEquals(16, InstanceAutoFix.effectiveJavaMajor(16, "Auto"))
        assertEquals(17, InstanceAutoFix.effectiveJavaMajor(17, "Auto"))
        assertEquals(21, InstanceAutoFix.effectiveJavaMajor(21, "Auto"))
        assertEquals(25, InstanceAutoFix.effectiveJavaMajor(25, "Auto"))
    }

    /** 畸形声明（0 / 负数）当未知处理，而不是当成"很老的 Java"或"很新的 Java" */
    @Test
    fun `畸形的 javaVersion 声明按未知处理`() {
        assertEquals(-1, InstanceAutoFix.effectiveJavaMajor(0, "Auto"))
        assertEquals(-1, InstanceAutoFix.effectiveJavaMajor(-5, "Auto"))
    }

    // ---------- 显式指定（explicit != "Auto"）----------

    /**
     * 显式选了 Java 时，**版本声明要被盖掉**。
     *
     * 这是本次修复里唯一一处"行为变了"的地方，是刻意的：原来显式选 jre8 时，
     * 如果 jre8 恰好没装，`javaMajorOf` 会回落到已装的 jre17 并**不删**参数；
     * 用户之后一装 jre8，实例反而启动不了。现在按"用户选了 jre8"直接算 8，
     * 参数该删就删，与装没装无关。
     */
    @Test
    fun `显式选 jre8 时即使版本声明 Java17 也按 8 算`() {
        assertEquals(8, InstanceAutoFix.effectiveJavaMajor(17, "jre8"))
        assertEquals(8, InstanceAutoFix.effectiveJavaMajor(null, "jre8"))
    }

    /** 四个内置 JRE 名字都要认 */
    @Test
    fun `四个内置 JRE 名字都认`() {
        assertEquals(8, InstanceAutoFix.effectiveJavaMajor(null, "jre8"))
        assertEquals(17, InstanceAutoFix.effectiveJavaMajor(null, "jre17"))
        assertEquals(21, InstanceAutoFix.effectiveJavaMajor(null, "jre21"))
        assertEquals(25, InstanceAutoFix.effectiveJavaMajor(null, "jre25"))
    }

    /**
     * 认不出来的名字 → 未知 → **不动用户的参数**。
     *
     * 刻意**不**去查 `JavaManager.javaList`：那样"用户选的 Java 没装"就会变成未知，
     * 而未知在 SVE 那一支是"要删"；用户自己填了个本地 JDK 的名字时不该被我们动参数。
     */
    @Test
    fun `认不出的 Java 名字算未知`() {
        for (name in listOf("jdk-21-local", "jre11", "", "auto", "AUTO")) {
            assertEquals(
                "名字 '$name' 应当算未知（-1），而不是被猜成某个版本",
                -1,
                InstanceAutoFix.effectiveJavaMajor(null, name)
            )
        }
    }

    // ---------- 与 MIN_JAVA_FOR_SVE 的关系 ----------

    private fun needsRemoval(declared: Int?, explicit: String): Boolean =
        InstanceAutoFix.effectiveJavaMajor(declared, explicit) < 17

    /**
     * 判错的代价不对称，所以未知必须落到"要删"这一侧。
     * `UNKNOWN_JAVA_MAJOR` 取 `-1` 就是为了让这里成立；改成正值会让策略反过来。
     */
    @Test
    fun `未知一律要删参数`() {
        assertTrue(needsRemoval(null, "Auto"))       // 远古版本
        assertTrue(needsRemoval(0, "Auto"))          // 畸形声明
        assertTrue(needsRemoval(null, "某个自定义名字")) // 认不出的名字
    }

    /** 只有 Java 8 要删；17/21/25 都留着（它们认识 UseSVE） */
    @Test
    fun `只有低于 17 才删`() {
        assertTrue(needsRemoval(null, "jre8"))
        assertFalse(needsRemoval(17, "Auto"))
        assertFalse(needsRemoval(21, "Auto"))
        assertFalse(needsRemoval(25, "Auto"))
        assertFalse(needsRemoval(null, "jre17"))
        assertFalse(needsRemoval(null, "jre25"))
    }

    /** 16 是边界值：低于 17 就要删（Java 16 也不认识 UseSVE） */
    @Test
    fun `Java16 也要删`() {
        assertTrue(needsRemoval(16, "Auto"))
    }
}
