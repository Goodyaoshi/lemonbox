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
        versionCode = (findProperty("lemonVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("lemonVersionName") as String?) ?: "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 只保留 arm64-v8a：剔除 32 位与 x86 原生库，显著减小包体。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 发布签名占位：真实密钥通过本地 gradle.properties / CI Secrets 注入，
        // 未提供时回退到 debug 签名，保证 assembleRelease 始终能产出可安装的包。
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
            signingConfig = if (findProperty("LEMON_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

ksp {
    // 导出 Room schema 到 app/schemas，便于审查迁移与后续做迁移测试。
    arg("room.schemaLocation", "$projectDir/schemas")
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

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
