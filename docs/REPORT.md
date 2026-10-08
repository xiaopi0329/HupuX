# 虎扑 Android 客户端分析报告 + Xposed 模块实现

> 课题：面向虎扑（com.hupu.games）的 Xposed 模块
> 样本版本：8.2.63.09241（versionCode 12314）
> 撰写日期：2026-10-03

---

> ⚠️ **两处已过时，模块实现以 [`../README.md`](../README.md) 为准。**
>
> 1. 本报告第 3 节的脱壳方法只重建了每段 dex 的 **112 字节头**，
>    **遗漏了壳对每段子 dex 前 4096 字节的整体置乱** —— 其中含 `string_ids` 表的
>    前 996 项（`112 + 996*4 == 4096`）。DexKit 兜底解析正是在这一点上撞了
>    native `abort()`（宿主两进程同时 SIGABRT）。完整修复见
>    [`../app/src/main/java/com/hupux/xpnb/DexUnpacker.java`](../app/src/main/java/com/hupux/xpnb/DexUnpacker.java)
>    的 `repairStringIds` 与 [`../README.md`](../README.md) 的「1b. DexKit 兜底解析」。
> 2. 可用的真实 dex 是 **20 段**（第 21 段 `classes21.dex` 的 `STRING_DATA` 段
>    本身落在被毁区，无法修复，已被丢弃），不是 21 段。
>
> 另外，本报告描述的是 **8.2.63** 的分析结论；虎扑升级后类名可能变化，
> 模块为此接入了 DexKit 兜底。

## 0. 摘要

本报告完成了三件事：

1. **确认虎扑 8.2.63 使用了网易易盾加固**，并从 APK 中离线完整还原出被保护的真实 dex（21 段、82,282 个类、521,591 个方法、721,739 条字符串常量）。
2. 基于还原出的代码，梳理了虎扑的**广告体系、开屏广告链路、VIP 体系、网络层**，并给出可直接落地的 Hook 点。
3. 实现了一个基于 **libxposed 现代 Xposed API（API 102）** 的模块 `HupuX`，源码在 `module/`，产物 APK 已生成。

核心结论一句话：**虎扑虽然用了易盾加固，但只加密了每个子 dex 的 112 字节头部，dex 正文（方法体、字符串、注解）全部是明文拼接在壳 dex 尾部；通过扫描 `map_list` 重建头部即可离线脱壳，因此模块可以按"类名 + 方法名"做精确 Hook，而不需要盲猜。**

---

## 1. 样本基本信息

| 项目 | 值 |
|---|---|
| 包名 | `com.hupu.games` |
| 版本名 / 版本号 | `8.2.63.09241` / `12314` |
| 应用名 | 虎扑 |
| APK 大小 | 77,683,924 字节 |
| minSdk / targetSdk / compileSdk | 24 / 30 / 33 |
| 原生库 | 仅 `arm64-v8a`（77 个 .so） |
| 启动 Activity | `com.hupu.games.main.MainActivity` |
| Activity 总数 | 340 |
| dex 数量 | 1 个壳 dex（+100MB 尾部数据） |

> 说明：APK 文件 `hupu_8.2.63.apk` 在项目根目录，来自国内应用市场镜像（豌豆荚 / 应用宝均可下到同版本）。

---

## 2. 加固识别：网易易盾

三条独立证据互相印证：

1. **Application 被替换**
   `AndroidManifest.xml` 中 `application android:name="com.netease.nis.wrapper.MyApplication"`。
   `nis` = NetEase Information Security（网易易盾），这是易盾加固最典型的特征。

2. **壳类集中**
   反编译 `classes.dex` 后只有 **30 个类**，全部集中在 `com.netease.nis.wrapper.*`：

   ```
   Lcom/netease/nis/wrapper/Entry;
   Lcom/netease/nis/wrapper/MyApplication;      <- manifest 里的 Application
   Lcom/netease/nis/wrapper/MyJni;
   Lcom/netease/nis/wrapper/Utils;
   Lcom/netease/nis/wrapper/b;                   <- extends ClassLoader，真正加载业务代码的壳加载器
   Lcom/netease/nis/wrapper/g;                   <- extends AppComponentFactory
   Lcom/netease/nis/wrapper/plugin/InstrumentationProxy;  <- 替换 Instrumentation
   ...（其余为 a~p 等混淆类）
   ```

   其中 `com.netease.nis.wrapper.b extends ClassLoader` 和
   `com.netease.nis.wrapper.plugin.InstrumentationProxy extends Instrumentation`
   说明壳在**类加载器**和**Instrumentation**两个层面接管了应用启动。

3. **原生库**
   `lib/arm64-v8a/libnesec.so`、`libnshelper.so`（易盾 native 层），
   `assets/nm_core_v2.0.0_524e1d07.dat`（易盾 core 数据，`nm_` 前缀）。
   壳 dex 字符串表里还存在 `7.6.3_943`，形如易盾版本号。

---

## 3. 脱壳过程（本报告的技术核心）

### 3.1 现象观察

`classes.dex` 头部声明 `file_size = 100,078,172`，但 `class_defs_size = 30`、
`string_ids_size = 1032`——**一个只有 30 个类的 dex 不可能有 100MB**。
对全文做字节统计发现：

