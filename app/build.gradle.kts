plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

/**
 * 把 Gradle 属性值渲染成 buildConfigField 可用的字符串字面量。
 * 值来自 CI Secrets / local.properties，可能含引号或反斜杠，必须先转义再嵌入。
 */
fun String.asBuildConfigLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.goodyaoshi.lemonbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goodyaoshi.lemonbox"
        minSdk = 26
        targetSdk = 35
        // 版本策略：versionName 用语义化版本，versionCode 由它推导
        // （major*1000000 + minor*10000 + patch*100），与 CI 从 tag 推导的规则一致
        // （见 .github/workflows/release.yml），保证只增不减。
        // 版本号只在用户明确要求时调整，不自动变更。
        versionCode = (findProperty("lemonVersionCode") as String?)?.toIntOrNull() ?: 1400
        versionName = (findProperty("lemonVersionName") as String?) ?: "0.0.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 原生库 ABI 收敛（F17）：本应用以「直接分发 APK」为主（见文件末尾的 copyReleaseApkForDistribution），
        // 走不了应用商店的 ABI 分包。产品上不需要兼容 32 位老机型，故只保留 arm64-v8a：
        // 包体最小、体积收益最大，且 64 位机型覆盖当下的绝大多数在售设备。
        // 若日后改为上架应用商店，建议改发布 AAB（bundleRelease），由商店按设备下发对应 ABI。
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 离线文字识别（F01）：模型约 6~31MB，按硬性规则不进仓库、也不随包分发，
        // 由用户在应用内显式确认后从官方百度 BOS 直链下载（地址与校验值固定在 OcrModelTier）；
        // 识别字典随 APK 走 assets，无需远程清单与编译期地址注入。
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
        // 图片云端存储（S3 兼容）：debug 包一律不支持，避免开发期把测试图片传进真实存储桶。
        debug {
            buildConfigField("boolean", "IMAGE_CLOUD_SUPPORTED", "false")
            buildConfigField("String", "S3_PAYLOAD_ENCRYPTED", "\"\"")
        }

        release {
            isMinifyEnabled = true
            // 图片云端存储（仅正式包）——「配置密文进包、用户只输口令」：
            // endpoint / region / bucket / prefix 与 AccessKey / SecretKey 整份由
            // tools/encrypt-s3-config.mjs 用口令加密成一段密文，随包分发（CI Secret
            // LEMON_S3_PAYLOAD_ENCRYPTED 注入）。BuildConfig 是明文字符串常量、反编译即可读出，
            // 所以进包的**只能是密文**：没有口令谁也用不了其中的密钥，而用户只需记住一个口令，
            // 不必在每台设备上各填一份 AccessKey / SecretKey。
            // 口令绝不进 CI、不进包；本地未配该属性时（如开发者自己跑 assembleRelease）为空串，
            // App 据此提示「此版本未内置云存储配置」，而不是崩溃。
            buildConfigField("boolean", "IMAGE_CLOUD_SUPPORTED", "true")
            buildConfigField(
                "String",
                "S3_PAYLOAD_ENCRYPTED",
                (findProperty("LEMON_S3_PAYLOAD_ENCRYPTED") as String? ?: "").asBuildConfigLiteral()
            )
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

    // .so 压缩（包体优化）：AGP 4.2 起默认 useLegacyPackaging = false —— .so 不压缩、按页对齐存放，
    // 便于 mmap 直接加载，代价是原始体积原样进包。本应用以「直接分发 APK 给用户下载」为主
    // （见文件末尾的 copyReleaseApkForDistribution），下载体积比安装后占用更敏感，
    // 故改回压缩存储换取更小的下载包。
    // 代价：安装时解压到应用 lib 目录，安装后磁盘占用增加、首次加载略慢。
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

    testOptions {
        // 纯 JVM 单测里用到极简 FakeContext（ContextWrapper）承载 SharedPreferences；
        // 让 mockable android.jar 的未实现方法返回默认值而非抛 "not mocked"。
        unitTests.isReturnDefaultValues = true
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

/**
 * 生成云存储配置密文的命令行工具：
 * ```
 * $env:S3_PAYLOAD_PASSPHRASE='…'; .\gradlew encryptS3Payload --args="--in s3cfg.json"
 * ```
 * 入口在 unit test 源码集（见 EncryptS3Payload.kt）：它不属于 App 运行时、不进 APK，
 * 却能直接复用 App 解密用的同一份 S3PayloadCipher，两端不可能算法漂移。
 * 因此这里用「主源码集 + 单测源码集」的 Kotlin 产物加测试变体 classpath 来运行它。
 */
tasks.register<JavaExec>("encryptS3Payload") {
    group = "tools"
    description = "用口令加密云存储配置，生成 GitHub Secret LEMON_S3_PAYLOAD_ENCRYPTED"
    dependsOn("compileDebugUnitTestKotlin")
    mainClass.set("com.goodyaoshi.lemonbox.data.imagecloud.EncryptS3PayloadKt")
    // classpath 在执行期才解析：AGP 的变体 configuration 到那时才一定存在。
    doFirst {
        classpath = files(
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
            configurations.getByName("debugUnitTestRuntimeClasspath")
        )
    }
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

    // 端侧离线文字识别（F01）：ONNX Runtime 完整版推理引擎；模型文件按需下载，不随包分发。
    // 不用 mobile 精简版：它虽小（arm64 .so 3.56MB vs 16.76MB），但精简构建不含完整 ONNX 模型
    // 解析器，直接加载 .onnx 会报 "ONNX format model is not supported"（实测 PP-OCRv6 确认）。
    // 体积改由 useLegacyPackaging 压缩 .so 来减。
    implementation(libs.onnxruntime.android)

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
