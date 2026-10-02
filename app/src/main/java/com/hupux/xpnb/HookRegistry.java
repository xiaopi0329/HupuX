package com.hupux.xpnb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * Hook 点登记表。
 *
 * <p>虎扑被网易易盾加固，业务类要等壳在运行期把真实 dex 交给 ClassLoader 之后才存在，
 * 因此不能在 {@code onPackageReady} 里直接 {@code Class.forName}。这里只登记
 * 「类名 → 要 hook 的方法」，真正的安装交给 {@link LazyHookInstaller} 在目标类
 * 被加载出来的那一刻执行。</p>
 */
public final class HookRegistry {

    /** 一条 hook 规则。 */
    public static final class Spec {
        public final String className;
        public final String methodName;
        public final XposedInterface.Hooker hooker;
        public final String feature;

        Spec(String className, String methodName,
             XposedInterface.Hooker hooker, String feature) {
            this.className = className;
            this.methodName = methodName;
            this.hooker = hooker;
            this.feature = feature;
        }
    }

    private static final Map<String, List<Spec>> SPECS = new LinkedHashMap<>();

    private HookRegistry() {
    }

    public static synchronized void register(String className, String methodName,
                                             XposedInterface.Hooker hooker, String feature) {
        SPECS.computeIfAbsent(className, k -> new ArrayList<>())
                .add(new Spec(className, methodName, hooker, feature));
    }

    /** 该类是不是我们关心的目标类。 */
    public static synchronized boolean isTarget(String className) {
        return SPECS.containsKey(className);
    }

    /** 所有还没装上 hook 的目标类名。 */
    public static synchronized List<String> targets() {
        return new ArrayList<>(SPECS.keySet());
    }

    /**
     * 给一个已经加载出来的类安装它对应的全部 hook。
     *
     * @return 本次成功安装的方法数
     */
    public static synchronized int applyTo(XposedInterface api, Class<?> clazz) {
        List<Spec> specs = SPECS.get(clazz.getName());
        if (specs == null) {
            return 0;
        }
        int ok = 0;
        for (Spec spec : specs) {
            ok += HookUtil.hookOnClass(api, clazz, spec.methodName, spec.hooker, spec.feature);
        }
        return ok;
    }
}
