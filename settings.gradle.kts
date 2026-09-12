pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        maven {
            name = "bugcord"
            url = uri("https://maven.aliucord.com/releases")
        }
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Holds the locally published hook library built from the Bugcord-Hook submodule.
        mavenLocal()
        maven {
            name = "bugcord"
            url = uri("https://maven.aliucord.com/releases")
        }
    }
}

include(":Bugcord")
include(":Injector")
include(":patches")
include(":kotlin-stdlib")
include(":voice")

rootProject.name = "Bugcord"

