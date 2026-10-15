import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from keystore.properties (not committed). Without it, only debug builds can be made.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.dashcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dashcast"
        minSdk = 29          // Android 10+
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.0"
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    lint {
        // AGP 8.5's lint crashes on the JDK 25 bundled with current Android Studio; skip the release-time check.
        // Remove once the Android Gradle plugin is upgraded.
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// APKs are named e.g. DashCast-0.3.0-release.apk
base { archivesName.set("DashCast-${android.defaultConfig.versionName}") }

// No third-party dependencies: the HTTP/WebSocket server is built in.
