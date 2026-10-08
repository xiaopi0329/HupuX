package com.hupux.xpnb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 易盾加固 classes.dex「容器」拆分器 —— 纯 Java，只用 JDK 标准库。
 *
 * <p>背景：被易盾加固的 APK 里，classes.dex 并不是一个合法 dex，而是一个容器：
 * 头部 112 字节 + 若干壳类，其后<b>明文拼接</b>了 N 个真实子 dex。每个子 dex 自己的
 * 112 字节头部被加密/抹掉，但正文（含 map_list）完好。所以只要能定位每个子 dex 的
 * map_list，就能反推它的起始偏移，再把被抹掉的头部按 dex 规范重建出来，喂给 dex 解析器
 * （DexKit / ART）使用。</p>
 *
 * <p>定位手段：map_list 的第一个 map_item 恒为 header_item，
 * 即 {@code {type=0, unused=0, size=1, offset=0}}，其前 8 字节表示为
 * {@code 00 00 00 00 01 00 00 00}（u2 type + u2 unused + u4 size），位于
 * {@code mapAbs + 4}（mapAbs 是 map_list 自身的绝对偏移，其前 4 字节是 u4 size）。
 * 子 dex 的头部虽然坏了，但这段自描述结构是明文，于是它成了容器里唯一可靠的锚点。</p>
 *
 * <p>本类是 {@code analysis/scripts/unpack.js} 的逐行等价移植，输出顺序与
 * {@code classes.dex, classes2.dex, ...} 完全一致。整个方法不会向外抛异常：
 * 失败时通过 {@link Reporter} 报告并返回空数组。</p>
 *
 * <p>刻意不依赖任何 Android / Kotlin / 第三方 API，因此同一个 .java 文件既能编进
 * APK 模块，也能被桌面 JDK 17 单独 javac 编译来做离线验证。</p>
 */
public final class DexUnpacker {

    /** 日志回调。允许为 null（内部判空后再调），也允许回调自身抛异常（会被吞掉）。 */
    public interface Reporter {
        void onLog(String msg);
    }

    // ---- dex map_item 的 type 取值（dex format 规范） ----
    private static final int MAP_HEADER = 0x0000;   // header_item
    private static final int MAP_STRING_ID = 0x0001; // string_id_item
    private static final int MAP_TYPE_ID = 0x0002;   // type_id_item
    private static final int MAP_PROTO_ID = 0x0003;  // proto_id_item
    private static final int MAP_FIELD_ID = 0x0004;  // field_id_item
    private static final int MAP_METHOD_ID = 0x0005; // method_id_item
    private static final int MAP_CLASS_DEF = 0x0006; // class_def_item
    private static final int MAP_MAP_LIST = 0x1000;  // map_list
    private static final int MAP_STRING_DATA = 0x2002; // string_data_item

    /** 标准 dex 头长度。子 dex 被抹掉的就是这 112 字节。 */
    private static final int HEADER_SIZE = 112;

    /**
     * map_list 项数的合理区间。下界 6：一个真实 dex 至少有 header / string_id /
     * type_id / proto_id / field_id / method_id / class_def / map_list 这 8 类里的多数，
     * 取 6 只是廉价的前置过滤。上界 64：map_list 紧跟在 dex 尾部，
     * 4 + 64*12 = 772 字节，再大就说明这里的 u4 不是项数，是误命中。
     */
    private static final long MIN_MAP_ITEMS = 6L;
    private static final long MAX_MAP_ITEMS = 64L;

    /**
     * 重建 string_ids 时，要求「从 STRING_DATA 顺序走出来的偏移」与现存表至少在
     * 尾部这么多项上逐项相同，才敢用走出来的结果覆盖前缀。
     *
     * <p>这是整个修复唯一的安全性依据：{@code STRING_DATA} 段里的字符串在文件里的
     * 物理顺序必须与 string_ids 的索引顺序一致（d8/dx 就是这么写的），否则走出来的
     * 只是同一批偏移的一个排列，直接填进表里会把索引全搞错。取 64 是因为它足够长到
     * 不可能偶然全中，又比任何正常 dex 的 string_ids 规模都小得多。
     */
    private static final long MIN_TRUSTED_SUFFIX = 64L;

    /** adler32 的模数（RFC 1950）。 */
    private static final long ADLER_MOD = 65521L;

