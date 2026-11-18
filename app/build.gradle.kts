import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.aniliphy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aniliphy"
        minSdk = 26
        targetSdk = 36
        versionCode = property("aniliphy.versionCode").toString().toInt()
        versionName = property("aniliphy.versionName").toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MyAnimeList's Client ID lives in local.properties (not in git) and goes into the APK only when it's built.
    val local = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    defaultConfig {
        buildConfigField("String", "MAL_CLIENT_ID", "\"" + (local?.getProperty("mal.clientId") ?: "") + "\"")
    }

    val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)
    implementation(libs.media3.session)
    implementation(libs.media3.effect)
    implementation(libs.cast.framework)
    implementation(libs.mediarouter)
    implementation(libs.work.runtime)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(project(":source-api"))
    implementation(project(":tachi-compat"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The copies the baseline profile is made and measured with install beside the real app (as com.aniliphy.benchmark),
// so making one never replaces the app on the phone or touches its account.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.applicationId.set("com.aniliphy.benchmark") }
    onVariants(selector().withBuildType("benchmarkRelease")) { it.applicationId.set("com.aniliphy.benchmark") }
}
