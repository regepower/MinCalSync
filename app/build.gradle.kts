plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.regepower.mincalsync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.regepower.mincalsync"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    // Release key comes from CI secrets. Always the same key, otherwise updates need a
    // reinstall and MinCalSync loses its record of which target events it owns.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "mincalsync"
                // keytool's default PKCS12 keystores use the store password for the key.
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Local builds without secrets: debug key, so the APK stays installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // Deflate classes.dex (AGP stores it uncompressed for minSdk >= 28) and drop Kotlin
    // metadata nobody reads at runtime. Both measured in the android-app-builder skill.
    packaging {
        dex { useLegacyPackaging = true }
        resources {
            excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

// No dependencies on purpose: framework APIs only (JobScheduler, CalendarContract, views).