| 指标 | 值 | 含义 |
|---|---|---|
| `com/hupu` 字节串出现次数 | 45,027 | 业务代码确实在文件里 |
| `Lcom/hupu` 出现次数 | 38,613 | 有大量类描述符 |
| 尾部（200KB 之后）香农熵 | 6.287 bit/byte | **远低于加密数据的 8.0**，说明是明文/明文近似数据 |

结论：**真实 dex 是明文拼接在壳 dex 后面的，只是每个子 dex 的头部被处理过。**

### 3.2 定位子 dex：扫描 `map_list`

dex 格式里 `map_list` 是"目录"，它的第一项固定是 header 自身：

```
map_item[0] = { type=0x0000, unused=0, size=1, offset=<header 在文件内的偏移> }
```

对应字节就是 `00 00 00 00 01 00 00 00`。于是可以全文扫描这个特征，再用两条规则校验：

- 该 map 必须同时含 `TYPE_MAP_LIST(0x1000)` 项，且它的 `offset` 满足
  `子dex起始 = map绝对位置 - 该item的offset`（自洽性检查）；
- `TYPE_STRING_ID` 项的 offset 必须是 `112`（标准 dex 中 string_ids 紧跟 112 字节头部）。

用这个方法一共定位到 **21 个子 dex**，且它们是**首尾相连**的。

### 3.3 重建头部

每个子 dex 的 112 字节头部不可用（内容呈随机分布），但除了头部以外的**所有表都在**，
而 `map_list` 已经把每张表的位置和长度都写全了。于是可以反推出完整的头部：

| 头部字段 | 取值来源 |
|---|---|
| magic | 固定 `dex\n035\0` |
| file_size | 子 dex 结束位置 − 起始位置 |
| header_size | `0x70` |
| endian_tag | `0x12345678` |
| map_off | `map_list` 项自报的 offset |
| string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs 的 size+off | 全部直接取自 `map_list` |
| data_off | 各数据区段 offset 的最小值（经验证 = `class_defs` 结束位置） |
| data_size | `file_size - data_off` |
| checksum | 写入 sha1 之后，对 `[12, file_size)` 计算 Adler-32 |
| signature | 对 `[32, file_size)` 计算 SHA-1 |

脚本：`C:\hupuwork\unpack.js`（关键实现，约 130 行）。

### 3.4 脱壳结果

```
found 21 dex candidates
classes.dex    壳 dex                 30 类
classes2.dex   …21.dex               82,252 类
-----------------------------------------------
合计                                 82,282 个类
                                     521,591 个方法
                                     721,739 条字符串
```

校验：20 个主 dex 的 `string_ids → type_ids → proto_ids → field_ids → method_ids →
class_defs` 六张表**完全首尾相连**（`table[i].off + size*itemSize == table[i+1].off`），
且 `class_defs` 结束位置恰好等于 `data_off`；所有 `class_def` 的
`annotations_off / class_data_off / static_values_off / interfaces_off` 均落在文件范围内。
再用 jadx 反编译，产出 41,588 个 .java 文件，可读性与正常未加固应用一致。

### 3.5 本方法的优缺点

| | |
|---|---|
| 优点 | 完全离线、不依赖 Root/Frida/真机；一次成功可拿到全部 dex；重建后的 dex 能被任何标准工具（jadx / baksmali / dexdump）直接读 |
| 缺点 | 强绑定"头部被加密、正文明文"这一种加固策略；如果厂商改成**指令抽取（VMP）**或对正文整体加密，本方法失效 |
| 已知问题 | jadx 在解析部分类的注解段时会越界报错（`newPos > limit`），导致这些类被整类跳过（例如 `SplashFragment`）。本项目因此额外写了一个独立的 dex 方法签名提取器 `dumpmethods.js`，直接从 `class_data_item` 解析方法名/参数/返回值，不依赖 jadx |

### 3.6 产出物清单

| 路径 | 说明 |
|---|---|
| `C:\hupuwork\unpack.js` | 脱壳脚本 |
| `C:\hupuwork\unpacked\classes*.dex` | 还原出的 21 段 dex |
| `C:\hupuwork\all-classes.txt` | 82,282 个类名（每行一个） |
| `C:\hupuwork\all-methods.txt` | 类名 + 全部方法签名（521,591 条） |
| `C:\hupuwork\all-strings.txt` | 721,739 条字符串常量 |
| `C:\hupuwork\jadx-out\sources` | jadx 反编译源码（41,588 个 .java） |
| `C:\hupuwork\dumpmethods.js` | 独立方法签名提取器（绕开 jadx 的注解解析 bug） |

---

## 4. 应用结构

按顶层包统计（82,282 个类中）：

