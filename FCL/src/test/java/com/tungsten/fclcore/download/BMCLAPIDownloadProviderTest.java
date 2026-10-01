package com.tungsten.fclcore.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.tungsten.fclcore.game.GameComponentType;

import org.junit.Test;

import java.net.URL;
import java.util.List;

public class BMCLAPIDownloadProviderTest {

    private final BMCLAPIDownloadProvider provider = new BMCLAPIDownloadProvider("https://bmclapi2.bangbang93.com");

    @Test
    public void injectURLRewritesMojangMeta() {
        assertEquals("https://bmclapi2.bangbang93.com/mc/game/version_manifest.json",
                provider.injectURL("https://piston-meta.mojang.com/mc/game/version_manifest.json"));
        assertEquals("https://bmclapi2.bangbang93.com/libraries/net/minecraftforge/forge.jar",
                provider.injectURL("https://libraries.minecraft.net/net/minecraftforge/forge.jar"));
    }

    @Test
    public void injectURLKeepsUnmatchedURL() {
        assertEquals("https://example.org/some/file.json",
                provider.injectURL("https://example.org/some/file.json"));
    }

    /**
     * 镜像必须排在官方地址前面。
     *
     * 候选是「按顺序试、失败才换下一个」，官方地址排前面的话国内每次请求都要先吃一次
     * 超时（山东等地直接 RST）—— 用户看到的就是「搜得出来，点进详情转圈，版本列表空白」。
     * 详情、版本列表、分类都走这条链，顺序错了这个修复就等于没做。
     */
    @Test
    public void modrinthCandidatesPreferMirrorThenOfficial() {
        List<URL> candidates = provider.injectURLWithCandidates("https://api.modrinth.com/v2/project/sodium/version");

        assertEquals(2, candidates.size());
        assertEquals("https://mod.mcimirror.top/modrinth/v2/project/sodium/version", candidates.get(0).toString());
        assertEquals("https://api.modrinth.com/v2/project/sodium/version", candidates.get(1).toString());
    }

    @Test
    public void curseForgeCandidatesPreferMirrorThenOfficial() {
        List<URL> candidates = provider.injectURLWithCandidates("https://api.curseforge.com/v1/mods/238222/files");

        assertEquals(2, candidates.size());
        assertEquals("https://mod.mcimirror.top/curseforge/v1/mods/238222/files", candidates.get(0).toString());
        assertEquals("https://api.curseforge.com/v1/mods/238222/files", candidates.get(1).toString());
    }

    /**
     * CDN 与 API 相反，**原站优先**。
     *
     * 镜像对文件请求只是 302 回原站，落点与原站那条 302 完全相同
     * （实测 `mod.mcimirror.top/files/9019/497/x.jar` 与 `edge.forgecdn.net/files/9019/497/x.jar`
     * 都定位到 `mediafilez.forgecdn.net/files/9019/497/x.jar`），所以把它排前面
     * 等于每次下载都白绕一跳。镜像留在候选里只作兜底，不是主路径。
     */
    @Test
    public void cdnCandidatesPreferOriginBecauseMirrorOnlyRedirects() {
        List<URL> candidates = provider.injectURLWithCandidates("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar");

        assertEquals(2, candidates.size());
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar", candidates.get(0).toString());
        assertEquals("https://mod.mcimirror.top/data/AANobbMI/versions/x/sodium.jar", candidates.get(1).toString());
    }

    @Test
    public void forgeCdnCandidatesAlsoPreferOrigin() {
        List<URL> candidates = provider.injectURLWithCandidates("https://edge.forgecdn.net/files/9019/497/jei.jar");

        assertEquals(2, candidates.size());
        assertEquals("https://edge.forgecdn.net/files/9019/497/jei.jar", candidates.get(0).toString());
        assertEquals("https://mod.mcimirror.top/files/9019/497/jei.jar", candidates.get(1).toString());
    }

    @Test
    public void rewrittenURLHasSingleCandidate() {
        List<URL> candidates = provider.injectURLWithCandidates("https://meta.fabricmc.net/v2/versions/loader");

        assertEquals(1, candidates.size());
        assertEquals("https://bmclapi2.bangbang93.com/fabric-meta/v2/versions/loader", candidates.get(0).toString());
    }

    @Test
    public void injectURLsWithCandidatesDeduplicates() {
        List<URL> candidates = provider.injectURLsWithCandidates(List.of(
                "https://api.modrinth.com/v2/search",
                "https://api.modrinth.com/v2/search"));

        assertEquals(2, candidates.size());
    }

    @Test
    public void fmllibsMirrorPairConfigured() {
        assertEquals("https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/fmllibs",
                provider.injectURL("https://hmcl.glavo.site/metadata/fmllibs"));
    }

    @Test
    public void versionListAndAssetURLsUseApiRoot() {
        assertEquals("https://bmclapi2.bangbang93.com/mc/game/version_manifest.json",
                provider.getVersionListURLs().get(0).toString());
        assertEquals("https://bmclapi2.bangbang93.com/assets/objects/ab/cdabcdef",
                provider.getAssetObjectCandidates("objects/ab/cdabcdef").get(0).toString());
    }

    @Test
    public void getVersionListCoversAllComponentTypes() {
        for (GameComponentType type : GameComponentType.ALL) {
            ComponentVersionList<?> list = provider.getVersionList(type);
            assertNotNull(list);
            assertSame(list, provider.getVersionList(type));
        }
    }

    @Test
    public void concurrencyAtLeastCpuCount() {
        assertTrue(provider.getConcurrency() >= Runtime.getRuntime().availableProcessors());
    }
}
