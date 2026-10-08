package com.hupux.xpnb;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * DexKit 兜底解析器 —— 目标类被改名 / 挪包时，直接翻宿主进程里的 dex 把真实类名认出来。
 *
 * <p>为什么需要它：{@link LazyHookInstaller} 靠「写死的类名 + Class.forName」找目标类。
 * 虎扑一旦升级，类名可能被混淆或挪到别的包，写死的名字就永远命中不了，Hook 静默失效。
 * 这里换一个思路：不看类名，看<b>被 hook 的方法长什么样</b>——方法名、参数个数、
 * 参数类型、返回类型。这些比类名稳定得多，记在 {@link HookFingerprints} 里。</p>
 *
 * <p>工作流程：先用「方法名 + 参数个数」向 DexKit 要候选（这两个条件在 DexKit 里是精确匹配，
 * 可靠）；拿到候选后再在 Java 侧用参数类型 / 返回类型 / 类名相似度打分，
 * <b>只接受得分最高且明显领先的候选</b>。认不准就放弃 —— 装错 Hook 比不装更危险。</p>
 *
 * <p>线程模型：所有 DexKit 调用都在单线程 worker 上串行执行，绝不占用主线程；
 * 结果通过 {@link Listener} 回到主线程。整个进程只建一个 bridge，用完释放。</p>
 */
public final class DexKitResolver {

    /** 解析结果回调，只在主线程调用。 */
    public interface Listener {
        /**
         * @param resolved 目标类名（登记时用的名字）→ 真实类名，只包含本次新认出来的
         */
        void onResolved(Map<String, String> resolved);
    }

    /** 最多查几轮。宿主 dex 集合基本固定，查多了只是白烧 CPU。 */
    private static final int MAX_PASSES = 4;

    /** 前两轮无条件跑（覆盖壳分阶段交出 dex 的情况），之后只在 dex 段数增长时才再跑。 */
    private static final int FREE_PASSES = 2;

    /** 单个候选的最低得分。低于这个分说明指纹太弱，宁可不认。 */
    private static final int MIN_SCORE = 7;

    /** 最高分必须比第二名高出这么多，否则视为歧义、放弃。 */
    private static final int MIN_MARGIN = 3;

    /** 候选类上限，防止某个大众化方法名把结果集撑爆。 */
    private static final int MAX_CANDIDATES = 200;

    /** 放宽包名后单次查询的结果上限，超了就放弃这条签名。 */
    private static final int MAX_LOOSE_RESULTS = 800;

    /**
     * 解包失败后的冷却时间。
     *
     * <p>解包要真读 35MB 压缩流、写 100MB 临时文件、再拆成 95MB 结果，一次几十秒。
     * 而安装器的定时重试是每秒一轮（快试 60 次、慢试 360 次），失败后不设冷却就会
     * 把整台机器按住反复解包，还顺带刷屏。所以失败后退避：同一个宿主 APK 只认一次，
     * 之后按「冷却 + 指数放大」重试，最多 {@link #MAX_PASSES} 次。</p>
     */
    private static final long UNPACK_RETRY_INITIAL_MS = 60_000L;

    /** 解包失败次数。失败不消耗 passes，所以单独计数。 */
    private static int unpackFailures;

    /** 解包失败后禁止再解包的截止时刻；解包阶段失败一次就置位，成功后清零。 */
    private static long cooldownUntil;

