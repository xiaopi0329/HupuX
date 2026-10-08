package com.hupux.xpnb;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

/**
 * 延迟安装器 —— 解决网易易盾加固下「类还不存在」的问题。
 *
 * <p>思路：不去猜类什么时候出现，而是挂钩 {@link ClassLoader#loadClass(String, boolean)}。
 * 只要有任何 ClassLoader 加载出我们关心的目标类，就立刻拿到它的 {@link Class} 对象并装 hook。
 * 这样做同时兼容两种情况：</p>
 * <ul>
 *     <li>真实 dex 由壳的 ClassLoader 加载（不是 App 的默认 ClassLoader）；</li>
 *     <li>加载时机晚于 {@code onPackageReady}。</li>
 * </ul>
 */
public final class LazyHookInstaller {

    private LazyHookInstaller() {
    }

    /** 已经处理过的类名，避免重复安装。 */
    private static final Set<String> HANDLED = ConcurrentHashMap.newKeySet();

    /** hook 过程中见过的 ClassLoader，兜底重试时用来再试一次 Class.forName。 */
    private static final List<ClassLoader> SEEN_LOADERS = new CopyOnWriteArrayList<>();

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 兜底重试：启动阶段每 1 秒一次、共 60 次（1 分钟）。 */
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final int MAX_RETRY = 60;

    /**
     * 慢速重试间隔：快试阶段结束后转到这个节奏，**不放弃**。
     *
     * <p>横幅/弹窗/动效这些场景类是懒加载的 —— 用户翻到对应页面才加载，
     * 很可能晚于启动后 1 分钟。旧逻辑 60 次后就停了，晚到的类永远装不上钩子。</p>
     */
    private static final long SLOW_RETRY_INTERVAL_MS = 5000L;

    /** 慢试阶段最多再试 30 分钟（覆盖一次正常的使用时长）。 */
    private static final int MAX_SLOW_RETRY = 360;

    private static int retryCount = 0;
    private static int slowRetryCount = 0;
    private static boolean installed = false;
    private static boolean announcedSlow = false;
    private static boolean scanning = false;
    private static XposedInterface api;