| 包 | 类数 | 说明 |
|---|---|---|
| `com.hupu.*` | 30,880 | 虎扑自己的业务代码（**未混淆类名**） |
| `com.byazt.*` | 7,416 | 广告相关（见 §5.4，推测） |
| `com.bytedance.*` | 5,201 | 字节系：穿山甲 `openadsdk`、`bdtracker`、`applog`、`ttsdk` |
| `com.noah.*` | 3,950 | **虎扑自研广告 SDK "Noah"**（sdk / adn / api / plugin / monitor） |
| `com.google.*` | 4,061 | GMS、protobuf、guava 等 |
| `androidx.media3` | 2,256 | ExoPlayer 播放器 |
| `io.reactivex` | 1,609 | RxJava |
| `androidx.camera` | 1,507 | CameraX |
| `com.ss.*` | 1,316 | 字节系（抖音/穿山甲公共库） |
| `com.tencent.*` | 1,243 | 腾讯系（GDT、Bugly 崩溃上报等） |
| `com.umeng.*` | 1,073 | 友盟统计 |
| `com.huawei.*` | 970 | HMS 推送 |
| `cn.jiguang.*` | 837 | 极光推送 |
| `okhttp3` / `retrofit2` | 367 / 88 | 网络层 |

虎扑自己代码的功能划分（举例，均为真实类名）：

| 模块 | 代表类 |
|---|---|
| 主框架 | `com.hupu.games.main.MainActivity`、`com.hupu.android.common.cill.NetConfig` |
| 开屏 | `com.hupu.games.main.splash.SplashFragment` / `SplashComponent` |
| 比赛数据 | `com.hupu.match.games.MatchActivity`、`com.hupu.android.football.*` |
| 社区/评分 | `com.hupu.android.bbs.page.*` |
| 推荐流 | `com.hupu.android.recommendfeedsbase.*` |
| 用户/会员 | `com.hupu.user.vip.VipManager`、`com.hupu.data.manager.VipManager` |
| 广告 | `com.hupu.adver_*`（见 §5） |

---

## 5. 广告体系

### 5.1 三层结构

```
① SDK 适配层  com.hupu.adver_base.sdk
     ├─ TTSdkManager        -> 穿山甲（com.bytedance.sdk.openadsdk）
     ├─ YlhSdkManager       -> 优量汇 / 腾讯 GDT（com.qq.e）
     └─ NoahSdkManager      -> 虎扑自研广告 SDK（com.noah.*）

② 场景层      com.hupu.adver_*
     adver_boot     开屏      adver_feed     信息流
     adver_float    浮窗      adver_banner   横幅
     adver_popup    弹窗      adver_drama    短剧
     adver_game     游戏      adver_creative 创意
     adver_animation 动效     adver_report   埋点上报
     adver_project  项目管理  adver_service  服务

③ UI 层       com.hupu.games.main.splash / com.hupu.adver_*/view
```

各场景类数量：`adver_creative 743`、`adver_base 662`、`adver_feed 548`、
`adver_drama 515`、`adver_boot 255`、`adver_game 142`、`adver_float 128`、
`adver_banner 117`、`adver_popup 109`、`adver_project 87`、`adver_report 78`、
`adver_dialog 73`、`adver_animation 70`、`adver_service 22`。

### 5.2 宿主里的第三方广告 SDK

| SDK | 证据 |
|---|---|
| 穿山甲 Pangle | `com.bytedance.sdk.openadsdk.*`（含 `TTAdSdk`、`AdSlot`、`CSJSplashAd`、`TTAdNative`）；manifest 有 `com.hupu.games.openadsdk.permission.TT_PANGOLIN`；native：`libpanglearmor.so`、`libPglbizssdk_ml.so`、`libbmf_hydra.so`、`libad_alog.so` |
| 腾讯 优量汇 GDT | `com.qq.e.*`（`ADActivity`、`ads.splash.SplashAD`）；`assets/gdt_plugin/gdtadv2.jar`（GDT 插件） |
| 阿里妈妈 | `assets/albb_ph.ttf`、`assets/alimama_bold.ttf` |
| 友盟 | `com.umeng.*`、`libumeng-spy.so` |
| MSA OAID | `libmsaoaidsec.so`、`libmsaoaidauth.so`（设备标识） |
| Bugly | `libcrashsdk.so`、字符串 `KEY_CRASH_CAUSE_XPOSED_ART` |

### 5.3 自研 SDK "Noah"

`com.noah.*` 共 3,950 个类，子包分布：`sdk 2055`、`adn 781`、`external 326`、
`api 258`、`plugin 251`、`logger 66`、`monitor 23`。
`adn` = Ad Network，说明 Noah 是一个**聚合/中介（mediation）SDK**，把穿山甲、
优量汇等外部广告网络统一包装成 `com.noah.api.NativeAd` / `RewardedVideoAd` / `SplashAd`。
虎扑侧入口是 `com.hupu.adver_base.sdk.NoahSdkManager`。

### 5.4 关于 `com.byazt.*`（7,416 个类）

这个包名高度混淆（子包全是 `vm / jdz / pi / ju / fy / qge / fh ...` 两三个字母），
但出现了 `com.byazt.af.SplashClickBar`、`SplashClickBarArrow`、`MultiDiggSplashView`
等开屏控件类，且与 `com.bytedance.sdk.*` 同处一个 dex。
**推测**：它是字节系某套广告模板/落地页 SDK 的二次混淆包名。
（这一条为推测，未做进一步验证。）

### 5.5 开屏广告完整链路（Hook 的主要目标）

