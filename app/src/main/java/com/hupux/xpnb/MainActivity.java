package com.hupux.xpnb;

import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

/**
 * 模块设置界面。
 *
 * <p>这里写进 SharedPreferences（组名 {@link Config#PREFS_NAME}）的开关，会被虎扑进程里的
 * 模块通过 {@code XposedInterface#getRemotePreferences(String)} 读到，所以改开关不用重装模块，
 * 但需要「强行停止虎扑」后重新打开才生效。</p>
 *
 * <p>开关行不写死在 XML 里：条目结构完全一致、数量又多，这里按数据表生成，
 * 增删一项只改一处，也避免上百行重复布局。</p>
 */
public class MainActivity extends AppCompatActivity {

    /** 一条设置项：存储 key + 标题 + 说明 + 默认值。 */
    private static final class Item {
        final String key;
        final int titleRes;
        final int descRes;
        final boolean def;

        Item(String key, int titleRes, int descRes, boolean def) {
            this.key = key;
            this.titleRes = titleRes;
            this.descRes = descRes;
            this.def = def;
        }
    }

    /** 广告拦截 —— 计入顶部状态统计。 */
    private static final Item[] BLOCK_ITEMS = {
            new Item(Config.KEY_SKIP_SPLASH,
                    R.string.item_skip_splash, R.string.item_skip_splash_desc, true),
            new Item(Config.KEY_BLOCK_SDK_INIT,
                    R.string.item_block_sdk, R.string.item_block_sdk_desc, true),
            new Item(Config.KEY_BLOCK_FEED,
                    R.string.item_block_feed, R.string.item_block_feed_desc, true),
            new Item(Config.KEY_BLOCK_FLOAT,
                    R.string.item_block_float, R.string.item_block_float_desc, true),
    };

    /** 兜底与调试 —— 不计入拦截统计。 */
    private static final Item[] DEBUG_ITEMS = {
            new Item(Config.KEY_VIEW_TREE_SKIP,
                    R.string.item_view_skip, R.string.item_view_skip_desc, true),
            new Item(Config.KEY_CLASS_PROBE,
                    R.string.item_probe, R.string.item_probe_desc, false),
            new Item(Config.KEY_VERBOSE,
                    R.string.item_verbose, R.string.item_verbose_desc, true),
    };

    private SharedPreferences prefs;

    /** 桌面图标挂在 activity-alias 上，隐藏 = 把这个 alias 禁用掉。 */
    private static final String LAUNCHER_ALIAS_SUFFIX = ".LauncherAlias";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);

        buildGroup(findViewById(R.id.ll_group_block), BLOCK_ITEMS);
        buildGroup(findViewById(R.id.ll_group_debug), DEBUG_ITEMS);

        LinearLayout appGroup = findViewById(R.id.ll_group_app);
        appGroup.addView(buildHideIconRow());
        appGroup.addView(divider());
        appGroup.addView(buildActionRow(
                R.string.item_view_log, R.string.item_view_log_desc,
                v -> startActivity(new Intent(this, LogActivity.class))));

        TextView footer = findViewById(R.id.tv_footer_info);
        footer.setText(getString(R.string.footer_info, versionName()));

        updateStatus();
    }

    // ---------- 隐藏桌面图标 ----------

    private ComponentName launcherAlias() {
        return new ComponentName(this, getPackageName() + LAUNCHER_ALIAS_SUFFIX);
    }

    private boolean isLauncherHidden() {
        try {
            int state = getPackageManager().getComponentEnabledSetting(launcherAlias());
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void setLauncherHidden(boolean hidden) {
        try {
            getPackageManager().setComponentEnabledSetting(
                    launcherAlias(),
                    hidden ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.toast_hide_icon_failed, t.toString()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「隐藏桌面图标」这一行。
     *
     * <p>它的状态不在 SharedPreferences 里，而是直接读系统里 activity-alias 的启用状态，
     * 这样无论从哪改都一致。</p>
     */
    private View buildHideIconRow() {
        LinearLayout row = newRowShell();
        LinearLayout texts = newTextsShell();

        TextView title = new TextView(this);
        title.setText(R.string.item_hide_icon);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setTextColor(getColor(R.color.text_primary));

        TextView desc = new TextView(this);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, dp(3), dp(12), 0);

        texts.addView(title);
        texts.addView(desc);

        final SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(isLauncherHidden());
        desc.setText(isLauncherHidden()
                ? R.string.item_hide_icon_desc_hidden : R.string.item_hide_icon_desc);

        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setLauncherHidden(isChecked);
            boolean nowHidden = isLauncherHidden();
            if (nowHidden != isChecked) {
                // 系统没接受（个别 ROM 会拦），把开关拨回去
                buttonView.setChecked(nowHidden);
            }
            desc.setText(nowHidden
                    ? R.string.item_hide_icon_desc_hidden : R.string.item_hide_icon_desc);
        });

        row.addView(texts);
        row.addView(toggle);
        row.setClickable(true);
        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }

    /** 只有标题和说明、点击执行一个动作的行（右侧一个 ›）。 */
    private View buildActionRow(int titleRes, int descRes, View.OnClickListener onClick) {
        LinearLayout row = newRowShell();
        LinearLayout texts = newTextsShell();

        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setTextColor(getColor(R.color.text_primary));

        TextView desc = new TextView(this);
        desc.setText(descRes);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, dp(3), dp(12), 0);

        texts.addView(title);
        texts.addView(desc);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        arrow.setTextColor(getColor(R.color.text_secondary));

        row.addView(texts);
        row.addView(arrow);
        row.setClickable(true);
        row.setOnClickListener(onClick);
        return row;
    }

    private LinearLayout newRowShell() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(16);
        row.setPadding(padH, dp(12), padH, dp(12));
        return row;
    }

    private LinearLayout newTextsShell() {
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return texts;
    }

    private void buildGroup(LinearLayout group, Item[] items) {
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                group.addView(divider());
            }
            group.addView(buildRow(items[i]));
        }
    }

    private View buildRow(final Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(16);
        row.setPadding(padH, dp(12), padH, dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText(item.titleRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setTextColor(getColor(R.color.text_primary));

        TextView desc = new TextView(this);
        desc.setText(item.descRes);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setPadding(0, dp(3), dp(12), 0);

        texts.addView(title);
        texts.addView(desc);

        final SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(prefs.getBoolean(item.key, item.def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(item.key, isChecked).apply();
                updateStatus();
            }
        });

        row.addView(texts);
        row.addView(sw);

        // 点整行也能切换，不必去戳那个小开关
        row.setClickable(true);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.setMarginStart(dp(16));
        v.setLayoutParams(lp);
        v.setBackgroundColor(getColor(R.color.divider));
        return v;
    }

    /** 顶部状态：当前生效了几项广告拦截。 */
    private void updateStatus() {
        int on = 0;
        for (Item item : BLOCK_ITEMS) {
            if (prefs.getBoolean(item.key, item.def)) {
                on++;
            }
        }
        TextView status = findViewById(R.id.tv_status);
        status.setText(getString(R.string.status_enabled, on, BLOCK_ITEMS.length));
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
