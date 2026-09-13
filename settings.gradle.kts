pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        // Holds the build plugin and the hook and webrtc libraries built from the submodules.
        mavenLocal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
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

