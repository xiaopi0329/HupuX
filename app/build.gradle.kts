import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
}

/**
 * 版本号 = 构建时间，精确到分钟，中间不带任何符号。
 *
 * 例：2026-10-03 04:46 构建 → versionName "202610030446"。
 *
 * versionCode 用「距 1970 的分钟数」：12 位时间戳塞不进 int（上限 21 亿），
 * 而分钟数现在才三千多万，既能单调递增、又和 versionName 一样精确到分钟。
 */
val buildTimeName = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
val buildTimeCode = (System.currentTimeMillis() / 60_000L).toInt()

android {
    namespace = "com.hupux.xpnb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hupux.xpnb"
        minSdk = 26
        targetSdk = 35
        versionCode = buildTimeCode
        versionName = buildTimeName
    }

    // 签名配置：keystore 不入库（见 .gitignore）。
    // 本地有 hupux-release.jks 就用它签名，没有就跳过硬签名，
    // 这样别人 clone 下来依然能 assembleRelease 出来做验证。
    val releaseKeystore = file("hupux-release.jks")
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "hupux123"
                keyAlias = "hupux"
                keyPassword = "hupux123"
            }
        }
    }

    buildTypes {
        release {
            // 开启代码压缩 + 资源收缩：模块里只有几个类，但 R8 会顺手把
            // 未使用的资源条目也去掉，包体积能再降一截。
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // 模块入口类必须保留原名，否则 META-INF/xposed/java_init.list 找不到入口
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // 协议门要用模块自己的版本号做判断，开 BuildConfig 取 VERSION_NAME
        // （gradle.properties 里默认关掉了，这里给本模块单独打开）
        buildConfig = true
    }

    packaging {
        resources {
            // 保持 META-INF/xposed/** 原样打进 APK（模块能被框架识别的关键）
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    // 唯一的依赖。libxposed API 运行期由框架提供，编译期 compileOnly 即可。
    //
    // 刻意不引入 androidx / appcompat：界面只用系统控件就够（Activity + Switch），
    // 而 appcompat 会连带 androidx 全家桶和 kotlin-stdlib，把 dex 从几十 KB
    // 撑到 5MB 以上 —— 那是这个模块原本 2.5MB 体积的主要来源。
    compileOnly("io.github.libxposed:api:102.0.0")
}
