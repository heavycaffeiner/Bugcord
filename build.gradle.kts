import java.security.MessageDigest

/** Discord version the patch targets. */
private val discordVersion = "126021"

plugins {
    alias(libs.plugins.bugcord.core) apply false
    alias(libs.plugins.bugcord.injector) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.dokka.html) apply false
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.kotlin) apply false
}

/** Directory holding Discord APKs the user supplied, resolved as a local Maven repository. */
private val localRepo = layout.projectDirectory.dir("Original/maven")

/**
 * Stages the supplied Discord APK into [localRepo] so the build can resolve it without a remote host.
 * Run this once after placing the APK in `Original/<version>/base.apk`.
 */
tasks.register("prepareDiscordApk") {
    group = "bugcord"
    description = "Publishes the locally supplied Discord APK into the local Maven repository"
    notCompatibleWithConfigurationCache("Stages a supplied file using script state")

    val apk = layout.projectDirectory.file("Original/$discordVersion/base.apk").asFile
    val targetDir = localRepo.dir("com/discord/discord/$discordVersion").asFile
    val target = File(targetDir, "discord-$discordVersion.apk")
    val pom = File(targetDir, "discord-$discordVersion.pom")

    outputs.file(target)
    outputs.file(pom)

    doLast {
        if (!apk.isFile)
            error("Missing $apk. Download Discord $discordVersion from APKMirror and place it there.")

        targetDir.mkdirs()
        apk.copyTo(target, overwrite = true)
        pom.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.discord</groupId>
  <artifactId>discord</artifactId>
  <version>$discordVersion</version>
  <packaging>apk</packaging>
</project>
"""
        )

        logger.lifecycle("Staged Discord $discordVersion (${target.length()} bytes, sha-256 ${target.sha256()})")
    }
}

private fun File.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
