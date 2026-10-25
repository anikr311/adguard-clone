plugins {
    id("com.android.application")
}

android {
    namespace = "com.adlocko.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.adlocko.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    // Release signing is NEVER the debug key. Provide a real keystore via
    // environment variables (locally or as CI secrets); without them the
    // release build stays unsigned and cannot be installed as an update to
    // a signed build.
    val keystorePath = System.getenv("ADLOCKO_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ADLOCKO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADLOCKO_KEY_ALIAS")
                keyPassword = System.getenv("ADLOCKO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Only what the app actually uses: WebViewAssetLoader for the UI.
    implementation("androidx.webkit:webkit:1.11.0")
}
