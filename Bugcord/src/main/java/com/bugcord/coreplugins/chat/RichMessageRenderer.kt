/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.Patcher
import com.bugcord.patcher.after
import com.bugcord.patcher.before
import com.bugcord.utils.ReflectUtils
import com.bugcord.wrappers.embeds.MessageEmbedWrapper
import com.discord.api.channel.Channel
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import de.robv.android.xposed.XC_MethodHook
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

        // Enable markdown links (allowMaskedLinks = true) for both safe and masked parsers
        val safeLinkParser = createParser.invoke(null, true, true, true, true, true)
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
            val message = entry.message
            val content = message.content.orEmpty().trimStart()
            val hasMarkdownOrEmbed = message.hasEmbeds() ||
                content.startsWith("#") ||
                content.startsWith("-") ||
                content.startsWith("*") ||
                content.startsWith(">") ||
                content.startsWith("```")

            // Top padding:
            // 2dp for messages with embeds or markdown headers/lists (avoids excessive top spacing)
            // 4dp for normal new author groups (prevents end message of previous group from having a large bottom gap)
            val top = when {
                hasMarkdownOrEmbed -> dp(itemView, 2)
                position > 0 -> dp(itemView, 4)
                else -> dp(itemView, 2)
            }
            // Bottom padding: 1dp per user instruction to tightly match follower rows
            val bottom = dp(itemView, 1)
            itemView.setPadding(itemView.paddingLeft, top, itemView.paddingRight, bottom)
        }
    }

    private fun patchEmbedRendering() {
        // Embeds with text description, title, author, or fields must render as rich cards, not inline media
        runCatching {
            patcher.before<EmbedResourceUtils>("isInlineEmbed", MessageEmbed::class.java) { param ->
                val embed = param.args[0] as? MessageEmbed ?: return@before
                val wrapper = MessageEmbedWrapper(embed)
                if (!wrapper.description.isNullOrBlank() ||
                    !wrapper.title.isNullOrBlank() ||
                    wrapper.author != null ||
                    !wrapper.fields.isNullOrEmpty()
                ) {
                    param.result = false
                }
            }
        }

        // Keep small images small in the viewport by setting minWidth to 0 (prevents upscaling to half-screen width)
        runCatching {
            patcher.before<EmbedResourceUtils>(
                "calculateScaledSize",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Resources::class.java,
                Int::class.javaPrimitiveType!!,
            ) { param ->
                param.args[5] = 0
            }
        }

        // Supply fallback dimensions when embed images lack width or height
        runCatching {
            patcher.after<EmbedResourceUtils>("getPreviewImage", MessageEmbed::class.java) { param ->
                val media = param.result as? RenderableEmbedMedia ?: return@after
                val w = media.b
                val h = media.c
                if (w == null || w <= 0 || h == null || h <= 0) {
                    param.result = RenderableEmbedMedia(media.a, 400, 300)
                }
            }
        }

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

        // Hook configureUI directly to ensure item view stays visible and unpadded
        runCatching {
            val modelClass = Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$Model")
            val configureUIMethod = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod("configureUI", modelClass)
            configureUIMethod.isAccessible = true
            Patcher.addPatch(configureUIMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val holder = param.thisObject as? WidgetChatListAdapterItemEmbed ?: return
                    holder.itemView.visibility = View.VISIBLE
                    holder.itemView.setPadding(0, 0, 0, 0)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val holder = param.thisObject as? WidgetChatListAdapterItemEmbed ?: return
                    holder.itemView.visibility = View.VISIBLE
                    holder.itemView.setPadding(0, 0, 0, 0)
                }
            })
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
            holder.itemView.setPadding(0, 0, 0, 0)

            if (fBinding != null) {
                val binding = fBinding.get(holder) ?: return@after
                val cardView = getBoundField(binding, "f") as? View
                val contentView = getBoundField(binding, "g") as? View
                val dividerView = getBoundField(binding, "i") as? View
                val mediaView = getBoundField(binding, "t") as? View
                val descView = getBoundField(binding, "h") as? TextView
                val titleView = getBoundField(binding, "r") as? TextView
                val authorView = getBoundField(binding, "e") as? TextView
                val imageView = getBoundField(binding, "m") as? ImageView

                cardView?.visibility = View.VISIBLE
                imageView?.adjustViewBounds = true

                if (mediaView?.visibility != View.VISIBLE) {
                    contentView?.visibility = View.VISIBLE
                    dividerView?.visibility = View.VISIBLE
                }

                val wrapper = MessageEmbedWrapper(embed)
                val rawAuthor = wrapper.author?.name
                if (!rawAuthor.isNullOrBlank() && authorView != null && authorView.text.isNullOrBlank()) {
                    authorView.text = rawAuthor
                    authorView.visibility = View.VISIBLE
                    contentView?.visibility = View.VISIBLE
                }

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
