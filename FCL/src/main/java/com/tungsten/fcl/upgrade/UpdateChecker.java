package com.tungsten.fcl.upgrade;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import com.google.gson.reflect.TypeToken;
import com.mio.promo.QuarkPromo;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.NetworkUtils;
import com.tungsten.fcllibrary.util.LocaleUtils;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * 版本表拉取。
 *
 * ⚠️ **本 fork 不做更新检查，也不会向用户提示任何新版本。** 类名是历史遗留（上游把它同时
 * 用于「检查更新」），本 fork 已把那条链路整个删掉：没有入口、没有弹窗、没有"发现新版本"
 * 的提示。这里只剩下两件事：
 *
 * 1. [getCurrentVersionCode]：公告过滤等地方要用的当前版本号，与更新提示无关；
 * 2. [refreshPromoOnly]：静默拉一次版本表，**只**把最新的网盘链接缓存给 [QuarkPromo]。
 *    它不比较版本、不弹对话框、不弹 Toast，拉取失败也完全静默。
 *
 * 要重新加回更新检查时先想清楚为什么——用户明确要求过「绝不出现更新提示」。
 */
public class UpdateChecker {

    /**
     * 版本表地址。中文环境走 Gitee 镜像，因为 raw.githubusercontent.com 在国内基本不可达；
     * 这个判定只影响"从哪拉"，与是否提示更新无关。
     */
    private static final String VERSION_MAP_URL_CN = "https://gitee.com/fcl-team/FCL-Repo/raw/main/res/version_map.json";
    private static final String VERSION_MAP_URL = "https://raw.githubusercontent.com/FCL-Team/FoldCraftLauncher/main/version_map.json";

    private static UpdateChecker instance;

    public static UpdateChecker getInstance() {
        if (instance == null) {
            instance = new UpdateChecker();
        }
        return instance;
    }

    private UpdateChecker() {
    }

    /**
     * 静默刷新网盘链接：不比较版本、不提示、不抛异常。
     *
     * 调用点只有一处（主界面初始化完成后），失败时保留上一次缓存，用户完全无感。
     */
    public Task<?> refreshPromoOnly(Context context) {
        final Context appContext = context.getApplicationContext();
        return Task.runAsync(() -> {
            try {
                String url = LocaleUtils.isChinese(appContext) ? VERSION_MAP_URL_CN : VERSION_MAP_URL;
                String res = NetworkUtils.doGet(NetworkUtils.toURL(url));
                ArrayList<RemoteVersion> versions = JsonUtils.GSON.fromJson(res,
                        new TypeToken<ArrayList<RemoteVersion>>() {
                        }.getType());
                if (versions == null || versions.isEmpty()) {
                    return;
                }
                versions.stream()
                        .max(Comparator.comparingInt(RemoteVersion::getVersionCode))
                        .ifPresent(version -> QuarkPromo.updateNetdiskUrl(appContext, version.getNetdiskUrl()));
            } catch (Throwable ignored) {
                // 推广链接是加分项，拉不到就算了，绝不能影响启动或弹任何提示
            }
        });
    }

    public static int getCurrentVersionCode(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            throw new IllegalStateException("Can't get current version code");
        }
    }
}