    /** u32 掩码：Java 的 int 有符号，所有 u32 算术都先提升到 long 再掩码，不依赖溢出行为。 */
    private static final long U32 = 0xFFFFFFFFL;

    /** dex 头里的 map_off 字段（u4），位于 [52,56)。 */
    private static final int OFF_FILE_SIZE = 32;
    private static final int OFF_MAP_OFF = 52;

    private DexUnpacker() {
        // 纯工具类，禁止实例化
    }

    /**
     * 把易盾 dex 容器拆成若干张「头部已重建」的独立 dex 镜像。
     *
     * @param container 容器字节。可以是 {@code MappedByteBuffer}（推荐，避免整份 100MB 进堆）；
     *                  所有偏移都相对 {@code container.position()} 计算，
     *                  可读长度取 {@code remaining()}。
     * @param reporter  进度/诊断日志回调，允许为 null。
     * @return 每张图一个 {@code byte[]}，顺序与 unpack.js 写出的
     *         {@code classes.dex, classes2.dex, ...} 一致；
     *         容器为 null、可读长度不足 112 字节、或中途出现任何异常时返回长度为 0 的数组。
     */
    public static byte[][] unpack(ByteBuffer container, Reporter reporter) {
        try {
            if (container == null) {
                log(reporter, "容器为 null，无法拆分，返回空数组");
                return new byte[0][];
            }

            final int base = container.position();
            final int len = container.remaining();
            if (len < HEADER_SIZE) {
                log(reporter, "容器可读长度 " + len + " < " + HEADER_SIZE + " 字节，装不下一个 dex 头，返回空数组");
                return new byte[0][];
            }
            log(reporter, "容器大小 = " + len + " 字节（position=" + base + ", capacity=" + container.capacity() + "）");

            // duplicate() 只共享同一块内存、不复制内容，所以即便容器是 100MB 的
            // MappedByteBuffer，这里也不会把整份容器搬进堆。显式设成小端，
            // 因为 dex 的所有多字节整数都是小端，且不依赖 duplicate() 是否继承字节序。
            final ByteBuffer buf = container.duplicate();
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ---------- 第 1 步：扫描候选 map_list ----------
            // 特征串 00 00 00 00 01 00 00 00 出现在 mapAbs+4。之所以不用「每个 dex 头里的
            // map_off 字段」来定位，是因为那些头已经被易盾抹掉了，容器里唯一还指向真实
            // map_list 的锚点，就是 map_list 自己的第一个 map_item。
            final List<Long> candidateMapAbs = new ArrayList<Long>();
            int hits = 0;
            int badSize = 0;
            int badLen = 0;

            for (int rel = 0; rel + 8 <= len; rel++) {
                final int i = base + rel;
                // 特征串的第 5 个字节是 0x01，先拿它做快速筛，避免逐字节比 8 次。
                if (buf.get(i + 4) != 0x01) {
                    continue;
                }
                if (buf.get(i) != 0 || buf.get(i + 1) != 0 || buf.get(i + 2) != 0 || buf.get(i + 3) != 0
                        || buf.get(i + 5) != 0 || buf.get(i + 6) != 0 || buf.get(i + 7) != 0) {
                    continue;
                }
                hits++;

                // 特征串起点是 mapAbs+4，故 map_list 自身从 rel-4 开始。
                final long mapAbs = (long) rel - 4L;
                if (mapAbs < 4L) {
                    badSize++;
                    continue;
                }

                final long n = u32(buf, base + (int) mapAbs);
                if (n < MIN_MAP_ITEMS || n > MAX_MAP_ITEMS) {
                    badSize++;
                    continue;
                }
                if (mapAbs + 4L + n * 12L > (long) len) {
                    badLen++;
                    continue;
                }
                candidateMapAbs.add(mapAbs);
            }
            log(reporter, "特征串命中 " + hits + " 次，其中通过初步校验（mapAbs>=4、6<=n<=64、map_list 完整落在容器内）的候选 "
                    + candidateMapAbs.size() + " 个（项数不合法 " + badSize + " 个，越界 " + badLen + " 个）");

            // ---------- 第 2 步：解析每个候选的 map_item 并校验 ----------
            // 用 LinkedHashMap 按 dexStart 去重：键相同时后写覆盖先写（与 unpack.js 的
            // Map.set 语义一致，即重复候选保留最后一个）。
            final Map<Long, DexCandidate> uniq = new LinkedHashMap<Long, DexCandidate>();
            int rejected = 0;
            int duplicates = 0;
            for (int c = 0; c < candidateMapAbs.size(); c++) {
                final long mapAbs = candidateMapAbs.get(c).longValue();
                final long n = u32(buf, base + (int) mapAbs);

                final long[] types = new long[(int) n];
                final long[] sizes = new long[(int) n];
                final long[] offs = new long[(int) n];
                for (int k = 0; k < n; k++) {
                    final int p = base + (int) mapAbs + 4 + k * 12;
                    // MapItem 布局：u2 type @+0，u2 unused @+2，u4 size @+4，u4 offset @+8
                    types[k] = u16(buf, p);
                    sizes[k] = u32(buf, p + 4);
                    offs[k] = u32(buf, p + 8);
                }

                if (types[0] != MAP_HEADER || offs[0] != 0L) {
                    rejected++;
                    continue;
                }
                final int sidIdx = indexOfType(types, MAP_STRING_ID);
                final int mlIdx = indexOfType(types, MAP_MAP_LIST);
                if (sidIdx < 0 || mlIdx < 0) {
                    rejected++;
                    continue;
                }
                if (offs[sidIdx] != (long) HEADER_SIZE) {
                    // string_ids 紧跟在 112 字节头后面，所以它的 off 必然等于 112。
                    // 这一条把「看起来像 map_list 的数据」和真正的 map_list 区分开，
                    // 也是 unpack.js 里最关键的假阳性过滤器。
                    rejected++;
                    continue;
                }

                // map_list 自身相对 dex 起点的偏移就是 map_off，于是 dexStart = mapAbs - map_off。
                final long dexStart = mapAbs - offs[mlIdx];
                if (dexStart < 0L) {
                    rejected++;
                    continue;
                }

                final DexCandidate dc = new DexCandidate(dexStart, mapAbs, n, types, sizes, offs);
                final DexCandidate prev = uniq.put(Long.valueOf(dexStart), dc);
                if (prev != null) {
                    duplicates++;
                }
            }
            log(reporter, "候选 map_list 校验：通过 " + uniq.size() + " 个，被结构校验拒绝 " + rejected
                    + " 个，dexStart 重复 " + duplicates + " 个");

            // 按 dexStart 升序排列 —— 容器的物理拼接顺序就是 dex 的加载顺序。
            final List<DexCandidate> list = new ArrayList<DexCandidate>(uniq.values());
            list.sort((p, q) -> Long.compare(p.dexStart, q.dexStart));
            log(reporter, "去重排序后得到 " + list.size() + " 个子 dex 候选区间");

            // ---------- 第 3 步：逐张重建头部并计算校验 ----------
            final MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            final List<byte[]> out = new ArrayList<byte[]>(list.size());

            for (int idx = 0; idx < list.size(); idx++) {
                final DexCandidate d = list.get(idx);
                final long start = d.dexStart;
                // 非最后一张：下一个 dex 的起点就是本张的终点（容器里没有任何对齐/填充）。
                // 最后一张：终点是它自己 map_list 的结尾 mapAbs+4+n*12，而不是容器 EOF ——
                // 尾部可能还挂着壳类或易盾的附加数据，不属于这张 dex。
                final long end = (idx + 1 < list.size())
                        ? list.get(idx + 1).dexStart
                        : d.mapAbs + 4L + d.n * 12L;
                final long size = end - start;
                final long fileSize = size; // 整段区间就属于这一张 dex

                if (size <= 0L || size > (long) Integer.MAX_VALUE) {
                    log(reporter, "丢弃第 " + idx + " 张图 [start=" + start + ", end=" + end
                            + "]：size=" + size + " 不合理");
                    continue;
                }
                // 防御性边界检查。按上面的构造，end <= len 恒成立（候选校验里已经保证
                // map_list 完整落在容器内，且 start < end），这里只是不让越界读变成抛异常。
                if (start < 0L || end > (long) len) {
                    log(reporter, "丢弃第 " + idx + " 张图 [start=" + start + ", end=" + end
                            + "]：超出容器范围 [0," + len + ")");
                    continue;
                }

                final byte[] body = new byte[(int) size];
                // 只把这一段复制进堆：调用方最终要把每张图交给 native，byte[] 是必需产物；
                // 但整份 100MB 容器始终留在原始 ByteBuffer 里，不进堆。
                final ByteBuffer slice = container.duplicate();
                slice.limit(base + (int) end);
                slice.position(base + (int) start);
                slice.get(body);

                final long dataOff = minDataOff(d);
                if (dataOff < 0L) {
                    // 不可能发生：map_list 项(type=0x1000)本身不在 ids 白名单里，
                    // 而它是被强制要求存在的，所以最小值至少等于 map_off。
                    log(reporter, "丢弃第 " + idx + " 张图：map_list 里没有任何 data 段项，无法确定 data_off");
                    continue;
                }

                final byte[] h = new byte[HEADER_SIZE];
                // magic "dex\n035\0"（u1[8]）
                h[0] = 'd';
                h[1] = 'e';
                h[2] = 'x';
                h[3] = '\n';
                h[4] = '0';
                h[5] = '3';
                h[6] = '5';
                h[7] = 0;
                // [8,12) checksum 与 [12,32) signature 都是派生值，最后才写
                putU32(h, OFF_FILE_SIZE, fileSize);
                putU32(h, 36, HEADER_SIZE);       // header_size
                putU32(h, 40, 0x12345678L);       // endian_tag（常量 ENDIAN_CONSTANT）
                putU32(h, 44, 0L);                // link_size：不允许动态链接，置 0
                putU32(h, 48, 0L);                // link_off
                putU32(h, OFF_MAP_OFF, d.mapAbs - start); // map_off 必须相对本 dex 起点

                putTable(h, 56, 60, d, MAP_STRING_ID);
                putTable(h, 64, 68, d, MAP_TYPE_ID);
                putTable(h, 72, 76, d, MAP_PROTO_ID);
                putTable(h, 80, 84, d, MAP_FIELD_ID);
                putTable(h, 88, 92, d, MAP_METHOD_ID);
                putTable(h, 96, 100, d, MAP_CLASS_DEF);

                // data 段的起点：只统计真正的 data 项，必须排除 header/string_id/type_id/
                // proto_id/field_id/method_id/class_def 这 7 个 ids/索引段。
                // 为什么：dex 规范里 data_off 指向「第一个 data 段项」，而 ids 段在它之前。
                // 若不排除，最小值会被 string_ids.off(=112) 抢走，data_off 就永远是 112，
                // data_size 也会虚报成 fileSize-112；DexKit 的 dataPtr() 依赖
                // offset >= header_->data_off 来判断「这是不是 data 段指针」，报错了会把
                // 索引段当成 data 段去解析。
                putU32(h, 104, fileSize - dataOff); // data_size
                putU32(h, 108, dataOff);            // data_off

                // 易盾抹掉的是每段前 4096 字节，不只是 112 字节的头：string_ids 表从 112
                // 开始、每项 4 字节，所以前 996 项（112 + 996*4 == 4096）的 string_data_off
                // 也是密文。slicer 解析字符串时会走到 dataPtr<u1>(string_data_off)，偏移越界
                // 就 SLICER_CHECK_GE 失败直接 abort（reader.h:119）—— 这就是宿主进程
                // SIGABRT 的真正原因。STRING_DATA 段在 4096 之后、是明文，用它把表重建出来。
                if (!repairStringIds(body, d, dataOff, idx, reporter)) {
                    log(reporter, "丢弃第 " + idx + " 张图：string_ids 无法确认，留着会让 dex 解析器 abort");
                    continue;
                }

                System.arraycopy(h, 0, body, 0, HEADER_SIZE);

                // 顺序有硬性要求：先写 SHA-1，再算 adler32。
                // signature 覆盖 [32, file_size)，也就是「头之后的一切」，它不包含 checksum；
                // 而 checksum 覆盖 [12, file_size)，正好把 20 字节 signature 包在里面。
                // 所以必须先把 signature 落定，才算得出正确的 checksum。反过来做的话，
                // 算 checksum 时 [12,32) 还是全 0，最终文件的自校验一定失败。
                sha1.reset();
                sha1.update(body, 32, body.length - 32);
                final byte[] signature = sha1.digest();
                System.arraycopy(signature, 0, body, 12, signature.length);

                putU32(body, 8, adler32(body, 12, body.length));

                out.add(body);

                log(reporter, "[" + idx + "] " + dexName(idx) + " start=" + start + " end=" + end + " size=" + size
                        + " map_off=" + (d.mapAbs - start) + " data_off=" + dataOff
                        + " class_defs=" + tableSize(d, MAP_CLASS_DEF) + " string_ids=" + tableSize(d, MAP_STRING_ID)
                        + " type_ids=" + tableSize(d, MAP_TYPE_ID) + " method_ids=" + tableSize(d, MAP_METHOD_ID));
            }

            log(reporter, "最终返回 " + out.size() + " 张 dex 镜像");
            return out.toArray(new byte[0][]);
        } catch (Throwable t) {
            // 健壮性硬要求：任何异常都不能冒泡到调用方（调用方在启动关键路径上），
            // 失败就报告 + 返回空数组，让上层走「拆分失败」的分支。
            log(reporter, "unpack 过程中出现异常，返回空数组：" + t);
            return new byte[0][];
        }
    }

