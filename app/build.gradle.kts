import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// machine-specific settings, never committed: your Jellyfin server and the release signing key (see README)
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

android {
    namespace = "com.mau.hub"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "2.0"
        // your Jellyfin server (jellyfin.server=http://... in local.properties); example address otherwise
        buildConfigField("String", "JELLYFIN_SERVER", "\"${local.getProperty("jellyfin.server", "http://jellyfin.example.com:8096")}\"")
        // Home Assistant for the tablet's section: ha.local (used when it answers, i.e. at home) and ha.remote in local.properties
        buildConfigField("String", "HA_LOCAL", "\"${local.getProperty("ha.local", "")}\"")
        buildConfigField("String", "HA_REMOTE", "\"${local.getProperty("ha.remote", "")}\"")
    }
    // two separate apps from the same code, so the TV and the tablet never share an install or its settings:
    // assembleTvRelease for the TV, assembleTabletRelease for the tablet
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.mau.hub.redesign" // what the TV runs
            manifestPlaceholders["appLabel"] = "Joint Launcher"
        }
        create("tablet") {
            dimension = "device"
            applicationId = "com.mau.hub.tablet"
            manifestPlaceholders["appLabel"] = "Joint Launcher Tablet"
        }
    }
    signingConfigs {
        // release.storeFile / storePassword / keyAlias / keyPassword in local.properties; without them, release builds stay unsigned
        local.getProperty("release.storeFile")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = local.getProperty("release.storePassword")
                keyAlias = local.getProperty("release.keyAlias")
                keyPassword = local.getProperty("release.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true // drops unused library code: a much smaller download on a 4 GB TV
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
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
    // player: audio/subtitle track choice, live MPEG-TS; MPEG-2 video still goes to VLC (this TV's decoder is unusable)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3") // QR code for the phone sign-in page
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json; Android's is stubbed in unit tests
}
