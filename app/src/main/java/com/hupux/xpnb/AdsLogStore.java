package com.hupux.xpnb;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 拦截日志的存储（模块 App 侧）。
 *
 * <p>一行一条记录，字段用 {@code |} 分隔：{@code 时间戳|时间|功能|目标|结果}。
 * 之所以落在模块 App 自己的私有目录里，是因为写入方（虎扑进程里的模块代码）
 * 是通过 {@link LogProvider} 进来的，写的是模块 App 的身份，而不是虎扑的身份。</p>
 */
public final class AdsLogStore {

    private static final String FILE_NAME = "ads_log.txt";
    /** 最多保留多少条，超出后丢最旧的。 */
    private static final int MAX_LINES = 500;
    private static final long TRIM_THRESHOLD_BYTES = 128 * 1024;

    private static final Object LOCK = new Object();

    private AdsLogStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    public static void append(Context ctx, String record) {
        if (ctx == null || record == null || record.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            try (FileOutputStream fos = new FileOutputStream(file(ctx), true)) {
                fos.write((record + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable t) {
                return;
            }
            trimLocked(ctx);
        }
    }

    /** 文件长到一定体积才做一次裁剪，避免每条都读写整个文件。 */
    private static void trimLocked(Context ctx) {
        try {
            File f = file(ctx);
            if (f.length() < TRIM_THRESHOLD_BYTES) {
                return;
            }
            List<String> lines = readAll(ctx);
            if (lines.size() <= MAX_LINES) {
                return;
            }
            List<String> keep = lines.subList(lines.size() - MAX_LINES, lines.size());
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
                for (String l : keep) {
                    w.write(l);
                    w.write("\n");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static List<String> readAll(Context ctx) {
        List<String> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        synchronized (LOCK) {
            File f = file(ctx);
            if (!f.exists()) {
                return out;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty()) {
                        out.add(line);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static void clear(Context ctx) {
        if (ctx == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File f = file(ctx);
                if (f.exists() && !f.delete()) {
                    // 删不掉就清空内容
                    try (FileOutputStream fos = new FileOutputStream(f, false)) {
                        fos.write(new byte[0]);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
