import java.util.Properties

// 签名：根目录放 keystore.properties（不进仓库）就用你自己的密钥，没有就用调试密钥
val ksFile = rootProject.file("keystore.properties")
val ks = Properties().also { p -> if (ksFile.exists()) ksFile.inputStream().use { p.load(it) } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gesturebrowser"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.gesturebrowser"
        minSdk = 29
        targetSdk = 35
        versionCode = 26
        versionName = "3.6"
    }

    signingConfigs {
        create("gb") {
            if (ks.isNotEmpty()) {
                storeFile = file(ks.getProperty("storeFile"))
                storePassword = ks.getProperty("storePassword")
                keyAlias = ks.getProperty("keyAlias")
                keyPassword = ks.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // 正式版：R8 优化 + 去掉无用资源，运行更快、包更小
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ks.isNotEmpty()) signingConfigs.getByName("gb") else signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = if (ks.isNotEmpty()) signingConfigs.getByName("gb") else signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
