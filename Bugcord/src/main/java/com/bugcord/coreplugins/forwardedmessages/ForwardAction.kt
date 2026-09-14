/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.forwardedmessages

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bugcord.Http
import com.bugcord.Utils
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.*
import com.bugcord.utils.GsonUtils
import com.bugcord.utils.ViewUtils.findViewById
import com.discord.models.message.Message
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.channels.WidgetChannelSelector
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.R

/**
 * Adds the "Forward" message action. The legacy client can render forwards but never had a way to
 * create one, so the action is sent straight to the API with a FORWARD message reference.
 */
internal class ForwardAction : CorePlugin(Manifest("ForwardAction")) {
    override val isHidden = true
    override val isRequired = true

    private val forwardViewId = View.generateViewId()

    override fun start(context: Context) {
        patcher.after<WidgetChatListActions>(
            "configureUI",
            WidgetChatListActions.Model::class.java,
        ) { (_, model: WidgetChatListActions.Model) ->
            val message = model.message ?: return@after
            val root = this.view as? ViewGroup ?: return@after
            val layout = root.findViewById<LinearLayout>("dialog_chat_actions_container") ?: return@after
            if (layout.findViewById<View>(forwardViewId) != null) return@after

            // The channel picker reports back through the sheet, so register before it can open
            WidgetChannelSelector.Companion!!.registerForResult(
                this,
                RESULT_KEY,
                false,
            ) { channelId, _ ->
                forward(message, channelId)
                dismiss()
            }

            val entry = makeEntry(layout.context) {
                WidgetChannelSelector.Companion!!.launchForText(
                    this,
                    message.guildId,
                    RESULT_KEY,
                    false,
                    0,
                )
            }

            // Sit next to Reply, which is where 344013 puts Forward
            val replyView = layout.findViewById<View?>("dialog_chat_actions_reply")
            val index = if (replyView != null) layout.indexOfChild(replyView) + 1 else 0
            layout.addView(entry, index)
        }
    }

    private fun makeEntry(ctx: Context, onClick: () -> Unit): View =
        TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
            id = forwardViewId
            text = "Forward"
            setOnClickListener { onClick() }
            ContextCompat.getDrawable(ctx, R.e.ic_share_24dp)?.run {
                mutate()
                setTint(ColorCompat.getThemedColor(ctx, R.b.colorInteractiveNormal))
                setCompoundDrawablesRelativeWithIntrinsicBounds(this, null, null, null)
            }
        }

    /**
     * Sends the forward. A forward is an empty message carrying a reference of type 1 pointing at
     * the source, which the server expands into a snapshot.
     */
    private fun forward(message: Message, targetChannelId: Long) {
        Utils.threadPool.execute {
            runCatching {
                Http.Request
                    .newDiscordRNRequest("/channels/$targetChannelId/messages", "POST")
                    .setRequestTimeout(10000)
                    .executeWithJson(GsonUtils.gsonRestApi, ForwardPayload(message))
                    .assertOk()
            }.onFailure {
                logger.errorToast("Failed to forward message", it)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private class ForwardPayload(source: Message) {
        @Suppress("unused")
        val content: String = ""

        @Suppress("unused")
        val message_reference = Reference(
            channel_id = source.channelId.toString(),
            message_id = source.id.toString(),
            guild_id = source.guildId?.toString(),
        )

        class Reference(
            @Suppress("unused") val channel_id: String,
            @Suppress("unused") val message_id: String,
            @Suppress("unused") val guild_id: String?,
        ) {
            /** 1 is FORWARD; 0, the default, is a reply. */
            @Suppress("unused")
            val type: Int = 1
        }
    }

    private companion object {
        const val RESULT_KEY = "BUGCORD_FORWARD_TARGET"
    }
}
