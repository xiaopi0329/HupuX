package com.hupux.xpnb;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * 开屏广告的兜底跳过：不依赖任何业务类名，纯靠视图树找「跳过」按钮并点击。
 *
 * 为什么需要它：虎扑 8.2.63 被网易易盾加固，广告 SDK 也会随版本变化。
 * AdHooks 里的精确 Hook 万一因为版本差异失效，这个兜底仍然能工作 —— 只要屏幕上
 * 出现「跳过」字样，就点它。这体现了加固环境下「静态 Hook + 运行时兜底」两条腿走路。
 */
public final class SplashSkipHelper {

    private SplashSkipHelper() {
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 开屏广告出现的时间不确定，按几个时间点重试。 */
    private static final long[] RETRY_DELAYS_MS = {200L, 600L, 1200L, 2000L, 3500L, 5500L};

    private static final String[] SKIP_KEYWORDS = {
            "跳过", "跳過", "关闭广告", "skip ad", "skip"
    };

    private static final int MAX_DEPTH = 32;

    /** 只为开屏阶段工作：进程起来后最多处理 N 次 Activity 的 onResume。 */
    private static final int MAX_HANDLED_RESUMES = 8;

    private static final AtomicInteger HANDLED = new AtomicInteger();

    /**
     * 每个 Activity 的「跳过」是否已点过。点过就不再对该页面做剩余的延迟扫描 ——
     * 否则一次 onResume 会排 6 次全树遍历，最多叠到 48 次，还会重复点击。
     * 用 WeakHashMap：页面销毁后条目跟着回收，不持有 Activity 引用。
     */
    private static final java.util.Map<Activity, Boolean> CLICKED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void install(XposedInterface api) {
        // 不再自己 hook Activity#onResume，登记进 ResumeHub 统一分发
        ResumeHub.add(activity -> {
            if (Config.viewTreeSkip) {
                schedule(activity);
            }
        });
        Config.i("[视图兜底] OK 已登记到 ResumeHub");
    }

    private static void schedule(final Activity activity) {
        if (CLICKED.containsKey(activity) || HANDLED.get() >= MAX_HANDLED_RESUMES) {
            return;
        }
        HANDLED.incrementAndGet();
        for (final long delay : RETRY_DELAYS_MS) {
            MAIN.postDelayed(() -> attempt(activity, delay), delay);
        }
    }

    private static void attempt(Activity activity, long delay) {
        try {
            if (!Config.viewTreeSkip || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            if (Boolean.TRUE.equals(CLICKED.get(activity))) {
                return; // 这个页面已经点过「跳过」，后面的延迟扫描直接跳过
            }
            if (activity.getWindow() == null) {
                return;
            }
            View root = activity.getWindow().getDecorView();
            if (root == null) {
                return;
            }
            View hit = findSkipView(root, 0);
            if (hit != null) {
                Config.i("[视图兜底] 命中「跳过」控件 delay=" + delay + "ms"
                        + " view=" + describe(hit)
                        + " activity=" + activity.getClass().getName());
                CLICKED.put(activity, Boolean.TRUE);
                click(hit);
            }
        } catch (Throwable t) {
            Config.e("[视图兜底] 扫描失败", t);
        }
    }

    private static View findSkipView(View view, int depth) {
        if (view == null || depth > MAX_DEPTH) {
            return null;
        }
        if (view.getVisibility() == View.VISIBLE && isSkipCandidate(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSkipView(group.getChildAt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isSkipCandidate(View view) {
        CharSequence text = view instanceof TextView ? ((TextView) view).getText() : null;
        if (text == null || text.length() == 0) {
            text = view.getContentDescription();
        }
        if (text == null || text.length() == 0) {
            return false;
        }
        String value = text.toString().trim().toLowerCase();
        boolean hit = false;
        for (String keyword : SKIP_KEYWORDS) {
            if (value.contains(keyword)) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            return false;
        }
        // 「跳过」通常是个小控件；避免误点占满屏幕的内容容器。
        int width = view.getWidth();
        if (width > 0) {
            int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
            if (width > screenWidth / 2) {
                return false;
            }
        }
        return true;
    }

    /** 找到控件后向上找可点击的父容器再点击，兼容「TextView 包在 FrameLayout 里」的写法。 */
    private static void click(View view) {
        View target = view;
        int guard = 0;
        while (target != null && !target.isClickable() && guard++ < 8) {
            if (!(target.getParent() instanceof View)) {
                break;
            }
            target = (View) target.getParent();
        }
        if (target == null) {
            target = view;
        }
        boolean ok = target.performClick();
        Config.i("[视图兜底] 已模拟点击 target=" + describe(target) + " 成功=" + ok);
    }

    private static String describe(View view) {
        StringBuilder sb = new StringBuilder(view.getClass().getSimpleName());
        if (view.getId() != View.NO_ID) {
            try {
                sb.append('#').append(view.getResources().getResourceEntryName(view.getId()));
            } catch (Throwable ignored) {
                sb.append('#').append(Integer.toHexString(view.getId()));
            }
        }
        CharSequence text = view instanceof TextView
                ? ((TextView) view).getText() : view.getContentDescription();
        if (text != null && text.length() > 0) {
            sb.append("(\"").append(text).append("\")");
        }
        return sb.toString();
    }
}
