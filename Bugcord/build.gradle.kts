@file:Suppress("UnstableApiUsage")

import com.vanniktech.maven.publish.AndroidMultiVariantLibrary

plugins {
    alias(libs.plugins.bugcord.core)
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka.html)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.publish)
}

group = "com.bugcord"
version = "2.9.11"

android {
    namespace = "com.bugcord"
    compileSdk = 36

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    defaultConfig {
        buildConfigField("String", "VERSION", "\"$version\"")
        buildConfigField("boolean", "RELEASE", System.getenv("RELEASE") ?: "false")
        buildConfigField("int", "DISCORD_VERSION", libs.versions.discord.get())
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        disable += "SetTextI18n"
    }
}

kotlin {
    jvmToolchain(21)

    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
            "-Xannotation-default-target=param-property",
            "-Xallow-kotlin-package", // Workaround to adding kotlin.enums.EnumEntries polyfill
        )
    }
}

dependencies {
    api(libs.bugcordhook)
    compileOnly(libs.appcompat)
    compileOnly(libs.constraintlayout)
    // Project stubs come first so they shadow the classes shipped in the Discord APK
    compileOnly(project(":Injector")) // Needed to access certain stubs
    compileOnly(project(":voice")) // Needed to access certain stubs
    compileOnly(libs.discord)
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.material)
    coreLibraryDesugaring(libs.desugar)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(arrayOf(
        "-Xlint:deprecation",
    ))
}

mavenPublishing {
    coordinates("com.bugcord", "Bugcord")
    configure(AndroidMultiVariantLibrary(
        includedBuildTypeValues = setOf("debug"),
    ))
}
