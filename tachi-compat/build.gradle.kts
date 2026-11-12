// The classes Mihon (manga) and Aniyomi (anime) extensions are built against, so their APKs run inside aniliphy.
// Most of the code is adapted from those apps, under the Apache License 2.0: see NOTICE.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.aniliphy.compat"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.json.okio)
    api(libs.kotlinx.serialization.protobuf)
    api(libs.kotlinx.coroutines.android)
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.injekt)
    api(libs.androidx.preference)
}
