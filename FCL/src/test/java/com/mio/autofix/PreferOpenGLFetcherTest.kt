package com.mio.autofix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26.2 这条门槛决定"要不要把渲染后端钉死在 OpenGL"。
 * 判错了的后果不对称：判漏 → 麒麟上游戏选到 Vulkan 直接崩；判多了 → 只是白写一个键。
 * 所以这里把各个形态的版本号都钉一遍。
 */
class PreferOpenGLFetcherTest {

    @Test
    fun `26_2 之前的版本不需要处理`() {
        assertFalse(PreferOpenGLFetcher.maySelectVulkan("1.7.10"))
        assertFalse(PreferOpenGLFetcher.maySelectVulkan("1.12.2"))
        assertFalse(PreferOpenGLFetcher.maySelectVulkan("1.16.5"))
        assertFalse(PreferOpenGLFetcher.maySelectVulkan("1.21.4"))
        assertFalse(PreferOpenGLFetcher.maySelectVulkan("26.1"))
    }

    @Test
    fun `26_2 起的正式版与快照都要处理`() {
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.2"))
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.3"))
        // 26.2 的首个快照
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26w14a"))
    }

    /**
     * 26.2 前后最常见的实际形态是带后缀的开发版。
     * 这几个如果被判成 false，对应实例就会漏掉强制 OpenGL。
     */
    @Test
    fun `带后缀的开发版不能漏掉`() {
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.2-snapshot-1"))
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.2-pre1"))
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.2-rc1"))
        assertTrue(PreferOpenGLFetcher.maySelectVulkan("26.3-snapshot-3"))
    }

    /** 加载器名单决定走模组还是走 JVM 参数，判错会让实例一个机制都拿不到 */
    @Test
    fun `加载器支持的加载器集合`() {
        assertTrue(PreferOpenGLFetcher.isSupportedLoader("fabric"))
        assertTrue(PreferOpenGLFetcher.isSupportedLoader("quilt"))
        assertTrue(PreferOpenGLFetcher.isSupportedLoader("forge"))
        // NeoForge 模组尚未支持，必须走 JVM 参数那条路
        assertFalse(PreferOpenGLFetcher.isSupportedLoader("neoforge"))
    }
}
