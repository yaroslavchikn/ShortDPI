plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.example.dpibypass"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.dpibypass"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    if (keystorePath != null) {
        signingConfigs.create("release") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.maybeCreate("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
