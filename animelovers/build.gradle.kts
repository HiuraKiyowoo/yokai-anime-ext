import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val berkasKeystore: File? = System.getenv("KEYSTORE_FILE")
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "eu.kanade.tachiyomi.extension.id.animelovers"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.id.animelovers"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.4.1"
    }

    signingConfigs {
        if (berkasKeystore != null) {
            create("rilis") {
                storeFile = berkasKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (berkasKeystore != null) {
                signingConfigs.getByName("rilis")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("yokai:source-api:1.0.0")
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
