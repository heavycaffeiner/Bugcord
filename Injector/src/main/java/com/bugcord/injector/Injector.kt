/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2022 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.injector

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.app.App
import com.discord.app.AppActivity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * External settings key controlling whether custom core is enabled.
 */
private const val BUGCORD_FROM_STORAGE_KEY = "AC_from_storage"

private val applicationInitialized = AtomicBoolean(false)
private val activityInitialized = AtomicBoolean(false)

/**
 * The main entrypoint, invoked by the overridden Discord class.
 * This is invoked shortly after [App.onCreate] starts executing.
 */
internal fun init(appCtx: Application) {
    if (applicationInitialized.getAndSet(true)) return

    Logger.init()
    Logger.d("Started Bugcord Injector!")

    try {
        if (!XposedBridge.disableHiddenApiRestrictions()) {
            Logger.w("Failed to disable hidden api restrictions")
        }
        if (!XposedBridge.disableProfileSaver()) {
            Logger.w("Failed to disable profile saver")
        }

        pruneArtProfile(appCtx)
    } catch (e: Exception) {
        Logger.e("Failed to setup environment", e)
    }

    try {
        Injector(appCtx).onApplicationCreate()
    } catch (t: Throwable) {
        Logger.errorToast(appCtx, "Failed to run Bugcord Injector", t)
    }
}

private class Injector(private val appCtx: Application) {
    /**
     * Bugcord's base directory in external storage that's accessible to the user, storing settings and plugins.
     */
    private val externalBaseDir = Environment.getExternalStorageDirectory().resolve("Bugcord")

    /**
     * Bugcord's main core settings file. This is used to determine whether a custom core is enabled.
     */
    private val externalSettingsFile = externalBaseDir.resolve("settings/Bugcord.json")

    /**
     * A custom core that was built and deployed to this device, accessible to the user.
     */
    private val externalCustomCoreFile = externalBaseDir.resolve("Bugcord.zip")

    /**
     * An official Bugcord core build downloaded by Injector.
     * This is inaccessible to users and is stored in internal cache.
     */
    private val internalCoreFile = appCtx.codeCacheDir.resolve("Bugcord.zip")

    /**
     * A copy of [externalCustomCoreFile], to prevent corruption while Bugcord is already running.
     * This is inaccessible to users and stored in internal cache.
     */
    private val internalCustomCoreFile = appCtx.codeCacheDir.resolve("Bugcord.custom.zip")

    /**
     * This is invoked when [App.onCreate] is called, triggering a possible early initialization of the Bugcord core
     * if permissions have been granted and the core has already been downloaded during a prior launch.
     */
    fun onApplicationCreate() {
        if (!isPermissionsGranted()) {
            restoreCoreFlow()
            return
        }

        Logger.d("Checking custom core settings")
        val useCustomCore = isUsingCustomCore()

        // Delete old custom cores copied to code cache if they exist
        if (!useCustomCore) {
            internalCustomCoreFile.delete()
        } else {
            Logger.d("Using custom Bugcord core!")
        }

        // Copy core bundle from external storage to internal cache to prevent deletion while running
        if (useCustomCore) {
            externalCustomCoreFile.copyTo(internalCustomCoreFile, overwrite = true)
        }
        // Download new stable core
        else if (!internalCoreFile.exists()) {
            restoreCoreFlow()
            return
        }

        // Load the core
        val loadTarget = if (useCustomCore) internalCustomCoreFile else internalCoreFile
        Logger.d("Adding Bugcord core ${loadTarget.absolutePath} the classpath...")
        addDexToClasspath(
            dexFile = loadTarget,
            classLoader = appCtx.classLoader,
        )

        // Start the loaded core
        try {
            startBugcord()
        } catch (t: Throwable) {
            Logger.errorToast(appCtx, "Failed to start Bugcord!", t)

            try {
                // Delete cached files so it is redownloaded the next time
                internalCoreFile.delete()
                internalCustomCoreFile.delete()
            } catch (e: Throwable) {
                Logger.e("Failed to delete cached core files", e)
            }

            if (useCustomCore && disableCustomCore()) {
                Logger.errorToast(appCtx, "Disabled loading custom core!")
            }
        }
    }

    /**
     * The "restoration" flow of Bugcord that waits for the user to launch an activity,
     * grant permissions, and then download the core and forcefully restart the process
     * to allow for early initialization of the Bugcord core.
     */
    fun restoreCoreFlow() {
        hookActivityOnCreate { activity, unhook ->
            val permissionsResult = ::requestPermissions.takeIf { !isPermissionsGranted() }?.invoke(activity)

            Thread {
                if (permissionsResult?.get() == false)
                    return@Thread

                // Unhook only after permission result retrieved
                unhook.unhook()

                if (!isUsingCustomCore() && !installCore())
                    return@Thread

                restartBugcord()
            }.start()
        }
    }

