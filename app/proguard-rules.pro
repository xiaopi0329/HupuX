# 模块入口类不能被裁剪/改名
-keep class com.hupux.xpnb.HupuModule { *; }
-keep class com.hupux.xpnb.MainActivity { *; }
-keep class com.hupux.xpnb.LogActivity { *; }
-keep class com.hupux.xpnb.LogProvider { *; }