```
com.hupu.adver_boot.SplashAdStarter.init(Application)          启动时初始化开屏广告模块
        │
com.hupu.adver_boot.HpSplashAd                                 开屏广告总控
        load(HpSplashLoadListener)     loadDataFromNet() / loadNetSuccess(AdStartResult)
        checkCurResponseCanUse()Z      ~ startTimeout() / startShowTimeout()
        show(android.view.ViewGroup)   <- 真正把广告贴到界面上
        showFromCache(...)
        registerShowListener / registerActionListener
        │
com.hupu.adver_boot.core.HpSplash*Ad                          按素材类型分四条渲染路径
        HpSplashSdkAd.show(ViewGroup)      走 SDK（Noah / 穿山甲）渲染
        HpSplashApiAd                       API 直投素材
        HpSplashImageAd.show(ViewGroup)     图片素材
        HpSplashVideoAd                     视频素材
        HpImgPreloadAd.startPreLoad(...)    预加载
        │
com.hupu.adver_base.sdk.*                                      SDK 落地
        NoahSdkManager.loadNoahSplash(..., NoahSplashSdkListener)
        TTSdkManager.loadTTSplash(..., TTSplashSdkListener)
        │
com.hupu.games.main.splash.SplashFragment                      UI 层
        showSplashAd()V / showSplashVideo()V   主动展示广告
        finishPage(String)V                    结束开屏
        isVip()Z / access$isVip(SplashFragment)Z
        addMonitor()V                           白屏监控
        registerFinishListener(Function0)
        onCreate / onCreateView / onViewCreated / onResume / onDestroy
```

关键接口：

```
com.hupu.adver_boot.listener.HpSplashLoadListener
        onLoadSuccess()V  onRenderSuccess()V  onError(?,?)V
com.hupu.adver_boot.listener.HpSplashShowListener
        onShowSuccess()V   onError(?,?)V
com.hupu.adver_base.sdk.NoahSdkManager$NoahSplashSdkListener
        onSplashLoadSuccess()V  onSplashRenderSuccess(com.noah.api.SplashAd)V
        onSplashAdClose(NoahSplashDismissType)V  onAdShow()V  onAdClicked()V  onError(?,?)V
com.hupu.adver_base.sdk.TTSplashSdkListener
        onSplashLoadSuccess()V  onSplashRenderSuccess(CSJSplashAd)V
        onSplashAdClose(I)V  onAdShow(CSJSplashAd)V  onAdClicked(CSJSplashAd)V  onError(?,?)V
```

### 5.6 信息流广告

```
com.hupu.adver_feed.HpFeedAd
        canLoadAd(com.hupu.adver_base.config.entity.AdPageConfig$AdPageEntity)Z   <- 「这一页要不要拉广告」总开关
        loadItemAd(I)V                       逐条加载
        canLoadAd / changeV3 / changeV4 / forceReplaceItemAd(View)V
com.hupu.adver_feed.core.HpFeedSdkAd
        process(..., FeedSdkAdapter$FeedSdkListener)V
com.hupu.adver_feed.data.FeedAdRepository
        getOtherData(HashMap) 返回 kotlinx.coroutines.flow.Flow
com.hupu.adver_feed.data.dispatch.IFeedThreadDispatch
        canInsert(AdFeedResponse)Z / returnInsertData(AdFeedResponse)Object / sendExposure(?,?)V
```

### 5.7 浮窗广告

```
com.hupu.adver_float.HpAdFloatCore
        loadFromNet()V                       发起请求
        loadSuccess(com.hupu.adver_float.data.entity.AdFloatResponse)V
        loadFail()V / clearData()V
com.hupu.adver_float.HpAdFloat / HpAdRvFloat  （Builder 模式，setPageId / setAttachContext / setViewFactory）
com.hupu.adver_float.viewmodel.FloatAdViewModel
```

---

## 6. VIP / 会员体系

| 类 | 关键成员 |
|---|---|
| `com.hupu.user.vip.VipManager` | `getVipLiveData()`、`setVipInfo(com.hupu.android.bbs.VIPInfo)`、`isShowVipPop()`、`showVipTipView(...)`、`hideVipTipDialog(FragmentActivity)`、`setVipPopTime(String)` |
| `com.hupu.data.manager.VipManager` | 带 `dataStore`、`getVipFromDisk` / `saveVipToDisk`，说明 VIP 状态在本地有持久化 |
| `com.hupu.android.bbs.VIPInfo` | VIP 数据模型 |
| `com.hupu.user.main.v2.cards.container.OnVipCardClickListener` | 会员卡片点击 |
| `com.hupu.games.main.splash.SplashFragment.isVip()Z` | **开屏页会判断 VIP 来决定是否展示广告**（这解释了为什么"开屏广告"和"VIP"在代码上耦合） |

SharedPreferences / DataStore 的具体 key 名未做进一步验证（需要运行时 dump，
可打开模块里的"类加载探针"辅助定位）。

---

## 7. 反调试 / 反 Xposed / 完整性校验

> ⚠️ 这一节是**最需要真机验证**的一节，也直接影响模块能否生效。

### 7.1 字符串层面的证据（72 万条字符串精确匹配）

