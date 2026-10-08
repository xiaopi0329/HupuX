# libxposed 现代 Xposed API 使用笔记

> 依据：`io.github.libxposed` 在 Maven Central 上的官方 artifact，以及其
> `XposedInterface.java` / `XposedModuleInterface.java` / `package-info.java` 源码。
> 本项目实际用它写出了 `module/` 里的 HupuX，编译并在 APK 里确认了元数据打包正确。

---

## 1. 是什么 / 版本

libxposed 是 LSPosed 团队推出的**现代 Xposed API**，用来取代老的
`de.robv.android.xposed`（XposedBridge API 82/93）。它把 Hook 模型从
"前后置回调 + `param.setResult()`" 改成了**类似 OkHttp 的拦截器链（Chain）**。

Maven Central 上 `io.github.libxposed` 下的 artifact：

| artifact | 可用版本 | 作用 |
|---|---|---|
| `api` | `101.0.0`、`101.0.1`、`102.0.0` | 开发模块必须依赖的 API |
| `interface` | `101.0.0`、`102.0.0` | 模块与框架服务（热重载、作用域）交互的 AIDL 接口 |
| `annotation` | `1.0.0` | `@SinceApi` 等注解 |
| `lint` | `1.0.0` | API 兼容性 lint |
| `service` | `101.0.0`、`102.0.0` | 服务端实现所需 |

> 注意：**没有 1.x 版本**。想用现代 API 就必须让 `minApiVersion >= 101`，
> 也就要求 **LSPosed 1.10.0 及以上**。

API 版本常量（`XposedInterface`）：

```java
int API_101 = 101;
int API_102 = 102;
int LIB_API = API_102;
int PRIORITY_DEFAULT = 50;
int PRIORITY_LOWEST  = Integer.MIN_VALUE;
int PRIORITY_HIGHEST = Integer.MAX_VALUE;
```

---

## 2. 模块身份三件套

libxposed 模块**不看 AndroidManifest**（manifest 只需要一个普通的、能被管理器打开的 App），
真正决定"这是不是一个 Xposed 模块"的是 APK 内的三个文件。
放在 `app/src/main/resources/META-INF/xposed/` 下，Gradle 会原样打进 APK 根目录。

### 2.1 `module.prop`（Java Properties 格式）

```properties
# 必填：最低要求的框架 API 版本
minApiVersion=101
# 必填：目标 API 版本
targetApiVersion=102
# 选填：作用域固定，不允许用户随意扩大到别的 App
staticScope=true
# 选填：Hooker 抛异常时的默认策略，protective（默认）或 passthrough
exceptionMode=protective
# 选填（API 102+）：App 更新时是否自动热重载
autoHotReload=false
```

模块在管理器里显示的名字/描述来自 manifest 的 `android:label` 和 `android:description`。

### 2.2 `java_init.list`

一行一个入口类的全限定名：

```
com.hupux.xpnb.HupuModule
```

（native 入口写 `native_init.list`。）**热重载只支持"恰好一个 Java 入口类"的模块。**

### 2.3 `scope.list`

默认作用域，一行一个包名：

```
com.hupu.games
```

---

## 3. 入口类

```java
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class HupuModule extends XposedModule {

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        // param.getProcessName() / param.isSystemServer()
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        // param.getPackageName() / getApplicationInfo() / isFirstPackage()
        // param.getDefaultClassLoader() / getClassLoader() / getAppComponentFactory()
        ClassLoader cl = param.getClassLoader();
    }
}
```

回调时机（来自官方源码注释）：

| 回调 | 时机 |
|---|---|
| `onModuleLoaded` | 模块被加载进目标进程时，每个模块代际一次 |
| `onPackageLoaded` | 默认 ClassLoader 就绪、`AppComponentFactory` 实例化**之前**（API 29+） |
| `onPackageReady` | `AppComponentFactory` 建好类加载器、准备创建 `Application` 时 |
| `onSystemServerStarting` | system_server 启动时（取代第一个包的 onPackageLoaded/onPackageReady） |
| `onHotReloading` / `onHotReloaded` | API 102 的热重载前后 |

