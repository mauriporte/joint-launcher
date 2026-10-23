plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mau.hub"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mau.hub"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json; Android's is stubbed in unit tests
}