    // ------------------------------------------------------------------
    // 内部数据结构
    // ------------------------------------------------------------------

    /** 一个通过全部校验的候选子 dex。 */
    private static final class DexCandidate {
        final long dexStart;   // 该 dex 在容器里的绝对起点
        final long mapAbs;     // 该 dex 的 map_list 在容器里的绝对位置
        final long n;          // map_list 项数
        final long[] types;
        final long[] sizes;
        final long[] offs;

        DexCandidate(long dexStart, long mapAbs, long n, long[] types, long[] sizes, long[] offs) {
            this.dexStart = dexStart;
            this.mapAbs = mapAbs;
            this.n = n;
            this.types = types;
            this.sizes = sizes;
            this.offs = offs;
        }
    }

    // ------------------------------------------------------------------
    // 内部助手
    // ------------------------------------------------------------------

    /** 与 unpack.js 的 {@code classes${idx === 0 ? '' : idx + 1}.dex} 命名保持一致。 */
    private static String dexName(int idx) {
        return idx == 0 ? "classes.dex" : "classes" + (idx + 1) + ".dex";
    }

    private static int indexOfType(long[] types, int type) {
        for (int k = 0; k < types.length; k++) {
            if (types[k] == (long) type) {
                return k;
            }
        }
        return -1;
    }

