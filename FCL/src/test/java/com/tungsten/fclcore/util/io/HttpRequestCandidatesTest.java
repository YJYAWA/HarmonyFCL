package com.tungsten.fclcore.util.io;

import static com.tungsten.fclcore.util.Lang.mapOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.reflect.TypeToken;

import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 候选回落本身的行为：第一个地址失败就换下一个，全部失败才算失败。
 *
 * 这是国内访问模组源的关键路径 —— 镜像排在前面，镜像不通时才回落官方地址。
 *
 * 用桩 {@link HttpRequest} 而不是真发请求：{@code NetworkUtils.createConnection} 会调用
 * {@code FCLApp.getAppContext()}（见 NetworkUtils:118 设 User-Agent 那里），纯 JVM 单元
 * 测试里没有 Android 上下文，任何真实请求都会先抛 NPE 再被 getStringWithRetry 包成
 * IOException —— 那样测出来的「失败」跟被测逻辑毫无关系。真正打网络的用例在
 * androidTest 的 NetworkUtilsDoGetTest 里。
 */
public class HttpRequestCandidatesTest {

    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<Map<String, String>>() {}.getType();

    private static final String OK_BODY = "{\"value\":\"from-second-candidate\"}";

    /** 镜像地址（首选）失败、官方地址（兜底）可用的桩。 */
    private static final class StubGetRequest extends HttpRequest.HttpGetRequest {
        private final boolean fail;

        StubGetRequest(String url, boolean fail) {
            super(url);
            this.fail = fail;
        }

        @Override
        public String getString() throws IOException {
            if (fail) {
                throw new IOException("模拟请求失败: " + url);
            }
            return OK_BODY;
        }
    }

    private static List<URL> urls(String... values) {
        return Arrays.stream(values).map(NetworkUtils::toURL).collect(Collectors.toList());
    }

    private static final String MIRROR = "https://mod.mcimirror.top/modrinth/v2/project/sodium/version";
    private static final String OFFICIAL = "https://api.modrinth.com/v2/project/sodium/version";

    /** 按 URL 决定成败的工厂：failMirror / failOfficial 指定哪几个地址失败。 */
    private static HttpRequestCandidates.RequestFactory factory(boolean failMirror, boolean failOfficial) {
        return url -> new StubGetRequest(url, url.equals(MIRROR) ? failMirror : failOfficial);
    }

    @Test
    public void fallsBackToOfficialWhenMirrorFails() throws IOException {
        Map<String, String> result = HttpRequestCandidates.getJson(
                urls(MIRROR, OFFICIAL), factory(true, false), MAP_TYPE);

        assertEquals("from-second-candidate", result.get("value"));
    }

    @Test
    public void usesMirrorWithoutTouchingOfficialWhenItSucceeds() throws IOException {
        // 官方地址被打成失败：若实现先去试了官方，这里就会抛异常而不是走到断言
        Map<String, String> result = HttpRequestCandidates.getJson(
                urls(MIRROR, OFFICIAL), factory(false, true), MAP_TYPE);

        assertEquals("from-second-candidate", result.get("value"));
    }

    @Test
    public void throwsWhenEveryCandidateFails() {
        IOException thrown = assertThrows(IOException.class, () -> HttpRequestCandidates.getJson(
                urls(MIRROR, OFFICIAL), factory(true, true), MAP_TYPE));

        // 每个候选的失败都挂上去，日志里才看得出是哪一个环节出的问题
        assertEquals(2, thrown.getSuppressed().length);
    }

    @Test
    public void throwsWhenNoCandidateIsAvailable() {
        IOException thrown = assertThrows(IOException.class, () -> HttpRequestCandidates.getJson(
                Collections.emptyList(), factory(false, false), MAP_TYPE));

        assertTrue(thrown.getMessage().contains("没有可用的候选地址"));
    }

    @Test
    public void postJsonReportsMissingCandidates() {
        // HttpPostRequest 是 final 的，没法像 GET 那样打桩；这里只覆盖「没有候选」这条
        // 不构造请求的分支，POST 的回落循环与 GET 共用同一个 tryAll。
        IOException thrown = assertThrows(IOException.class, () -> HttpRequestCandidates.postJson(
                Collections.emptyList(), HttpRequest::POST,
                mapOf(com.tungsten.fclcore.util.Pair.pair("hashes", Collections.singletonList("abc"))),
                MAP_TYPE));

        assertTrue(thrown.getMessage().contains("没有可用的候选地址"));
    }
}
