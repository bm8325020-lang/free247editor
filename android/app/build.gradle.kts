plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "clipfactory.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.free247.clipfactory"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "1.9.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/clipfactory.jks")
            storePassword = "clipfactory"
            keyAlias = "clipfactory"
            keyPassword = "clipfactory"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    // one APK per phone type: each carries its own Python + ffmpeg for the in-app downloader
    splits {
        abi {
            isEnable = !project.hasProperty("allAbis")
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // the in-app yt-dlp runs Python from native libraries that must be extracted on install
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module",
                "META-INF/versions/9/previous-compilation-data.bin", "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.anthropic:anthropic-java:3.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
