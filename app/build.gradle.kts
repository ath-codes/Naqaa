plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.naqaa.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.naqaa.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
    }

    // مفتاح توقيع ثابت: يسمح بتحديث التطبيق فوق النسخة المثبتة دون حذفها
    signingConfigs {
        create("fixed") {
            storeFile = file("naqaa.keystore")
            storePassword = "naqaa123"
            keyAlias = "naqaa"
            keyPassword = "naqaa123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
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
        viewBinding = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
