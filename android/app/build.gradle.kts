plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// google-services.json 이 있을 때만 Firebase 를 켠다. 없으면 FCM 없이(서버 동기화 폴링 + 로컬 백업 알람) 동작한다.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "kr.ssbin.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.ssbin.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "3.4.0"
    }

    // 고정 서명 키(덮어 설치·앱 안 업데이트용). CI 가 Secret 에서 키 파일을 만들고 ASSISTANT_KEYSTORE 로 경로를 준다.
    // 없으면(로컬 등) 기본 디버그 키.
    signingConfigs {
        getByName("debug") {
            System.getenv("ASSISTANT_KEYSTORE")?.takeIf { it.isNotBlank() }?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // v3 작업물 뷰어: 마크다운 렌더링
    implementation("io.noties.markwon:core:4.6.2")
    // 장소 탭: 지도에서 위치 고르기(카카오 지도 SDK, arm64-v8a·armeabi-v7a)
    implementation("com.kakao.maps.open:android:2.15.2")
}
