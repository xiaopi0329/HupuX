package com.hupux.xpnb;

import android.app.Activity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;

/**
 * Activity#onResume 的单点分发器。
 *
 * <p>协议门（{@link AgreementGate}）、视图兜底（{@link SplashSkipHelper}）、
 * 设置入口（{@link SettingsEntryInjector}）原本各自 hook 一次 onResume —— 同一个热路径方法
 * 被挂了三条拦截链，每次界面恢复都要走三遍。这里改成只 hook 一次，登记的任务依次分发，
 * 每个任务独立 try/catch：一个任务抛异常不会影响其它任务。</p>
 *
 * <p>分发顺序 = 注册顺序，均为 {@code chain.proceed()} 之后执行（与原先三个钩子的语义一致）。</p>
 */
public final class ResumeHub {

    /** 一个在 Activity 恢复时执行的任务。 */
    public interface Task {
        void onResume(Activity activity);
    }

    private static final List<Task> TASKS = new CopyOnWriteArrayList<>();

    private static boolean installed;

    private ResumeHub() {
    }

    /** 注册任务。install 之前之后都可以调。 */
    public static void add(Task task) {
        if (task != null) {
            TASKS.add(task);
        }
    }

    /** hook android.app.Activity#onResume，幂等。 */
    public static synchronized void install(XposedInterface api) {
        if (installed) {
            return;
        }
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            api.hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            Activity activity = (Activity) self;
                            // 兜底：万一 Application#attachBaseContext 那条路没挂上，
                            // 这里也能把 Context 交给拦截日志上报用
                            AdsLog.setContext(activity.getApplicationContext());
                            for (Task task : TASKS) {
                                try {
                                    task.onResume(activity);
                                } catch (Throwable t) {
                                    Config.e("[Resume] 任务执行失败", t);
                                }
                            }
                        }
                        return result;
                    });
            installed = true;
            HookUtil.installedCount++;
            Config.i("[Resume] OK 已挂钩 Activity#onResume（单点分发，任务数=" + TASKS.size() + "）");
        } catch (Throwable t) {
            Config.e("[Resume] 挂钩 Activity#onResume 失败", t);
        }
    }
}
