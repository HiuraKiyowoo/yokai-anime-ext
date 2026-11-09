import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Penanda tangan.
 *
 * Kalau CI menyediakan keystore lewat env (KEYSTORE_FILE + sandi), pakai itu —
 * sidik jarinya harus sama dengan `signingKeyFingerprint` di repo.json, kalau
 * tidak ekstensi tidak akan dipercaya otomatis. Kalau tidak ada, jatuh ke
 * keystore debug supaya build lokal tetap bisa jalan.
 */
val berkasKeystore: File? = System.getenv("KEYSTORE_FILE")
    ?.let { File(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "eu.kanade.tachiyomi.extension.id.wibutime"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.id.wibutime"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.5.2"
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

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*")
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
    // compileOnly, BUKAN implementation: kelas pustaka ini sudah ada di dalam
    // aplikasi. Kalau ikut terpaket, ekstensi membawa salinan kelas sendiri,
    // classloader memuat salinan itu lebih dulu (ChildFirstPathClassLoader),
    // lalu cast ke antarmuka milik aplikasi gagal -> aplikasi FC saat memuat
    // ekstensi. Ini juga yang membuat APK ekstensi membengkak jadi 24 MB.
    compileOnly("yokai:source-api:1.0.0")
    compileOnly("com.squareup.okhttp3:okhttp:5.4.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
