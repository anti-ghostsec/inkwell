plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.inkwell.viewer"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.inkwell.viewer"
        minSdk = 26
        targetSdk = 36
        // Each build must count higher than the one it updates: the release
        // workflow passes the number of commits (-PversionCode=...).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
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
    // Only for the tests of the comic reader (CbzTest), not in the app.
    testImplementation("junit:junit:4.13.2")
}
