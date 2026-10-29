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
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Signing material lives outside the repository; the build stays unsigned
    // when it is absent, so a clean clone still assembles.
    val keystorePath = System.getenv("POCKET_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("POCKET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POCKET_KEY_ALIAS")
                keyPassword = System.getenv("POCKET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // Debug builds share the project key when it is configured, so a release
        // APK can be installed straight over a debug build instead of forcing an
        // uninstall (Android rejects replacing an app signed with another key).
        debug {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