    public static void install(XposedInterface apiIn, ClassLoader appClassLoader) {
        if (installed) {
            return;
        }
        installed = true;
        api = apiIn;
        remember(appClassLoader);

        // 这里刻意**不**挂钩 ClassLoader#loadClass，原因有两条：
        //   1) 实测（LSPosed 与 FPA 上都是）目标类全部由下面的定时重试命中，
        //      loadClass 那条路从未真正捕获过——因为 Java 层的 loadClass 只覆盖
        //      显式调用，ART 在链接/校验阶段隐式解析类时不会走它；
        //   2) loadClass 是极热路径，在 FPA(LSPlant) 上挂钩它会让虎扑卡死在启动阶段
        //      （主线程停在 Hook 安装之后，进程活着但没有窗口）。
        // 需要时可以在设置里打开「运行时类加载探针」用另一条路观察类加载。
        Config.i("[延迟安装] 不挂钩 ClassLoader#loadClass，改用定时重试解析目标类");

        // 兜底：有些类可能在我们 hook 之前就已经加载，定时重试
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                retryResolve();
            }
        }, RETRY_INTERVAL_MS);

        // 界面恢复时补一次扫描：场景类多在进页面那一刻才加载，
        // 比起干等定时器，onResume 是更贴近真实加载时机的触发点。
        ResumeHub.add(activity -> kick());
    }

    /**
     * 立刻补一轮扫描（幂等、可重入安全）。
     *
     * <p>由 ResumeHub 在每次 Activity 恢复时调用：用户翻到带横幅/弹窗的页面，
     * 类刚被加载出来，这一轮就能把钩子装上，不必等下一个慢试周期。</p>
     */
    public static void kick() {
        if (!installed || api == null || scanning) {
            return;
        }
        List<String> pending = HookRegistry.targets();
        boolean hasPending = false;
        for (String name : pending) {
            if (!HANDLED.contains(name)) {
                hasPending = true;
                break;
            }
        }
        if (!hasPending) {
            return;
        }
        scanning = true;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    scanPending();
                } finally {
                    scanning = false;
                }
                // 这里只做安装，不推进重试计数，也不重排定时器
            }
        });
    }

    private static void remember(Object loader) {
        if (loader instanceof ClassLoader && !SEEN_LOADERS.contains(loader)) {
            SEEN_LOADERS.add((ClassLoader) loader);
        }
    }

    /**
     * 某个类刚被加载出来。命中目标就装 hook。
     *
     * <p>注意这里不能直接在当前调用栈里做重活：此刻正处于 ClassLoader 的加载临界区，
     * 所以丢到主线程队列里执行，既避免死锁，也几乎立刻生效。</p>
     */
    private static void onClassLoaded(final XposedInterface api, final Class<?> clazz) {
        final String name = clazz.getName();
        if (!HookRegistry.isTarget(name) || !HANDLED.add(name)) {
            return;
        }
        Config.i("[延迟安装] 目标类已加载：" + name);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                apply(api, clazz);
            }
        });
    }

    private static void apply(XposedInterface api, Class<?> clazz) {
        int n = HookRegistry.applyTo(api, clazz);
        if (n > 0) {
            Config.i("[延迟安装] 已为 " + clazz.getName() + " 安装 " + n + " 个方法");
        } else {
            Config.w("[延迟安装] " + clazz.getName() + " 没找到可 hook 的方法");
        }
    }

    /**
     * 扫一轮还没命中的目标类，谁出现了就给谁装 hook。
     *
     * @return 是否全部处理完
     */
    private static boolean scanPending() {
        XposedInterface current = api;
        if (current == null) {
            return false;
        }
        List<String> pending = HookRegistry.targets();
        List<String> unresolved = new ArrayList<>();
        for (String name : pending) {
            if (HANDLED.contains(name)) {
                continue;
            }
            boolean hit = false;
            for (ClassLoader loader : Collections.unmodifiableList(SEEN_LOADERS)) {
                try {
                    Class<?> clazz = Class.forName(name, false, loader);
                    if (HANDLED.add(name)) {
                        Config.i("[延迟安装] 兜底重试命中：" + name
                                + " via " + loader.getClass().getName());
                        apply(current, clazz);
                    }
                    hit = true;
                    break;
                } catch (Throwable ignored) {
                    // 这个 loader 没有，换下一个
                }
            }
            if (!hit) {
                unresolved.add(name);
            }
        }

        // 按写死的类名一个都没找到 —— 这时候才值得动用 DexKit：
        // 它不看类名，直接翻宿主 dex 用「方法长什么样」把目标认出来。
        if (!unresolved.isEmpty()) {
            resolveByDexKit(current, unresolved);
        }

        for (String name : pending) {
            if (!HANDLED.contains(name)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 交给 {@link DexKitResolver} 按方法指纹找真实类名。
     *
     * <p>重活全在它的后台线程上，这里只是下单，不会卡住主线程。</p>
     */
    private static void resolveByDexKit(final XposedInterface current, List<String> unresolved) {
        if (!Config.dexkitResolve || SEEN_LOADERS.isEmpty()) {
            return;
        }
        DexKitResolver.resolveAsync(SEEN_LOADERS.get(0), unresolved,
                new DexKitResolver.Listener() {
                    @Override
                    public void onResolved(java.util.Map<String, String> resolved) {
                        for (java.util.Map.Entry<String, String> entry : resolved.entrySet()) {
                            String registered = entry.getKey();
                            String real = entry.getValue();
                            if (HANDLED.contains(registered)) {
                                continue;
                            }
                            Class<?> clazz = loadAnywhere(real);
                            if (clazz == null) {
                                continue;
                            }
                            // 关键：按**登记名**取规则，真实类名只用来拿 Class
                            int n = HookRegistry.applyTo(current, clazz, registered);
                            if (n > 0 && HANDLED.add(registered)) {
                                Config.i("[延迟安装] DexKit 认领 " + registered + " -> " + real
                                        + "，装上 " + n + " 个方法");
                                AdsLog.info("延迟安装", "DexKit 认领 " + registered + "（真实类名 "
                                        + real + "），装上 " + n + " 个方法");
                            } else {
                                Config.w("[延迟安装] DexKit 认下 " + real
                                        + " 但没有匹配到可 hook 的方法（登记名 " + registered + "）");
                            }
                        }
                    }
                });
    }

    /** 用所有见过的 ClassLoader 找真实类名的 Class 对象。 */
    private static Class<?> loadAnywhere(String className) {
        for (ClassLoader loader : Collections.unmodifiableList(SEEN_LOADERS)) {
            try {
                return Class.forName(className, false, loader);
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        Config.w("[延迟安装] DexKit 认出的类加载不出来：" + className);
        return null;
    }

    /**
     * 兜底重试：快试 1 分钟（每秒）→ 转慢试（每 5 秒）→ 全部命中为止。
     *
     * <p>旧版本在 60 次后直接放弃，导致懒加载的场景类（横幅/弹窗/浮泡/动效）
     * 如果在启动 1 分钟后才被用户翻到，就永远装不上钩子。现在快试结束后转入慢试，
     * 慢试最多再持续 {@link #MAX_SLOW_RETRY} 个周期（约 30 分钟）。</p>
     */
    private static void retryResolve() {
        boolean allDone;
        try {
            allDone = scanPending();
        } catch (Throwable t) {
            Config.e("[延迟安装] 扫描失败", t);
            allDone = false;
        }

        if (allDone) {
            List<String> pending = HookRegistry.targets();
            Config.i("[延迟安装] 全部目标类已处理，共 " + pending.size() + " 个");
            AdsLog.info("模块", "全部 " + pending.size() + " 个广告类已装好 Hook");
            return;
        }

        if (retryCount < MAX_RETRY) {
            retryCount++;
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryResolve();
                }
            }, RETRY_INTERVAL_MS);
            return;
        }

        // 快试阶段结束，仍未全部命中 —— 转入慢试，别放弃
        if (!announcedSlow) {
            announcedSlow = true;
            Config.i("[延迟安装] 快试 " + MAX_RETRY + " 次后仍有懒加载类未出现，转入慢试（每 "
                    + (SLOW_RETRY_INTERVAL_MS / 1000) + " 秒一次）：" + pendingNames());
        }
        if (slowRetryCount < MAX_SLOW_RETRY) {
            slowRetryCount++;
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryResolve();
                }
            }, SLOW_RETRY_INTERVAL_MS);
        } else {
            Config.w("[延迟安装] 慢试 " + MAX_SLOW_RETRY + " 个周期后仍有目标类未出现："
                    + pendingNames());
        }
    }

    private static List<String> pendingNames() {
        List<String> out = new ArrayList<>();
        for (String name : HookRegistry.targets()) {
            if (!HANDLED.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }
}
