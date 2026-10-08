package com.hupux.xpnb;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

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

    /** 供 attachBaseContext 拿到 Context 后补装探针用（配置可能那时才读到）。 */
    private static XposedInterface sApi;
    private static ClassLoader sClassLoader;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Config.i("模块已注入 process=" + param.getProcessName()
                + " systemServer=" + param.isSystemServer()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion()
                + " target=" + Config.TARGET_PACKAGE);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!Config.TARGET_PACKAGE.equals(param.getPackageName())) {
            // scope.list 只写了虎扑，正常不会走到这里，留个保险。
            return;
        }

        // 读取模块 App 里的开关（跨进程共享）
        boolean remoteOk = false;
        try {
            SharedPreferences sp = getRemotePreferences(Config.PREFS_NAME);
            Config.load(sp);
            // FPA 3.8 上 getRemotePreferences 不抛异常，但返回的是一份**空配置**
            // （getAll() 为空），所有 key 都读不到 → 用户改的开关全部失效、
            // 默默退回默认值。这里把「空」显式识别出来，交给 Provider 兜底通道补救。
            Config.remoteEmpty = sp.getAll().isEmpty();
            Config.configSource = Config.remoteEmpty ? "default" : "remote";
            remoteOk = !Config.remoteEmpty;
        } catch (Throwable t) {
            Config.e("读取远程配置失败，使用默认值", t);
        }

        // 自报家门：不同的框架（LSPosed / FPA / LSPatch / HKP）能力有差异，
        // 把「当前框架 + 开关有没有真的读到」写进拦截日志，
        // 免 root 环境下出问题时不用连电脑看 logcat 也能定位。
        String frameworkInfo = "框架 " + getFrameworkName() + " " + getFrameworkVersion()
                + " / API " + getApiVersion() + " / 模块 " + BuildConfig.VERSION_NAME
                + (remoteOk ? "，已读到模块开关"
                : "，远程配置为空(FPA 已知问题)，改用 Provider 兜底");
        Config.i("[框架] " + frameworkInfo);
        AdsLog.info("框架", frameworkInfo);

        // Config.loadFromProvider 依赖虎扑自己的 Context（attachBaseContext 时拿到），
        // 那条钩子在 captureAppContext 里挂上后会顺带调用一次。

        Config.i("开始安装 Hook firstPackage=" + param.isFirstPackage()
                + " | 开屏=" + Config.skipSplashAd
                + " SDK初始化拦截=" + Config.blockAdSdkInit
                + " 信息流=" + Config.blockFeedAd
                + " 浮窗=" + Config.blockFloatAd
                + " 场景广告=" + Config.blockSceneAd
                + " 视图兜底=" + Config.viewTreeSkip
                + " 探针=" + Config.classProbe);

        ClassLoader cl = param.getClassLoader();
        sApi = this;
        sClassLoader = cl;

        captureAppContext(this);

        // BuildConfig 里的版本号是编译期常量，会被直接内联，运行时零开销
        String moduleVersion = BuildConfig.VERSION_NAME;

        // 先登记 hook 规则，再由「延迟安装器」在目标类真正被加载出来时装上去。
        // 不能在这里直接 Class.forName：网易易盾的真实 dex 是运行期才交给 ClassLoader 的。
        AdHooks.register();
        LazyHookInstaller.install(this, cl);

        // Activity#onResume 只挂一条拦截链，下面三个组件登记任务进来分发
        ResumeHub.install(this);
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
                            Context ctx = (Context) self;
                            AdsLog.setContext(ctx);
                            // FPA 上远程配置是空的：拿到 Context 后立刻用
                            // Provider 兜底通道把真正的开关值拉进来
                            Config.loadFromProvider(ctx);
                            // 探针的安装决策在 onPackageReady 时是按默认值做的，
                            // 配置补齐后补装一次（install 自身幂等）
                            if (sApi != null && sClassLoader != null) {
                                ClassProbe.install(sApi, sClassLoader);
                            }
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
