plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.goodyaoshi.lemonbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goodyaoshi.lemonbox"
        minSdk = 26
        targetSdk = 35
        // 版本策略：每次对外发布固定 +1（versionCode 只增不减），versionName 用语义化版本。
        // 版本号只在用户明确要求时调整，不自动变更。
        versionCode = (findProperty("lemonVersionCode") as String?)?.toIntOrNull() ?: 10
        versionName = (findProperty("lemonVersionName") as String?) ?: "0.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 原生库 ABI 收敛（F17）：本应用以「直接分发 APK」为主（见文件末尾的 copyReleaseApkForDistribution），
        // 走不了应用商店的 ABI 分包。产品上不需要兼容 32 位老机型，故只保留 arm64-v8a：
        // 包体最小、体积收益最大，且 64 位机型覆盖当下的绝大多数在售设备。
        // 若日后改为上架应用商店，建议改发布 AAB（bundleRelease），由商店按设备下发对应 ABI。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 正式签名：密钥信息通过 local.properties / CI Secrets 注入，绝不写进仓库。
        create("release") {
            val keystorePath = findProperty("LEMON_KEYSTORE_PATH") as String?
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = findProperty("LEMON_KEYSTORE_PASSWORD") as String?
                keyAlias = findProperty("LEMON_KEY_ALIAS") as String?
                keyPassword = findProperty("LEMON_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 不再回退 debug 签名：缺签名时保持无签名，
            // 由文件末尾的任务图校验直接让发布构建失败，杜绝误用调试签名对外发布。
            signingConfig = if (findProperty("LEMON_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 发布构建签名硬校验：只要本次构建会产出 release 包，就必须提供正式签名，
// 否则直接失败，杜绝静默使用 debug 签名对外发布。
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { it.name == "assembleRelease" || it.name == "bundleRelease" }
    if (buildingRelease && findProperty("LEMON_KEYSTORE_PATH") == null) {
        throw GradleException(
            "缺少发布签名配置：请在 local.properties 或 CI Secrets 中提供 " +
                "LEMON_KEYSTORE_PATH / LEMON_KEYSTORE_PASSWORD / LEMON_KEY_ALIAS / LEMON_KEY_PASSWORD。"
        )
    }
}

ksp {
    // 导出 Room schema 到 app/schemas，便于审查迁移与后续做迁移测试。
    arg("room.schemaLocation", "$projectDir/schemas")
}

// 分发副本：assembleRelease 完成后，把产物额外复制一份为「柠檬百宝箱.apk」到分发目录。
// 原始 app\build\outputs\apk\release\app-release.apk 保持不动，二者同时存在、内容一致。
// 分发目录可用 -PlemonApkOutputDir=... 覆盖，默认 D:/Downloads/kotlin。
val lemonApkOutputDir = (findProperty("lemonApkOutputDir") as String?) ?: "D:/Downloads/kotlin"

val copyReleaseApkForDistribution by tasks.registering(Copy::class) {
    description = "把 release APK 复制为「柠檬百宝箱.apk」到分发目录"
    group = "build"
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(lemonApkOutputDir)
    rename { "柠檬百宝箱.apk" }
    // 产物尚未生成时（例如只跑了别的任务）静默跳过，不打断构建。
    onlyIf { layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.exists() }
}

// 只挂在 release 构建上，debug 等其它任务不受影响。
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyReleaseApkForDistribution)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.zxing.lite)
    implementation(libs.androidx.appcompat)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // 局域网同步（同一 WiFi 内互传）
    implementation(libs.nanohttpd)

    // 农历换算（纪念日农历生日按农历推算每年循环；MIT）
    implementation("cn.6tail:tyme4kt-android:1.5.0")

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
