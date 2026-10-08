package com.hupux.xpnb;

import java.lang.reflect.Method;

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
        registerScene();
        registerCreative();
    }

    private static void registerSplash() {
        final String f = "开屏";

        // 广告真正被展示的入口。
        //
        // 注意：不能只是简单地 return null。开屏页在等「广告已关闭」的回调来决定何时收尾
        // （HpSplashActionListener#onAdDismissed），把 show() 拦掉却又不给回调，
        // 开屏页就会永远等下去 —— 实测表现就是卡在开屏界面。
        // 所以这里拦掉展示之后，主动把「广告已关闭」补给它。
        HookRegistry.register("com.hupu.adver_boot.HpSplashAd", "show", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "HpSplashAd#show");
                return chain.proceed();
            }
            if (Config.verbose) {
                Config.v("[" + f + "] 拦截 HpSplashAd.show() this="
                        + HookUtil.shortName(chain.getThisObject()));
            }
            AdsLog.blocked(f, "HpSplashAd#show");
            notifyAdDismissed(chain.getThisObject());
            return null;
        }, f);

        // 缓存路径：二次启动时开屏走 showFromCache 而不是 show，不拦就绕过去了。
        // 处理方式与 show 完全一致：拦截 + 补报「广告已关闭」。
        HookRegistry.register("com.hupu.adver_boot.HpSplashAd", "showFromCache", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "HpSplashAd#showFromCache");
                return chain.proceed();
            }
            if (Config.verbose) {
                Config.v("[" + f + "] 拦截 HpSplashAd.showFromCache()");
            }
            AdsLog.blocked(f, "HpSplashAd#showFromCache");
            notifyAdDismissed(chain.getThisObject());
            return null;
        }, f);

        // 开屏页 UI 里主动展示广告的两个方法：拦掉原方法之后，直接让开屏页收尾。
        HookRegistry.register("com.hupu.games.main.splash.SplashFragment", "showSplashAd", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "SplashFragment#showSplashAd");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 SplashFragment.showSplashAd()");
            AdsLog.blocked(f, "SplashFragment#showSplashAd");
            finishSplashPage(chain.getThisObject());
            return null;
        }, f);

        HookRegistry.register("com.hupu.games.main.splash.SplashFragment", "showSplashVideo", chain -> {
            if (!Config.skipSplashAd) {
                AdsLog.allowed(f, "SplashFragment#showSplashVideo");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 SplashFragment.showSplashVideo()");
            AdsLog.blocked(f, "SplashFragment#showSplashVideo");
            finishSplashPage(chain.getThisObject());
            return null;
        }, f);
    }

    /**
     * 把「广告已关闭」补报给开屏页。
     *
     * <p>{@code HpSplashAd} 内部持有注册进来的 {@code HpSplashActionListener}，
     * Kotlin 会为私有字段生成 {@code access$getActionListener$p} 这样的合成访问器，
     * 反射调它即可拿到监听器，再调用 {@code onAdDismissed}。</p>
     */
    private static void notifyAdDismissed(Object ad) {
        if (ad == null) {
            return;
        }
        try {
            Class<?> adClass = ad.getClass();
            Method getter = adClass.getDeclaredMethod("access$getActionListener$p", adClass);
            getter.setAccessible(true);
            Object listener = getter.invoke(null, ad);
            if (listener == null) {
                Config.w("[开屏] 动作监听器尚未注册，无法补报关闭事件");
                return;
            }
            ClassLoader cl = adClass.getClassLoader();
            Class<?> dismissTypeClass = Class.forName(
                    "com.hupu.adver_boot.listener.HpBootAdDismissType", false, cl);
            Object[] constants = dismissTypeClass.getEnumConstants();
            Object dismissType = (constants != null && constants.length > 0) ? constants[0] : null;
            Method onDismissed = listener.getClass()
                    .getMethod("onAdDismissed", dismissTypeClass);
            onDismissed.invoke(listener, dismissType);
            Config.i("[开屏] 已补报「广告已关闭」，开屏页可正常收尾");
        } catch (Throwable t) {
            Config.w("[开屏] 补报关闭事件失败：" + t);
        }
    }

    /** 直接让开屏页收尾（SplashFragment 自己的出口方法）。 */
    private static void finishSplashPage(Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            Method finish = fragment.getClass().getDeclaredMethod("finishPage", String.class);
            finish.setAccessible(true);
            finish.invoke(fragment, "");
            Config.i("[开屏] 已直接结束开屏页");
        } catch (Throwable t) {
            Config.w("[开屏] 结束开屏页失败：" + t);
        }
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

        // 优量汇（腾讯 GDT）是第三家 SDK，漏了它 GDT 的开屏/信息流照样能拉。
        // 注意 YlhSdkManager 没有 $Companion —— initSdk 是类本身的实例方法
        // （方法表：initSdk(android/app/Application)V virtual）。
        HookRegistry.register("com.hupu.adver_base.sdk.YlhSdkManager", "initSdk", chain -> {
            if (!Config.blockAdSdkInit) {
                AdsLog.allowed(f, "YlhSdkManager#initSdk");
                return chain.proceed();
            }
            Config.v("[" + f + "] 拦截 YlhSdkManager.initSdk() 优量汇/GDT");
            AdsLog.blocked(f, "YlhSdkManager#initSdk（优量汇）");
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
        // 这是高频路径（一次刷新可触发多次），关掉详细日志时不再做字符串拼接。
        HookRegistry.register("com.hupu.adver_feed.HpFeedAd", "loadItemAd", chain -> {
            if (!Config.blockFeedAd) {
                AdsLog.allowed(f, "HpFeedAd#loadItemAd");
                return chain.proceed();
            }
            if (Config.verbose) {
                Config.v("[" + f + "] 拦截 HpFeedAd.loadItemAd()");
            }
            AdsLog.blocked(f, "HpFeedAd#loadItemAd");
            return null;
        }, f);

        // 单条广告的加载器（loadItemAd 的下游，更深处再拦一道）
        sceneBlockOn("com.hupu.adver_feed.HpFeedAdItem", "loadAd", f, "HpFeedAdItem#loadAd",
                () -> Config.blockFeedAd);

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

    /**
     * 场景广告：横幅 / 弹窗 / 浮泡（popup）/ 动效。
     *
     * <p>这几个场景复用浮窗已真机验证过的「loadFromNet + loadSuccess 双拦」模式：
     * 拦掉网络请求，再拦掉成功回调，缓存数据也进不来。横幅另有缓存入口
     * {@code loadFromData}，一并拦掉。全部是 void 方法，返回 null 即「原方法不执行」。</p>
     *
     * <p>类名与方法名均已对照脱壳后的方法表（analysis/all-methods.txt）逐条核验。
     * 刻意不拦 {@code loadData()} 这类有返回值的链式入口 —— 拦掉返回 null 会让调用方 NPE。</p>
     */
    private static void registerScene() {
        final String f = "场景广告";

        // 横幅（单图）：lonely = 单个横幅
        sceneBlock("com.hupu.adver_banner.lonely.HpBannerAdCore", "loadFromNet", f, "HpBannerAdCore#loadFromNet");
        sceneBlock("com.hupu.adver_banner.lonely.HpBannerAdCore", "loadFromData", f, "HpBannerAdCore#loadFromData");
        sceneBlock("com.hupu.adver_banner.lonely.HpBannerAdCore", "loadSuccess", f, "HpBannerAdCore#loadSuccess");
        // 横幅（轮播）：mul = 多图轮播
        sceneBlock("com.hupu.adver_banner.mul.HpMulBannerAdCore", "loadFromNet", f, "HpMulBannerAdCore#loadFromNet");
        sceneBlock("com.hupu.adver_banner.mul.HpMulBannerAdCore", "loadSuccess", f, "HpMulBannerAdCore#loadSuccess");
        // 弹窗广告
        sceneBlock("com.hupu.adver_dialog.HpAdDialogCore", "loadFromNet", f, "HpAdDialogCore#loadFromNet");
        sceneBlock("com.hupu.adver_dialog.HpAdDialogCore", "loadSuccess", f, "HpAdDialogCore#loadSuccess");
        // 浮泡（页面内小浮窗）
        sceneBlock("com.hupu.adver_popup.HpAdPopupCore", "loadFromNet", f, "HpAdPopupCore#loadFromNet");
        sceneBlock("com.hupu.adver_popup.HpAdPopupCore", "loadSuccess", f, "HpAdPopupCore#loadSuccess");
        // 动效广告（图标动画等）
        sceneBlock("com.hupu.adver_animation.animation.HpAnimationAd", "loadFromNet", f, "HpAnimationAd#loadFromNet");
        sceneBlock("com.hupu.adver_animation.animation.HpAnimationAd", "loadSuccess", f, "HpAnimationAd#loadSuccess");
    }

    /** 场景广告的统一登记：开关开着就拦，关了就放行并记录。 */
    private static void sceneBlock(String className, String methodName,
                                   String feature, String label) {
        sceneBlockOn(className, methodName, feature, label, () -> Config.blockSceneAd);
    }

    /** 同 {@link #sceneBlock}，但开关由调用方给（比如信息流深处那道用 blockFeedAd）。 */
    private static void sceneBlockOn(String className, String methodName,
                                     String feature, String label,
                                     java.util.function.BooleanSupplier enabled) {
        HookRegistry.register(className, methodName, chain -> {
            if (!enabled.getAsBoolean()) {
                AdsLog.allowed(feature, label);
                return chain.proceed();
            }
            if (Config.verbose) {
                Config.v("[" + feature + "] 拦截 " + label);
            }
            AdsLog.blocked(feature, label);
            return null;
        }, feature);
    }

    /**
     * 创意广告族（{@code com.hupu.adver_creative.*}）—— 用运行时类加载探针对照
     * 脱壳方法表扫出来的**整片未覆盖广告面**，共 7 个场景：
     *
     * <ul>
     *     <li>搜索框右侧图标广告（search）</li>
     *     <li>话题页广告（topic）</li>
     *     <li>下拉刷新广告（refresh，含 SDK 渲染兜底）</li>
     *     <li>视频贴片锚点/抽帧广告（videoanchor / videodraw）</li>
     *     <li>来电样式视频弹窗（videoCall）</li>
     *     <li>「我的」页广告（mine）</li>
     *     <li>穿山甲商城位（csjmall）</li>
     * </ul>
     *
     * <p>全部挂在「场景广告」开关下，与横幅/弹窗/浮泡/动效同一档。
     * 方法签名已逐条对照 all-methods.txt：均为 void，返回 null 即「原方法不执行」；
     * {@code loadData()} 这类有返回值的链式入口刻意不拦（返回 null 会让调用方 NPE）。</p>
     */
    private static void registerCreative() {
        final String f = "场景广告";

        // 搜索框右侧图标广告
        sceneBlock("com.hupu.adver_creative.search.HpSearchIconAdCore", "loadFromNet", f, "HpSearchIconAdCore#loadFromNet");
        sceneBlock("com.hupu.adver_creative.search.HpSearchIconAdCore", "loadSuccess", f, "HpSearchIconAdCore#loadSuccess");
        // 话题页广告
        sceneBlock("com.hupu.adver_creative.topic.HpTopicAd", "loadFromNet", f, "HpTopicAd#loadFromNet");
        sceneBlock("com.hupu.adver_creative.topic.HpTopicAd", "loadSuccess", f, "HpTopicAd#loadSuccess");
        // 下拉刷新广告
        sceneBlock("com.hupu.adver_creative.refresh.HpAdRefresh", "loadFromNet", f, "HpAdRefresh#loadFromNet");
        sceneBlock("com.hupu.adver_creative.refresh.HpAdRefresh", "loadSuccess", f, "HpAdRefresh#loadSuccess");
        sceneBlock("com.hupu.adver_creative.refresh.core.HpRefreshSdkAd", "process", f, "HpRefreshSdkAd#process");
        // 视频锚点广告（贴片挂角）
        sceneBlock("com.hupu.adver_creative.videoanchor.HpVideoAnchorItemAd", "loadAd", f, "HpVideoAnchorItemAd#loadAd");
        // 视频抽帧广告
        sceneBlock("com.hupu.adver_creative.videodraw.HpVideoDrawItemAd", "loadAd", f, "HpVideoDrawItemAd#loadAd");
        // 来电样式视频弹窗
        sceneBlock("com.hupu.adver_creative.videoCall.HpAdVideoCallCore", "loadFromNet", f, "HpAdVideoCallCore#loadFromNet");
        sceneBlock("com.hupu.adver_creative.videoCall.HpAdVideoCallCore", "loadSuccess", f, "HpAdVideoCallCore#loadSuccess");
        sceneBlock("com.hupu.adver_creative.videoCall.HpAdVideoCallCore", "showDialog", f, "HpAdVideoCallCore#showDialog");
        // 「我的」页广告
        sceneBlock("com.hupu.adver_creative.mine.MineTabAd", "loadFromNet", f, "MineTabAd#loadFromNet");
        // 穿山甲商城位（该类没有 loadSuccess —— 那是 OnLoadListener 接口上的方法，
        // 实测 MISS 后按它真实的方法表换成 loadAd，双保险仍然成立）
        sceneBlock("com.hupu.adver_creative.csjmall.HpCsjAdCore", "loadFromNet", f, "HpCsjAdCore#loadFromNet");
        sceneBlock("com.hupu.adver_creative.csjmall.HpCsjAdCore", "loadAd", f, "HpCsjAdCore#loadAd");
    }
}
