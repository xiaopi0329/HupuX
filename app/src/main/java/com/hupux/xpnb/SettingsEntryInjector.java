package com.hupux.xpnb;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 把模块入口注入到虎扑自己的「设置」页里。
 *
 * <p>虎扑的设置页是 {@code com.hupu.user.ui.SetupActivity}，布局结构为
 * {@code ScrollView → LinearLayoutCompat(id = ll_content) → 若干条目组}。
 * 我们不去猜它的数据模型，也不改它的 Adapter，而是等页面 onResume（视图树已就绪）后，
 * 在 {@code ll_content} 里插入一行自己的条目，点击即打开模块的设置界面。</p>
 *
 * <p>为了让它看起来像虎扑原生的条目，样式（字号、颜色、字体、行高、背景）都从
 * 同一容器里已有条目上「抄」过来，而不是硬编码。</p>
 */
public final class SettingsEntryInjector {

    /** 虎扑设置页。 */
    private static final String SETTINGS_ACTIVITY = "com.hupu.user.ui.SetupActivity";
    /** 设置页里承载所有条目的容器 id 名（运行时按名字取 id，避免硬编码资源号）。 */
    private static final String CONTENT_ID_NAME = "ll_content";
    /** 标题栏右侧的容器（放返回键 / 右侧操作），我们把入口放这里，贴屏幕右边。 */
    private static final String RIGHT_SLOT_ID_NAME = "ll_right";
    /** 用这一条原生条目当样式样板。 */
    private static final String STYLE_SAMPLE_TEXT = "账号安全";
    /** 「退出登录」所在的那一组，我们把入口插在它前面。 */
    private static final String BEFORE_GROUP_TEXT = "退出登录";
    /** 自己插入的行的标记，避免重复插入。 */
    private static final String ROW_TAG = "hupux_settings_entry";

    /** 模块自己的包名与设置界面。 */
    private static final String MODULE_PACKAGE = "com.hupux.xpnb";
    private static final String MODULE_ACTIVITY = "com.hupux.xpnb.MainActivity";

    private static final String ENTRY_TITLE = "HupuX";
    private static final String ENTRY_SUBTITLE = "去广告模块设置（开屏 / 信息流 / 浮窗）";
    /** 标题栏里显示的短文案（位置窄，宜短）。 */
    private static final String TOOLBAR_TEXT = "HupuX";

    private SettingsEntryInjector() {
    }

