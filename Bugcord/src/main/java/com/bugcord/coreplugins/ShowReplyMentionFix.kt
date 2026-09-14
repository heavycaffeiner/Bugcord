/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins

import android.content.Context
import android.widget.TextView
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.after
import com.bugcord.patcher.before
import com.bugcord.wrappers.embeds.MessageEmbedWrapper
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreMessageReplies
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.api.message.embed.MessageEmbed

internal class ShowReplyMentionFix : CorePlugin(Manifest("ShowReplyMentionFix")) {
    override val isHidden = true
    override val isRequired = true

    init {
        manifest.description = "Fixes showing reply mention"
    }

    override fun start(context: Context) {
        val mConfigureReplyAvatar = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredMethod("configureReplyAvatar", User::class.java, GuildMember::class.java)
            .apply { isAccessible = true }
        val mConfigureReplyName = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredMethod("configureReplyName", String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val mGetAuthorTextColor = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredMethod("getAuthorTextColor", GuildMember::class.java)
            .apply { isAccessible = true }
        val fReplyHolder = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredField("replyHolder")
            .apply { isAccessible = true }
        val fReplyLinkItem = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredField("replyLinkItem")
            .apply { isAccessible = true }
        val fReplyText = WidgetChatListAdapterItemMessage::class.java
            .getDeclaredField("replyText")
            .apply { isAccessible = true }

        patcher.before<WidgetChatListAdapterItemMessage>("configureReplyPreview", MessageEntry::class.java) {
            if (fReplyHolder[this] == null || fReplyLinkItem[this] == null) return@before

            val messageEntry = it.args[0] as MessageEntry
            val replyData = messageEntry.replyData
            if (replyData == null || replyData.messageState !is StoreMessageReplies.MessageState.Loaded) return@before

            val refEntry = replyData.messageEntry
            val refAuthor = CoreUser(refEntry.message.author)
            val refAuthorMember = refEntry.author
            mConfigureReplyAvatar(this, refAuthor, refAuthorMember)

            val refAuthorId = refAuthor.id
            mConfigureReplyName(
                this,
                refEntry.nickOrUsernames[refAuthorId] ?: refAuthor.username,
                mGetAuthorTextColor(this, refAuthorMember),
                messageEntry.message.mentions.any { u -> u.id == refAuthorId }
            )
        }

        patcher.after<WidgetChatListAdapterItemMessage>("configureReplyPreview", MessageEntry::class.java) {
            val messageEntry = it.args[0] as MessageEntry
            val replyData = messageEntry.replyData
            if (replyData == null || replyData.messageState !is StoreMessageReplies.MessageState.Loaded) return@after

            val referencedMessage = replyData.messageEntry.message
            if (!referencedMessage.content.isNullOrBlank()) return@after
            val embeds = referencedMessage.embeds
            if (embeds.isNullOrEmpty()) return@after

            val formatted = embeds.joinToString("\n") { embed ->
                buildString {
                    append("▸ ")
                    MessageEmbedFormatter.appendTo(this, embed)
                }
            }
            val replyText = fReplyText[this] as? TextView ?: return@after
            replyText.text = formatted
        }

        // configureReplyAuthor was mostly reimplemented in our patch in configureReplyPreview,
        // however it is also used for interactions, so we prevent it from calling only when interactionAuthor is null
        patcher.before<WidgetChatListAdapterItemMessage>(
            "configureReplyAuthor",
            User::class.java,
            GuildMember::class.java,
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[2] as MessageEntry

            if (messageEntry.interactionAuthor == null)
                it.result = null
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

private object MessageEmbedFormatter {
    fun appendTo(output: StringBuilder, embed: MessageEmbed) {
        val richEmbed = MessageEmbedWrapper(embed)
        var wroteContent = false

        richEmbed.author?.name?.takeIf { it.isNotBlank() }?.let {
            output.append(it)
            wroteContent = true
        }
        richEmbed.title?.takeIf { it.isNotBlank() }?.let {
            if (wroteContent) output.append(" | ")
            output.append(it)
            wroteContent = true
        }
        richEmbed.description?.takeIf { it.isNotBlank() }?.let {
            if (wroteContent) output.append("\n")
            output.append(it)
            wroteContent = true
        }
        richEmbed.fields.orEmpty().forEach { field ->
            val name = field.name.trim()
            val value = field.value.trim()
            if (name.isEmpty() && value.isEmpty()) return@forEach
            if (wroteContent) output.append("\n")
            if (name.isNotEmpty()) output.append(name)
            if (name.isNotEmpty() && value.isNotEmpty()) output.append(": ")
            if (value.isNotEmpty()) output.append(value)
            wroteContent = true
        }
        richEmbed.footer?.text?.takeIf { it.isNotBlank() }?.let {
            if (wroteContent) output.append("\n")
            output.append(it)
            wroteContent = true
        }
        if (!wroteContent) output.append("Embed")
    }
}
