package com.hupux.xpnb;

/**
 * 精确 Hook 点：全部来自对虎扑 8.2.63 脱壳 dex 的静态分析（见 docs/REPORT.md）。
 *
 * 虎扑的广告体系分三层：
 *   1) com.hupu.adver_base.sdk  —— 广告 SDK 适配层，聚合了穿山甲（TTSdkManager）
 *      与虎扑自研的 Noah（NoahSdkManager）；
 *   2) com.hupu.adver_boot / adver_feed / adver_float —— 开屏 / 信息流 / 浮窗广告；
 *   3) com.hupu.games.main.splash.SplashFragment —— 开屏页 UI 层。
 *
 * 新 API 的拦截器链模型：不调用 chain.proceed() 就等于「不执行原方法」。
 *
 * 注意这里只<b>登记规则</b>，不加载类：虎扑有网易易盾加固，真实 dex 由壳在运行期加载，
 * 具体安装时机交给 {@link LazyHookInstaller}。类名与方法名均已逐条对照脱壳 dex 校验。
 */
public final class AdHooks {

    private AdHooks() {
    }

    public static void register() {
        registerSplash();
        registerSdkInit();
        registerFeed();
        registerFloat();
    }

    private static void registerSplash() {
        final String f = "开屏";

        // 广告真正被展示的入口。不 proceed 就等于「不展示广告」，
        // SplashFragment 会走它自己的兜底逻辑（显示品牌页并倒计时结束）。
        HookRegistry.register("com.hupu.adver_boot.HpSplashAd", "show", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "HpSplashAd#show");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpSplashAd.show() this="
                    + HookUtil.shortName(chain.getThisObject()));
            AdsLog.blocked(f, "HpSplashAd#show");
            return null;
        }, f);

        // 开屏页 UI 里主动展示广告的两个方法，一并拦掉。
        HookRegistry.register("com.hupu.games.main.splash.SplashFragment", "showSplashAd", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "SplashFragment#showSplashAd");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 SplashFragment.showSplashAd()");
            AdsLog.blocked(f, "SplashFragment#showSplashAd");
            return null;
        }, f);

        HookRegistry.register("com.hupu.games.main.splash.SplashFragment", "showSplashVideo", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "SplashFragment#showSplashVideo");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 SplashFragment.showSplashVideo()");
            AdsLog.blocked(f, "SplashFragment#showSplashVideo");
            return null;
        }, f);
    }

    private static void registerSdkInit() {
        final String f = "SDK初始化";

        // TTSdkManager / NoahSdkManager 都是 Kotlin object，方法挂在 $Companion 上。
        HookRegistry.register("com.hupu.adver_base.sdk.TTSdkManager$Companion", "initSdk", chain -> {
            if (!Config.blockAdSdkInit) {
                AdsLog.allowed(f, "TTSdkManager#initSdk");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 TTSdkManager.initSdk() 穿山甲");
            AdsLog.blocked(f, "TTSdkManager#initSdk（穿山甲）");
            return null;
        }, f);

        HookRegistry.register("com.hupu.adver_base.sdk.NoahSdkManager$Companion", "initSdk", chain -> {
            if (!Config.blockAdSdkInit) {
                AdsLog.allowed(f, "NoahSdkManager#initSdk");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 NoahSdkManager.initSdk() 虎扑自研 Noah");
            AdsLog.blocked(f, "NoahSdkManager#initSdk（虎扑自研）");
            return null;
        }, f);

        // 顺带掐掉开屏广告加载器的初始化
        HookRegistry.register("com.hupu.adver_boot.SplashAdStarter", "init", chain -> {
            if (!Config.blockAdSdkInit) {
                AdsLog.allowed(f, "SplashAdStarter#init");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 SplashAdStarter.init()");
            AdsLog.blocked(f, "SplashAdStarter#init");
            return null;
        }, f);
    }

    private static void registerFeed() {
        final String f = "信息流";

        // canLoadAd 是「这一页要不要拉广告」的总开关，直接返回 false 整页不加载。
        HookRegistry.register("com.hupu.adver_feed.HpFeedAd", "canLoadAd", chain -> {
            if (!Config.blockFeedAd) {
                AdsLog.allowed(f, "HpFeedAd#canLoadAd");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpFeedAd.canLoadAd() -> false");
            AdsLog.blocked(f, "HpFeedAd#canLoadAd → false");
            return Boolean.FALSE;
        }, f);

        // 已经拉回来的广告条目，插入列表前再拦一道。
        HookRegistry.register("com.hupu.adver_feed.HpFeedAd", "loadItemAd", chain -> {
            if (!Config.blockFeedAd) {
                AdsLog.allowed(f, "HpFeedAd#loadItemAd");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpFeedAd.loadItemAd()");
            AdsLog.blocked(f, "HpFeedAd#loadItemAd");
            return null;
        }, f);

        // SDK 侧真正加载广告的过程，双保险。
        HookRegistry.register("com.hupu.adver_feed.core.HpFeedSdkAd", "process", chain -> {
            if (!Config.blockFeedAd) {
                AdsLog.allowed(f, "HpFeedSdkAd#process");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpFeedSdkAd.process()");
            AdsLog.blocked(f, "HpFeedSdkAd#process");
            return null;
        }, f);
    }

    private static void registerFloat() {
        final String f = "浮窗";

        HookRegistry.register("com.hupu.adver_float.HpAdFloatCore", "loadFromNet", chain -> {
            if (!Config.blockFloatAd) {
                AdsLog.allowed(f, "HpAdFloatCore#loadFromNet");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpAdFloatCore.loadFromNet()");
            AdsLog.blocked(f, "HpAdFloatCore#loadFromNet");
            return null;
        }, f);

        HookRegistry.register("com.hupu.adver_float.HpAdFloatCore", "loadSuccess", chain -> {
            if (!Config.blockFloatAd) {
                AdsLog.allowed(f, "HpAdFloatCore#loadSuccess");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 HpAdFloatCore.loadSuccess()");
            AdsLog.blocked(f, "HpAdFloatCore#loadSuccess");
            return null;
        }, f);
    }
}
