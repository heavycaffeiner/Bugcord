/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.bugcord.Constants
import com.bugcord.Utils
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.Patcher
import com.bugcord.patcher.after
import com.bugcord.patcher.before
import com.bugcord.patcher.instead
import com.bugcord.utils.ReflectUtils
import com.bugcord.wrappers.embeds.MessageEmbedWrapper
import com.bugcord.wrappers.messages.AttachmentWrapper.Companion.height
import com.bugcord.wrappers.messages.AttachmentWrapper.Companion.proxyUrl
import com.bugcord.wrappers.messages.AttachmentWrapper.Companion.type
import com.bugcord.wrappers.messages.AttachmentWrapper.Companion.url
import com.bugcord.wrappers.messages.AttachmentWrapper.Companion.width
import com.discord.api.channel.Channel
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.spans.BlockBackgroundSpan
import com.discord.utilities.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BulletListNode
import com.discord.widgets.chat.list.InlineMediaView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemSticker
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.media.WidgetMedia
import de.robv.android.xposed.XC_MethodHook
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Enables the legacy parser's message header/list rules for normal messages. */
internal class RichMessageRenderer : CorePlugin(Manifest("RichMessageRenderer")) {
    override val isHidden = true
    override val isRequired = true

    private val mediaContainerId = View.generateViewId()

    override fun start(context: Context) {
        // Must run before any parser is built so every cached parser picks up the fixed rules
        patchListItemRule()
        patchBoldRendering()
        patchHeaderRendering()
        patchCodeBlockPadding()
        configureParser()
        patchMessageLayout()
        patchEmbedRendering()
        patchImageLinkSuppression()
    }

    /**
     * Replaces the stock list rule, whose pattern treats a bare `-` as a bullet and swallows it.
     * `1234-5678` lost its hyphen because `-5678` parsed as a list item.
     */
    private fun patchListItemRule() {
        runCatching {
            patcher.instead<Rules>("createListItemRule") { ListItemRule<BasicRenderContext, Any>() }
        }
    }