在还原出的 dex 里确实存在 Xposed 检测所需的字符串常量：

| 字符串 | 所在 dex | 索引 | 含义 |
|---|---|---|---|
| `xposedmodule` | classes10 | 59335 | Xposed 模块 manifest 里的 `<meta-data>` 名，用于**扫描已安装模块** |
| `xposedminversion` | classes10 | — | 同上 |
| `de.robv.android.xposed.XposedBridge` | classes10 / classes9 | 33102 | 检测经典 Xposed 类是否存在 |
| `de.robv.android.xposed.XposedHelpers` / `XposedInit` | classes10 | 33104 / 33105 | 同上 |
| `de.robvf.android.xposed.XposedHelpers` | classes10 | 33106 | **注意拼写是 `robvf`**，疑似故意写错的变体，也可能是混淆产物 |
| `/proc/self/maps` | 多处 | — | 内存映射扫描（检测注入的 so） |

这三组字符串分别位于 **classes9.dex / classes10.dex / classes14.dex**，
说明检测代码分散在多个 dex 中。

### 7.2 一个反直觉的发现

我们对 `classes10.dex` 做了完整的 `code_item` 解析（50,177 个有代码的方法），
**逐条扫描 `const-string`(0x1A) 与 `const-string/jumbo`(0x1B) 指令**，
结果：`xposedmodule`（索引 0xE7C7）**没有任何一条指令引用它**，
`de.robv.android.xposed.*` 同样没有。

可能的原因（均**未验证**）：

1. 这些字符串是被**保留但未被引用**的残留（例如检测逻辑被阉割/开关关闭）；
2. 检测逻辑被**指令抽取**或放在 native 层，Java 侧只留字符串；
3. 字符串由反射或 `StringBuilder` 拼接间接使用；
4. 检测在**更高版本才启用**，当前版本只是预埋。

### 7.3 与第三方分析报告的对照

GitHub 上有一份针对虎扑的公开分析
（`593653436/hupu-xposed-detection-analysis`），其结论是虎扑会弹三种提示：

| 提示 | 触发条件 |
|---|---|
| 检测到 root 权限 | KernelSU 给该应用授予了 root |
| **检测到该应用在 hook 环境中运行** | LSPosed 往该进程注入任何东西（不论模块是否加载成功） |
| 检测到 Xposed 环境 | 系统存在 Xposed / Riru / Magisk 残留文件 |

**但是**：我们在这份 8.2.63 样本的 dex 与 `resources.arsc` 中**都搜不到**
这三句提示文案（UTF-8 与 UTF-16 两种编码都试过），说明那份报告对应的是**另一个（更早的）版本**，
或者是通过服务端下发文案。

### 7.4 其他相关证据

| 发现 | 判定 |
|---|---|
| `KEY_CRASH_CAUSE_XPOSED_ART` / `KEY_CRASH_CAUSE_XPOSED_DALVIK` / `onXposedCrash` | **不是虎扑自己的检测**，是腾讯 Bugly 崩溃上报 SDK 的崩溃分类常量 |
| `androidx.camera...AutoFlashUnderExposedQuirk`、`mtrl_exposed_*` | 误命中（`exposed` 是普通英文单词） |
| `libverifier.so`、`libEncryptorP.so`、`libnesec.so` | 易盾/加密组件，**未验证**是否做签名校验 |
| `okhttp3.CertificatePinner` | 在包内（367 个 okhttp3 类），**未验证**是否配置了 pinner |
| `java.lang.ClassLoader#loadClass` 在模块侧可 hook | 需要用运行时探针验证真机上是否被检测 |

### 7.5 结论与对模块的影响

**虎扑内置了 Xposed/root 检测所需的字符串与路径清单**（这一点必须承认，
不能因为没找到报错文案就认为它没有检测）。因此本模块存在一个**明确的、尚未验证的风险**：

LSPosed 注入到虎扑进程后，虎扑可能弹提示、甚至主动退出，导致模块"看着装上了但用不了"。

应对策略（留给后续工作）：

1. 先用本模块的**类加载探针**（设置里打开 `类加载探针`）把虎扑启动阶段加载的
   `com.hupu.*` 类名全量打出来，定位检测类；
2. 找到检测方法后，按同样的方式 hook 它，让检测恒为"安全"；
3. 或者在 LSPosed 里只给虎扑的**主进程**开作用域，减少注入面。

---

## 8. 网络层与接口

- 框架：`okhttp3`（367 类）+ `retrofit2`（88 类）+ 字节 TTNet（`libttquic.so`、`libttboringssl.so`、`libttcrypto.so`）。
- 域名（从字符串常量中提取，均为真实出现）：

```
https://games.mobileapi.hupu.com      （比赛/游戏数据）
https://bbs.mobileapi.hupu.com        （社区）
https://basicdata.hupu.com            （基础数据）
https://bbs.hupu.com/
https://live-api.hupucdn.com/1/       （直播）
http://basketball-message.hupucdn.com/bballMsg
```

并存在 `-sit` / `-stg` / `-test` / `-pre` 等测试环境域名，说明同一套代码支持多环境切换。

---

## 9. 建议的 Hook 点（Top 清单）

以下全部来自真实还原出的 dex，可直接使用。

