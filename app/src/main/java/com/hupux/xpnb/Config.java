package com.hupux.xpnb;

import android.content.SharedPreferences;
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
    public static final String KEY_VIEW_TREE_SKIP = "view_tree_skip";
    public static final String KEY_CLASS_PROBE = "class_probe";
    public static final String KEY_VERBOSE = "verbose_log";

    public static volatile boolean skipSplashAd = true;
    public static volatile boolean blockAdSdkInit = true;
    public static volatile boolean blockFeedAd = true;
    public static volatile boolean blockFloatAd = true;
    public static volatile boolean viewTreeSkip = true;
    public static volatile boolean classProbe = false;
    public static volatile boolean verbose = true;

    private Config() {
    }

    public static void load(SharedPreferences sp) {
        skipSplashAd = sp.getBoolean(KEY_SKIP_SPLASH, true);
        blockAdSdkInit = sp.getBoolean(KEY_BLOCK_SDK_INIT, true);
        blockFeedAd = sp.getBoolean(KEY_BLOCK_FEED, true);
        blockFloatAd = sp.getBoolean(KEY_BLOCK_FLOAT, true);
        viewTreeSkip = sp.getBoolean(KEY_VIEW_TREE_SKIP, true);
        classProbe = sp.getBoolean(KEY_CLASS_PROBE, false);
        verbose = sp.getBoolean(KEY_VERBOSE, true);
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
