package com.hupux.xpnb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 首次使用协议门。
 *
 * <p>规则：模块第一次作用到作用域应用（虎扑）时，进入应用先弹一份「仅供学习使用」的协议。</p>
 * <ul>
 *     <li>点「同意」→ 记住已同意的模块版本，正常进入应用；</li>
 *     <li>点「拒绝」/ 按返回 → 直接结束进程（闪退），且**不写入任何记录**，
 *         所以下次进入还会再弹一次。</li>
 * </ul>
 *
 * <p>记录写在<b>虎扑自己的</b> SharedPreferences 里：Hook 代码运行在虎扑进程中、
 * 用的是虎扑的身份，读写自己的数据即可，不依赖模块 App 是否安装。</p>
 *
 * <p>只记一个「已同意」布尔值，不跟模块版本号绑定：模块版本号从 1.0.1 起改成
 * 构建时间戳，每次编译都会变，跟着版本走会导致每装一次就弹一次，那就不是
 * 「首次进入时弹一次」了。</p>
 */
public final class AgreementGate {

    private static final String PREFS_NAME = "hupux_agreement";
    private static final String KEY_AGREED = "agreed";

    private static final String TITLE = "使用协议";
    private static final String MESSAGE =
            "「HupuX」是一个用于学习 Android 逆向与 Xposed 模块开发的实验性项目，"
                    + "在继续使用前请确认你已阅读并同意以下条款：\n\n"
                    + "1. 本模块仅供个人学习、研究使用，请勿用于任何商业用途或非法用途；\n"
                    + "2. 请勿传播、分发本模块，或分发由本模块产生的任何修改版应用；\n"
                    + "3. 使用本模块所产生的一切后果，由使用者自行承担。\n\n"
                    + "点击「同意」表示你已理解并接受上述条款；"
                    + "点击「拒绝」将立即退出应用，下次进入会再次询问。";
    private static final String BUTTON_AGREE = "同意并继续";
    private static final String BUTTON_REJECT = "拒绝并退出";

    /** 同一次进程启动只处理一次。 */
    private static volatile boolean handled;

    private AgreementGate() {
    }

    public static void install(XposedInterface api, final String moduleVersion) {
        try {
            Method onResume = Activity.class.getDeclaredMethod("onResume");
            api.hook(onResume)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            try {
                                maybePrompt((Activity) self, moduleVersion);
                            } catch (Throwable t) {
                                Config.e("[协议] 处理失败", t);
                            }
                        }
                        return result;
                    });
            Config.i("[协议] OK 已挂钩 Activity#onResume");
        } catch (Throwable t) {
            Config.e("[协议] 挂钩失败", t);
        }
    }

    private static void maybePrompt(Activity activity, String moduleVersion) {
        if (handled) {
            return;
        }
        if (!Config.TARGET_PACKAGE.equals(activity.getPackageName())) {
            return;
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        if (hasAgreed(activity)) {
            handled = true;
            Config.i("[协议] 已同意过，直接进入（模块版本 " + moduleVersion + "）");
            return;
        }

        handled = true;
        Config.i("[协议] 首次使用（模块版本 " + moduleVersion + "），弹出协议");
        showDialog(activity, moduleVersion);
    }

    private static void showDialog(final Activity activity, final String moduleVersion) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(TITLE);
        builder.setMessage(MESSAGE);
        builder.setCancelable(false);
        builder.setPositiveButton(BUTTON_AGREE, (dialog, which) -> {
            markAgreed(activity);
            Config.i("[协议] 用户已同意（版本 " + moduleVersion + "）");
            AdsLog.info("协议", "用户已同意使用协议（版本 " + moduleVersion + "）");
        });
        builder.setNegativeButton(BUTTON_REJECT, (dialog, which) -> {
            Config.w("[协议] 用户拒绝，退出应用");
            exitApp(activity);
        });

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        // 返回键也按「拒绝」处理：不记录，下次继续弹
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                Config.w("[协议] 返回键 = 拒绝，退出应用");
                exitApp(activity);
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean hasAgreed(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_AGREED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markAgreed(Context ctx) {
        try {
            prefs(ctx).edit().putBoolean(KEY_AGREED, true).apply();
        } catch (Throwable t) {
            Config.e("[协议] 写入同意记录失败", t);
        }
    }

    /** 拒绝后直接结束进程，对外表现就是「闪退」。 */
    private static void exitApp(Activity activity) {
        try {
            activity.finishAffinity();
        } catch (Throwable ignored) {
        }
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }
}
