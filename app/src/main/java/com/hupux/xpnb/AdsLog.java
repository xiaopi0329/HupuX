package com.hupux.xpnb;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 注入侧（跑在虎扑进程里）的拦截日志上报。
 *
 * <p>模块 App 和虎扑是两个进程、两个 UID，写不到对方的私有目录，
 * 所以这里把记录通过 {@link LogProvider} 插进模块 App。为了不给虎扑添负担：</p>
 * <ul>
 *     <li>记录先进内存队列，攒一下再写（延迟 800ms 合并同一批）；</li>
 *     <li>写入在独立线程做，不占用虎扑主线程；</li>
 *     <li>模块 App 没装或 Provider 不可用就丢掉这批，并打一条警告，绝不重试刷屏。</li>
 * </ul>
 */
public final class AdsLog {

    private static final String TAG = "HupuX";

    private static final Uri ENDPOINT = Uri.parse("content://" + LogProvider.AUTHORITY + "/log");

    private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static final int MAX_PENDING = 200;

    private static volatile Context appContext;
    private static volatile boolean flushScheduled;
    private static ExecutorService executor;

    private AdsLog() {
    }

    /** 拿到虎扑的 Context 之后才能上报。越早调用越好。 */
    public static void setContext(Context ctx) {
        if (ctx == null || appContext != null) {
            return;
        }
        appContext = ctx.getApplicationContext();
        if (!PENDING.isEmpty()) {
            scheduleFlush(0);
        }
    }

    // ---------- 对外的记录入口 ----------

    /** 拦截成功：原方法没有执行。 */
    public static void blocked(String feature, String detail) {
        record(feature, detail, "已拦截");
    }

    /** 开关关掉了 / 不满足条件，放行原方法。 */
    public static void allowed(String feature, String detail) {
        record(feature, detail, "已放行");
    }

    /** Hook 安装成功。 */
    public static void hooked(String feature, String detail, int count) {
        record(feature, detail, "Hook 已安装" + (count > 1 ? "（" + count + " 个重载）" : ""));
    }

    /** Hook 没找到目标方法（多半是虎扑换版本了）。 */
    public static void miss(String feature, String detail) {
        record(feature, detail, "Hook 未找到");
    }

    public static void info(String feature, String detail) {
        record(feature, detail, "");
    }

    public static void record(String feature, String detail, String result) {
        try {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String line = System.currentTimeMillis() + "|" + time + "|"
                    + feature + "|" + detail + "|" + result;
            PENDING.add(line);
            while (PENDING.size() > MAX_PENDING) {
                PENDING.poll();
            }
            Log.i(TAG, "[记录] " + feature + " " + detail + " " + result);
            scheduleFlush(800);
        } catch (Throwable ignored) {
        }
    }

    // ---------- 批量上报 ----------

    private static void scheduleFlush(long delayMs) {
        synchronized (AdsLog.class) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hupux-log");
                t.setDaemon(true);
                return t;
            });
        }
        executor.execute(() -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException ignored) {
            }
            drain();
            synchronized (AdsLog.class) {
                flushScheduled = false;
            }
        });
    }

    private static void drain() {
        Context ctx = appContext;
        if (ctx == null || PENDING.isEmpty()) {
            return;
        }
        String line;
        while ((line = PENDING.poll()) != null) {
            try {
                ContentValues values = new ContentValues();
                values.put(LogProvider.COLUMN_RECORD, line);
                ctx.getContentResolver().insert(ENDPOINT, values);
            } catch (Throwable t) {
                // 模块 App 没装 / Provider 不可用：丢掉这批，别反复重试
                PENDING.clear();
                Log.w(TAG, "[记录] 写入模块日志失败（模块 App 是否已安装？）：" + t);
                return;
            }
        }
    }
}