| # | 类 | 方法 | 用途 | 预期效果 | 风险 |
|---|---|---|---|---|---|
| 1 | `com.hupu.adver_boot.HpSplashAd` | `show(android.view.ViewGroup)V` | 开屏广告展示入口 | 不展示广告，直接走兜底页 | 低 |
| 2 | `com.hupu.games.main.splash.SplashFragment` | `showSplashAd()V` | 开屏页主动展示广告 | 同上 | 低 |
| 3 | `com.hupu.games.main.splash.SplashFragment` | `showSplashVideo()V` | 开屏视频广告 | 同上 | 低 |
| 4 | `com.hupu.adver_boot.SplashAdStarter` | `init(android.app.Application)V` | 开屏广告模块初始化 | 从源头不初始化，最彻底 | 中（需确认是否存在超时兜底） |
| 5 | `com.hupu.adver_base.sdk.TTSdkManager$Companion` | `initSdk(...)` | 穿山甲 SDK 初始化 | 穿山甲全线广告失效 | 中 |
| 6 | `com.hupu.adver_base.sdk.NoahSdkManager$Companion` | `initSdk(android.app.Application)V` | Noah 聚合 SDK 初始化 | 聚合广告全线失效 | 中 |
| 7 | `com.hupu.adver_feed.HpFeedAd` | `canLoadAd(AdPageConfig$AdPageEntity)Z` | 信息流广告总开关 | 返回 false 则整页不加载信息流广告 | 低 |
| 8 | `com.hupu.adver_feed.HpFeedAd` | `loadItemAd(I)V` | 单条广告加载 | 广告条目不入列表 | 低 |
| 9 | `com.hupu.adver_float.HpAdFloatCore` | `loadFromNet()V` | 浮窗广告请求 | 浮窗广告不再出现 | 低 |
| 10 | `com.hupu.adver_feed.core.HpFeedSdkAd` | `process(...)V` | SDK 侧信息流渲染 | 兜底拦截 | 低 |
| 11 | `android.app.Activity` | `onResume()V` | UI 兜底 | 遍历视图树点「跳过」，不依赖任何业务类名 | 极低 |

---

## 10. 模块实现

### 10.1 技术选型

- **API**：`io.github.libxposed:api:102.0.0`（Maven Central 上 `io.github.libxposed` 目前只有
  `101.0.0 / 101.0.1 / 102.0.0` 三个版本，即 libxposed API 1.0/1.1）。
- **模块声明**：`META-INF/xposed/module.prop`（`minApiVersion=101`、`targetApiVersion=102`）、
  `META-INF/xposed/java_init.list`、`META-INF/xposed/scope.list`。
- **Hook 模型**：libxposed 的**拦截器链**。
  `hook(method).setPriority(...).setExceptionMode(...).intercept(chain -> {...})`；
  不调用 `chain.proceed()` 就是"不执行原方法"，改返回值靠 `return`，改参数用 `chain.proceed(newArgs)`。
  （新 API 没有 `setResult`。）

### 10.2 源码结构

```
module/app/src/main/java/com/hupux/module/
    HupuModule.java        入口类（onModuleLoaded / onPackageReady）
    Config.java            开关定义、跨进程 SharedPreferences 读取、日志
    AdHooks.java           §9 里的精确 Hook 点（开屏 / SDK初始化 / 信息流 / 浮窗）
    HookRegistry.java      规则表：类名 → 要 hook 的方法（只登记，不加载类）
    LazyHookInstaller.java 延迟安装器：hook ClassLoader#loadClass，目标类一出现就装 hook
    HookUtil.java          反射查找方法 + 调用 libxposed hook() 的公共逻辑
    SplashSkipHelper.java  视图兜底：hook Activity#onResume，自动点「跳过」
    ClassProbe.java        运行时类加载探针（默认关闭，用于验证脱壳结果 / 定位类名变化）
    MainActivity.java      设置界面（7 个开关）
```

### 10.2.1 为什么必须有「延迟安装器」

这是本次开发中**踩到的最大的坑**，也是加固应用写 Xposed 模块的关键点：

在 `onPackageReady` 里拿到的 ClassLoader 是**壳的** ClassLoader，此刻
`com.hupu.adver_*` 这些业务类**还不存在**——易盾会在之后才把解密好的
20 个 dex 以 `InMemoryDexFile` 的形式交给 `PathClassLoader`。
所以如果直接写：

```java
Class<?> c = Class.forName("com.hupu.adver_boot.HpSplashAd", false, cl);
// -> ClassNotFoundException
```

会一个类都找不到。正确做法是**两条腿走路**：

1. **被动等待**：hook `java.lang.ClassLoader#loadClass(String, boolean)` 与
   `#loadClass(String)`，任何类被加载出来时都过一遍规则表，命中就装 hook
   （注意不能在 `loadClass` 的调用栈里直接装，那里是类加载临界区，容易死锁，
   要 `post` 到主线程队列）；
2. **主动兜底**：每秒用"见过的 ClassLoader"对所有还没命中的目标类重试
   `Class.forName`，最多 60 次——覆盖"类在模块 hook 之前就已经加载完"的情况。

实测结论：**两条路都出现过，缺一不可**：