    // ---- 打分权重 ----
    private static final int W_WELL_FORMED = 2;    // 方法名 + 参数个数对上
    private static final int W_RETURN_MATCH = 3;   // 返回类型也对上
    private static final int W_PARAMS_MATCH = 4;   // 参数类型全对上
    private static final int W_SAME_NAME = 8;      // 类名（去掉包名）完全一样
    private static final int W_NAME_CONTAINS = 4;  // 类名互相包含
    private static final int W_SAME_PACKAGE = 3;   // 包名一样
    private static final int W_PKG_PREFIX = 1;     // 包名前三段一样
    private static final int W_OTHER_TARGET = -12; // 认到了另一个登记过的目标类上，基本是错的
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hupux-dexkit");
            t.setDaemon(true);
            return t;
        }
    });

    /** 已经查过的目标类（不管查到没查到），避免每秒重复扫 dex。 */
    private static final Set<String> QUERIED = ConcurrentHashMap.newKeySet();

    /** 正在跑一轮，防止定时器把任务堆起来。 */
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    /** 原生库是否加载过 / 是否可用。 */
    private static boolean nativeTried;
    private static boolean nativeReady;

    private static DexKitBridge bridge;
    private static ClassLoader bridgeLoader;
    private static int passes;
    private static int lastDexNum = -1;

    private DexKitResolver() {
    }

    /**
     * 异步解析一批目标类。可以在主线程调用：真正的重活会丢到后台线程。
     *
     * @param hostLoader 宿主的 ClassLoader（dex 从它身上取，必须是虎扑自己的）
     * @param targets    还没命中、需要解析的目标类名
     * @param listener   结果回调（主线程）；没有结果时不会觚调用
     */
    public static void resolveAsync(final ClassLoader hostLoader, final List<String> targets,
                                    final Listener listener) {
        if (hostLoader == null || targets == null || targets.isEmpty() || listener == null) {
            return;
        }
        if (!Config.dexkitResolve) {
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, String> out = new LinkedHashMap<>();
                try {
                    resolveBlocking(hostLoader, targets, out);
                } catch (Throwable t) {
                    Config.e("[DexKit] 兜底解析异常", t);
                } finally {
                    RUNNING.set(false);
                }
                if (out.isEmpty()) {
                    return;
                }
                final Map<String, String> result = out;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            listener.onResolved(result);
                        } catch (Throwable t) {
                            Config.e("[DexKit] 回调处理失败", t);
                        }
                    }
                });
            }
        });
    }

    /** 释放原生资源。全部目标处理完之后由安装器调用。 */
    public static void release() {
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                closeBridge();
            }
        });
    }

    /** 只在 worker 线程调用。 */
    private static void resolveBlocking(ClassLoader hostLoader, List<String> targets,
                                        Map<String, String> out) {
        List<String> todo = new ArrayList<>();
        for (String name : targets) {
            if (!QUERIED.contains(name) && HookFingerprints.of(name) != null) {
                todo.add(name);
            }
        }
        if (todo.isEmpty()) {
            return;
        }

        DexKitBridge b = ensureBridge(hostLoader);
        if (b == null) {
            return;   // ensureBridge 里已经打过日志了
        }

        int dexNum = dexNum(b);
        if (dexNum <= 1) {
            // 壳还没把真实 dex 交给 ClassLoader，这一轮不算数，等下一次重试
            Config.i("[DexKit] 宿主 dex 尚未就绪（段数=" + dexNum + "），暂不解析");
            return;
        }
        if (passes >= MAX_PASSES || (passes >= FREE_PASSES && dexNum <= lastDexNum)) {
            return;
        }
        passes++;
        lastDexNum = dexNum;
        Config.i("[DexKit] 第 " + passes + " 轮兜底解析：待处理 " + todo.size()
                + " 个目标类，宿主 dex 段数=" + dexNum);

        int found = 0;
        for (String target : todo) {
            HookFingerprints.Sig[] sigs = HookFingerprints.of(target);
            if (sigs == null || sigs.length == 0) {
                continue;
            }
            String real = resolveOne(b, target, sigs);
            QUERIED.add(target);
            if (real != null) {
                out.put(target, real);
                found++;
            }
        }

        if (found > 0) {
            Config.i("[DexKit] 本轮按指纹认领 " + found + " 个目标类");
            AdsLog.info("DexKit", "按方法指纹认领 " + found + " 个被改名/挪包的目标类");
        } else {
            Config.i("[DexKit] 本轮没有可确认的目标类（" + todo.size() + " 个都缺足够指纹）");
        }

        if (passes >= MAX_PASSES || !hasUnqueried(targets)) {
            closeBridge();
        }
    }

    private static boolean hasUnqueried(List<String> targets) {
        for (String name : targets) {
            if (!QUERIED.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 认一个目标类。
     *
     * @return 真实类名；认不准返回 null
     */
    private static String resolveOne(DexKitBridge b, String target, HookFingerprints.Sig[] sigs) {
        // 先查「参数/返回类型已知」的签名 —— 这类签名区分度最高
        List<HookFingerprints.Sig> ordered = new ArrayList<>(sigs.length);
        for (HookFingerprints.Sig s : sigs) {
            if (s.complete()) {
                ordered.add(s);
            }
        }
        for (HookFingerprints.Sig s : sigs) {
            if (!s.complete()) {
                ordered.add(s);
            }
        }

        List<List<MethodData>> results = new ArrayList<>(ordered.size());
        Set<String> candidates = new LinkedHashSet<>();
        for (HookFingerprints.Sig s : ordered) {
            List<MethodData> hits = query(b, s, true);
            results.add(hits);
            collect(hits, candidates);
        }

        if (candidates.isEmpty()) {
            // com.hupu 包内没捞到 —— 放宽到全 dex，但只认完整签名，免得候选爆掉
            results.clear();
            candidates.clear();
            for (HookFingerprints.Sig s : ordered) {
                List<MethodData> hits = s.complete()
                        ? query(b, s, false) : Collections.<MethodData>emptyList();
                results.add(hits);
                collect(hits, candidates);
            }
            if (!candidates.isEmpty()) {
                Config.i("[DexKit] " + target + "：com.hupu 包内没有候选，放宽到全 dex 后有 "
                        + candidates.size() + " 个");
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        String targetSimple = simpleName(target);
        String targetPkg = packageName(target);

        String bestName = null;
        String secondName = null;
        int bestScore = Integer.MIN_VALUE;
        int secondScore = 0;
        boolean bestStrong = false;
        boolean bestSameName = false;

        for (String candidate : candidates) {
            int score = 0;
            int strongHits = 0;

            for (int i = 0; i < ordered.size(); i++) {
                HookFingerprints.Sig s = ordered.get(i);
                int bestPts = -1;
                boolean bestIsStrong = false;
                for (MethodData m : results.get(i)) {
                    if (!candidate.equals(declaredName(m))) {
                        continue;
                    }
                    boolean returnOk = true;
                    boolean paramsOk = true;
                    int pts = W_WELL_FORMED;

                    if (s.returnType != null) {
                        returnOk = sameType(s.returnType, m.getReturnTypeName());
                        if (returnOk) {
                            pts += W_RETURN_MATCH;
                        }
                    }
                    if (s.params != null) {
                        List<String> actual = m.getParamTypeNames();
                        int known = 0;
                        int matched = 0;
                        for (int k = 0; k < s.params.length; k++) {
                            if (s.params[k] == null) {
                                continue;
                            }
                            known++;
                            if (actual != null && k < actual.size()
                                    && sameType(s.params[k], actual.get(k))) {
                                matched++;
                            }
                        }
                        if (known == 0) {
                            // 参数类型全是未知，不扣分
                        } else if (matched == known) {
                            pts += W_PARAMS_MATCH;
                        } else {
                            paramsOk = false;
                            pts += (int) Math.round((double) W_PARAMS_MATCH * matched / known);
                        }
                    }

                    if (pts > bestPts) {
                        bestPts = pts;
                        bestIsStrong = s.complete() && returnOk && paramsOk;
                    }
                }
                if (bestPts > 0) {
                    score += bestPts;
                    if (bestIsStrong) {
                        strongHits++;
                    }
                }
            }

            String candSimple = simpleName(candidate);
            boolean sameName = candSimple.equals(targetSimple);
            if (sameName) {
                score += W_SAME_NAME;
            } else if (candSimple.contains(targetSimple) || targetSimple.contains(candSimple)) {
                score += W_NAME_CONTAINS;
            }

            String candPkg = packageName(candidate);
            if (candPkg.equals(targetPkg)) {
                score += W_SAME_PACKAGE;
            } else if (commonPrefixSegments(candPkg, targetPkg) >= 3) {
                score += W_PKG_PREFIX;
            }

            if (!candidate.equals(target) && HookRegistry.isTarget(candidate)) {
                // 认到另一个登记过的目标类上，基本是认错了
                score += W_OTHER_TARGET;
            }

            if (score > bestScore) {
                // 只有真的存在「上一个最好」时才把它降为次选；否则 secondScore 会一直是
                // Integer.MIN_VALUE，后面 bestScore - secondScore 直接溢出成负数，
                // 唯一的候选反而被判成「不唯一」。
                if (bestName != null) {
                    secondScore = bestScore;
                    secondName = bestName;
                }
                bestScore = score;
                bestName = candidate;
                bestStrong = strongHits > 0;
                bestSameName = sameName;
            } else if (bestName != null && score > secondScore) {
                secondScore = score;
                secondName = candidate;
            }
        }

        if (bestName == null) {
            return null;
        }
        if (bestScore < MIN_SCORE) {
            Config.i("[DexKit] " + target + "：最佳候选 " + bestName + " 得分只有 " + bestScore + "，指纹太弱，放弃");
            return null;
        }
        if (!bestStrong && !bestSameName) {
            Config.i("[DexKit] " + target + "：候选 " + bestName
                    + " 只有弱指纹命中（方法名+参数个数），不足以确认，放弃");
            return null;
        }
        if (secondName != null && bestScore - secondScore < MIN_MARGIN) {
            Config.w("[DexKit] " + target + "：候选不唯一，" + bestName + "=" + bestScore
                    + " 与 " + secondName + "=" + secondScore + " 太接近，放弃");
            AdsLog.miss("DexKit", shortNameOf(target) + " 候选不唯一，未安装");
            return null;
        }

        Config.i("[DexKit] " + target + " -> " + bestName + "（得分 " + bestScore
                + "，次选 " + secondName + "=" + secondScore + "）");
        return bestName;
    }

    /**
     * 按「方法名 + 参数个数」精确查询候选。这两个条件在 DexKit 里是精确匹配，可靠；
     * 参数类型和返回类型交给 Java 侧打分，避免踩到 DexKit 匹配类型的默认值陷阱
     * （class/returnType 默认 Equals，usingStrings 默认 Contains）。
     *
     * @param restrict 是否限制在 com.hupu 包内
     */
    private static List<MethodData> query(DexKitBridge b, HookFingerprints.Sig s, boolean restrict) {
        try {
            FindMethod find = FindMethod.create();
            if (restrict) {
                find.searchPackages("com.hupu");
            }
            MethodMatcher matcher = MethodMatcher.create().name(s.name);
            if (s.paramCount >= 0) {
                matcher.paramCount(s.paramCount, s.paramCount);
            }
            find.matcher(matcher);

            List<MethodData> list = b.findMethod(find);
            if (list == null) {
                return Collections.emptyList();
            }
            if (!restrict && list.size() > MAX_LOOSE_RESULTS) {
                Config.w("[DexKit] " + s.name + " 全 dex 命中 " + list.size()
                        + " 个，超过上限，跳过这条签名");
                return Collections.emptyList();
            }
            return list;
        } catch (Throwable t) {
            Config.w("[DexKit] 查询失败 " + s.name + "/" + s.paramCount + " -> " + t);
            return Collections.emptyList();
        }
    }

    private static void collect(List<MethodData> list, Set<String> out) {
        for (MethodData m : list) {
            if (out.size() >= MAX_CANDIDATES) {
                return;
            }
            String name = declaredName(m);
            if (name != null) {
                out.add(name);
            }
        }
    }

    private static String declaredName(MethodData m) {
        try {
            return m.getDeclaredClassName();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // bridge / 原生库
    // ------------------------------------------------------------------

    /**
     * 建 bridge。dex 不走 {@code create(classLoader, true)} —— 那条路会让宿主进程直接
     * SIGABRT：壳在内存里映射的 dex 镜像有一张的头部 {@code file_size} 不是 4 的倍数
     * （实测 2797417），而 DexKit 内置的 slicer 在 {@code reader.cc:1014} 有
     * {@code SLICER_CHECK_EQ(header_->data_size % 4, 0)}，断言失败即 native abort，
     * Java 的 try/catch 完全接不住。
     *
     * <p>所以改成：把宿主 APK 里那个「壳 dex + 明文拼接的真实 dex」容器自己拆成
     * 21 个头部完好的独立 dex（{@link DexUnpacker}，算法与离线脱壳脚本一致），
     * 打成临时 zip 再交给 {@code create(zipPath)}。DexKit 会把每个条目解压进
     * native 自有内存，所以 create() 返回后临时 zip 可以立刻删掉。</p>
     */
    private static DexKitBridge ensureBridge(ClassLoader hostLoader) {
        DexKitBridge current = bridge;
        if (current != null && current.isValid() && bridgeLoader == hostLoader) {
            return current;
        }
        // 上一次准备失败还在冷却里：什么都不做，也不打日志 —— 安装器是每秒来敲一次门的。
        if (System.currentTimeMillis() < cooldownUntil) {
            return null;
        }
        closeBridge();
        if (!ensureNative()) {
            return failSetup("libdexkit.so 加载失败");
        }

        byte[][] images = null;
        File zip = null;
        try {
            File apk = loadHostApk(hostLoader);
            if (apk == null) {
                Config.w("[DexKit] 没能从宿主 ClassLoader 里定位到 APK 路径");
                return failSetup("没能定位宿主 APK");
            }
            images = readImages(apk);
            if (images == null || images.length == 0) {
                Config.w("[DexKit] 容器里没拆出任何 dex，兜底解析不可用");
                return failSetup("没能从宿主 APK 里拆出 dex");
            }
            zip = buildZip(images);
            if (zip == null) {
                Config.w("[DexKit] 临时 dex zip 写入失败");
                return failSetup("临时 dex zip 写入失败");
            }

            DexKitBridge created = DexKitBridge.create(zip.getAbsolutePath());
            if (created == null || !created.isValid()) {
                Config.w("[DexKit] bridge 创建后无效（isValid=false）");
                return failSetup("bridge 创建失败");
            }
            try {
                int cores = Runtime.getRuntime().availableProcessors();
                created.setThreadNum(Math.max(1, Math.min(4, cores)));
            } catch (Throwable ignored) {
                // 线程数设置失败不影响查询
            }
            bridge = created;
            bridgeLoader = hostLoader;
            unpackFailures = 0;
            cooldownUntil = 0L;
            Config.i("[DexKit] bridge 已建立（自拆 " + images.length + " 段独立 dex），dex 段数="
                    + dexNum(created));
            return created;
        } catch (Throwable t) {
            Config.e("[DexKit] bridge 创建失败", t);
            return failSetup("bridge 创建失败：" + t);
        } finally {
            // 数据已经进了 DexKit 的 native 内存，这两个都可以立刻放掉
            if (zip != null && !zip.delete()) {
                zip.deleteOnExit();
            }
            images = null;
        }
    }

    /**
     * 记一次「准备 bridge 失败」并安排退避。
     *
     * <p>失败不消耗 {@code passes}（那是查询轮次），所以必须自己退避，否则安装器的
     * 每秒/每 5 秒重试会一直把整条解包流水线跑起来。退避按失败次数翻倍，到
     * {@link #MAX_PASSES} 次就直接判死，不再打扰宿主。</p>
     */
    private static DexKitBridge failSetup(String what) {
        unpackFailures++;
        AdsLog.miss("DexKit", what);
        if (unpackFailures >= MAX_PASSES) {
            cooldownUntil = Long.MAX_VALUE;
            Config.w("[DexKit] 已经连续 " + unpackFailures + " 次没能准备好兜底解析，放弃（" + what + "）");
        } else {
            long backoff = UNPACK_RETRY_INITIAL_MS << (unpackFailures - 1);
            cooldownUntil = System.currentTimeMillis() + backoff;
            Config.w("[DexKit] 兜底解析暂不可用（" + what + "），" + (backoff / 1000) + " 秒后再试第 "
                    + (unpackFailures + 1) + " 次");
        }
        return null;
    }

    /**
     * 从宿主 ClassLoader 的 dex 路径表里找出 APK 的绝对路径。
     *
     * <p>顺手把当前线程的 context class loader 设成宿主 loader：{@code DexFile} 打开失败时
     * 会退回用 context class loader 去找 APK（ART 自己的兜底逻辑），设上等于把这条路铺好。</p>
     */
    private static File loadHostApk(ClassLoader hostLoader) {
        Thread.currentThread().setContextClassLoader(hostLoader);
        Object pathList = readField(hostLoader, "pathList");
        if (pathList == null) {
            return null;
        }
        Object elements = readField(pathList, "dexElements");
        if (elements == null || !elements.getClass().isArray()) {
            return null;
        }
        int n = Array.getLength(elements);
        for (int i = 0; i < n; i++) {
            Object element = Array.get(elements, i);
            Object path = readField(element, "path");
            if (path instanceof File && ((File) path).isFile()) {
                return (File) path;
            }
        }
        return null;
    }

    /**
     * 把宿主 APK 的 {@code classes.dex} 条目解出来，喂给 {@link DexUnpacker} 拆成
     * 21 个头部完好的独立 dex。
     *
     * <p>容器是 APK <b>里面</b>的那个 {@code classes.dex} 条目（100MB 量级），不是整个 APK
     * （77MB）—— 整个 APK 里既没有 4 对齐的 dex 头，也没有 map_list 锚点。</p>
     *
     * <p>这个条目可能是 STORED（未压缩）也可能是 DEFLATE。虎扑 8.2.63 就是 DEFLATE
     * （35MB → 100MB），所以两种都要能吃：STORED 直接引用映射；DEFLATE 先流式解压到
     * 临时文件、再映射回来喂解析器 —— 100MB 的容器不能进 Java 堆，宿主的堆上限只有几百 MB，
     * 而且解析结果本身还要在堆里占 ~95MB。</p>
     */
    private static byte[][] readImages(File apk) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(apk, "r");
        RandomAccessFile tmpRaf = null;
        File tmp = null;
        try {
            FileChannel channel = raf.getChannel();
            long size = channel.size();
            if (size <= 0 || size > Integer.MAX_VALUE) {
                Config.w("[DexKit] 宿主 APK 大小异常：" + size);
                return null;
            }
            MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size);
            DexEntry entry = findDexEntry(map, size);
            if (entry == null) {
                Config.w("[DexKit] 宿主 APK 里没找到 classes.dex 条目");
                return null;
            }

            ByteBuffer container;
            if (entry.method == 0) {
                ByteBuffer view = map.duplicate();
                view.position((int) entry.dataOff);
                view.limit((int) (entry.dataOff + entry.compSize));
                container = view.slice().order(java.nio.ByteOrder.LITTLE_ENDIAN);
                Config.i("[DexKit] classes.dex 是 STORED 存储，直接映射 " + entry.compSize + " 字节");
            } else {
                File dir = extractDir();
                if (dir == null) {
                    Config.w("[DexKit] 没有可写的临时目录，无法解压宿主容器");
                    return null;
                }
                tmp = new File(dir, "hupu-container-" + System.nanoTime() + ".bin");
                sweepOldTemps(dir);
                long started = System.currentTimeMillis();                long produced = inflateRaw(map, entry, tmp);
                if (produced <= 0) {
                    Config.w("[DexKit] 宿主 classes.dex 解压失败（method=" + entry.method
                            + "，压缩 " + entry.compSize + " 字节）");
                    return null;
                }
                if (entry.uncompSize > 0 && produced != entry.uncompSize) {
                    Config.w("[DexKit] 解出的字节数 " + produced + " 与 zip 目录里记录的 "
                            + entry.uncompSize + " 不一致，仍按实际字节继续");
                }
                Config.i("[DexKit] classes.dex 是 DEFLATE 存储，已流式解压到临时文件："
                        + produced + " 字节，耗时 " + (System.currentTimeMillis() - started) + " ms");
                tmpRaf = new RandomAccessFile(tmp, "r");
                container = tmpRaf.getChannel().map(FileChannel.MapMode.READ_ONLY, 0L, produced);
            }

            final long unpackStarted = System.currentTimeMillis();
            byte[][] images = DexUnpacker.unpack(container, new DexUnpacker.Reporter() {
                @Override
                public void onLog(String msg) {
                    Config.i("[DexKit] " + msg);
                }
            });
            if (images == null) {
                return null;
            }
            long bytes = 0;
            for (byte[] image : images) {
                if (image != null) {
                    bytes += image.length;
                }
            }
            Config.i("[DexKit] 宿主容器已拆解：" + images.length + " 段 / " + (bytes / 1024 / 1024)
                    + " MB，耗时 " + (System.currentTimeMillis() - unpackStarted) + " ms");
            return images;
        } finally {
            closeQuietly(tmpRaf);
            closeQuietly(raf);
            if (tmp != null && !tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    /**
     * 在 zip 的 EOCD / 中央目录里定位 {@code classes.dex}，返回它的存储方式与数据区位置。
     *
     * <p>这里不依赖 Android 的任何 zip API —— 宿主进程里没有 Context，也不该去碰宿主的文件 API。
     * 只做「定位」，取字节交给调用方（STORED 直接用映射，DEFLATE 走 {@link #inflateRaw}）。</p>
     */
    private static DexEntry findDexEntry(MappedByteBuffer map, long size) {
        map.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // 1) 从**文件末尾**回扫 EOCD（签名 0x06054b50）。EOCD 是 zip 的最后一个结构，
        //    只可能出现在最后 22 + 65535 字节里；从头部往前扫是永远找不到的。
        int maxBack = (int) Math.min(size, 65557L);
        int end = (int) size - 4;
        int stop = (int) size - maxBack;
        long eocd = -1;
        for (int i = end; i >= stop; i--) {
            if (u32(map, i) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            Config.w("[DexKit] 宿主 APK 尾部没有 EOCD 签名，不是标准 zip");
            return null;
        }
        long cdCount = u16(map, (int) eocd + 10);
        long cdOffset = u32(map, (int) eocd + 16);

        // 2) 遍历中央目录项（签名 0x02014b50）
        long p = cdOffset;
        for (long i = 0; i < cdCount; i++) {
            if (p < 0 || p + 46 > size || u32(map, (int) p) != 0x02014b50L) {
                return null;
            }
            long flags = u16(map, (int) p + 8);
            long method = u16(map, (int) p + 10);
            long compSize = u32(map, (int) p + 20);
            long uncompSize = u32(map, (int) p + 24);
            long nameLen = u16(map, (int) p + 28);
            long extraLen = u16(map, (int) p + 30);
            long commentLen = u16(map, (int) p + 32);
            long localOff = u32(map, (int) p + 42);
            String name = readAscii(map, (int) p + 46, (int) nameLen);
            if ("classes.dex".equals(name)) {
                if ((flags & 1L) != 0L) {
                    Config.w("[DexKit] classes.dex 条目被加密（flags=" + flags + "）");
                    return null;
                }
                if (method != 0 && method != 8) {
                    Config.w("[DexKit] classes.dex 用了不支持的压缩方式（method=" + method + "）");
                    return null;
                }
                if (localOff + 30 > size || u32(map, (int) localOff) != 0x04034b50L) {
                    return null;
                }
                long lNameLen = u16(map, (int) localOff + 26);
                long lExtraLen = u16(map, (int) localOff + 28);
                long dataOff = localOff + 30 + lNameLen + lExtraLen;
                if (dataOff + compSize > size) {
                    return null;
                }
                // 数据描述符（bit 3）会让本地头里的 size 字段为 0，一律以中央目录为准。
                return new DexEntry(method, dataOff, compSize, uncompSize);
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
        return null;
    }

    /**
     * 把 {@code classes.dex} 的 DEFLATE 数据流式解压到 {@code out}，返回写出的字节数；失败返回 -1。
     *
     * <p>用 {@link Inflater} 逐块喂：容器解压后是 100MB 量级，宿主堆只有几百 MB，
     * 不能整块进堆；这里只有 256KB 的输入/输出窗口 + 64KB 写缓冲。压缩流的起点由
     * {@link DexEntry#dataOff} 精确定位，末尾有没有多余的 0 填充不影响 inflate。</p>
     */
    private static long inflateRaw(MappedByteBuffer map, DexEntry entry, File out) {
        Inflater inflater = new Inflater(true); // nowrap：zip 的裸 deflate 没有 zlib 头
        OutputStream os = null;
        try {
            long remaining = entry.compSize;
            int pos = (int) entry.dataOff;
            byte[] inBuf = new byte[256 * 1024];
            byte[] outBuf = new byte[256 * 1024];
            byte[] fileBuf = new byte[64 * 1024];
            int fileLen = 0;
            long produced = 0L;
            os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            while (true) {
                if (inflater.needsInput() && remaining > 0) {
                    int n = (int) Math.min(inBuf.length, remaining);
                    ByteBuffer slice = map.duplicate();
                    slice.position(pos);
                    slice.limit(pos + n);
                    slice.get(inBuf, 0, n);
                    inflater.setInput(inBuf, 0, n);
                    pos += n;
                    remaining -= n;
                }
                int k = inflater.inflate(outBuf);
                if (k > 0) {
                    produced += k;
                    int off = 0;
                    while (off < k) {
                        int m = Math.min(fileBuf.length - fileLen, k - off);
                        System.arraycopy(outBuf, off, fileBuf, fileLen, m);
                        fileLen += m;
                        off += m;
                        if (fileLen == fileBuf.length) {
                            os.write(fileBuf, 0, fileLen);
                            fileLen = 0;
                        }
                    }
                    continue;
                }
                if (inflater.finished()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    Config.w("[DexKit] classes.dex 的 deflate 流需要预设字典，无法解压");
                    return -1;
                }
                if (inflater.needsInput()) {
                    if (remaining <= 0) {
                        Config.w("[DexKit] classes.dex 的 deflate 流提前结束（已产出 " + produced + " 字节）");
                        return -1;
                    }
                    continue;
                }
                Config.w("[DexKit] classes.dex 的 deflate 流状态异常，已产出 " + produced + " 字节");
                return -1;
            }
            if (fileLen > 0) {
                os.write(fileBuf, 0, fileLen);
            }
            os.flush();
            if (entry.uncompSize > 0 && produced != entry.uncompSize) {
                Config.w("[DexKit] classes.dex 解压校验不一致：实得 " + produced + "，目录记录 "
                        + entry.uncompSize);
            }
            return produced;
        } catch (Throwable t) {
            Config.e("[DexKit] classes.dex 解压失败", t);
            return -1;
        } finally {
            inflater.end();
            closeQuietly(os);
        }
    }

    /** zip 里 {@code classes.dex} 的定位结果：存储方式 + 压缩数据区 + 大小。 */
    private static final class DexEntry {
        final long method;
        final long dataOff;
        final long compSize;
        final long uncompSize;

        DexEntry(long method, long dataOff, long compSize, long uncompSize) {
            this.method = method;
            this.dataOff = dataOff;
            this.compSize = compSize;
            this.uncompSize = uncompSize;
        }
    }

    private static String readAscii(MappedByteBuffer map, int offset, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (map.get(offset + i) & 0xFF));
        }
        return sb.toString();
    }

    private static long u16(MappedByteBuffer map, int offset) {
        return map.getShort(offset) & 0xFFFFL;
    }

    private static long u32(MappedByteBuffer map, int offset) {
        return map.getInt(offset) & 0xFFFFFFFFL;
    }

    /**
     * 把拆出来的 dex 写成临时 zip。
     *
     * <p>DexKit 的 {@code AddZipPath} 只认严格连续的 {@code classes.dex} /
     * {@code classes2.dex} / … 入口名，第一个缺口就停止；条目不能加密。这里用 STORED：
     * 解压后的大小与内容完全一致，DexKit 侧对齐时直接零拷贝引用映射，少一次拷贝。</p>
     */
    private static File buildZip(byte[][] images) throws Exception {
        File dir = extractDir();
        if (dir == null) {
            return null;
        }
        sweepOldZips(dir);
        File zip = new File(dir, "hupu-dex-" + System.nanoTime() + ".zip");
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(
                new FileOutputStream(zip), 1 << 16));
        try {
            for (int i = 0; i < images.length; i++) {
                byte[] data = images[i];
                if (data == null || data.length == 0) {
                    continue;
                }
                String name = i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex";
                CRC32 crc = new CRC32();
                crc.update(data, 0, data.length);
                ZipEntry entry = new ZipEntry(name);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write(data, 0, data.length);
                zos.closeEntry();
                images[i] = null;   // 及时放掉，别把 100MB 全压在堆里
            }
        } finally {
            closeQuietly(zos);
        }
        Config.i("[DexKit] 临时 dex zip 已生成：" + zip.getName() + "（" + ((zip.length() / 1024) / 1024) + " MB）");
        return zip;
    }

    /**
     * 清掉本模块上一次留在缓存目录里的临时 dex zip。
     *
     * <p>正常路径上 {@code buildZip} 的结果会在 {@code ensureBridge} 的 finally 里删掉，但
     * 进程被 kill、或者同一目录被另一个进程（虎扑有 pushcore 等独立进程）写坏时会留下
     * 95MB 的残骸。宿主 cache 目录就这么大，攒几个就是几百 MB，所以每次开新 zip 之前先扫一遍。</p>
     */
    private static void sweepOldZips(File dir) {
        try {
            File[] old = dir.listFiles();
            if (old == null) {
                return;
            }
            int removed = 0;
            for (File f : old) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                String name = f.getName();
                if (!name.startsWith("hupu-dex-") || !name.endsWith(".zip")) {
                    continue;
                }
                if (f.delete()) {
                    removed++;
                }
            }
            if (removed > 0) {
                Config.i("[DexKit] 清掉 " + removed + " 个上次残留的临时 dex zip");
            }
        } catch (Throwable ignored) {
            // 清理失败不影响本次解析
        }
    }

    /** 与 {@link #sweepOldZips} 同理，清掉上次解压容器留下的 {@code hupu-container-*.bin}。 */
    private static void sweepOldTemps(File dir) {
        try {
            File[] old = dir.listFiles();
            if (old == null) {
                return;
            }
            int removed = 0;
            for (File f : old) {
                if (f == null || !f.isFile()) {
                    continue;
                }
                String name = f.getName();
                if (!name.startsWith("hupu-container-") || !name.endsWith(".bin")) {
                    continue;
                }
                if (f.delete()) {
                    removed++;
                }
            }
            if (removed > 0) {
                Config.i("[DexKit] 清掉 " + removed + " 个上次残留的临时容器文件");
            }
        } catch (Throwable ignored) {
            // 清理失败不影响本次解析
        }
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static int dexNum(DexKitBridge b) {
        try {
            return b.getDexNum();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void closeBridge() {
        DexKitBridge b = bridge;
        bridge = null;
        bridgeLoader = null;
        if (b != null) {
            try {
                b.close();
                Config.i("[DexKit] bridge 已释放");
            } catch (Throwable t) {
                Config.w("[DexKit] bridge 释放失败：" + t);
            }
        }
    }

    /**
     * 加载 libdexkit.so。
     *
     * <p>首选 {@code System.loadLibrary}：模块 APK 里 4 个 ABI 的 so 都是 STORED 且
     * 4096 页对齐，满足 bionic 与 LSPosed 模块类加载器 {@code findLibrary} 的双重硬要求，
     * 走 LSPosed 注入的 {@code <模块APK>!/lib/<abi>} 搜索路径能直接加载。</p>
     *
     * <p>兜底：个别框架（FPA / LSPatch）路径不同，失败时把 so 从模块 APK 里解出来写到
     * 宿主私有目录再 {@code System.load} 绝对路径 —— 这是 DexKit 官方 issue #14 的配方。</p>
     */
    private static synchronized boolean ensureNative() {
        if (nativeTried) {
            return nativeReady;
        }
        nativeTried = true;
        try {
            System.loadLibrary("dexkit");
            nativeReady = true;
            Config.i("[DexKit] libdexkit.so 已通过模块 APK 的 so 路径加载");
            return true;
        } catch (Throwable t) {
            Config.w("[DexKit] System.loadLibrary(\"dexkit\") 失败：" + t + "，改用解包加载");
        }
        nativeReady = loadFromModuleApk();
        if (!nativeReady) {
            AdsLog.miss("DexKit", "libdexkit.so 加载失败，兜底解析不可用");
        }
        return nativeReady;
    }

    private static boolean loadFromModuleApk() {
        File dir = extractDir();
        if (dir == null) {
            Config.w("[DexKit] 找不到可写目录，无法解出 libdexkit.so");
            return false;
        }
        ClassLoader self = DexKitResolver.class.getClassLoader();
        if (self == null) {
            return false;
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            String entry = "lib/" + abi + "/libdexkit.so";
            InputStream in = null;
            try {
                in = self.getResourceAsStream(entry);
                if (in == null) {
                    continue;
                }
                File out = new File(dir, "libdexkit-" + abi + ".so");
                copy(in, out);
                System.load(out.getAbsolutePath());
                Config.i("[DexKit] 已从模块 APK 解出并加载 " + entry + " -> " + out);
                return true;
            } catch (Throwable t) {
                Config.w("[DexKit] 从 " + entry + " 加载失败：" + t);
            } finally {
                closeQuietly(in);
            }
        }
        return false;
    }

    private static File extractDir() {
        try {
            Context ctx = AdsLog.appContext();
            if (ctx != null) {
                File d = new File(ctx.getCacheDir(), "dexkit");
                if (d.isDirectory() || d.mkdirs()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp != null) {
                File d = new File(tmp, "hupux-dexkit");
                if (d.isDirectory() || d.mkdirs()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void copy(InputStream in, File out) throws Exception {
        OutputStream os = null;
        try {
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            closeQuietly(os);
        }
        out.setReadable(true, false);
        out.setExecutable(true, false);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // 名字 / 类型工具
    // ------------------------------------------------------------------

    private static String simpleName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    private static String packageName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(0, dot) : "";
    }

    private static String shortNameOf(String className) {
        return simpleName(className);
    }

    private static int commonPrefixSegments(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String[] sa = a.split("\\.");
        String[] sb = b.split("\\.");
        int n = Math.min(sa.length, sb.length);
        int i = 0;
        while (i < n && sa[i].equals(sb[i])) {
            i++;
        }
        return i;
    }

    /** 两边都归一化成点号形式再比，兼容「点号名 / 描述符 / 数组 / 原生类型简写」 */
    private static boolean sameType(String a, String b) {
        String na = normalizeType(a);
        String nb = normalizeType(b);
        return na != null && na.equals(nb);
    }

    static String normalizeType(String t) {
        if (t == null) {
            return null;
        }
        String s = t.trim();
        if (s.isEmpty()) {
            return s;
        }
        int depth = 0;
        while (s.startsWith("[")) {
            depth++;
            s = s.substring(1);
        }
        if (s.startsWith("L") && s.endsWith(";") && s.length() > 2) {
            s = s.substring(1, s.length() - 1);
        }
        s = s.replace('/', '.');
        if (s.length() == 1) {
            switch (s.charAt(0)) {
                case 'V': s = "void"; break;
                case 'Z': s = "boolean"; break;
                case 'B': s = "byte"; break;
                case 'C': s = "char"; break;
                case 'S': s = "short"; break;
                case 'I': s = "int"; break;
                case 'J': s = "long"; break;
                case 'F': s = "float"; break;
                case 'D': s = "double"; break;
                default: break;
            }
        }
        if (depth == 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < depth; i++) {
            sb.append("[]");
        }
        return sb.toString();
    }
}
