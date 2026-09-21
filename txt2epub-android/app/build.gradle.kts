import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 어떤 빌드가 설치돼 있는지 앱 화면에서 바로 보이게 한다.
 * 내려받은 APK 파일명이 늘 같아서 예전 것을 다시 설치해도 알아채기 어렵다.
 */
val gitSha: String = runCatching {
    val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir).redirectErrorStream(true).start()
    p.inputStream.bufferedReader().readText().trim().take(12).ifEmpty { "nogit" }
}.getOrDefault("nogit")

val builtAt: String = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
    .format(Date())

android {
    namespace = "com.kaicia.txt2epub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kaicia.txt2epub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILT_AT", "\"$builtAt\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 릴리스 빌드가 경고로 멈추지 않게 한다. 오류는 그대로 본다.
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
