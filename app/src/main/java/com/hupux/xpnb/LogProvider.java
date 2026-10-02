package com.hupux.xpnb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

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
}
