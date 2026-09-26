package com.tungsten.fclauncher.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 内置 MobileGlues 渲染器的配置目录。
 *
 * <p>MobileGlues 把 {@code config.json}、{@code latest.log}、{@code glsl_cache.tmp}、
 * {@code stats.json} 全都放在 {@code MG_DIR_PATH} 指向的目录里（默认是 {@code /sdcard/MG}，
 * 那是独立插件 APK 的路径，需要「所有文件访问」权限才能写）。内置之后把它指到 FCL 自己的
 * 应用外部目录：游戏进程与启动器同属一个 uid，无需任何额外权限即可读写，
 * 因此内嵌后只需要给 FCL 授权一次。
 *
 * <p>种子文件只在缺失时写入，绝不覆盖——这是内嵌后用户调整 MobileGlues 参数的唯一入口。
 * 其中 {@code enableANGLE=2}（ForceDisable）与 {@code angleDepthClearFixMode=0}（Disabled）
 * 是刻意的：ANGLE 走 GLES→Vulkan，麒麟 Maleoon 驱动的 Vulkan 能力不足。这两个键在
 * MobileGlues 源码里也被再次硬置（见仓库内 patches/mobileglues-no-vulkan.patch），
 * 所以即使有人手改这里，也回不去 ANGLE，更不会出现任何 Vulkan 调用。
 */
public final class MobileGluesConfig {

    public static final String DIR_NAME = "mobileglues";
    public static final String CONFIG_FILE = "config.json";

    /**
     * 键名与取值都对齐 MobileGlues 自己的磁盘格式（插件端 MGConfigCodec.kt / native 端 config_get_int），
     * 其余未列出的键由 MobileGlues 使用它自己的默认值。
     */
    private static final String DEFAULT_CONFIG = "{\n"
            + "  \"enableANGLE\": 2,\n"
            + "  \"angleDepthClearFixMode\": 0,\n"
            + "  \"enableNoError\": 0,\n"
            + "  \"enableExtTimerQuery\": 1,\n"
            + "  \"enableExtComputeShader\": 0,\n"
            + "  \"enableExtDirectStateAccess\": 0,\n"
            + "  \"maxGlslCacheSize\": 32,\n"
            + "  \"customGLVersion\": 0,\n"
            + "  \"fsr1Setting\": 0\n"
            + "}\n";

    private MobileGluesConfig() {
    }

    /**
     * MobileGlues 的工作目录：应用外部目录（{@code Android/data/<包名>/files/mobileglues}），
     * 拿不到外部目录（极少见）时退回应用内部目录。
     */
    public static File getDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, DIR_NAME);
    }

    /**
     * 确保目录与 {@code config.json} 存在，返回目录绝对路径（用作 {@code MG_DIR_PATH}）。
     * 已有 config.json 时不改动其内容。
     */
    public static String ensureDir(Context context) {
        File dir = getDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            // 建不出来就让 MobileGlues 用它自己的默认路径；ANGLE 仍被源码强制关闭
            return dir.getAbsolutePath();
        }
        File config = new File(dir, CONFIG_FILE);
        if (!config.exists()) {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(config), StandardCharsets.UTF_8)) {
                writer.write(DEFAULT_CONFIG);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return dir.getAbsolutePath();
    }
}
