import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val berkasKeystore: File? = System.getenv("KEYSTORE_FILE")
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "eu.kanade.tachiyomi.extension.id.kuramanime"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.id.kuramanime"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.4.2"
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
    compileOnly("org.jspecify:jspecify:1.0.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
