package com.hupux.xpnb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 接收虎扑进程里模块代码写来的拦截日志。
 *
 * <p>必须 exported：写日志的代码跑在虎扑的进程里（UID 是虎扑的），
 * 和模块 App 不是同一个身份，只能通过 ContentProvider 跨进程写。</p>
 *
 * <p>代价是这个 Provider 谁都能访问。里面只有广告拦截记录、不含隐私数据，
 * 对课程项目可以接受；真要收紧可以再加签名级权限或校验调用方包名。</p>
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.hupux.xpnb.log";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/log");

    /** 写入的记录字段名。 */
    public static final String COLUMN_RECORD = "record";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Context ctx = getContext();
        if (ctx != null && values != null) {
            AdsLogStore.append(ctx, values.getAsString(COLUMN_RECORD));
        }
        return uri;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_RECORD});
        Context ctx = getContext();
        if (ctx != null) {
            for (String record : AdsLogStore.readAll(ctx)) {
                cursor.addRow(new Object[]{record});
            }
        }
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Context ctx = getContext();
        if (ctx != null) {
            AdsLogStore.clear(ctx);
            return 1;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.hupux.log";
    }

    /**
     * 配置兜底通道：把模块 App 自己的开关值返回给虎扑进程里的模块代码。
     *
     * <p>为什么需要：框架的 {@code XposedInterface#getRemotePreferences} 在部分框架上
     * （实测 FPA 3.8）返回的是一份<b>空配置</b> —— 不抛异常、但所有 key 都读不到，
     * 于是模块默默退回默认值，用户在设置页改的开关根本不生效。
     * 这里直接用已 exported 的 Provider {@code call} 跨进程读模块 App 的
     * SharedPreferences，两边都是同一个文件，绝对一致。</p>
     *
     * <p>返回的 Bundle 里每个 key 都显式写好（含默认值），调用方按 key 取即可。</p>
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!"getConfig".equals(method)) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        SharedPreferences sp = ctx.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        Bundle out = new Bundle();
        out.putString(Config.KEY_CONFIG_SOURCE, "provider");
        out.putBoolean(Config.KEY_SKIP_SPLASH, sp.getBoolean(Config.KEY_SKIP_SPLASH, true));
        out.putBoolean(Config.KEY_BLOCK_SDK_INIT, sp.getBoolean(Config.KEY_BLOCK_SDK_INIT, true));
        out.putBoolean(Config.KEY_BLOCK_FEED, sp.getBoolean(Config.KEY_BLOCK_FEED, true));
        out.putBoolean(Config.KEY_BLOCK_FLOAT, sp.getBoolean(Config.KEY_BLOCK_FLOAT, true));
        out.putBoolean(Config.KEY_BLOCK_SCENE, sp.getBoolean(Config.KEY_BLOCK_SCENE, true));
        out.putBoolean(Config.KEY_VIEW_TREE_SKIP, sp.getBoolean(Config.KEY_VIEW_TREE_SKIP, true));
        out.putBoolean(Config.KEY_CLASS_PROBE, sp.getBoolean(Config.KEY_CLASS_PROBE, false));
        out.putBoolean(Config.KEY_VERBOSE, sp.getBoolean(Config.KEY_VERBOSE, true));
        return out;
    }
}
