plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.caniko.cenix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.caniko.cenix"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
        }
        debug {
            isDebuggable = true
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            if (project.hasProperty("omitNative")) {
                excludes += setOf("**/*.so")
            }
        }
    }
}

dependencies {
    val room = "2.6.1"
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.room:room-runtime:$room")
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("net.java.dev.jna:jna:5.19.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:$room")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$room")
}