```
[延迟安装] 目标类已加载：com.hupu.adver_boot.HpSplashAd
[延迟安装] 兜底重试命中：com.hupu.adver_float.HpAdFloatCore via dalvik.system.PathClassLoader[...]
```

### 10.3 两条腿走路的设计

- **精确路径**（AdHooks）：基于脱壳后的真实类名，命中即生效，行为可预测。
- **兜底路径**（SplashSkipHelper）：完全不依赖业务类名，只 hook 系统类 `Activity#onResume`，
  遍历视图树找到文案含「跳过」的小控件并模拟点击。即使虎扑升级换了广告类名，兜底仍然有效。

这正是应对**加固 + 频繁发版**这类目标的务实做法。

### 10.4 构建

```powershell
# 必须 JDK 21：JDK 17 下 Gradle 会报 "Failed to load native library 'native-platform.dll'"
$env:JAVA_HOME='D:\codex\虎扑\tools\jdk\jdk-21.0.12.1+1'
$env:ANDROID_HOME='D:\codex\虎扑\tools\android-sdk'      # 需要 platforms;android-35 和 build-tools;35.0.0
# GRADLE_USER_HOME 必须留在工作区内，否则沙箱放行不了 C:\Users\...\AppData\Roaming\.gradle
$env:GRADLE_USER_HOME='D:\codex\虎扑\.gradle_home'
$env:TMP='D:\codex\虎扑\.tmp'; $env:TEMP='D:\codex\虎扑\.tmp'
& 'D:\codex\虎扑\tools\gradle\bin\gradle.bat' -p 'D:\codex\虎扑\module' assembleRelease
```

产物：

```
module/app/build/outputs/apk/release/app-release.apk
module/app/build/outputs/apk/debug/app-debug.apk
```

### 10.5 安装与启用（在真机上）

1. 安装 APK；
2. 打开 LSPosed 管理器 → 模块 → 启用「虎扑助手 HupuX」；
3. 作用域勾选「虎扑」（`scope.list` 已默认写好，且 `staticScope=true`）；
4. 强行停止虎扑 → 重新打开；
5. 验证：`adb logcat -s HupuX`，应看到
   `模块已注入 process=com.hupu.games ...`、`[开屏] OK 已 hook ...`、
   `Hook 安装完成，本进程共成功 N 个方法`。

---

## 11. 复现步骤（从零开始）

```powershell
# 1) 拿 APK（示例：8.2.63）
#    豌豆荚 https://www.wandoujia.com/apps/com.hupu.games
#    或应用宝 https://android.myapp.com/myapp/detail.htm?apkName=com.hupu.games

# 2) 抽出壳 dex
#    APK 内的 classes.dex 就是「壳 + 100MB 尾部密文/明文」

# 3) 脱壳
node C:\hupuwork\unpack.js  <壳classes.dex>  C:\hupuwork\unpacked
#    -> 输出 classes.dex ~ classes21.dex

# 4) 出类名 / 方法名
node C:\hupuwork\listclasses.js C:\hupuwork\unpacked C:\hupuwork\all-classes.txt
node C:\hupuwork\dumpmethods.js C:\hupuwork\unpacked C:\hupuwork\all-methods.txt
node C:\hupuwork\strings.js     C:\hupuwork\unpacked C:\hupuwork\all-strings.txt

# 5) 反编译看代码（可选）
jadx -d C:\hupuwork\jadx-out --no-res -j 8 C:\hupuwork\unpacked

# 6) 构建模块
cd D:\codex\虎扑\module ; gradle assembleRelease
```

配套脚本都在 `C:\hupuwork\`：`unpack.js`（脱壳）、`listclasses.js`（类名）、
`dumpmethods.js`（方法签名）、`strings.js`（字符串）、`dexparse.js`（单 dex 解析）、
`show.js`（按关键字查看某个类的全部方法）。

---

## 12. 局限与未验证项

1. **脱壳方法依赖易盾当前的存储策略**。若后续版本改为指令抽取（VMP），需要改用运行时 dump（FART/BlackDex 思路）。
2. **jadx 对部分类反编译失败**（注解段越界），已用自研方法签名提取器绕过，但这些类的**方法体**无法直接阅读（例如 `SplashFragment` 的具体实现）。
3. ~~未在真机上完成端到端验证~~ → **已完成，见 §14**。
4. **未验证项**：虎扑是否配置 SSL Pinning、易盾壳的完整性校验行为、VIP 本地存储的具体 key、
   `com.byazt.*` 的确切归属。
5. §7 的 Xposed 检测：dex 里存在 `xposedmodule` / `de.robv.android.xposed.*` /
   `/proc/self/maps` 等字符串，但当前版本里没有找到引用它们的指令。
   **真机实测：模块注入后虎扑正常启动、正常停留在前台，没有弹提示、没有退出**
   （见 §14），说明这些字符串在 8.2.63 上大概率没有实际启用。
6. 本次分析**只做静态分析 + 模块实现**，没有绕过任何账号、付费或服务端校验。

---

## 14. 真机验证结果（2026-10-03）

这一节是**实际跑出来的**，不是推测。

### 14.1 环境

| 项目 | 值 |
|---|---|
| 设备 | 小米 25091RP04C（Android 17 / SDK 37） |
| Root | ReSukiSU v4.1.0（KernelSU 系） |
| 框架 | Zygisk Next + **LSPosed 2.2.0** |
| 目标 | 虎扑 `com.hupu.games` 8.2.63 |
| 模块 | `com.hupux.xpnb`（模块名 HupuX）v1.0.0（自签名 release APK） |

### 14.2 日志节选（`adb logcat -s HupuX`）

```
I HupuX: 模块已注入 process=com.hupu.games systemServer=false framework=LSPosed 2.2.0 api=102 target=com.hupu.games
I HupuX: 开始安装 Hook firstPackage=true | 开屏=true SDK初始化拦截=true 信息流=true 浮窗=true 视图兜底=true 探针=false
I HupuX: [延迟安装] OK 已挂钩 ClassLoader#loadClass(java.lang.String, boolean)
I HupuX: [延迟安装] OK 已挂钩 ClassLoader#loadClass(java.lang.String)
I HupuX: [视图兜底] OK 已 hook android.app.Activity#onResume
I HupuX: 规则登记完成，等待目标类加载（当前已成功 1 个方法）

