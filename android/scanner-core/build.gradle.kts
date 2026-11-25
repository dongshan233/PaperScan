plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.scanner.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    // Flow/StateFlow are part of the public API (ScanCoordinator exposes them)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("org.opencv:opencv:4.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