    /**
     * Requests the necessary storage permissions and returns a future whether they were granted.
     * This should be called on or before [AppActivity.onCreate] before the lifecycle is RESUMED.
     */
    @SuppressLint("UseKtx")
    fun requestPermissions(activity: AppActivity): CompletableFuture<Boolean> {
        val future = CompletableFuture<Boolean>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val userMsg = "Bugcord requires the 'All files access' permission to use its folder in Internal Storage!"
            appCtx.showToast(userMsg)

            activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val granted = Environment.isExternalStorageManager()
                if (!granted) {
                    appCtx.showToast(userMsg)
                    Logger.e("User did not grant MANAGE_EXTERNAL_STORAGE permission!")
                }
                future.complete(granted)
            }.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:${activity.packageName}"))
            )
        } else {
            val userMsg = "Bugcord requires storage permissions to use its folder in Internal Storage!"
            appCtx.showToast(userMsg)

            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    appCtx.showToast(userMsg)
                    Logger.e("User did not grant WRITE_EXTERNAL_STORAGE permission!")
                }
                future.complete(granted)
            }.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        return future
    }

    /**
     * Performs the whole download flow of obtaining the latest core build.
     *
     * This first checks whether the current installation is compatible with the
     * latest core and prompts to reinstall with Manager when possible.
     */
    private fun installCore(): Boolean {
        return try {
            val data = fetchBuildData()
            Logger.d("Retrieved remote build data: $data")

            if (data.discordVersion > com.discord.BuildConfig.VERSION_CODE ||
                KotlinVersion.CURRENT < data.kotlinVersionParsed
            ) {
                // TODO: launch bugcord manager reinstall
                Logger.errorToast(appCtx, "Your base Discord is outdated. Please reinstall using Bugcord Manager.")
                false
            } else {
                downloadLatestCore(outputFile = internalCoreFile)
                true
            }
        } catch (e: Throwable) {
            Logger.errorToast(appCtx, "Failed to download latest Bugcord core!", e)
            false
        }
    }

    // -------- Starting Bugcord -------- //
    /**
     * Finds the correct Bugcord core entrypoint and runs it.
     */
    private fun startBugcord() {
        Logger.d("Obtaining Bugcord core entrypoints...")

        try {
            val c = Class.forName("com.bugcord.Main")
            val onApplicationInit = c.getDeclaredMethod("onApplicationInit", Application::class.java)

            Logger.d("Starting early Bugcord core...")
            onApplicationInit.invoke(null, appCtx)
            Logger.d("Finished early starting Bugcord")
        } catch (_: NoSuchMethodException) {
            startLegacyBugcord()
        }
    }

    /**
     * Finds the old Bugcord core entrypoints and runs it.
     * Old Bugcord core did not support early init, so we wait for Activity creation.
     */
    private fun startLegacyBugcord() {
        Logger.d("Obtaining Bugcord core legacy entrypoints...")
        val c = Class.forName("com.bugcord.Main")
        val preInit = c.getDeclaredMethod("preInit", AppActivity::class.java)
        val init = c.getDeclaredMethod("init", AppActivity::class.java)

        hookActivityOnCreate { activity, unhook ->
            unhook.unhook()
            Logger.d("Starting Bugcord core...")
            preInit.invoke(null, activity)
            init.invoke(null, activity)
            Logger.d("Finished starting Bugcord")
        }
    }

    // -------- Utilities -------- //

    /**
     * Checks whether external storage write permissions have been granted.
     */
    private fun isPermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            appCtx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Forcefully restarts the Bugcord app process.
     */
    private fun restartBugcord(): Nothing {
        Logger.i("Force restarting Bugcord...")
        try {
            val intent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                .let { Intent.makeRestartActivityTask(it?.component) }

            appCtx.startActivity(intent)
        } catch (e: Exception) {
            Logger.e("Failed to restart Bugcord!", e)
        } finally {
            exitProcess(0)
        }
    }

    /**
     * Checks whether using an external custom Bugcord core is possible and enabled.
     */
    private fun isUsingCustomCore(): Boolean {
        val settings = try {
            if (!externalSettingsFile.exists()) {
                Logger.d("Bugcord settings file missing, skipping custom core check...")
                return false
            }
            if (!externalCustomCoreFile.exists()) {
                Logger.d("Bugcord custom core missing, skipping custom core check...")
                return false
            }

            JSONObject(externalSettingsFile.readText())
        } catch (e: Exception) {
            // This may include permission errors
            Logger.d("Failed to read external Bugcord settings, skipping custom core check...", e)
            return false
        }

        return settings.optBoolean(BUGCORD_FROM_STORAGE_KEY, false)
    }

    /**
     * Tries to disable the setting that enables using a custom external Bugcord core, if possible.
     * If permissions were not granted to read/write to the external settings, then this fails silently.
     */
    private fun disableCustomCore(): Boolean {
        return try {
            if (!externalSettingsFile.exists()) return true

            val settings = JSONObject(externalSettingsFile.readText())
            if (!settings.optBoolean(BUGCORD_FROM_STORAGE_KEY, false)) {
                return true
            }

            settings.put(BUGCORD_FROM_STORAGE_KEY, false)
            externalSettingsFile.writeText(settings.toString())
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Attempts to hook [AppActivity.onCreate] if it has not been invoked yet and run a [callback]
     * when it is executed. This allows for an unlimited amount of callbacks to be registered prior to
     * the activity initializing, however disallows hooking once it's initialized. As such, it should not
     * be used multiple times throughout different parts of the same initialization flow.
     */
    private fun hookActivityOnCreate(callback: (AppActivity, XC_MethodHook.Unhook) -> Unit) {
        Logger.d("Hooking AppActivity.onCreate")

        if (activityInitialized.get()) {
            throw IllegalStateException("Cannot hook AppActivity.onCreate when it was already invoked!")
        }

        try {
            var unhook: XC_MethodHook.Unhook? = null
            unhook = XposedBridge.hookMethod(
                AppActivity::class.java.getDeclaredMethod("onCreate", Bundle::class.java),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        activityInitialized.set(true)
                        callback(param.thisObject as AppActivity, unhook!!)
                    }
                },
            )
        } catch (e: Exception) {
            Logger.errorToast(appCtx, "Failed to initialize Bugcordhook!", e)
            throw e
        }
    }
}
