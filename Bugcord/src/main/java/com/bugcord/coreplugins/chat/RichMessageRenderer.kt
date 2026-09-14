/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.view.View
import android.widget.TextView
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.after
import com.bugcord.patcher.before
import com.bugcord.utils.ReflectUtils
import com.bugcord.wrappers.embeds.MessageEmbedWrapper
import com.discord.api.channel.Channel
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.util.regex.Pattern

/** Enables the legacy parser's message header/list rules for normal messages. */
internal class RichMessageRenderer : CorePlugin(Manifest("RichMessageRenderer")) {
    override val isHidden = true
    override val isRequired = true

    override fun start(context: Context) {
        configureParser()
        patchAvatarGroupSpacing()
        patchEmbedRendering()
    }

    private fun configureParser() {
        runCatching {
            val rulesClass = Class.forName("com.discord.utilities.textprocessing.Rules")
            val modernListPattern = Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*?)(\\n|$)")
            ReflectUtils.setFinalField(rulesClass, null, "PATTERN_LIST_ITEM", modernListPattern)
        }

        val parserClass = DiscordParser::class.java
        val createParser = parserClass.getDeclaredMethod(
            "createParser",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val safeLinkParser = createParser.invoke(null, false, true, true, true, true)
        val maskedLinkParser = createParser.invoke(null, true, true, true, true, true)
        ReflectUtils.setFinalField(parserClass, null, "SAFE_LINK_PARSER", safeLinkParser)
        ReflectUtils.setFinalField(parserClass, null, "MASKED_LINK_PARSER", maskedLinkParser)
    }

    private fun patchAvatarGroupSpacing() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemMessage
            val position = param.args[0] as? Int ?: return@after
            val entry = param.args[1] as? MessageEntry ?: return@after

            // Apply message spacing only to the first message; leave middle messages untouched
            if (entry.isMinimal()) return@after

            val itemView = holder.itemView
            val top = if (position > 0) dp(itemView, 16) else dp(itemView, 10)
            val bottom = dp(itemView, 4)
            itemView.setPadding(itemView.paddingLeft, top, itemView.paddingRight, bottom)
        }
    }

    private fun patchEmbedRendering() {
        // Ensure embeds are always created and never dropped by user setting checks
        runCatching {
            patcher.before<ChatListEntry.Companion>(
                "createEmbedEntries",
                Message::class.java,
                StoreMessageState.State::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
                Channel::class.java,
                GuildMember::class.java,
                Map::class.java,
                Map::class.java,
            ) { param ->
                param.args[5] = true
            }
        }

        // Always allow media rendering in embeds
        runCatching {
            patcher.before<WidgetChatListAdapterItemEmbed>("shouldRenderMedia") { param ->
                param.result = true
            }
        }

        val fBinding = runCatching {
            WidgetChatListAdapterItemEmbed::class.java.getDeclaredField("binding").apply {
                isAccessible = true
            }
        }.getOrNull()

        patcher.after<WidgetChatListAdapterItemEmbed>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemEmbed
            val entry = param.args[1] as? EmbedEntry ?: return@after
            val embed = entry.embed
            holder.itemView.visibility = View.VISIBLE

            if (fBinding != null) {
                val binding = fBinding.get(holder) ?: return@after
                val cardView = getBoundField(binding, "f") as? View
                val contentView = getBoundField(binding, "g") as? View
                val dividerView = getBoundField(binding, "i") as? View
                val mediaView = getBoundField(binding, "t") as? View
                val descView = getBoundField(binding, "h") as? TextView
                val titleView = getBoundField(binding, "r") as? TextView

                cardView?.visibility = View.VISIBLE

                if (mediaView?.visibility != View.VISIBLE) {
                    contentView?.visibility = View.VISIBLE
                    dividerView?.visibility = View.VISIBLE
                }

                val wrapper = MessageEmbedWrapper(embed)
                val rawTitle = wrapper.title
                if (!rawTitle.isNullOrBlank() && titleView != null && titleView.text.isNullOrBlank()) {
                    titleView.text = rawTitle
                    titleView.visibility = View.VISIBLE
                    contentView?.visibility = View.VISIBLE
                }

                val rawDesc = wrapper.description
                if (!rawDesc.isNullOrBlank() && descView != null && descView.text.isNullOrBlank()) {
                    descView.text = rawDesc
                    descView.visibility = View.VISIBLE
                    contentView?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun getBoundField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun MessageEntry.isMinimal(): Boolean = runCatching {
        javaClass.getDeclaredField("isMinimal").apply { isAccessible = true }.getBoolean(this)
    }.getOrDefault(false)

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
