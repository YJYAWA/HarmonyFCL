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
package com.tungsten.fclcore.util.io;

import com.tungsten.fclcore.util.Logging;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;

/**
 * 按候选地址依次尝试同一个请求，返回第一个成功的响应。
 *
 * 模组源（Modrinth / CurseForge）的 **API 与 CDN 在国内大多直连不通**，而下载源里已经
 * 配好了镜像改写规则。所以这类请求不能把原始地址写死 —— 必须走
 * {@link com.tungsten.fclcore.download.DownloadProvider#injectURLWithCandidates(String)}
 * 给出的候选列表。顺序由 `BMCLAPIDownloadProvider` 决定：国内镜像在前、原始地址兜底。
 *
 * 拿不到候选的调用点（例如某个静态方法没有 DownloadProvider 可用）说明这条路径没接上
 * 镜像，国内用户在那个功能上就是必失败的 —— 与其悄悄退回直连，不如把候选传进来。
 */
public final class HttpRequestCandidates {

    private HttpRequestCandidates() {
    }

    /**
     * 由候选地址构造请求。
     *
     * CurseForge 要借它给每个候选单独补 API key —— key 只在 URL 以
     * `https://api.curseforge.com` 开头时才带得上，镜像地址是
     * `https://mod.mcimirror.top/...`，由镜像自己配 key，不能再往上加。
     */
    @FunctionalInterface
    public interface RequestFactory {
        HttpRequest create(String url) throws MalformedURLException;
    }

    /** POST 专用：body 要设在 {@link HttpRequest.HttpPostRequest} 上，基类没有这个方法。 */
    @FunctionalInterface
    public interface PostRequestFactory {
        HttpRequest.HttpPostRequest create(String url) throws MalformedURLException;
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run(String url) throws IOException;
    }

    public static <T> T getJson(List<URL> candidates, RequestFactory factory, Type type) throws IOException {
        return tryAll(candidates, url -> factory.create(url).getJson(type));
    }

    public static <T> T postJson(List<URL> candidates, PostRequestFactory factory, Object body, Type type) throws IOException {
        return tryAll(candidates, url -> factory.create(url).json(body).getJson(type));
    }

    /**
     * 逐个候选尝试。全部失败时抛一个汇总异常，把每个候选自己的原因挂在 suppressed 上 ——
     * 只留最后一个的话，"镜像 404 而原地址超时"这种情况就说不清了。
     */
    private static <T> T tryAll(List<URL> candidates, Attempt<T> attempt) throws IOException {
        IOException failure = null;
        for (URL candidate : candidates) {
            try {
                return attempt.run(candidate.toString());
            } catch (IOException e) {
                Logging.LOG.log(Level.WARNING, "请求失败，改试下一个地址: " + candidate, e);
                if (failure == null) {
                    failure = new IOException("所有候选地址都失败");
                }
                failure.addSuppressed(e);
            }
        }
        throw failure != null ? failure : new IOException("没有可用的候选地址");
    }
}
