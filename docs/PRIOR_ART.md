# 既有工作调研（Prior Art）

> 目的：看看别人对虎扑做过什么，避免重复造轮子，也避免踩同样的坑。
> 下面每一条都注明了来源；没查证的一律标注"未验证"。

---

## 1. AmamiyaHotaru / HupuBlackList

- 地址：https://github.com/AmamiyaHotaru/HupuBlackList
- 定位：一款 **LSPosed/Xposed 插件**，给虎扑加**本地黑名单**功能（隐藏被拉黑用户的发帖/回复）。
- 已实现功能（作者 README）：
  - 隐藏拉黑用户的专区发帖
  - 折叠拉黑用户的专区回复
  - 关键词屏蔽
  - （未完成）隐藏拉黑用户的首页推荐
- 安装方式值得注意：**面向未 Root 用户，提供了两条路**
  1. 用 **Shizuku + LSPatch** 把模块直接补丁进虎扑 APK；
  2. 下载作者**已经补丁好的虎扑安装包**。
  这对我们很有参考价值——如果测试机不方便装 LSPosed 模块，LSPatch 是备选路线。
- 作者自述"能正常运行的部分全是 GPT 完成，出现的 BUG 全是我导致的"，
  并且提醒**卸载虎扑或清数据会导致黑名单数据清空**——说明它的数据存在虎扑自己的私有目录里。
- 对我方的启发：黑名单这种"改数据展示"的功能，需要 hook 列表/适配器层；
  而本课题做的"去广告"更靠上游（广告加载入口），两者 Hook 点不重合。

## 2. 593653436 / hupu-xposed-detection-analysis

- 地址：https://github.com/593653436/hupu-xposed-detection-analysis
- 定位：虎扑 **Xposed 检测机制**的完整逆向分析记录（含自制的 Android/arm64 分析工具源码）。
- 核心结论（作者原文）：虎扑会报**三句不同的话**，对应三个完全不同的原因：

| # | 提示文案 | 触发条件 | 作者验证状态 |
|---|---|---|---|
| 1 | 检测到 root 权限 | KernelSU 给该应用授予 root（`allow: 1`）→ `/system/bin/su` 对它可见 | 已复现 |
| 2 | **检测到该应用在 hook 环境中运行** | **LSPosed 往该应用进程里注入任何东西**（不论模块是否加载成功） | 已复现 3 次 |
| 3 | 检测到 Xposed 环境 | 系统存在 Xposed / Riru / Magisk 的**残留文件** | 仅历史记录中出现，未复现 |

- 几条很有价值的实测事实：
  - 检测是**三段式**：Java 层文件/包查询 + `/proc/self/maps` 扫描（启动期 68 次）+ `stat` 探测；
  - **native 层没有 Xposed 字样**：69 个 `.so` 与 classes.dex 做 ASCII + UTF-16LE 双编码扫描，
    `xposed` / `magisk` / `frida` / `substrate` 一处都没有，全在 Java 层
    （包括 `xposedmodule` / `xposedminversion` —— 即**扫描已安装模块的清单**）；
  - **它从不执行命令**：启动期 `faccessat` 947 次、`newfstatat` 459 次、`openat` 462 次，
    而 `execve` / `execveat` = **0** 次，纯 `stat` 探测；
  - `/data/adb` 权限是 `0700`，所以应用探任何 `/data/adb/...` 只能拿到 `EACCES`，
    而 `EACCES` 被容忍 → **这一整类路径在结构上不可能成为触发点**；
  - KernelSU 的 su 隐藏按 `allow` 决定（不在名单里的 uid 也得到 `ENOENT`），
    只有 `allow=1` 的应用才看得见 `/system/bin/su`；
  - 触发后的"处理"是**干净退出**，不是崩溃（日志里没有 `FATAL` / `signal` / `tombstone`）。

### 与本次分析（8.2.63 样本）的对照

我们在自己还原出的 dex 里**确认了检测字符串的存在**：

```
classes10.dex: xposedmodule(59335), xposedminversion,
               de.robv.android.xposed.XposedBridge / XposedHelpers / XposedInit,
               de.robvf.android.xposed.XposedHelpers   <- 注意拼写是 robvf
classes9.dex / classes14.dex: de.robv.android.xposed..."
多处: /proc/self/maps
```

**但**：

1. 我们在 `classes10.dex` 的 50,177 个有代码的方法里逐条扫描 `const-string` /
   `const-string/jumbo` 指令，**没有一条指令引用 `xposedmodule`**；
2. 那三句提示文案（"检测到Xposed环境"等）在 8.2.63 的 dex 与 `resources.arsc` 里
   **都搜不到**（UTF-8 / UTF-16 都试过）。

**结论**：那份报告对应的应该是**另一个（更早的）虎扑版本**；8.2.63 保留了检测字符串，
但当前没有证据表明它在运行时会真的触发。**这一点必须真机验证**（见 REPORT 第 7 节）。

## 3. 网易易盾加固的脱壳资料

本项目**没有**使用现成的脱壳机（FART / BlackDex / Youpk），而是自己写脚本离线脱壳。
原因是本样本属于易盾里较简单的一类：**dex 正文明文、只有 112 字节头部被处理**，
不需要在运行时 dump。

通用思路对比：

| 方法 | 原理 | 适用 | 缺点 |
|---|---|---|---|
| **本项目：扫 map_list 重建头部** | map_list 完好且自洽，可反推全部表位置 | 头部加密/清零，正文明文 | 对"指令抽取（VMP）"无效 |
| FART / Youpk 等主动调用脱壳 | 运行时遍历 `DexFile`，主动触发所有方法解密后 dump | 通用性强 | 需要 Root + 运行时环境，会被反调试针对 |
| BlackDex 等内存漫游 | 扫 `/proc/self/mem` 找 dex 魔数特征 | 通用 | 同样需要 Root；只对内存中已解密的 dex 有效 |
| 定制类加载器 / 修改 `openInMemoryDexFile` | hook 框架加载点，直接截获解密后的 ByteBuffer | 精准 | 需要知道加固方案的具体加载路径 |

**教训**：脱壳前先做**熵分析 + 字符串统计**，能快速判断"正文到底是明文还是密文"，
少走很多弯路（本项目就是靠 `com/hupu` 出现 4.5 万次、熵只有 6.29 判定为明文的）。

## 4. libxposed / LSPosed 版本要求

- Maven Central 上 `io.github.libxposed:api` 只有 **101.0.0 / 101.0.1 / 102.0.0** 三个版本
  （已核实 `maven-metadata.xml`），**没有 1.x**。
- 现代 API（`module.prop` + `minApiVersion`）需要 **LSPosed 1.10.0 及以上**。
- 更早的 LSPosed（如 1.8.x / 1.9.x）只支持老的 `de.robv.android.xposed` API
  （`assets/xposed_init` + `IXposedHookLoadPackage`）。
- 因此**要先确认测试机上的 LSPosed 版本**：不确定的话，可以用模块 App 的日志或
  `adb logcat` 观察框架启动日志；实在不确定，退路是用 LSPatch 把模块补丁进 APK
  （见第 1 节 HupuBlackList 的做法）。

## 5. 参考链接汇总

- https://github.com/AmamiyaHotaru/HupuBlackList
- https://github.com/593653436/hupu-xposed-detection-analysis
- https://github.com/libxposed/api
- https://github.com/LSPosed/LSPosed
- https://github.com/LSPosed/LSPatch
- https://repo.maven.apache.org/maven2/io/github/libxposed/
