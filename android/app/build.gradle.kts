plugins {
    id("com.android.application")
}

android {
    namespace = "dev.kouragita.pocket"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kouragita.pocket"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
