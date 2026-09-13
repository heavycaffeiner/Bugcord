#!/usr/bin/env bash
# Downloads the prebuilt artifacts the Bugcord build resolves, into local-repo/.
# local-repo is a directory acting as a Maven repository, so a fresh clone needs this
# script once before Gradle can resolve anything.
set -euo pipefail

HOST="${BUGCORD_ARTIFACT_HOST:-https://github.com/thirdscam}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DISCORD_APK_SHA256="07a24b005ae0aca13d02161424111692ab2667be0724d598c914620d35c2ae03"

download() {
    local url="$1" path="$2"
    mkdir -p "$ROOT/local-repo/$(dirname "$path")"
    curl -fsSL --retry 5 --retry-delay 2 -o "$ROOT/local-repo/$path" "$url"
}

# The unmodified Discord APK the patch targets, staged where prepareDiscordApk looks for it.
stage_discord_apk() {
    local apk="$ROOT/Original/126021/base.apk"
    [ -f "$apk" ] && return

    mkdir -p "$(dirname "$apk")"
    curl -fsSL --retry 5 --retry-delay 2 -o "$apk"         "$HOST/Bugcord-Maven/releases/download/126021/base.apk"

    local actual
    actual="$(sha256sum "$apk" | cut -d' ' -f1)"
    if [ "$actual" != "$DISCORD_APK_SHA256" ]; then
        rm -f "$apk"
        echo "Discord APK checksum mismatch: expected $DISCORD_APK_SHA256, got $actual" >&2
        exit 1
    fi
}