**坑（我实际编译时踩到）**：`PackageReadyParam` / `PackageLoadedParam` **没有**
`getProcessName()`，进程名只有 `ModuleLoadedParam` 才有。

另外，模块被注入后，该进程里**每个**被加载的包都会回调，所以回调里一定要
`if (!目标包名.equals(param.getPackageName())) return;`，不需要的进程可以调用
`detach()` 主动停止接收回调。

---

## 4. Hook：拦截器链模型

### 4.1 核心签名（逐字摘自 XposedInterface.java）

```java
interface Hooker {
    Object intercept(@NonNull Chain chain) throws Throwable;
}

interface Chain {
    @NonNull Executable getExecutable();
    Object getThisObject();
    @NonNull List<Object> getArgs();
    Object getArg(int index);
    Object proceed() throws Throwable;
    Object proceed(@NonNull Object[] args) throws Throwable;
    Object proceedWith(@NonNull Object thisObject) throws Throwable;
    Object proceedWith(@NonNull Object thisObject, @NonNull Object[] args) throws Throwable;
}

interface HookBuilder {
    HookBuilder setPriority(int priority);
    HookBuilder setExceptionMode(@NonNull ExceptionMode mode);
    @NonNull HookHandle intercept(@NonNull Hooker hooker);
    @SinceApi(API_102) HookBuilder setId(@Nullable String id);
}

interface HookHandle {
    @NonNull Executable getExecutable();
    void unhook();
    @SinceApi(API_102) @Nullable String getId();
    @SinceApi(API_102) @NonNull HookHandle replaceHook(@NonNull Hooker hooker);
}

enum ExceptionMode { DEFAULT, PROTECTIVE, PASSTHROUGH }

@NonNull HookBuilder hook(@NonNull Executable origin);
@NonNull HookBuilder hookClassInitializer(@NonNull Class<?> origin);
boolean deoptimize(@NonNull Executable executable);
```

### 4.2 三种常见操作

```java
// ① 完全拦截（不执行原方法）—— 不调用 proceed 即可
hook(m).intercept(chain -> {
    return null;              // void 方法返回 null；非 void 要返回对应类型的值
});

// ② 改返回值 —— proceed 之后返回别的值
hook(m).intercept(chain -> {
    Object result = chain.proceed();
    return Boolean.FALSE;     // 例如让 canLoadAd() 恒为 false
});

// ③ 改参数 —— 用 proceed 的重载
hook(m).intercept(chain -> {
    List<Object> args = chain.getArgs();
    Object[] copy = args.toArray();
    copy[0] = "改写后的参数";
    return chain.proceed(copy);
});
```

**新 API 没有 `setResult()` / `setObjectExtra()`**，请用 `return` 和 `proceed` 重载。

### 4.3 按「类名 + 方法名」Hook

`hook(Executable)` 需要一个具体的 `Method`/`Constructor` 对象，所以要先自己找：

```java
Class<?> clazz = Class.forName(className, false, classLoader);  // false = 不触发静态初始化
for (Method m : clazz.getDeclaredMethods()) {
    if (!m.getName().equals(methodName)) continue;              // 名字相同即视为同名重载
    api.hook(m)
       .setPriority(XposedInterface.PRIORITY_DEFAULT)
       .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
       .intercept(hooker);
}
```

这个写法能一次性覆盖所有同名重载（因为不依赖参数类型）。

### 4.4 `deoptimize`

ART 在 AOT/内联优化之后，某些方法可能"被内联进调用者"，直接 Hook 可能不生效。
`api.deoptimize(executable)` 会强制反优化。本项目没有使用它（实测 8.2.63 的
目标方法都能正常 hook），但在"hook 装上却没反应"时，这是第一个该试的手段。

