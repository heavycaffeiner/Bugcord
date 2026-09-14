package com.bugcord.coreplugins.voice

import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import com.bugcord.Logger
import com.bugcord.api.PatcherAPI
import com.bugcord.patcher.InsteadHook
import com.bugcord.patcher.PreHook
import com.bugcord.patcher.after
import com.discord.utilities.voice.ScreenShareManager
import com.discord.utilities.voice.VoiceEngineForegroundService
import com.discord.utilities.voice.VoiceEngineServiceController
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

internal object ScreenshareForeground {
    private val logger = Logger("ScreenshareForeground")

    // com.discord.utilities.voice.VoiceEngineForegroundService
    private const val NOTIFICATION_ID = 101

    private val companionField by lazy { VoiceEngineServiceController::class.java.getDeclaredField("Companion") }
    private val getController by lazy { companionField.type.getDeclaredMethod("getINSTANCE") }
    private val bindingField by lazy {
        VoiceEngineServiceController::class.java
            .getDeclaredField("serviceBinding")
            .apply { isAccessible = true }
    }
    private val managerField by lazy {
        VoiceEngineForegroundService::class.java
            .getDeclaredField("screenShareManager")
            .apply { isAccessible = true }
    }
    private val intentField by lazy {
        ScreenShareManager::class.java
            .getDeclaredField("screenshareIntent")
            .apply { isAccessible = true }
    }
    private val serviceCompanionField by lazy {
        VoiceEngineForegroundService::class.java
            .getDeclaredField("Companion")
            .apply { isAccessible = true }
    }
    private val stopForegroundAndUnbind by lazy {
        serviceCompanionField.type.getDeclaredMethod(
            "stopForegroundAndUnbind",
            VoiceEngineForegroundService.Connection::class.java,
        )
    }

    private var heldTeardown = false
    private var replaying = false

    fun register(patcher: PatcherAPI) = runCatching {
        val capturer = Class.forName("b.a.q.m0.b")
        val startCapture = capturer.getDeclaredMethod(
            "startCapture",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        // Sharing app audio retries forever if the screenshare dies
        patcher.patch(capturer.getDeclaredMethod("b"), InsteadHook.DO_NOTHING)

        patcher.patch(startCapture, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                promote()

                param.result = null

                runCatching {
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                }.onFailure {
                    logger.error("Screenshare capture could not start", it)
                }
            }
        })

        patcher.patch(stopForegroundAndUnbind, PreHook { param ->
            if (replaying || !isStreaming()) return@PreHook

            heldTeardown = true
            param.result = null

            logger.info("Held back the voice service teardown, a screenshare is still running")
        })

        // The stock listener stops the stream on any Disconnected state, including the transient
        // reconnect that happens when joining someone else's stream. Only a final disconnect,
        // willReconnect = false, may tear our own stream down.
        runCatching {
            val listener = Class.forName("com.discord.utilities.voice.ScreenShareManager\$RtcConnectionListener")
            val stateChange = Class.forName("com.discord.rtcconnection.RtcConnection\$StateChange")
            val disconnected = Class.forName("com.discord.rtcconnection.RtcConnection\$State\$d")
            val fState = stateChange.getDeclaredField("a").apply { isAccessible = true }
            val fWillReconnect = disconnected.getDeclaredField("a").apply { isAccessible = true }

            patcher.patch(
                listener.getDeclaredMethod("onStateChange", stateChange),
                PreHook { param ->
                    val state = fState.get(param.args[0]) ?: return@PreHook
                    if (!disconnected.isInstance(state)) return@PreHook
                    if (fWillReconnect.getBoolean(state)) {
                        param.result = null
                        logger.info("Ignored a reconnecting disconnect, keeping the screenshare alive")
                    }
                },
            )
        }.onFailure {
            logger.error("Could not guard the screenshare against reconnects", it)
        }

        // handleStateUpdate clears screen capture whenever the active stream key changes. Watching
        // someone else's stream replaces the active stream with theirs, so the key differs and our
        // own capture gets torn down. Suppress that teardown while we are the one streaming.
        runCatching {
            val manager = ScreenShareManager::class.java
            val stateClass = Class.forName("com.discord.utilities.voice.ScreenShareManager\$State")
            val handleStateUpdate = manager.getDeclaredMethod("handleStateUpdate", stateClass)
                .apply { isAccessible = true }
            val fPreviousState = manager.getDeclaredField("previousState").apply { isAccessible = true }
            val getActiveStream = stateClass.getDeclaredMethod("getActiveStream")
            val getMeId = stateClass.getDeclaredMethod("getMeId")
            val activeStream = Class.forName("com.discord.stores.StoreApplicationStreaming\$ActiveApplicationStream")
            val getStream = activeStream.getDeclaredMethod("getStream")
            val getOwnerId = Class.forName("com.discord.models.domain.ModelApplicationStream")
                .getDeclaredMethod("getOwnerId")

            fun ownerOf(state: Any?): Long? {
                val stream = state?.let { getActiveStream.invoke(it) }?.let { getStream.invoke(it) }
                return stream?.let { getOwnerId.invoke(it) as? Long }
            }

            patcher.patch(handleStateUpdate, PreHook { param ->
                val previous = fPreviousState.get(param.thisObject) ?: return@PreHook
                val meId = getMeId.invoke(previous) as? Long ?: return@PreHook
                // Only guard a stream we own; a foreign previous stream is not ours to keep
                if (ownerOf(previous) != meId) return@PreHook

                val next = param.args[0]
                if (ownerOf(next) == meId) return@PreHook

                // Keep our own stream as the manager's state so the capture survives
                param.result = null
                logger.info("Kept our screenshare while the active stream switched to another user")
            })
        }.onFailure {
            logger.error("Could not guard the screenshare against stream switches", it)
        }

        // Remove the hold because the screenshare intent gets cleared here
        patcher.after<ScreenShareManager>("stopStream") { releaseTeardown() }

        logger.info("Registered")
    }.onFailure {
        logger.error("Failed to hook the voice service, screensharing may die early", it)
    }

    private fun releaseTeardown() {
        if (!heldTeardown) return
        heldTeardown = false

        val connection = boundConnection() ?: return
        logger.info("Screenshare ended, letting the voice service go")

        replaying = true

        runCatching {
            stopForegroundAndUnbind.invoke(serviceCompanionField[null], connection)
        }.onFailure {
            logger.error("Could not finish the held back teardown", it)
        }

        replaying = false
    }

    private fun isStreaming(): Boolean = runCatching {
        val service = boundConnection()?.service ?: return false
        val manager = managerField[service] ?: return false

        intentField[manager] != null
    }.getOrElse {
        logger.error("Could not get isStreaming status, is there any screenshare running?", it)
        false
    }

    private fun promote() {
        val service = boundConnection()?.service ?: return

        runCatching {
            val notification = service.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .firstOrNull { it.id == NOTIFICATION_ID }
                ?.notification
                ?: return logger.debug("No voice notification to re-post, cannot re-promote")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = service.foregroundServiceType or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                service.startForeground(NOTIFICATION_ID, notification, types)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }

            logger.debug("Voice service put back in the foreground")
        }.onFailure {
            logger.error("Could not restore the voice foreground service", it)
        }
    }

    // The controller already owns the binding, so it is read from there rather than tracked
    private fun boundConnection(): VoiceEngineForegroundService.Connection? = runCatching {
        val controller = getController.invoke(companionField[null])

        bindingField[controller] as? VoiceEngineForegroundService.Connection
    }.getOrElse {
        logger.error("Could not reach the voice service binding", it)
        null
    }
}
