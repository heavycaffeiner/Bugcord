/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.view.View
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.Patcher
import com.bugcord.patcher.after
import com.bugcord.utils.ReflectUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import java.util.WeakHashMap
import java.util.regex.Pattern
/** Enables the legacy parser's message header/list rules for normal messages. */
internal class RichMessageRenderer : CorePlugin(Manifest("RichMessageRenderer")) {
    override val isHidden = true
    override val isRequired = true

    private val originalPadding = WeakHashMap<View, Padding>()

    override fun start(context: Context) {
        configureParser()
        patchAvatarGroupSpacing()
        patchEmbedVisibility()
    }
    private fun configureParser() {
        runCatching {
            val rulesClass = Class.forName("com.discord.utilities.textprocessing.Rules")
            val modernListPattern = Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*)(?=\\n|$)")
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
            val itemView = holder.itemView
            val base = originalPadding.getOrPut(itemView) {
                Padding(itemView.paddingLeft, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            }
            // A non-minimal row starts a new author group. Put the group gap before it,
            // while keeping the follower chain connected through the base bottom padding.
            val extra = if (position > 0 && !entry.isMinimal()) dp(itemView, 6) else 0
            itemView.setPadding(base.left, base.top + extra, base.right, base.bottom)
        }
    }

    private fun patchEmbedVisibility() {
        patcher.after<WidgetChatListAdapterItemEmbed>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemEmbed
            if (holder.itemView.visibility == View.GONE) {
                holder.itemView.visibility = View.VISIBLE
            }
        }
    }

    private fun MessageEntry.isMinimal(): Boolean = runCatching {
        javaClass.getDeclaredField("isMinimal").apply { isAccessible = true }.getBoolean(this)
    }.getOrDefault(false)

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    override fun stop(context: Context) {
        originalPadding.clear()
        patcher.unpatchAll()
    }
}
