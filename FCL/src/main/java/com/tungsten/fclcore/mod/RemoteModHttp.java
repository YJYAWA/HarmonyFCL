/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
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
package com.tungsten.fclcore.mod;

import com.tungsten.fclcore.download.DownloadProvider;
import com.tungsten.fclcore.util.Logging;
import com.tungsten.fclcore.util.io.NetworkUtils;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * 模组源（Modrinth / CurseForge）请求的候选地址来源。
 *
 * 为什么需要这么一个东西：{@link RemoteModRepository} 里**只有 `search()` 带
 * `DownloadProvider` 参数**，详情、版本列表、分类、指纹匹配这些接口都没有 —— 于是它们
 * 过去一律把 `https://api.modrinth.com` / `https://api.curseforge.com` 写死直连。
 * 这两家的 API 在国内大多不通，用户看到的就是「搜得出来，点进详情一直转圈，也就下不动」：
 * 搜索走了镜像候选，其余全在撞墙。
 *
 * 这里把启动器选定的 [`DownloadProvider`] 装进来，让那些没有参数可拿的接口也能走同一套
 * 镜像改写规则。候选顺序见
 * {@link com.tungsten.fclcore.download.BMCLAPIDownloadProvider#injectURLWithCandidates}。
 *
 * 装载点是 {@code com.tungsten.fcl.setting.DownloadProviders#init()}（它本身就负责跟随
 * 「下载源」设置切换 provider，装进去的是稳定的 Wrapper，设置变了也自动跟着变）。
 */
public final class RemoteModHttp {

    private static volatile DownloadProvider provider;

    private RemoteModHttp() {
    }

    public static void install(DownloadProvider downloadProvider) {
        provider = downloadProvider;
    }

    /**
     * 给出该 URL 的候选地址列表（国内镜像在前、原始地址兜底）。
     *
     * 没装 provider 时退回原始地址而不是抛异常 —— 模组浏览不该因为一个初始化顺序问题
     * 整个用不了，但会打一条日志，免得这种静默退化没人发现。
     */
    public static List<URL> candidates(String url) {
        DownloadProvider current = provider;
        if (current == null) {
            Logging.LOG.log(Level.WARNING, "下载源尚未初始化，模组请求将直连: " + url);
            return Collections.singletonList(NetworkUtils.toURL(url));
        }
        return current.injectURLWithCandidates(url);
    }

    /**
     * 只要首选地址，不给兜底。
     *
     * 给「404 是定论」的调用点用（按哈希反查本地文件那个）：那边的逻辑是收到 404 就
     * 负缓存、不再追问，而候选回落会把异常吞掉、去试下一个地址，等于把这个判定废掉。
     * 国内首选是镜像，镜像查不到就是查不到；国外首选就是原始地址，行为与改前一致。
     */
    public static URL preferred(String url) {
        return candidates(url).get(0);
    }
}
