plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.caniko.cenix.fixture"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.caniko.cenix.fixture"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
}
