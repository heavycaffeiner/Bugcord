package com.bugcord.updater

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import com.bugcord.*
import com.bugcord.api.NotificationsAPI
import com.bugcord.entities.NotificationData
import com.bugcord.fragments.ConfirmDialog
import com.bugcord.settings.*
import com.bugcord.updater.CoreUpdater.UPDATER_DATA_URL
import com.bugcord.updater.CoreUpdater.isCustomCoreLoaded
import com.bugcord.utils.*
import dalvik.system.BaseDexClassLoader
import java.io.File

/**
 * Handles checking for core/base updates and updating the Bugcord core itself.
 */
internal object CoreUpdater {
    private val logger = Logger("Updater/Core")

    /**
     * Fetches the remote build info.
     */
    private fun fetchBugcordData(): BuildData {
        return Http.simpleJsonGet(UPDATER_DATA_URL, BuildData::class.java)
    }

    /**
     * Checks for base & core updates, and updates the core if possible.
     */
    @JvmStatic
    fun checkForUpdates() {
        if (isUpdaterDisabled() || isCustomCoreLoaded()) return

        try {
            logger.debug("Checking for Bugcord updates...")
            val data = fetchBugcordData()

            if (data.coreVersion > SemVer.parse(BuildConfig.VERSION)) {
                if (Constants.DISCORD_VERSION < data.discordVersion
                    || !ManagerBuild.hasKotlin(data.kotlinVersion.toString())
                    || !ManagerBuild.hasInjector(data.kotlinVersion.toString())
                    || !ManagerBuild.hasPatches(data.patchesVersion.toString())
                ) {
                    val notificationData = NotificationData()
                        .setTitle("Updater")
                        .setBody("This installation is outdated!\n" +
                            "Click to reinstall Bugcord using Bugcord Manager...")
                        .setAutoDismissPeriodSecs(10)
                        .setOnClick { reinstallBugcord() }

                    NotificationsAPI.display(notificationData)
                } else if (!isAutoUpdateEnabled()) {
                    val notificationData = NotificationData()
                        .setTitle("Updater")
                        .setBody("Bugcord has an update available!\n" +
                            "Click to automatically update...")
                        .setAutoDismissPeriodSecs(10)
                        // TODO: open Updater screen instead once it support showing core updates
                        .setOnClick { updateBugcord() }

                    NotificationsAPI.display(notificationData)
                } else {
                    updateBugcord()
                }
            } else {
                logger.debug("No updates found!")
            }
        } catch (e: Exception) {
            logger.errorToast("Failed to check updates for Bugcord", e)
        }
    }

    /**
     * Launches manager to perform an update installation.
     * If manager is not installed, prompt to install it.
     */
    private fun reinstallBugcord() {
        val intent = Intent("com.bugcord.manager.REINSTALL")
            .setClassName("com.bugcord.manager", "com.bugcord.manager.MainActivity")
            .putExtra("bugcord.packageName", Utils.appContext.packageName)

        try {
            Utils.appActivity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ConfirmDialog()
                .setTitle("Updater")
                .setDescription("Bugcord Manager is not installed on this device.\n" +
                    "Open latest Github releases to download Manager?")
                .setOnOkListener { Utils.launchUrl("https://github.com/thirdscam/Bugcord-Manager/releases/latest") }
                .show(Utils.appActivity.supportFragmentManager, "No Manager")
        }
    }

    /**
     * Forcefully replaces the local Bugcord version with the latest from Github.
     * This does not perform any checks as to whether it is safe to do so.
     */
    private fun updateBugcord() = Utils.threadPool.execute {
        try {
            logger.debug("Downloading new core bundle from $CORE_ZIP_URL...")
            Http.simpleDownload(CORE_ZIP_URL, Utils.appContext.codeCacheDir.resolve("Bugcord.zip"))
            logger.debug("Finished downloading core")

            Utils.promptRestart("Bugcord update requires a restart. Restart now?")
        } catch (e: Exception) {
            logger.errorToast("Failed to update Bugcord!", e)
        }
    }

    /**
     * Determines whether the updater has been disabled by the user.
     */
    @JvmStatic
    fun isUpdaterDisabled(): Boolean = Main.settings.getBool(BUGCORD_DISABLE_UPDATER, false)

    /**
     * Determines whether automatic core updates have been disabled by the user.
     */
    @JvmStatic
    fun isAutoUpdateEnabled(): Boolean = Main.settings.getBool(AUTO_UPDATE_BUGCORD_KEY, false)

    /**
     * Determines whether custom core loading has been enabled by the user.
     * Note that this does not guarantee that a custom core is actually currently loaded,
     * refer to [isCustomCoreLoaded] for more information.
     */
    @JvmStatic
    fun isCustomCoreEnabled(): Boolean {
        return Main.settings.getBool(BUGCORD_FROM_STORAGE_KEY, false)
    }

    /**
     * Determines whether the Bugcord core is currently loaded from external storage.
     * This does not take into account the user-configurable toggle [BUGCORD_FROM_STORAGE_KEY] itself.
     * This only works if the currently installed injector version is v2.3.0+, otherwise, it will always return false.
     */
    @JvmStatic
    fun isCustomCoreLoaded(): Boolean = _customCoreLoaded

    // Check classloader paths to see if Bugcord.custom.zip is loaded
    private val _customCoreLoaded: Boolean by lazy {
        val pathList = ReflectUtils.getField(
            BaseDexClassLoader::class.java,
            this.javaClass.classLoader,
            "pathList",
        )!!

        @Suppress("UNCHECKED_CAST")
        val dexElements = ReflectUtils.getField(
            pathList,
            "dexElements",
        )!! as Array<Any>

        val fieldName = when {
            Build.VERSION.SDK_INT < 23 -> "file"
            Build.VERSION.SDK_INT < 26 -> "zip"
            else -> "path"
        }

        dexElements.any { element ->
            val file = ReflectUtils.getField(element, fieldName)!! as File
            file.name == "Bugcord.custom.zip"
        }
    }

    /**
     * The model of the data available at [UPDATER_DATA_URL].
     */
    private data class BuildData(
        @SerializedName("versionCode")
        var discordVersion: Int,
        var coreVersion: SemVer,
        var injectorVersion: SemVer,
        var patchesVersion: SemVer,
        var kotlinVersion: SemVer,
    )

    private const val UPDATER_DATA_URL = "https://raw.githubusercontent.com/thirdscam/Bugcord/builds/data.json"
    private const val CORE_ZIP_URL = "https://raw.githubusercontent.com/thirdscam/Bugcord/builds/Bugcord.zip"
}
