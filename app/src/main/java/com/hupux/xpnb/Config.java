package com.hupux.xpnb;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * 模块的全局配置与日志工具。
 *
 * 开关存在模块 App 自己的 SharedPreferences 里，被注入到虎扑进程的模块代码通过
 * XposedInterface#getRemotePreferences(String) 读到同一份数据，因此改开关不需要重装模块。
 */
public final class Config {

    public static final String TAG = "HupuX";

    /** 目标应用包名（虎扑）。 */
    public static final String TARGET_PACKAGE = "com.hupu.games";

    /** 与设置界面约定的 SharedPreferences 组名。 */
    public static final String PREFS_NAME = "hupux_config";

    public static final String KEY_SKIP_SPLASH = "skip_splash_ad";
    public static final String KEY_BLOCK_SDK_INIT = "block_ad_sdk_init";
    public static final String KEY_BLOCK_FEED = "block_feed_ad";
    public static final String KEY_BLOCK_FLOAT = "block_float_ad";
    public static final String KEY_BLOCK_SCENE = "block_scene_ad";
    public static final String KEY_VIEW_TREE_SKIP = "view_tree_skip";
    public static final String KEY_CLASS_PROBE = "class_probe";
    public static final String KEY_DEXKIT = "dexkit_resolve";
    public static final String KEY_VERBOSE = "verbose_log";
    /** 配置来源标记（只在 Provider 兜底通道的返回里出现）。 */
    public static final String KEY_CONFIG_SOURCE = "config_source";

    public static volatile boolean skipSplashAd = true;
    public static volatile boolean blockAdSdkInit = true;
    public static volatile boolean blockFeedAd = true;
    public static volatile boolean blockFloatAd = true;
    public static volatile boolean blockSceneAd = true;
    public static volatile boolean viewTreeSkip = true;
    public static volatile boolean classProbe = false;
    public static volatile boolean dexkitResolve = true;
    public static volatile boolean verbose = true;

    private Config() {
    }

    public static void load(SharedPreferences sp) {
        skipSplashAd = sp.getBoolean(KEY_SKIP_SPLASH, true);
        blockAdSdkInit = sp.getBoolean(KEY_BLOCK_SDK_INIT, true);
        blockFeedAd = sp.getBoolean(KEY_BLOCK_FEED, true);
        blockFloatAd = sp.getBoolean(KEY_BLOCK_FLOAT, true);
        blockSceneAd = sp.getBoolean(KEY_BLOCK_SCENE, true);
        viewTreeSkip = sp.getBoolean(KEY_VIEW_TREE_SKIP, true);
        classProbe = sp.getBoolean(KEY_CLASS_PROBE, false);
        dexkitResolve = sp.getBoolean(KEY_DEXKIT, true);
        verbose = sp.getBoolean(KEY_VERBOSE, true);
    }

    /** 远程配置（框架通道）读到的是不是一份空配置 —— FPA 3.8 上会这样。 */
    public static volatile boolean remoteEmpty = false;

    /** 实际生效的配置来源：remote / provider / default。 */
    public static volatile String configSource = "default";

    /**
     * 配置兜底：框架的 getRemotePreferences 在 FPA 上返回空配置（不抛异常、但所有 key 都读不到），
     * 用户改的开关因此不生效。这里改用模块自己的 exported Provider 跨进程读同一个
     * SharedPreferences 文件，读到就覆盖当前值。拿到虎扑 Context 后调用一次即可。
     */
    public static void loadFromProvider(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            Uri uri = Uri.parse("content://" + LogProvider.AUTHORITY);
            Bundle b = ctx.getContentResolver().call(uri, "getConfig", null, null);
            if (b == null || !"provider".equals(b.getString(KEY_CONFIG_SOURCE))) {
                return;
            }
            skipSplashAd = b.getBoolean(KEY_SKIP_SPLASH, true);
            blockAdSdkInit = b.getBoolean(KEY_BLOCK_SDK_INIT, true);
            blockFeedAd = b.getBoolean(KEY_BLOCK_FEED, true);
            blockFloatAd = b.getBoolean(KEY_BLOCK_FLOAT, true);
            blockSceneAd = b.getBoolean(KEY_BLOCK_SCENE, true);
            viewTreeSkip = b.getBoolean(KEY_VIEW_TREE_SKIP, true);
            classProbe = b.getBoolean(KEY_CLASS_PROBE, false);
            dexkitResolve = b.getBoolean(KEY_DEXKIT, true);
            verbose = b.getBoolean(KEY_VERBOSE, true);
            configSource = "provider";
            i("[配置] 已经 Provider 兜底通道读到模块开关：开屏=" + skipSplashAd
                    + " SDK=" + blockAdSdkInit + " 信息流=" + blockFeedAd
                    + " 浮窗=" + blockFloatAd + " 场景=" + blockSceneAd
                    + " 视图兜底=" + viewTreeSkip + " 探针=" + classProbe
                    + " 详细日志=" + verbose + " DexKit=" + dexkitResolve);
        } catch (Throwable t) {
            w("[配置] Provider 兜底通道不可用：" + t);
        }
    }

    /** 详细日志：只有开关打开时才输出。 */
    public static void v(String msg) {
        if (verbose) {
            Log.i(TAG, msg);
        }
    }

    /** 关键日志：始终输出，用来确认模块到底有没有生效。 */
    public static void i(String msg) {
        Log.i(TAG, msg);
    }

    public static void w(String msg) {
        Log.w(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(TAG, msg, t);
    }
}
