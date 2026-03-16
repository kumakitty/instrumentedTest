plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

 android {
    namespace = "com.example.androidinstrumentedtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.androidinstrumentedtest"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(file("src/androidTest/assets"))
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    // ...existing code...
    
    // ...existing code...
    
    // Paddle OCR - 官方 AAR 库（使用本地AAR）
    // 由于官方Maven库不可用，我们使用本地编译的AAR或直接使用PaddleOCR Demo的库
    // implementation("com.baidu.paddle:paddle-lite-ocr:2.9.0")
    
    // 繁简体转换库 - OpenCC (主应用和测试都需要)
    implementation("com.github.houbb:opencc4j:1.7.2")
    androidTestImplementation("com.github.houbb:opencc4j:1.7.2")
    
    // ============ androidTest 依赖 ============
    // 注: PaddleOCR 的原生库已放在 jniLibs/ 目录
    // 不需要额外的 AAR 依赖，JNI 库会自动加载

    // 测试框架依赖
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.documentfile:documentfile:1.0.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
