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
    // ONNX Runtime 1.24.3, bundled into this AAR: the Java classes from the Maven artifact
    // (libs/) plus libonnxruntime.so rebuilt with only the 44 ops ZIPA uses, arm64-v8a only
    // (src/main/jniLibs/). 10.6 MB of native code instead of 25 MB per ABI (Release config; MinSizeRel was 9.4 MB but ~6% slower). Recipe in NOTES.md.
    // Consumers must NOT also depend on com.microsoft.onnxruntime:onnxruntime-android.
    implementation(files("libs/onnxruntime-1.24.3-classes.jar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android's is stubbed)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
