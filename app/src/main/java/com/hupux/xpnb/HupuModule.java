package com.hupux.xpnb;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * HupuX —— 虎扑（com.hupu.games）去广告 Xposed 模块的入口类。
 *
 * 基于 libxposed 现代 Xposed API（minApiVersion=101 / targetApiVersion=102）实现，
 * 入口类由 APK 内的 META-INF/xposed/java_init.list 声明。
 *
 * 两个要点：
 *   1. 虎扑 8.2.63 被网易易盾加固，真实 dex 在运行期才由壳解密加载。
 *      本项目先离线脱壳（扫 map_list 重建 dex 头部，见 docs/REPORT.md），
 *      因此这里可以直接按「类名 + 方法名」精确 Hook。
 *   2. Hook 统一走 XposedModule 继承来的 hook(Executable)，
 *      并在 Hooker 里读取 Config 里的开关，做到「开关可配、逻辑集中」。
 */
public final class HupuModule extends XposedModule {

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Config.i("模块已注入 process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion()
                + " target=" + Config.TARGET_PACKAGE);
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!Config.TARGET_PACKAGE.equals(param.getPackageName())) {
            // scope.list 只写了虎扑，正常不会走到这里，留个保险。
            return;
        }

        // 读取模块 App 里的开关（跨进程共享）
        boolean remoteOk = false;
        try {
            SharedPreferences sp = getRemotePreferences(Config.PREFS_NAME);
            Config.load(sp);
            remoteOk = true;
        } catch (Throwable t) {
            Config.e("读取远程配置失败，使用默认值", t);
        }

        // 自报家门：不同的框架（LSPosed / FPA / LSPatch / HKP）能力有差异，
        // 把「当前框架 + 开关有没有真的读到」写进拦截日志，
        // 免 root 环境下出问题时不用连电脑看 logcat 也能定位。
        String frameworkInfo = "框架 " + getFrameworkName() + " " + getFrameworkVersion()
                + " / API " + getApiVersion() + " / 模块 " + BuildConfig.VERSION_NAME
                + (remoteOk ? "，已读到模块开关" : "，读不到模块开关，本次用默认配置");
        Config.i("[框架] " + frameworkInfo);
        AdsLog.info("框架", frameworkInfo);

        Config.i("开始安装 Hook firstPackage=" + param.isFirstPackage()
                + " | 开屏=" + Config.skipSplashAd
                + " SDK初始化拦截=" + Config.blockAdSdkInit
                + " 信息流=" + Config.blockFeedAd
                + " 浮窗=" + Config.blockFloatAd
                + " 视图兜底=" + Config.viewTreeSkip
                + " 探针=" + Config.classProbe);

        ClassLoader cl = param.getClassLoader();

        captureAppContext(this);

        // BuildConfig 里的版本号是编译期常量，会被直接内联，运行时零开销
        String moduleVersion = BuildConfig.VERSION_NAME;

        // 先登记 hook 规则，再由「延迟安装器」在目标类真正被加载出来时装上去。
        // 不能在这里直接 Class.forName：网易易盾的真实 dex 是运行期才交给 ClassLoader 的。
        AdHooks.register();
        LazyHookInstaller.install(this, cl);
        AgreementGate.install(this, moduleVersion);
        SplashSkipHelper.install(this);
        SettingsEntryInjector.install(this);
        ClassProbe.install(this, cl);

        Config.i("规则登记完成，等待目标类加载（当前已成功 "
                + HookUtil.installedCount + " 个方法）");
    }

    /**
     * 尽早拿到虎扑的 Context —— 拦截日志要通过 ContentResolver 写进模块 App，
     * 没有 Context 就没法上报。挂在 Application#attachBaseContext 上是最早的时机。
     */
    private void captureAppContext(XposedInterface api) {
        // 不同 Android 版本 / 不同框架下，attachBaseContext 的声明位置不一样：
        // 有的版本在 android.app.Application 上，有的只在 android.content.ContextWrapper 上。
        // 依次尝试，谁先成功就用谁。
        if (hookAttach(api, Application.class)) {
            return;
        }
        if (hookAttach(api, android.content.ContextWrapper.class)) {
            return;
        }
        Config.w("[日志] attachBaseContext 两种声明位置都没找到，改用 Activity 兜底");
    }

    private boolean hookAttach(XposedInterface api, Class<?> owner) {
        try {
            Method attach = owner.getDeclaredMethod("attachBaseContext", Context.class);
            api.hook(attach)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        // 挂在 ContextWrapper 上时会命中 Activity / Service 等，
                        // 只认 Application，确保拿到的是应用的 Context
                        if (self instanceof Application) {
                            AdsLog.setContext((Context) self);
                        }
                        return result;
                    });
            Config.i("[日志] OK 已挂钩 " + owner.getSimpleName() + "#attachBaseContext");
            return true;
        } catch (Throwable t) {
            Config.w("[日志] " + owner.getSimpleName() + "#attachBaseContext 不可用：" + t);
            return false;
        }
    }
}