    /**
     * The chat font family has no bold face, so `StyleSpan(BOLD)` resolves to the same glyphs and
     * `**bold**` renders identically to body text. Replace the span with one that loads the real
     * bold face and also enables stroke emboldening.
     */
    private fun patchBoldRendering() {
        runCatching {
            val boldStyles = Class.forName("b.a.t.b.b.a").getDeclaredMethod("invoke")
            Patcher.addPatch(boldStyles, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = listOf(BoldSpan())
                }
            })
        }
    }

    /**
     * 344013 sizes headers relative to the body text: h1 is 1.5x, h2 1.25x, h3 1.1x, all bold, and
     * only h1 and h2 carry extra vertical padding. The legacy build uses fixed 20sp/16sp/16sp
     * appearances with 16sp padding on every level, which reads far heavier.
     */
    private fun patchHeaderRendering() {
        runCatching {
            val headerNode = Class.forName("com.discord.utilities.textprocessing.node.HeaderNode")
            val fIndicators = headerNode.getDeclaredField("numHeaderIndicators").apply { isAccessible = true }
            val render = headerNode.getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                BasicRenderContext::class.java,
            )
            Patcher.addPatch(render, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val node = param.thisObject as? Node<*> ?: return
                    val builder = param.args[0] as? SpannableStringBuilder ?: return
                    val context = (param.args[1] as? BasicRenderContext)?.context ?: return
                    val level = fIndicators.getInt(node)

                    val start = builder.length
                    node.children?.forEach { child ->
                        @Suppress("UNCHECKED_CAST")
                        (child as Node<Any>).render(builder, param.args[1])
                    }

                    val scale = when (level) {
                        1 -> 1.5f
                        2 -> 1.25f
                        else -> 1.1f
                    }
                    // h3 sits tight against its body text in 344013
                    val padding = if (level >= 3) 0 else dpToPx(context, 8)

                    builder.setSpan(RelativeSizeSpan(scale), start, builder.length, SPAN_FLAGS)
                    builder.setSpan(BoldSpan(), start, builder.length, SPAN_FLAGS)
                    builder.setSpan(VerticalPaddingSpan(padding, padding), start, builder.length, SPAN_FLAGS)
                    param.result = null
                }
            })
        }
    }

    private fun configureParser() {
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

    /**
     * A code block renders a trailing newline inside its own background span, so the block paints
     * one empty line past its last line of code and the 5dp bottom padding sits below that. Pull
     * the block spans back off the newline; the newline itself stays so the block still separates
     * from whatever follows.
     */
    private fun patchCodeBlockPadding() {
        runCatching {
            val node = Class.forName("com.discord.utilities.textprocessing.node.BlockBackgroundNode")
            val render = node.getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                BasicRenderContext::class.java,
            )
            Patcher.addPatch(render, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val builder = param.args[0] as? SpannableStringBuilder ?: return
                    val end = builder.length
                    if (end == 0 || builder[end - 1] != '\n') return

                    for (span in builder.getSpans(0, end, Any::class.java)) {
                        if (span !is BlockBackgroundSpan && span !is VerticalPaddingSpan) continue
                        if (builder.getSpanEnd(span) != end) continue
                        val start = builder.getSpanStart(span)
                        val flags = builder.getSpanFlags(span)
                        if (start >= end - 1) continue
                        builder.setSpan(span, start, end - 1, flags)
                    }
                }
            })
        }
    }

    private fun patchMessageLayout() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemMessage
            val entry = param.args[1] as? MessageEntry ?: return@after
            val itemView = holder.itemView as? ConstraintLayout ?: return@after

            val hasInlineMedia = renderInlineMedia(itemView, entry.message)
            applySpacing(itemView, entry, hasInlineMedia)
        }
    }

    /**
     * Draws image and video media inside the message row itself so a media message renders as one
     * message instead of an empty header row followed by a separate media row.
     * Returns true when at least one media view is attached.
     */
    private fun renderInlineMedia(root: ConstraintLayout, message: Message): Boolean {
        val media = mergeableMedia(message)
        val existing = root.findViewById<LinearLayout>(mediaContainerId)

        if (media.isEmpty()) {
            existing?.apply {
                removeAllViews()
                visibility = View.GONE
            }
            return false
        }

        val container = existing ?: createMediaContainer(root) ?: return false
        container.visibility = View.VISIBLE
        while (container.childCount > media.size) container.removeViewAt(container.childCount - 1)

        media.forEachIndexed { index, item ->
            val view = container.getChildAt(index) as? InlineMediaView ?: InlineMediaView(root.context).also {
                it.radius = dp(root, 8).toFloat()
                it.cardElevation = 0f
                it.setCardBackgroundColor(Color.TRANSPARENT)
                container.addView(
                    it,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(root, 2) },
                )
            }
            bindMedia(view, item)
        }
        return true
    }

    private fun createMediaContainer(root: ConstraintLayout): LinearLayout? {
        val guidelineId = Utils.getResId("uikit_chat_guideline", "id")
        val textId = Utils.getResId("chat_list_adapter_item_text", "id")
        if (guidelineId == 0 || textId == 0) return null

        return LinearLayout(root.context).apply {
            id = mediaContainerId
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                startToEnd = guidelineId
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = textId
                horizontalBias = 0f
                marginEnd = dp(root, 12)
            }
            root.addView(this)
        }
    }

    private fun bindMedia(view: InlineMediaView, item: Any) {
        val autoPlayGifs = runCatching {
            StoreStream.getUserSettings().getIsAutoPlayGifsEnabled()
        }.getOrDefault(false)

        when (item) {
            is MessageAttachment -> {
                val (width, height) = scaledSize(view, item.width ?: 0, item.height ?: 0)
                view.updateUIWithAttachment(item, width, height, autoPlayGifs)
                view.setOnClickListener { WidgetMedia.Companion!!.launch(it.context, item) }
            }
            is MessageEmbed -> {
                val preview = EmbedResourceUtils.INSTANCE.getPreviewImage(item)
                val (width, height) = scaledSize(view, preview?.b ?: 0, preview?.c ?: 0)
                view.updateUIWithEmbed(item, width, height, autoPlayGifs)
                view.setOnClickListener { WidgetMedia.Companion!!.launch(it.context, item) }
            }
        }
    }

    /**
     * Scales only what overflows the viewport. An image narrower than the maximum keeps its own
     * pixel size, so a small bot embed stays small instead of stretching to the screen width.
     */
    private fun scaledSize(view: View, width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) {
            return ViewGroup.LayoutParams.WRAP_CONTENT to ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val utils = EmbedResourceUtils.INSTANCE
        val density = view.resources.displayMetrics.density
        val maxWidth = utils.computeMaximumImageWidthPx(view.context)
        val maxHeight = utils.getMAX_IMAGE_VIEW_HEIGHT_PX()

        // Embed dimensions are in density-independent units; convert before comparing to px bounds
        val naturalWidth = (width * density).toInt()
        val naturalHeight = (height * density).toInt()
        if (naturalWidth <= maxWidth && naturalHeight <= maxHeight) return naturalWidth to naturalHeight

        val scale = minOf(maxWidth.toFloat() / naturalWidth, maxHeight.toFloat() / naturalHeight)
        return (naturalWidth * scale).toInt() to (naturalHeight * scale).toInt()
    }

    /** Attachments and embeds that the message row draws itself, in render order. */
    private fun mergeableMedia(message: Message): List<Any> {
        val attachments = message.attachments?.filter { isMergeableAttachment(it) }.orEmpty()
        if (attachments.isEmpty() && message.embeds.isNullOrEmpty()) return attachments

        val shownUrls = HashSet<String>()
        attachments.forEach {
            shownUrls.add(it.url)
            shownUrls.add(it.proxyUrl)
        }

        val media = ArrayList<Any>(attachments)
        message.embeds?.forEach { embed ->
            if (!isMergeableEmbed(embed)) return@forEach
            val previewUrl = EmbedResourceUtils.INSTANCE.getPreviewImage(embed)?.a
            if (previewUrl != null && !shownUrls.add(previewUrl)) return@forEach
            media.add(embed)
        }
        return media
    }

    private fun isMergeableAttachment(attachment: MessageAttachment): Boolean = runCatching {
        if (!StoreStream.getUserSettings().getIsAttachmentMediaInline()) return@runCatching false
        // Spoilers keep their own row so the reveal overlay stays intact
        if (attachment.h()) return@runCatching false
        val type = attachment.type
        if (type != MessageAttachmentType.IMAGE && type != MessageAttachmentType.VIDEO) return@runCatching false
        (attachment.width ?: 0) > 0 && (attachment.height ?: 0) > 0
    }.getOrDefault(false)

    private fun isMergeableEmbed(embed: MessageEmbed): Boolean = runCatching {
        val settings = StoreStream.getUserSettings()
        if (!settings.getIsEmbedMediaInlined() || !settings.getIsRenderEmbedsEnabled()) return@runCatching false
        // Rich embeds carry text and keep rendering as a card
        if (!EmbedResourceUtils.INSTANCE.isInlineEmbed(embed)) return@runCatching false
        val preview = EmbedResourceUtils.INSTANCE.getPreviewImage(embed) ?: return@runCatching false
        (preview.b ?: 0) > 0 && (preview.c ?: 0) > 0
    }.getOrDefault(false)

    /** True when the message still produces embed or attachment rows of its own. */
    private fun hasTrailingRows(message: Message): Boolean {
        if (message.attachments?.any { !isMergeableAttachment(it) } == true) return true
        return message.embeds?.any { !isMergeableEmbed(it) } == true
    }

    private fun applySpacing(itemView: View, entry: MessageEntry, hasInlineMedia: Boolean) {
        // Apply message spacing only to the first message; leave middle messages untouched
        if (entry.isMinimal()) return

        val message = entry.message
        // Every first message gets the same 2dp lead, media or not
        val top = dp(itemView, 2)
        // Rows that continue this message must touch it, otherwise they read as a separate message
        val bottom = if (hasTrailingRows(message) || hasInlineMedia) 0 else dp(itemView, 2)
        itemView.setPadding(itemView.paddingLeft, top, itemView.paddingRight, bottom)
    }

    private fun patchImageLinkSuppression() {
        runCatching {
            val urlNodeClass = Class.forName("com.discord.utilities.textprocessing.node.UrlNode")
            val renderContextClass = Class.forName("com.discord.utilities.textprocessing.node.UrlNode\$RenderContext")
            val fMask = urlNodeClass.getDeclaredField("mask").apply { isAccessible = true }
            val fUrl = urlNodeClass.getDeclaredField("url").apply { isAccessible = true }

            val renderMethod = urlNodeClass.getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                renderContextClass,
            )
            Patcher.addPatch(renderMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mask = fMask.get(param.thisObject) as? String
                    val url = fUrl.get(param.thisObject) as? String ?: return
                    // For bare image links, suppress rendering the URL text so only the image is visible
                    if (mask == null && isImageLink(url)) {
                        param.result = null
                    }
                }
            })
        }
    }

    private fun isImageLink(url: String): Boolean {
        val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
        val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".gifv")
        if (imageExtensions.any { cleanUrl.endsWith(it) }) return true
        if (cleanUrl.contains("cdn.discordapp.com/attachments/") || cleanUrl.contains("media.discordapp.net/attachments/")) {
            return true
        }
        if (cleanUrl.contains("pbs.twimg.com/media/") || cleanUrl.contains("i.imgur.com/") || cleanUrl.contains("i.redd.it/")) {
            return true
        }
        return false
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

        // An image that already fits the viewport keeps its own size. The stock implementation
        // upscales anything below half the maximum width, which blows small bot embeds up to the
        // full screen width.
        runCatching {
            patcher.after<EmbedResourceUtils>(
                "calculateScaledSize",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Resources::class.java,
                Int::class.javaPrimitiveType!!,
            ) { param ->
                val width = param.args[0] as? Int ?: return@after
                val height = param.args[1] as? Int ?: return@after
                val maxWidth = param.args[2] as? Int ?: return@after
                val maxHeight = param.args[3] as? Int ?: return@after
                val resources = param.args[4] as? Resources ?: return@after
                if (width <= 0 || height <= 0) return@after

                val density = resources.displayMetrics.density
                val naturalWidth = (width * density).toInt()
                val naturalHeight = (height * density).toInt()
                if (naturalWidth <= maxWidth && naturalHeight <= maxHeight) {
                    param.result = Pair(naturalWidth, naturalHeight)
                }
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

        // Drop the rows for media the message row now draws itself
        runCatching {
            patcher.after<ChatListEntry.Companion>(
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
                val entries = param.result as? List<*> ?: return@after
                val kept = entries.filterNot {
                    it is AttachmentEntry && isMergeableAttachment(it.attachment) ||
                        it is EmbedEntry && isMergeableEmbed(it.embed)
                }
                if (kept.size != entries.size) param.result = kept
            }
        }

        // Always allow media rendering in embeds
        runCatching {
            patcher.before<WidgetChatListAdapterItemEmbed>("shouldRenderMedia") { param ->
                param.result = true
            }
        }

        // Hook configureUI directly so the item view stays visible with the intended spacing
        runCatching {
            val modelClass = Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed\$Model")
            val configureUIMethod = WidgetChatListAdapterItemEmbed::class.java.getDeclaredMethod("configureUI", modelClass)
            configureUIMethod.isAccessible = true
            Patcher.addPatch(configureUIMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = applyEmbedPadding(param)

                override fun afterHookedMethod(param: MethodHookParam) = applyEmbedPadding(param)

                private fun applyEmbedPadding(param: MethodHookParam) {
                    val holder = param.thisObject as? WidgetChatListAdapterItemEmbed ?: return
                    holder.itemView.visibility = View.VISIBLE
                    holder.itemView.setPadding(0, dp(holder.itemView, 2), 0, 0)
                }
            })
        }

        // Stickers render in their own row and need the same leading gap as media
        runCatching {
            patcher.after<WidgetChatListAdapterItemSticker>(
                "onConfigure",
                Int::class.javaPrimitiveType!!,
                ChatListEntry::class.java,
            ) { param ->
                val holder = param.thisObject as WidgetChatListAdapterItemSticker
                val view = holder.itemView
                view.setPadding(view.paddingLeft, dp(view, 2), view.paddingRight, view.paddingBottom)
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
            // Match the media message spacing so a standalone embed row is not glued to the header
            holder.itemView.setPadding(0, dp(holder.itemView, 2), 0, 0)

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

                // The card ships a 5dp bottom margin that stacks on the row spacing, which reads as
                // a gap between the message and its embed
                (cardView?.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                    if (params.bottomMargin != 0) {
                        params.bottomMargin = 0
                        cardView.layoutParams = params
                    }
                }

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

    private fun dpToPx(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private companion object {
        const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    }
}

/**
 * List rule that requires whitespace after the bullet, so `1234-5678` keeps its hyphen.
 * The trailing newline is left for the text rule, which keeps consecutive items parsing as lists.
 */
private class ListItemRule<T : BasicRenderContext, S : Any> :
    Rule.BlockRule<T, Node<T>, S>(Pattern.compile("^([^\\S\\r\\n]*)[*-][ \\t]+(.*?)[ \\t]*(?=\\n|$)")) {

    override fun parse(matcher: Matcher, parser: Parser<T, in Node<T>, S>, state: S): ParseSpec<T, S> {
        val nestedLevel = if (matcher.group(1).isNullOrEmpty()) 1 else 2
        return ParseSpec(BulletListNode<T>(nestedLevel, false), state, matcher.start(2), matcher.end(2))
    }
}

/**
 * Renders bold text with the real bold face. The chat font family has no bold variant, so
 * `StyleSpan(BOLD)` alone leaves the glyphs unchanged while still reporting a bold typeface,
 * which defeats any check based on `Typeface.isBold`. Stroke emboldening is applied
 * unconditionally so the weight is visible even if the font fails to load.
 */
private class BoldSpan : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) = apply(paint)

    override fun updateMeasureState(paint: TextPaint) = apply(paint)

    private fun apply(paint: TextPaint) {
        boldTypeface(paint)?.let { paint.typeface = it }
        paint.isFakeBoldText = true
    }

    private fun boldTypeface(paint: TextPaint): Typeface? {
        cached?.let { return it }
        val context = Utils.appContext
        val loaded = runCatching {
            ResourcesCompat.getFont(context, Constants.Fonts.whitney_bold)
        }.getOrNull() ?: Typeface.create(paint.typeface, Typeface.BOLD)
        cached = loaded
        return loaded
    }

    private companion object {
        // The bold face never changes at runtime, so load it once for every bold span
        @Volatile
        var cached: Typeface? = null
    }
}