    private static long tableSize(DexCandidate d, int type) {
        final int k = indexOfType(d.types, type);
        return k < 0 ? 0L : d.sizes[k];
    }

    /**
     * data 段起点 = 所有「不属于 7 个 ids/索引段」的 map_item 里 off 的最小值。
     * 至少会命中 map_list 自己这一项，所以正常路径下不会返回 -1。
     */
    private static long minDataOff(DexCandidate d) {
        long min = -1L;
        for (int k = 0; k < d.n; k++) {
            final int t = (int) d.types[k];
            if (t == MAP_HEADER || t == MAP_STRING_ID || t == MAP_TYPE_ID || t == MAP_PROTO_ID
                    || t == MAP_FIELD_ID || t == MAP_METHOD_ID || t == MAP_CLASS_DEF) {
                continue;
            }
            if (min < 0L || d.offs[k] < min) {
                min = d.offs[k];
            }
        }
        return min;
    }

    /**
     * 重建 string_ids 表。
     *
     * 易盾把每段前 4096 字节整体换成了别的内容，其中就包括 string_ids 表从 112 开始的前
     * 996 项（112 + 996*4 == 4096）。这些 string_data_off 是垃圾值，slicer 解析字符串时会
     * 走到 dataPtr&lt;u1&gt;(string_data_off) 并因偏移越界 SLICER_CHECK_GE 失败直接 abort
     * （reader.h:119）—— 宿主进程 SIGABRT 的真正原因。
     *
     * 好消息是 string_data_item 段（map type 0x2002）在 4096 之后、是明文，而且 d8/dx 就是按
     * string_ids 索引顺序依次写出这些 item 的，所以顺序走一遍就能把整张表算回来。
     *
     * 但「顺序」这个前提必须验证，否则算出来的只是同一批偏移的某种排列，填回去会把索引全部
     * 错位。验证办法：拿走出来的偏移与现存表从尾部往前逐项比对，只有尾部至少
     * MIN_TRUSTED_SUFFIX 项完全一致（说明顺序假设成立）才采纳。
     *
     * @return true 表示这张图可以继续用（表本来就是好的，或者已经修好了）；false 表示无法
     *         确认，调用方应当丢弃这张图 —— 留着它只会让 DexKit 再次 abort 宿主进程。
     */
    private static boolean repairStringIds(byte[] body, DexCandidate d, long dataOff, int idx,
                                           Reporter reporter) {
        final int sidIdx = indexOfType(d.types, MAP_STRING_ID);
        if (sidIdx < 0) {
            return true;    // 没有 string_ids 表，无从修也无需修
        }
        final long sidSize = d.sizes[sidIdx];
        final long sidOff = d.offs[sidIdx];
        if (sidSize <= 0L || sidSize > (long) Integer.MAX_VALUE) {
            return false;
        }
        final int count = (int) sidSize;
        if (sidOff < 0L || sidOff + sidSize * 4L > (long) body.length) {
            log(reporter, "第 " + idx + " 张图的 string_ids 表越界（off=" + sidOff
                    + " size=" + count + "）");
            return false;
        }

        final int sdIdx = indexOfType(d.types, MAP_STRING_DATA);
        final long[] walk = sdIdx < 0
                ? null
                : walkStringData(body, d.offs[sdIdx], d.sizes[sdIdx], count);
        if (walk == null) {
            // 走不出来（string_data_item 段本身落在被毁区，或长度对不上）：只要现存表自己
            // 完全自洽，就当作没被破坏。
            if (existingTableSane(body, (int) sidOff, count, dataOff)) {
                return true;
            }
            log(reporter, "第 " + idx + " 张图无法重建 string_ids（string_data_item 段不可用）");
            return false;
        }

        // 从尾部往前数，走出来的偏移与现存表连续相同的项数。
        int suffix = 0;
        while (suffix < count && walk[count - 1 - suffix]
                == u32(body, (int) sidOff + (count - 1 - suffix) * 4)) {
            suffix++;
        }
        if (suffix == count) {
            return true;    // 整张表都对得上，本来就是好的
        }
        if (suffix < MIN_TRUSTED_SUFFIX) {
            log(reporter, "第 " + idx + " 张图的 string_ids 只能对上尾部 " + suffix + " 项（需要 "
                    + MIN_TRUSTED_SUFFIX + " 项），顺序假设不成立，丢弃");
            return false;
        }

        for (int i = 0; i < count; i++) {
            putU32(body, (int) sidOff + i * 4, walk[i]);
        }
        log(reporter, "第 " + idx + " 张图重建 string_ids：" + (count - suffix) + " 项被毁、"
                + suffix + " 项校验一致");
        return true;
    }

