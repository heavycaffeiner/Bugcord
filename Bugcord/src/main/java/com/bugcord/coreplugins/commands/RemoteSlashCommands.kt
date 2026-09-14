/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.commands

import android.content.Context
import android.net.Uri
import com.bugcord.Http
import com.bugcord.Utils
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.PreHook
import com.bugcord.utils.GsonUtils
import com.bugcord.utils.GsonUtils.fromJson
import com.bugcord.utils.ReflectUtils
import com.discord.api.commands.GuildApplicationCommands
import com.discord.stores.Dispatcher
import com.discord.stores.StoreApplicationCommands
import com.discord.stores.StoreGatewayConnection
import com.discord.stores.StoreStream
import org.json.JSONObject
import org.json.JSONArray

/**
 * Restores third party bot slash commands.
 *
 * The legacy client asks for a guild's application commands over gateway opcode
 * REQUEST_GUILD_APPLICATION_COMMANDS, which the server no longer answers, so the command list stays
 * empty for every bot. Modern clients read the same data from
 * `GET /channels/{id}/application-commands/search`, whose payload carries the same
 * `applications` and `application_commands` arrays the legacy store already parses. The gateway
 * request is replaced with that REST call and the result is handed to the untouched store.
 */
internal class RemoteSlashCommands : CorePlugin(Manifest("RemoteSlashCommands")) {
    override val isHidden = true
    override val isRequired = true

    private val store by lazy { StoreStream.getApplicationCommands() }
    private val storeClass = StoreApplicationCommands::class.java
    private val dispatcher by lazy {
        ReflectUtils.getField(storeClass, store, "dispatcher") as Dispatcher
    }
    private val loadingField by lazy {
        storeClass.getDeclaredField("isLoadingDiscoveryCommands").apply { isAccessible = true }
    }

    override fun start(context: Context) {
        val requestCommands = StoreGatewayConnection::class.java.getDeclaredMethod(
            "requestApplicationCommands",
            Long::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
            Integer::class.java,
            Int::class.javaPrimitiveType,
            List::class.java,
        )

        patcher.patch(requestCommands, PreHook { param ->
            val guildId = param.args[0] as? Long ?: return@PreHook
            val nonce = param.args[1] as? String ?: return@PreHook

            val query = param.args[3] as? String

            // Report success so the store keeps waiting for the payload instead of falling back
            // to the built-in command list
            param.result = true
            fetch(guildId, nonce, query)
        })
    }

    private fun fetch(guildId: Long, nonce: String, query: String?) {
        val url = if (guildId > 0L) {
            "/guilds/$guildId/application-command-index"
        } else {
            val channelId = StoreStream.getChannelsSelected().id
            if (channelId > 0L) "/channels/$channelId/application-command-index" else "/users/@me/application-command-index"
        }

        Utils.threadPool.execute {
            runCatching {
                val response = Http.Request.newDiscordRNRequest(url)
                    .setRequestTimeout(REQUEST_TIMEOUT_MS)
                    .execute()
                response.assertOk()

                val json = JSONObject(response.text()).apply {
                    put("nonce", nonce)
                    put("guild_id", guildId.toString())
                }

                if (!query.isNullOrBlank()) {
                    val rawCmds = json.optJSONArray("application_commands")
                    if (rawCmds != null) {
                        val filtered = JSONArray()
                        for (i in 0 until rawCmds.length()) {
                            val cmd = rawCmds.getJSONObject(i)
                            if (cmd.optString("name").contains(query, ignoreCase = true)) {
                                filtered.put(cmd)
                            }
                        }
                        json.put("application_commands", filtered)
                    }
                }
                val commands = GsonUtils.gsonRestApi
                    .fromJson(json.toString(), GuildApplicationCommands::class.java)

                // The store mutates its state on the dispatcher thread only
                dispatcher.schedule {
                    store.handleApplicationCommandsUpdate(commands)
                    // requestApplicationCommands latches this before calling the gateway and only
                    // clears it on a real response; left set, every later request returns early
                    runCatching { loadingField.setBoolean(store, false) }
                }
            }.onFailure {
                logger.error("Failed to fetch application commands for guild $guildId", it)
                runCatching { dispatcher.schedule { loadingField.setBoolean(store, false) } }
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 24
        const val MAX_LIMIT = 50
        const val REQUEST_TIMEOUT_MS = 10000
    }
}