download "$HOST/Bugcord-Gradle/releases/download/2.3.2/com.bugcord.core.gradle.plugin-2.3.2.pom" "com/bugcord/core/com.bugcord.core.gradle.plugin/2.3.2/com.bugcord.core.gradle.plugin-2.3.2.pom"
download "$HOST/Bugcord-Gradle/releases/download/2.3.2/com.bugcord.injector.gradle.plugin-2.3.2.pom" "com/bugcord/injector/com.bugcord.injector.gradle.plugin/2.3.2/com.bugcord.injector.gradle.plugin-2.3.2.pom"
download "$HOST/Bugcord-Gradle/releases/download/2.3.2/gradle-2.3.2.jar" "com/bugcord/gradle/2.3.2/gradle-2.3.2.jar"
download "$HOST/Bugcord-Gradle/releases/download/2.3.2/gradle-2.3.2.pom" "com/bugcord/gradle/2.3.2/gradle-2.3.2.pom"
download "$HOST/Bugcord-Gradle/releases/download/2.3.2/gradle-2.3.2.module" "com/bugcord/gradle/2.3.2/gradle-2.3.2.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-base-cmd-2.4.0.pom" "com/aliucord/d2j/d2j-base-cmd/2.4.0/d2j-base-cmd-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-base-cmd-2.4.0.jar" "com/aliucord/d2j/d2j-base-cmd/2.4.0/d2j-base-cmd-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-base-cmd-2.4.0.module" "com/aliucord/d2j/d2j-base-cmd/2.4.0/d2j-base-cmd-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-external-2.4.0.pom" "com/aliucord/d2j/d2j-external/2.4.0/d2j-external-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-external-2.4.0.jar" "com/aliucord/d2j/d2j-external/2.4.0/d2j-external-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-jasmin-2.4.0.pom" "com/aliucord/d2j/d2j-jasmin/2.4.0/d2j-jasmin-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-jasmin-2.4.0.jar" "com/aliucord/d2j/d2j-jasmin/2.4.0/d2j-jasmin-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-jasmin-2.4.0.module" "com/aliucord/d2j/d2j-jasmin/2.4.0/d2j-jasmin-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-smali-2.4.0.pom" "com/aliucord/d2j/d2j-smali/2.4.0/d2j-smali-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-smali-2.4.0.jar" "com/aliucord/d2j/d2j-smali/2.4.0/d2j-smali-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/d2j-smali-2.4.0.module" "com/aliucord/d2j/d2j-smali/2.4.0/d2j-smali-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-ir-2.4.0.pom" "com/aliucord/d2j/dex-ir/2.4.0/dex-ir-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-ir-2.4.0.jar" "com/aliucord/d2j/dex-ir/2.4.0/dex-ir-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-ir-2.4.0.module" "com/aliucord/d2j/dex-ir/2.4.0/dex-ir-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-api-2.4.0.pom" "com/aliucord/d2j/dex-reader-api/2.4.0/dex-reader-api-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-api-2.4.0.jar" "com/aliucord/d2j/dex-reader-api/2.4.0/dex-reader-api-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-api-2.4.0.module" "com/aliucord/d2j/dex-reader-api/2.4.0/dex-reader-api-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-2.4.0.pom" "com/aliucord/d2j/dex-reader/2.4.0/dex-reader-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-2.4.0.jar" "com/aliucord/d2j/dex-reader/2.4.0/dex-reader-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-reader-2.4.0.module" "com/aliucord/d2j/dex-reader/2.4.0/dex-reader-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-tools-2.4.0.pom" "com/aliucord/d2j/dex-tools/2.4.0/dex-tools-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-tools-2.4.0.jar" "com/aliucord/d2j/dex-tools/2.4.0/dex-tools-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-tools-2.4.0.module" "com/aliucord/d2j/dex-tools/2.4.0/dex-tools-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-translator-2.4.0.pom" "com/aliucord/d2j/dex-translator/2.4.0/dex-translator-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-translator-2.4.0.jar" "com/aliucord/d2j/dex-translator/2.4.0/dex-translator-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-translator-2.4.0.module" "com/aliucord/d2j/dex-translator/2.4.0/dex-translator-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-writer-2.4.0.pom" "com/aliucord/d2j/dex-writer/2.4.0/dex-writer-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-writer-2.4.0.jar" "com/aliucord/d2j/dex-writer/2.4.0/dex-writer-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex-writer-2.4.0.module" "com/aliucord/d2j/dex-writer/2.4.0/dex-writer-2.4.0.module"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex2jar-2.4.0.pom" "com/aliucord/d2j/dex2jar/2.4.0/dex2jar-2.4.0.pom"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex2jar-2.4.0.jar" "com/aliucord/d2j/dex2jar/2.4.0/dex2jar-2.4.0.jar"
download "$HOST/Bugcord-Maven/releases/download/2.4.0/dex2jar-2.4.0.module" "com/aliucord/d2j/dex2jar/2.4.0/dex2jar-2.4.0.module"
download "$HOST/Bugcord-Hook/releases/download/1.1.7/Bugcordhook-1.1.7.aar" "com/bugcord/Bugcordhook/1.1.7/Bugcordhook-1.1.7.aar"
download "$HOST/Bugcord-Hook/releases/download/1.1.7/Bugcordhook-1.1.7.pom" "com/bugcord/Bugcordhook/1.1.7/Bugcordhook-1.1.7.pom"
download "$HOST/Bugcord-Hook/releases/download/1.1.7/Bugcordhook-1.1.7.module" "com/bugcord/Bugcordhook/1.1.7/Bugcordhook-1.1.7.module"
download "$HOST/Bugcord-WebRTC/releases/download/1.0.2/Bugcordwebrtc-1.0.2.aar" "com/bugcord/Bugcordwebrtc/1.0.2/Bugcordwebrtc-1.0.2.aar"
download "$HOST/Bugcord-WebRTC/releases/download/1.0.2/Bugcordwebrtc-1.0.2.pom" "com/bugcord/Bugcordwebrtc/1.0.2/Bugcordwebrtc-1.0.2.pom"
download "$HOST/Bugcord-WebRTC/releases/download/1.0.2/Bugcordwebrtc-1.0.2.module" "com/bugcord/Bugcordwebrtc/1.0.2/Bugcordwebrtc-1.0.2.module"

stage_discord_apk

echo "Fetched the Bugcord toolchain into $ROOT/local-repo"