    /**
     * 顺序走一遍 string_data_item 段，返回每个 item 的起始偏移。
     *
     * 每个 item 是「uleb128 utf16_size + MUTF-8 字节 + 0x00 终止符」。走到第 expect 个 item
     * 就停 —— string_ids 表里有几项就该有几项，多一个少一个都说明走法不对。
     *
     * @return 长度为 expect 的偏移数组；任何一步对不上都返回 null。
     */
    private static long[] walkStringData(byte[] body, long sdOff, long sdSize, int expect) {
        if (sdOff < 0L || sdOff >= (long) body.length || sdSize != (long) expect) {
            return null;
        }
        final long end = (long) body.length;
        final long[] offs = new long[expect];
        long p = sdOff;
        for (int i = 0; i < expect; i++) {
            if (p >= end) {
                return null;
            }
            offs[i] = p;
            // uleb128 utf16_size：u4 最多 5 字节，第 5 字节后必须收尾。
            int shift = 0;
            while (true) {
                if (p >= end || shift > 28) {
                    return null;
                }
                final int b = body[(int) p++] & 0xFF;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            // MUTF-8 字节，直到 0x00 终止符。
            while (true) {
                if (p >= end) {
                    return null;
                }
                final int c = body[(int) p++] & 0xFF;
                if (c == 0) {
                    break;
                }
                final int extra;
                if ((c & 0x80) == 0) {
                    extra = 0;
                } else if ((c & 0xE0) == 0xC0) {
                    extra = 1;
                } else if ((c & 0xF0) == 0xE0) {
                    extra = 2;
                } else if ((c & 0xF8) == 0xF0) {
                    extra = 3;
                } else {
                    return null;    // 非法前导字节：说明走进了被毁区
                }
                if (p + extra > end) {
                    return null;
                }
                for (int k = 0; k < extra; k++) {
                    if ((body[(int) p++] & 0xC0) != 0x80) {
                        return null;
                    }
                }
            }
        }
        return offs;
    }

    /**
     * 保守检查现存 string_ids 表是否自洽：每一项都指向 data 段之内，并且从该处开始能解析出
     * 一个完整的 string_data_item。用于「走序不可用」时的兜底判断。
     */
    private static boolean existingTableSane(byte[] body, int sidOff, int count, long dataOff) {
        for (int i = 0; i < count; i++) {
            final long v = u32(body, sidOff + i * 4);
            if (v < dataOff || v >= (long) body.length || !stringItemParses(body, (int) v)) {
                return false;
            }
        }
        return true;
    }

    /** 从 off 开始能否解析出一个完整的 string_data_item（uleb128 + MUTF-8 + 0x00）。 */
    private static boolean stringItemParses(byte[] body, int off) {
        final int end = body.length;
        int p = off;
        int shift = 0;
        while (true) {
            if (p >= end || shift > 28) {
                return false;
            }
            final int b = body[p++] & 0xFF;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        while (true) {
            if (p >= end) {
                return false;
            }
            final int c = body[p++] & 0xFF;
            if (c == 0) {
                return true;
            }
            final int extra;
            if ((c & 0x80) == 0) {
                extra = 0;
            } else if ((c & 0xE0) == 0xC0) {
                extra = 1;
            } else if ((c & 0xF0) == 0xE0) {
                extra = 2;
            } else if ((c & 0xF8) == 0xF0) {
                extra = 3;
            } else {
                return false;
            }
            if (p + extra > end) {
                return false;
            }
            for (int k = 0; k < extra; k++) {
                if ((body[p++] & 0xC0) != 0x80) {
                    return false;
                }
            }
        }
    }

    /** 从 map_list 里取指定 type 的项，把 size/off 写进 112 字节头；缺项写 0（与 unpack.js 一致）。 */
    private static void putTable(byte[] h, int sizeOff, int offOff, DexCandidate d, int type) {
        final int k = indexOfType(d.types, type);
        putU32(h, sizeOff, k < 0 ? 0L : d.sizes[k]);
        putU32(h, offOff, k < 0 ? 0L : d.offs[k]);
    }

    /** 小端写 u4。值先掩到低 32 位，保证 int 溢出不会污染高位。 */
    private static void putU32(byte[] a, int off, long v) {
        a[off] = (byte) (v & 0xFFL);
        a[off + 1] = (byte) ((v >>> 8) & 0xFFL);
        a[off + 2] = (byte) ((v >>> 16) & 0xFFL);
        a[off + 3] = (byte) ((v >>> 24) & 0xFFL);
    }

    /** 小端读 u4，结果提升为无符号 long。 */
    private static long u32(ByteBuffer b, int index) {
        return ((long) b.getInt(index)) & U32;
    }

    /** 小端读 u4（数组版），结果提升为无符号 long。 */
    private static long u32(byte[] a, int off) {
        return (a[off] & 0xFFL)
                | ((a[off + 1] & 0xFFL) << 8)
                | ((a[off + 2] & 0xFFL) << 16)
                | ((a[off + 3] & 0xFFL) << 24);
    }

    /** 小端读 u2，结果提升为无符号 long。 */
    private static long u16(ByteBuffer b, int index) {
        return ((long) b.getShort(index)) & 0xFFFFL;
    }

    /**
     * adler32（RFC 1950），自实现以免依赖 java.util.zip。逐字节模 65521，
     * 与 unpack.js 的实现完全等价（JS 里 a、c 都是整数，不存在浮点误差）。
     */
    private static long adler32(byte[] b, int from, int to) {
        long a = 1L;
        long c = 0L;
        for (int k = from; k < to; k++) {
            a = (a + (long) (b[k] & 0xFF)) % ADLER_MOD;
            c = (c + a) % ADLER_MOD;
        }
        return ((c << 16) | a) & U32;
    }

    /**
     * 安全地调 reporter：null 直接跳过；回调自己抛异常也不能影响拆分流程
     * （否则一个爱抛异常的日志实现会把 21 张图全丢掉）。
     */
    private static void log(Reporter reporter, String msg) {
        if (reporter == null) {
            return;
        }
        try {
            reporter.onLog(msg);
        } catch (Throwable ignored) {
            // 回调异常一律吞掉
        }
    }
}
