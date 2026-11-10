plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.sensoryforecast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sensoryforecast"
        // 공모전 개발 환경 요구사항: Android API 28 이상
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 제출용 APK가 바로 설치되도록 디버그 키로 서명합니다. 정식 키를 쓰면 keystore.properties로 바꿉니다.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.texttospeech)
    implementation(libs.pleos.speechtotext)

    testImplementation(libs.junit)
}
