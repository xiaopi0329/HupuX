package com.hupux.xpnb;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 目标类的「结构化指纹」表。
 *
 * <p>本文件由 {@code analysis/scripts/gen_fingerprints.py} 从脱壳方法表
 * （{@code analysis/all-methods.txt}）生成，<b>请勿手工编辑</b>。</p>
 *
 * <p>用途：虎扑升级后类名可能被改名或挪到别的包，写死的类名就找不到目标了。
 * 指纹只记录「被 hook 的方法长什么样」——方法名 + 参数个数 + 参数类型 + 返回类型，
 * 这些信息比类名稳定得多。{@link DexKitResolver} 在运行期用 DexKit 查询宿主 dex，
 * 再用这张表把目标类认出来。</p>
 */
public final class HookFingerprints {

    /** 一个被 hook 的方法的签名指纹。 */
    public static final class Sig {
        public final String name;
        public final int paramCount;
        /** Java 类型名（点号形式）；脱壳表里查不到时为 null。 */
        public final String[] params;
        /** Java 类型名（点号形式）；脱壳表里查不到时为 null。 */
        public final String returnType;

        public Sig(String name, int paramCount, String[] params, String returnType) {
            this.name = name;
            this.paramCount = paramCount;
            this.params = params;
            this.returnType = returnType;
        }

        /** 参数与返回类型是否都已知（未知时只能靠方法名 + 参数个数匹配）。 */
        public boolean complete() {
            return paramCount >= 0 && params != null && returnType != null;
        }
    }

    private static final Map<String, Sig[]> TABLE;

    static {
        Map<String, Sig[]> m = new HashMap<>();
        // ===== 生成内容开始（不要手工改这一段） =====
        m.put("com.hupu.adver_boot.HpSplashAd", new Sig[]{ // HpSplashAd
                new Sig("show", 1, new String[]{"android.view.ViewGroup"}, "void"),
                new Sig("showFromCache", 2, new String[]{null, null}, "void"),
        });
        m.put("com.hupu.games.main.splash.SplashFragment", new Sig[]{ // SplashFragment
                new Sig("showSplashAd", 0, new String[]{}, "void"),
                new Sig("showSplashVideo", 0, new String[]{}, "void"),
        });
        m.put("com.hupu.adver_base.sdk.TTSdkManager$Companion", new Sig[]{ // TTSdkManager$Companion
                new Sig("initSdk", 2, new String[]{null, null}, "void"),
        });
        m.put("com.hupu.adver_base.sdk.NoahSdkManager$Companion", new Sig[]{ // NoahSdkManager$Companion
                new Sig("initSdk", 1, new String[]{"android.app.Application"}, "void"),
        });
        m.put("com.hupu.adver_boot.SplashAdStarter", new Sig[]{ // SplashAdStarter
                new Sig("init", 1, new String[]{"android.app.Application"}, "void"),
        });
        m.put("com.hupu.adver_base.sdk.YlhSdkManager", new Sig[]{ // YlhSdkManager
                new Sig("initSdk", 1, new String[]{"android.app.Application"}, "void"),
        });
        m.put("com.hupu.adver_feed.HpFeedAd", new Sig[]{ // HpFeedAd
                new Sig("canLoadAd", 1, new String[]{
                        "com.hupu.adver_base.config.entity.AdPageConfig$AdPageEntity",
                }, "boolean"),
                new Sig("loadItemAd", 1, new String[]{"int"}, "void"),
        });
        m.put("com.hupu.adver_feed.HpFeedAdItem", new Sig[]{ // HpFeedAdItem
                new Sig("loadAd", 2, new String[]{null, null}, "void"),
        });
        m.put("com.hupu.adver_feed.core.HpFeedSdkAd", new Sig[]{ // HpFeedSdkAd
                new Sig("process", 7, new String[]{
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "com.hupu.adver_feed.sdk.FeedSdkAdapter$FeedSdkListener",
                }, "void"),
        });
        m.put("com.hupu.adver_float.HpAdFloatCore", new Sig[]{ // HpAdFloatCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_float.data.entity.AdFloatResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_banner.lonely.HpBannerAdCore", new Sig[]{ // HpBannerAdCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadFromData", 1, new String[]{
                        "com.hupu.adver_banner.lonely.data.entity.AdBannerResponse",
                }, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_banner.lonely.data.entity.AdBannerResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_banner.mul.HpMulBannerAdCore", new Sig[]{ // HpMulBannerAdCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_banner.mul.data.entity.AdMulBannerResult",
                }, "void"),
        });
        m.put("com.hupu.adver_dialog.HpAdDialogCore", new Sig[]{ // HpAdDialogCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_dialog.data.entity.AdDialogResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_popup.HpAdPopupCore", new Sig[]{ // HpAdPopupCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_popup.data.entity.AdPopupResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_animation.animation.HpAnimationAd", new Sig[]{ // HpAnimationAd
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_animation.animation.data.AdAnimationResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.search.HpSearchIconAdCore", new Sig[]{ // HpSearchIconAdCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_creative.search.data.AdSearchIconResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.topic.HpTopicAd", new Sig[]{ // HpTopicAd
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_creative.topic.data.AdTopicResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.refresh.HpAdRefresh", new Sig[]{ // HpAdRefresh
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_creative.refresh.data.entity.AdRefreshResponse",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.refresh.core.HpRefreshSdkAd", new Sig[]{ // HpRefreshSdkAd
                new Sig("process", 6, new String[]{null, null, null, null, null, null}, "void"),
        });
        m.put("com.hupu.adver_creative.videoanchor.HpVideoAnchorItemAd", new Sig[]{ // HpVideoAnchorItemAd
                new Sig("loadAd", 1, new String[]{
                        "com.hupu.adver_creative.videoanchor.data.entity.AdVideoAnchorResult",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.videodraw.HpVideoDrawItemAd", new Sig[]{ // HpVideoDrawItemAd
                new Sig("loadAd", 1, new String[]{
                        "com.hupu.adver_creative.videodraw.data.entity.AdVideoDrawResult",
                }, "void"),
        });
        m.put("com.hupu.adver_creative.videoCall.HpAdVideoCallCore", new Sig[]{ // HpAdVideoCallCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadSuccess", 1, new String[]{
                        "com.hupu.adver_creative.videoCall.data.entity.AdVideoCallResponse",
                }, "void"),
                new Sig("showDialog", 0, new String[]{}, "void"),
        });
        m.put("com.hupu.adver_creative.mine.MineTabAd", new Sig[]{ // MineTabAd
                new Sig("loadFromNet", 1, new String[]{"boolean"}, "void"),
        });
        m.put("com.hupu.adver_creative.csjmall.HpCsjAdCore", new Sig[]{ // HpCsjAdCore
                new Sig("loadFromNet", 0, new String[]{}, "void"),
                new Sig("loadAd", 1, new String[]{
                        "com.hupu.adver_creative.csjmall.data.AdCsjMallResult",
                }, "void"),
        });
        // ===== 生成内容结束 =====
        TABLE = Collections.unmodifiableMap(m);
    }

    /** 取某个目标类的全部方法指纹；没有登记过返回 null。 */
    public static Sig[] of(String className) {
        return TABLE.get(className);
    }

    private HookFingerprints() {
    }
}
