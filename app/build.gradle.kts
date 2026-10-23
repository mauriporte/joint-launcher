import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mau.hub"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mau.hub.redesign" // installs next to the running Joint Launcher instead of replacing it
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        // your Jellyfin server, from the git-ignored local.properties (jellyfin.server=http://...); example address otherwise
        val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
        buildConfigField("String", "JELLYFIN_SERVER", "\"${local.getProperty("jellyfin.server", "http://jellyfin.example.com:8096")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2") // rows: builds only what's near the screen, D-pad focus included
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json; Android's is stubbed in unit tests
}
