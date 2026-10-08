package com.hupux.xpnb;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * 运行时类加载探针（默认关闭）。
 *
 * 虎扑被网易易盾加固，静态分析要靠脱壳（本项目用扫 map_list 重建 dex 头部的方式完成）。
 * 这个探针提供另一条路：直接监听 ClassLoader#loadClass(String, boolean)，把虎扑自己
 * 加载出来的类名打到 logcat，用来验证脱壳结果、或在新版本里快速定位类名变化。
 *
 * 用法：打开设置里的开关，重启虎扑，然后 adb logcat -s HupuX | findstr PROBE
 */
public final class ClassProbe {

    private ClassProbe() {
    }

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    private static final int MAX_LOG = 5000;

    /** 已安装过（幂等）：配置补齐后可以再调一次，装上就直接返回。 */
    private static volatile boolean installed;

    /** 只关心虎扑自己的业务类，过滤 androidx / kotlin 之类的噪音。 */
    private static final String[] INTEREST_PREFIXES = {
            "com.hupu.adver", "com.hupu.games.main.splash"
    };

    public static void install(XposedInterface api, ClassLoader cl) {
        if (installed) {
            return;
        }
        if (!Config.classProbe) {
            Config.v("[探针] 未开启，跳过");
            return;
        }
        installed = true;
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            api.hook(loadClass)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (result instanceof Class) {
                            Object name = chain.getArg(0);
                            if (name instanceof String) {
                                record((String) name);
                            }
                        }
                        return result;
                    });
            HookUtil.installedCount++;
            Config.i("[探针] OK 已 hook java.lang.ClassLoader#loadClass(String, boolean)");
        } catch (Throwable t) {
            Config.e("[探针] hook ClassLoader#loadClass 失败", t);
        }
    }

    private static void record(String name) {
        if (SEEN.size() >= MAX_LOG || !SEEN.add(name)) {
            return;
        }
        for (String prefix : INTEREST_PREFIXES) {
            if (name.startsWith(prefix)) {
                Config.i("[探针] PROBE " + name);
                return;
            }
        }
    }
}