---

## 5. 其他有用能力

```java
void log(int priority, @Nullable String tag, @NonNull String msg);
void log(int priority, @Nullable String tag, @NonNull String msg, @Nullable Throwable tr);

@NonNull ApplicationInfo getModuleApplicationInfo();
@NonNull SharedPreferences getRemotePreferences(@NonNull String group);   // 跨进程读模块 App 的 SP
@NonNull String[] listRemoteFiles();
@NonNull ParcelFileDescriptor openRemoteFile(@NonNull String name) throws FileNotFoundException;

@NonNull String getFrameworkName();
@NonNull String getFrameworkVersion();
long getFrameworkVersionCode();
int getApiVersion();
```

`getRemotePreferences(group)` 是本项目解决"设置开关"的关键：模块 App 用普通
`getSharedPreferences("组名", MODE_PRIVATE)` 写入，被注入到目标进程的模块代码用
`getRemotePreferences("组名")` 读出来，两边拿到的是同一份数据。

---

## 6. Gradle 配置要点

```kotlin
dependencies {
    // 运行期由框架提供这些类，所以是 compileOnly，不能 implementation
    compileOnly("io.github.libxposed:api:102.0.0")
}

android {
    // 入口类必须保留原类名，否则 java_init.list 找不到它
}
```

已知的依赖坑：如果模块同时依赖 `androidx.appcompat`，会遇到
`kotlin-stdlib` 与 `kotlin-stdlib-jdk8` 的重复类，需要对齐版本：

```kotlin
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    }
}
```

---

## 7. 完整最小示例

下面这个类可以直接作为一个"能把虎扑所有类名打到 logcat"的探针模块入口：

```java
package com.example.probe;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class ProbeModule extends XposedModule {

    private static final String TAG = "XposedProbe";
    private static final String TARGET = "com.hupu.games";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        Log.i(TAG, "module loaded: " + param.getProcessName()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) {
            return;
        }
        // 关键：用目标包自己的 ClassLoader，加固 App 的业务类只有它认识
        ClassLoader cl = param.getClassLoader();

        try {
            // ① 打印类加载过程，用来找被加固 App 的真实类名
            Method loadClass = ClassLoader.class.getDeclaredMethod(
                    "loadClass", String.class, boolean.class);
            hook(loadClass).intercept(chain -> {
                Object result = chain.proceed();
                Object name = chain.getArg(0);
                if (name instanceof String && ((String) name).startsWith("com.hupu")) {
                    Log.i(TAG, "loaded: " + name);
                }
                return result;
            });

            // ② 精确 Hook 一个已知方法
            Class<?> clazz = Class.forName(
                    "com.hupu.adver_boot.HpSplashAd", false, cl);
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"show".equals(m.getName())) {
                    continue;
                }
                hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Log.i(TAG, "拦截 HpSplashAd.show()");
                            return null;      // 不 proceed = 不执行原方法
                        });
            }
        } catch (Throwable t) {
            Log.e(TAG, "hook failed", t);
        }
    }
}
```

---

## 8. 常见坑速查

| 现象 | 原因 / 解法 |
|---|---|
| 模块完全不生效，logcat 里没有任何日志 | `scope.list` 没写对，或没在管理器里启用，或作用域没勾选目标 App |
| 入口类找不到 | `java_init.list` 里的类名写错，或该被 R8 改了名（要 keep） |
| `Class.forName` 找不到目标类 | 用了默认 ClassLoader。加固 App 必须用 `param.getClassLoader()` |
| 模块被框架拒绝加载 | `minApiVersion` 高于框架 API；例如设备上是 LSPosed 1.9.x，就只能用老 API |
| hook 装上但没反应 | 方法被内联，试 `deoptimize(m)`；或方法名/类名在版本升级后变了 |
| 编译期报 kotlin-stdlib 重复类 | 见 §6 的 `resolutionStrategy` |
