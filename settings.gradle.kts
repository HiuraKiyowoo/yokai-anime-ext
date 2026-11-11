pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        id("com.android.application") version "8.12.2"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // Pustaka Yokai (injekt-koin, unifile) hanya ada di JitPack
        maven("https://jitpack.io")
        maven("https://s01.oss.sonatype.org/content/repositories/releases/")
    }
}

rootProject.name = "yokai-anime-ext"

include(":wibutime")
include(":animein")
include(":animelovers")
include(":otakudesu")
include(":nontonhentai")

/*
 * Pustaka antarmuka sumber (eu.kanade.tachiyomi.source.*) tidak diterbitkan ke
 * Maven oleh proyek Yokai. Daripada memaksanya lewat JitPack, repo aplikasi
 * disertakan sebagai build komposit: koordinat "yokai:source-api" diganti
 * dengan proyek :source:api yang asli. Ekstensi jadi selalu dibangun terhadap
 * API yang sama persis dengan aplikasinya.
 */
val repoYokai = file("yokai")
if (repoYokai.isDirectory) {
    includeBuild(repoYokai) {
        dependencySubstitution {
            substitute(module("yokai:source-api")).using(project(":source:api"))
        }
    }
} else {
    logger.warn("Direktori 'yokai' tidak ada — dependensi yokai:source-api tidak akan bisa diselesaikan.")
}
