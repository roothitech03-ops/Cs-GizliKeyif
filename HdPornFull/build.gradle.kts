plugins {
    id("com.android.library") version "8.1.4"
    kotlin("android") version "1.9.22"
}

android {
    namespace = "com.wus.hdpornfull"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // CloudStream supplies these at runtime as the host APK.
    compileOnly("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    compileOnly("com.github.recloudstream:library:master-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jsoup:jsoup:1.17.2")
}