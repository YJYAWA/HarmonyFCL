/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.download;

import static com.tungsten.fclcore.util.Pair.pair;

import com.tungsten.fclcore.download.cleanroom.CleanroomVersionList;
import com.tungsten.fclcore.download.fabric.FabricAPIVersionList;
import com.tungsten.fclcore.download.fabric.FabricVersionList;
import com.tungsten.fclcore.download.forge.ForgeBMCLVersionList;
import com.tungsten.fclcore.download.game.GameVersionList;
import com.tungsten.fclcore.download.legacyfabric.LegacyFabricAPIVersionList;
import com.tungsten.fclcore.download.legacyfabric.LegacyFabricVersionList;
import com.tungsten.fclcore.download.liteloader.LiteLoaderBMCLVersionList;
import com.tungsten.fclcore.download.neoforge.NeoForgeBMCLVersionList;
import com.tungsten.fclcore.download.optifine.OptiFineBMCLVersionList;
import com.tungsten.fclcore.download.quilt.QuiltAPIVersionList;
import com.tungsten.fclcore.download.quilt.QuiltVersionList;
import com.tungsten.fclcore.game.GameComponentType;
import com.tungsten.fclcore.util.Pair;
import com.tungsten.fclcore.util.io.NetworkUtils;

import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BMCLAPIDownloadProvider implements DownloadProvider {
    private final String apiRoot;
    private final GameVersionList game;
    private final FabricVersionList fabric;
    private final FabricAPIVersionList fabricApi;
    private final ForgeBMCLVersionList forge;
    private final CleanroomVersionList cleanroom;
    private final LegacyFabricVersionList legacyFabric;
    private final LegacyFabricAPIVersionList legacyFabricApi;
    private final NeoForgeBMCLVersionList neoforge;
    private final LiteLoaderBMCLVersionList liteLoader;
    private final OptiFineBMCLVersionList optifine;
    private final QuiltVersionList quilt;
    private final QuiltAPIVersionList quiltApi;
    private final List<Pair<String, String>> replacement;
    private final List<Pair<String, String>> apiMirrorReplacement;
    private final List<Pair<String, String>> cdnMirrorReplacement;

    public BMCLAPIDownloadProvider(String apiRoot) {
        this.apiRoot = apiRoot;
        this.game = new GameVersionList(this);
        this.fabric = new FabricVersionList(this);
        this.fabricApi = new FabricAPIVersionList(this);
        this.forge = new ForgeBMCLVersionList(apiRoot);
        this.cleanroom = new CleanroomVersionList(this);
        this.neoforge = new NeoForgeBMCLVersionList(apiRoot);
        this.liteLoader = new LiteLoaderBMCLVersionList(this);
        this.optifine = new OptiFineBMCLVersionList(apiRoot);
        this.quilt = new QuiltVersionList(this);
        this.quiltApi = new QuiltAPIVersionList(this);
        this.legacyFabric = new LegacyFabricVersionList(this);
        this.legacyFabricApi = new LegacyFabricAPIVersionList(this);

        this.replacement = Arrays.asList(
                pair("https://bmclapi2.bangbang93.com", apiRoot),
                pair("https://launchermeta.mojang.com", apiRoot),
                pair("https://piston-meta.mojang.com", apiRoot),
                pair("https://piston-data.mojang.com", apiRoot),
                pair("https://launcher.mojang.com", apiRoot),
                pair("https://libraries.minecraft.net", apiRoot + "/libraries"),
                pair("http://files.minecraftforge.net/maven", apiRoot + "/maven"),
                pair("https://files.minecraftforge.net/maven", apiRoot + "/maven"),
                pair("https://maven.minecraftforge.net", apiRoot + "/maven"),
                pair("https://maven.neoforged.net/releases/", apiRoot + "/maven/"),
                pair("http://dl.liteloader.com/versions/versions.json", apiRoot + "/maven/com/mumfrey/liteloader/versions.json"),
                pair("http://dl.liteloader.com/versions", apiRoot + "/maven"),
                pair("https://meta.fabricmc.net", apiRoot + "/fabric-meta"),
                pair("https://maven.fabricmc.net", apiRoot + "/maven"),
                pair("https://authlib-injector.yushi.moe", apiRoot + "/mirrors/authlib-injector"),
                pair("https://repo1.maven.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
                pair("https://repo.maven.apache.org/maven2", "https://mirrors.cloud.tencent.com/nexus/repository/maven-public"),
                pair("https://hmcl.glavo.site/metadata/cleanroom", "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/cleanroom"),
                pair("https://hmcl.glavo.site/metadata/fmllibs", "https://alist.8mi.tech/d/mirror/HMCL-Metadata/Auto/fmllibs"),
                pair("https://zkitefly.github.io/unlisted-versions-of-minecraft", "https://alist.8mi.tech/d/mirror/unlisted-versions-of-minecraft/Auto")
        );

        // MCIM（https://github.com/mcmod-info-mirror/mcim-rust-api）是**接口镜像**：
        // 这些 API 国内直连基本不通，镜像真的替你把请求转出去 —— 所以**镜像排前面**。
        //
        // 注意只放 API，别放 CDN。CDN 那两条曾一度也排在前面，实测是错的：
        // `mod.mcimirror.top/files/9019/497/x.jar` 只是 302 到
        // `https://mediafilez.forgecdn.net/files/9019/497/x.jar` —— 和原站
        // `edge.forgecdn.net` 那条 302 的落点**一模一样**。也就是说镜像对文件请求
        // 只是让你多走一跳、并不会替你把字节取回来；真撞墙时它也救不了。
        // 所以 CDN 保持**原站优先**，镜像只作兜底（见 cdnMirrorReplacement）。
        this.apiMirrorReplacement = Arrays.asList(
                pair("https://api.modrinth.com", "https://mod.mcimirror.top/modrinth"),
                pair("https://api.curseforge.com", "https://mod.mcimirror.top/curseforge")
        );

        this.cdnMirrorReplacement = Arrays.asList(
                pair("https://cdn.modrinth.com", "https://mod.mcimirror.top"),
                pair("https://edge.forgecdn.net", "https://mod.mcimirror.top")
        );
    }

    public String getApiRoot() {
        return apiRoot;
    }

    @Override
    public List<URL> getVersionListURLs() {
        return Collections.singletonList(NetworkUtils.toURL(apiRoot + "/mc/game/version_manifest.json"));
    }

    @Override
    public List<URL> getAssetObjectCandidates(String assetObjectLocation) {
        return Collections.singletonList(NetworkUtils.toURL(apiRoot + "/assets/" + assetObjectLocation));
    }

    @Override
    public ComponentVersionList<?> getVersionList(GameComponentType componentType) {
        switch (componentType) {
            case GAME:
                return game;
            case FABRIC:
                return fabric;
            case FABRIC_API:
                return fabricApi;
            case FORGE:
                return forge;
            case CLEANROOM:
                return cleanroom;
            case NEO_FORGE:
                return neoforge;
            case LITELOADER:
                return liteLoader;
            case OPTIFINE:
                return optifine;
            case QUILT:
                return quilt;
            case QUILT_API:
                return quiltApi;
            case LEGACY_FABRIC:
                return legacyFabric;
            case LEGACY_FABRIC_API:
                return legacyFabricApi;
            default:
                throw new IllegalArgumentException("Unrecognized component type: " + componentType);
        }
    }

    private static String injectURL(List<Pair<String, String>> replacement, String baseURL) {
        for (Pair<String, String> pair : replacement) {
            if (baseURL.startsWith(pair.getKey())) {
                return pair.getValue() + baseURL.substring(pair.getKey().length());
            }
        }
        return baseURL;
    }

    @Override
    public String injectURL(String baseURL) {
        return injectURL(replacement, baseURL);
    }

    @Override
    public List<URL> injectURLWithCandidates(String baseURL) {
        String injected = injectURL(replacement, baseURL);
        if (!injected.equals(baseURL)) {
            // BMCLAPI 主规则命中：改写后的地址就是唯一候选
            return Collections.singletonList(NetworkUtils.toURL(injected));
        }

        // 模组源 API：镜像真代理，**镜像排前面**。候选是「按顺序试、失败才换下一个」，
        // 官方地址排前面的话国内每次请求都要先吃一次超时（山东等地直接 RST），
        // 用户看到的就是「搜得出来，点进去转圈、版本列表空白」。
        //
        // 非国内用户不受影响：AutoDownloadProvider 的候选链里 Mojang 排在 BMCLAPI
        // 之前，那边给出的是原始地址，本来就先试。
        String apiInjected = injectURL(apiMirrorReplacement, baseURL);
        if (!apiInjected.equals(baseURL)) {
            return Arrays.asList(
                    NetworkUtils.toURL(apiInjected),
                    NetworkUtils.toURL(baseURL)
            );
        }

        // 模组文件 CDN：镜像只是 302 回原站（落点与原站那条 302 完全相同），
        // 排前面等于每次下载都白绕一跳。**原站优先**，镜像留作兜底。
        String cdnInjected = injectURL(cdnMirrorReplacement, baseURL);
        if (!cdnInjected.equals(baseURL)) {
            return Arrays.asList(
                    NetworkUtils.toURL(baseURL),
                    NetworkUtils.toURL(cdnInjected)
            );
        }

        return Collections.singletonList(NetworkUtils.toURL(baseURL));
    }

    @Override
    public int getConcurrency() {
        return Math.max(Runtime.getRuntime().availableProcessors() * 2, 6);
    }
}