    public static void install(XposedInterface api) {
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
                            // 兜底：万一 Application#attachBaseContext 没挂上，
                            // 这里也能把 Context 交给日志上报用
                            AdsLog.setContext(activity.getApplicationContext());
                            if (SETTINGS_ACTIVITY.equals(activity.getClass().getName())) {
                                try {
                                    inject(activity);
                                } catch (Throwable t) {
                                    Config.e("[设置入口] 注入失败", t);
                                }
                            }
                        }
                        return result;
                    });
            HookUtil.installedCount++;
            Config.i("[设置入口] OK 已挂钩 " + SETTINGS_ACTIVITY + " 的 onResume");
        } catch (Throwable t) {
            Config.e("[设置入口] 挂钩失败", t);
        }
    }

    private static void inject(Activity activity) {
        // 首选：标题栏右侧「设置」标题的右边、贴着屏幕边缘
        ViewGroup rightSlot = findViewById(activity, RIGHT_SLOT_ID_NAME);
        if (rightSlot != null) {
            if (rightSlot.findViewWithTag(ROW_TAG) == null) {
                injectToolbarEntry(activity, rightSlot);
            }
            return;
        }

        // 兜底：万一某个版本没有 ll_right，就退回列表里插入一行
        int contentId = activity.getResources()
                .getIdentifier(CONTENT_ID_NAME, "id", activity.getPackageName());
        if (contentId == 0) {
            Config.w("[设置入口] 找不到容器 id " + CONTENT_ID_NAME + "，跳过");
            return;
        }
        View root = activity.findViewById(contentId);
        if (!(root instanceof ViewGroup)) {
            Config.w("[设置入口] 容器不是 ViewGroup，跳过");
            return;
        }
        ViewGroup container = (ViewGroup) root;

        if (container.findViewWithTag(ROW_TAG) != null) {
            return; // 已经插过了
        }

        TextView sample = findSampleTextView(container);
        if (sample == null) {
            Config.w("[设置入口] 没找到样式样板，使用默认样式");
        }

        LinearLayout row = buildRow(activity, sample);
        row.setTag(ROW_TAG);

        int index = indexBeforeGroup(container, BEFORE_GROUP_TEXT);
        View ref = index < container.getChildCount()
                ? container.getChildAt(index) : null;
        if (ref != null && ref.getBackground() != null) {
            // 抄原生的条目背景，这样点击水波纹/分割线观感一致
            row.setBackground(ref.getBackground());
        }
        container.addView(row, index, buildLayoutParams(ref));

        Config.i("[设置入口] 已把「" + ENTRY_TITLE + "」插入设置页 index=" + index
                + "，点击可打开模块设置");
    }

    private static ViewGroup findViewById(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        if (id == 0) {
            return null;
        }
        View v = activity.findViewById(id);
        return v instanceof ViewGroup ? (ViewGroup) v : null;
    }

    /**
     * 往标题栏右侧塞一个「虎扑助手」按钮。
     *
     * <p>样式取自标题栏里的标题 TextView（字号 / 颜色 / 字体），点击时用系统自带的
     * 无边界水波纹，所以看起来就是虎扑自己的标题栏按钮。</p>
     */
    private static void injectToolbarEntry(Activity activity, ViewGroup slot) {
        TextView title = findByText(activity.getWindow().getDecorView(), "设置");

        TextView button = new TextView(activity);
        button.setText(TOOLBAR_TEXT);
        button.setTag(ROW_TAG);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        button.setClickable(true);
        button.setFocusable(true);
        button.setSingleLine(true);

        if (title != null) {
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, title.getTextSize());
            button.setTextColor(title.getCurrentTextColor());
            button.setTypeface(title.getTypeface());
        } else {
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        }

        // 右侧留一点内边距，避免文字贴死屏幕边缘
        button.setPadding(dp(activity, 8), 0, dp(activity, 12), 0);

        int ripple = borderlessRipple(activity);
        if (ripple != 0) {
            button.setBackgroundResource(ripple);
        }

        // 铺满整个右侧槽位、文字靠右对齐 —— 这样一定贴着屏幕右边
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        button.setLayoutParams(lp);

        button.setOnClickListener(v -> openModuleSettings(activity));
        slot.addView(button);

        Config.i("[设置入口] 已把「" + TOOLBAR_TEXT + "」注入标题栏右侧（ll_right），贴屏幕右边");
    }

    /** 取系统自带的「无边界可点击水波纹」背景。 */
    private static int borderlessRipple(Activity activity) {
        TypedValue out = new TypedValue();
        boolean ok = activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, out, true);
        return ok ? out.resourceId : 0;
    }

    private static LinearLayout buildRow(Activity activity, TextView sample) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        int padH = dp(activity, 16);
        int padV = dp(activity, 8);
        row.setPadding(padH, padV, padH, padV);

        TextView title = new TextView(activity);
        title.setText(ENTRY_TITLE);

        TextView subtitle = new TextView(activity);
        subtitle.setText(ENTRY_SUBTITLE);

        // 从原生条目上抄样式，保证观感一致
        if (sample != null) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.getTextSize());
            title.setTextColor(sample.getCurrentTextColor());
            title.setTypeface(sample.getTypeface());
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.getTextSize() * 0.78f);
            subtitle.setTextColor(sample.getCurrentTextColor() & 0x00FFFFFF | 0x99000000);
        } else {
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        }

        row.addView(title);
        row.addView(subtitle);

        View.OnClickListener open = v -> openModuleSettings(activity);
        row.setOnClickListener(open);
        title.setOnClickListener(open);
        subtitle.setOnClickListener(open);
        return row;
    }

    /**
     * 借用原生条目的左右边距，但高度用 WRAP_CONTENT。
     *
     * <p>原生条目多是固定高度（约 110px），直接照抄会把「标题 + 副标题」两行挤掉后面那行，
     * 所以这里只继承间距，高度交给内容自己撑开。</p>
     */
    private static ViewGroup.LayoutParams buildLayoutParams(View ref) {
        LinearLayout.LayoutParams out = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (ref != null && ref.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams src = (ViewGroup.MarginLayoutParams) ref.getLayoutParams();
            out.setMargins(src.leftMargin, src.topMargin, src.rightMargin, src.bottomMargin);
        }
        return out;
    }

    private static int indexBeforeGroup(ViewGroup container, String marker) {
        for (int i = 0; i < container.getChildCount(); i++) {
            if (containsText(container.getChildAt(i), marker)) {
                return i;
            }
        }
        return container.getChildCount();
    }

    private static boolean containsText(View view, String marker) {
        if (view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && marker.contentEquals(t)) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), marker)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TextView findSampleTextView(View view) {
        TextView byText = findByText(view, STYLE_SAMPLE_TEXT);
        if (byText != null) {
            return byText;
        }
        return findFirstTextView(view);
    }

    private static TextView findByText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findByText(g.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TextView findFirstTextView(View view) {
        if (view instanceof TextView) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findFirstTextView(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void openModuleSettings(Activity activity) {
        try {
            Intent intent = new Intent();
            intent.setClassName(MODULE_PACKAGE, MODULE_ACTIVITY);
            // 跨应用启动必须带 NEW_TASK，否则部分 ROM（含 MIUI）会把这次启动丢掉
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            Config.i("[设置入口] 已打开模块设置界面");
        } catch (Throwable t) {
            Config.e("[设置入口] 打开模块设置失败（模块 App 是否被卸载？）", t);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
