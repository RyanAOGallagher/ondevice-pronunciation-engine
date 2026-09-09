plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weaversmind.pronunciation"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // keeps the AAR's own copy uncompressed; consumers must repeat this in their app
    // module for a fast first load (library noCompress doesn't propagate).
    androidResources { noCompress.add("onnx") }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android's is stubbed)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
