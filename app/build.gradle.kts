plugins {
    alias(libs.plugins.android.application)
}

// ── 自动打包 PC 端 Receiver ──────────────────────────────────────
val pcDebugDir = rootProject.file("pc_proj/ShareToPcReciver/ShareToPcReciver/bin/Debug")
val assetsDir = file("src/main/assets")
val pcZipName = "ShareToPcReciver.zip"

tasks.register<Zip>("packagePcReceiver") {
    group = "pc"
    description = "打包 PC 端 Receiver (Debug) 到 assets"

    from(pcDebugDir)
    include("*.exe", "*.dll", "*.exe.config", "config.json")
    archiveFileName.set(pcZipName)
    destinationDirectory.set(assetsDir)
}

// 在 Android 构建前自动打包 PC 端
tasks.named("preBuild") {
    dependsOn("packagePcReceiver")
}

// ── Android 配置 ──────────────────────────────────────────────────
android {
    namespace = "github.linsmod.sharetopc"
    buildFeatures {
        buildConfig = true
    }
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "github.linsmod.sharetopc"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.work.runtime)
    implementation(libs.preference)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
