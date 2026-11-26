plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.goodbanner.camprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.goodbanner.camprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Provided by the Xposed/LSPosed framework at runtime; compile only.
    compileOnly("de.robv.android.xposed:api:82")
}
