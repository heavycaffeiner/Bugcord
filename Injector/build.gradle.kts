version = "2.4.1"

plugins {
    alias(libs.plugins.bugcord.injector)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "com.bugcord.injector"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "VERSION", "\"$version\"")
        buildConfigField("String", "TAG", "\"Injector\"")
    }

    buildTypes {
        named("release") {
            isMinifyEnabled = false
        }
    }

    androidResources {
        enable = false
    }

    buildFeatures {
        buildConfig = true
        resValues = false
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
            "-Xallow-kotlin-package", // Workaround to adding kotlin.enums.EnumEntries polyfill
        )
    }
}

dependencies {
    compileOnly(libs.bugcordhook)
    compileOnly(libs.appcompat)
    compileOnly(libs.discord)
    compileOnly(libs.kotlin.stdlib)
}
