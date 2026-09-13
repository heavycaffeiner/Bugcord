pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        // Holds the prebuilt artifacts tools/fetch-toolchain.sh downloads.
        maven { url = uri("local-repo") }
        mavenLocal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Holds the prebuilt artifacts tools/fetch-toolchain.sh downloads.
        maven { url = uri("local-repo") }
        mavenLocal()
        maven {
            name = "aliucord"
            url = uri("https://maven.aliucord.com/releases")
        }
        // Holds the Discord APK the user supplied, staged by the prepareDiscordApk task.
        maven {
            name = "localDiscord"
            url = uri("Original/maven")
        }
    }
}

include(":Bugcord")
include(":Injector")
include(":patches")
include(":kotlin-stdlib")
include(":voice")

rootProject.name = "Bugcord"

