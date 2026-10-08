# 模块入口类不能被裁剪/改名
-keep class com.hupux.xpnb.HupuModule { *; }
-keep class com.hupux.xpnb.MainActivity { *; }
-keep class com.hupux.xpnb.LogActivity { *; }
-keep class com.hupux.xpnb.LogProvider { *; }

# DexKit 自带的规则（见 AAR 里的 proguard.txt）只保留 native 方法，但它的 Kotlin
# 元数据引用了 kotlin.jvm.internal.SourceDebugExtension —— 那是 Kotlin 2.x 才有的
# 注解，而 dexkit 的 pom 把 kotlin-stdlib 钉在 1.5.0，于是 R8 报
# "Missing class kotlin.jvm.internal.SourceDebugExtension"。该注解只出现在
# @kotlin.Metadata 里，运行期完全用不到，dontwarn 即可。
# （备选方案是把 kotlin-stdlib 抬到 2.x，但 dex 会明显变大，不值得。）
-dontwarn kotlin.jvm.internal.SourceDebugExtension
