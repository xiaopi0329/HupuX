package com.hupux.xpnb;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
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

    /** 兜底重试：每 1 秒一次，最多 60 次（1 分钟）。 */
    private static final long RETRY_INTERVAL_MS = 1000L;
    private static final int MAX_RETRY = 60;

    private static int retryCount = 0;
    private static boolean installed = false;

    public static void install(XposedInterface api, ClassLoader appClassLoader) {
        if (installed) {
            return;
        }
        installed = true;
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
                retryResolve(api);
            }
        }, RETRY_INTERVAL_MS);
    }

    private static Method findLoadClass(Class<?>... params) {
        try {
            Method m = ClassLoader.class.getDeclaredMethod("loadClass", params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            Config.w("[延迟安装] 找不到 ClassLoader#loadClass 重载 -> " + t);
            return null;
        }
    }

    private static void hookLoadClass(final XposedInterface api, Method loadClass) {
        if (loadClass == null) {
            return;
        }
        try {
            api.hook(loadClass)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Class) {
                            remember(chain.getThisObject());
                            onClassLoaded(api, (Class<?>) result);
                        }
                        return result;
                    });
            Config.i("[延迟安装] OK 已挂钩 ClassLoader#loadClass"
                    + HookUtil.describe(loadClass));
        } catch (Throwable t) {
            Config.e("[延迟安装] 挂钩 ClassLoader#loadClass 失败", t);
        }
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

    /** 兜底重试：用见过的 ClassLoader 再试一次那些还没命中的目标类。 */
    private static void retryResolve(final XposedInterface api) {
        retryCount++;
        List<String> pending = HookRegistry.targets();
        for (String name : pending) {
            if (HANDLED.contains(name)) {
                continue;
            }
            for (ClassLoader loader : Collections.unmodifiableList(SEEN_LOADERS)) {
                try {
                    Class<?> clazz = Class.forName(name, false, loader);
                    if (HANDLED.add(name)) {
                        Config.i("[延迟安装] 兜底重试命中：" + name
                                + " via " + loader.getClass().getName());
                        apply(api, clazz);
                    }
                    break;
                } catch (Throwable ignored) {
                    // 这个 loader 没有，换下一个
                }
            }
        }

        boolean allDone = true;
        for (String name : pending) {
            if (!HANDLED.contains(name)) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            Config.i("[延迟安装] 全部目标类已处理，共 " + pending.size() + " 个");
            AdsLog.info("模块", "全部 " + pending.size() + " 个广告类已装好 Hook");
            return;
        }
        if (retryCount < MAX_RETRY) {
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    retryResolve(api);
                }
            }, RETRY_INTERVAL_MS);
        } else {
            Config.w("[延迟安装] 重试 " + MAX_RETRY + " 次后仍有目标类未出现："
                    + pending);
        }
    }
}
