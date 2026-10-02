package com.hupux.xpnb;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 反射 + Hook 的公共工具。
 */
public final class HookUtil {

    private HookUtil() {
    }

    /** 记录成功安装的 Hook 数量，用于汇总日志。 */
    public static int installedCount = 0;

    /**
     * 按「类名 + 方法名」安装 Hook，会一次性 hook 掉所有同名重载。
     *
     * 注意：必须用目标包自己的 ClassLoader 去加载类。虎扑被网易易盾加固，
     * 真实业务类只存在于它自己的 ClassLoader 里，用默认的 Class.forName 是找不到的。
     */
    public static void hookByName(XposedInterface api, ClassLoader cl,
                                  String className, String methodName,
                                  XposedInterface.Hooker hooker, String feature) {
        Class<?> clazz;
        try {
            clazz = Class.forName(className, false, cl);
        } catch (Throwable t) {
            Config.w("[" + feature + "] 找不到类 " + className + " -> " + t);
            return;
        }

        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            Config.w("[" + feature + "] 枚举方法失败 " + className + " -> " + t);
            return;
        }

        int ok = 0;
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            try {
                api.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                ok++;
            } catch (Throwable t) {
                Config.e("[" + feature + "] hook 失败 " + className + "#"
                        + methodName + describe(m), t);
            }
        }

        installedCount += ok;
        if (ok > 0) {
            Config.i("[" + feature + "] OK 已 hook " + className + "#" + methodName
                    + "（" + ok + " 个重载）");
        } else {
            Config.w("[" + feature + "] MISS 未找到 " + className + "#" + methodName);
        }
    }

    public static String describe(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i].getName());
        }
        return sb.append(")").toString();
    }

    /**
     * 给一个**已经拿到的** Class 安装 hook（会一次性 hook 掉所有同名重载）。
     *
     * <p>与 {@link #hookByName} 的区别：这里不做类查找，因此可以用在
     * 「类刚被 ClassLoader 加载出来」的时机，绕开加固壳的加载时序问题。</p>
     *
     * @return 本次成功安装的方法数
     */
    public static int hookOnClass(XposedInterface api, Class<?> clazz,
                                  String methodName,
                                  XposedInterface.Hooker hooker, String feature) {
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            Config.w("[" + feature + "] 枚举方法失败 " + clazz.getName() + " -> " + t);
            return 0;
        }

        int ok = 0;
        for (Method m : methods) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            try {
                api.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                ok++;
            } catch (Throwable t) {
                Config.e("[" + feature + "] hook 失败 " + clazz.getName() + "#"
                        + methodName + describe(m), t);
            }
        }

        installedCount += ok;
        if (ok > 0) {
            Config.i("[" + feature + "] OK 已 hook " + clazz.getName() + "#" + methodName
                    + "（" + ok + " 个重载）");
            AdsLog.hooked(feature, shortClassName(clazz) + "#" + methodName, ok);
        } else {
            Config.w("[" + feature + "] MISS " + clazz.getName() + " 里没有 " + methodName
                    + "，实际方法：" + listMethods(clazz));
            AdsLog.miss(feature, shortClassName(clazz) + "#" + methodName);
        }
        return ok;
    }

    /** 只保留类名最后一段，日志里看着短一些。 */
    private static String shortClassName(Class<?> clazz) {
        String name = clazz.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** 列出类里所有方法名，便于和静态分析结果对照。 */
    private static String listMethods(Class<?> clazz) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(m.getName());
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }

    public static String shortName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