I HupuX: [开屏] OK 已 hook com.hupu.adver_boot.HpSplashAd#show（1 个重载）
I HupuX: [开屏] OK 已 hook com.hupu.games.main.splash.SplashFragment#showSplashAd（1 个重载）
I HupuX: [开屏] OK 已 hook com.hupu.games.main.splash.SplashFragment#showSplashVideo（1 个重载）
I HupuX: [SDK初始化] OK 已 hook com.hupu.adver_base.sdk.TTSdkManager$Companion#initSdk（1 个重载）
I HupuX: [SDK初始化] OK 已 hook com.hupu.adver_base.sdk.NoahSdkManager$Companion#initSdk（1 个重载）
I HupuX: [SDK初始化] OK 已 hook com.hupu.adver_boot.SplashAdStarter#init（1 个重载）
I HupuX: [信息流] OK 已 hook com.hupu.adver_feed.HpFeedAd#canLoadAd（1 个重载）
I HupuX: [信息流] OK 已 hook com.hupu.adver_feed.HpFeedAd#loadItemAd（1 个重载）
I HupuX: [信息流] OK 已 hook com.hupu.adver_feed.core.HpFeedSdkAd#process（1 个重载）
I HupuX: [浮窗] OK 已 hook com.hupu.adver_float.HpAdFloatCore#loadFromNet（1 个重载）
I HupuX: [浮窗] OK 已 hook com.hupu.adver_float.HpAdFloatCore#loadSuccess（1 个重载）
I HupuX: [延迟安装] 全部目标类已处理，共 8 个

# ↓↓↓ 真正拦截到的广告调用 ↓↓↓
I HupuX: [浮窗] 拦截 HpAdFloatCore.loadFromNet()
I HupuX: [信息流] 拦截 HpFeedAd.loadItemAd()      (连续 4 次)
```

同时确认虎扑进程仍然存活，`topResumedActivity=com.hupu.games/.main.MainActivity`，
即**没有被 Xposed 检测踢出去**。

### 14.3 意外的收获：脱壳结果被运行时交叉验证

模块打印出的目标 ClassLoader 字符串里包含：

```
dalvik.system.PathClassLoader[
  DexPathList[[
    zip file ".../com.hupu.games-.../base.apk",
    dex file "InMemoryDexFile[cookie=...]",   <- 1
    dex file "InMemoryDexFile[cookie=...]",   <- 2
    ... 共 20 个 InMemoryDexFile
  ]]]
```

**运行时加载的正是 20 个"内存中解密出来的 dex"**，与我们离线脱壳得到的
`classes2.dex ~ classes21.dex`（20 段真实 dex）**数量完全一致**。
这是对第 3 节脱壳方法的独立交叉验证，同时也解释了 §10.2.1 的现象：
这些 dex 通过 `InMemoryDexFile` 交给 `PathClassLoader`，加载时机晚于
`onPackageReady`，所以必须用延迟安装。

---

## 13. 附：证据文件索引

| 文件 | 内容 |
|---|---|
| `D:\codex\虎扑\hupu_8.2.63.apk` | 原始样本 |
| `D:\codex\虎扑\analysis\shell-classes.dex` | 壳 dex（含 100MB 尾部数据） |
| `D:\codex\虎扑\analysis\AndroidManifest.xmltree.txt` | manifest 文本化 |
| `D:\codex\虎扑\analysis\jadx-out\` | 壳类反编译结果 |
| `C:\hupuwork\unpacked\` | 还原的 21 段 dex |
| `C:\hupuwork\all-classes.txt` | 82,282 个类名 |
| `C:\hupuwork\all-methods.txt` | 521,591 条方法签名 |
| `C:\hupuwork\all-strings.txt` | 721,739 条字符串 |
| `C:\hupuwork\jadx-out\sources\` | 41,588 个反编译 .java |
| `D:\codex\虎扑\module\` | 模块源码 |
| `D:\codex\虎扑\module\app\build\outputs\apk\` | 构建产物 |
