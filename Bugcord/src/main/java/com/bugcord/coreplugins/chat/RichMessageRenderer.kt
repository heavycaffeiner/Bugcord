/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Paint
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
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.res.ResourcesCompat
import com.bugcord.Constants
import com.bugcord.Utils
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.Patcher
import com.bugcord.patcher.PreHook
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
import com.discord.api.message.embed.MessageEmbed
import com.discord.embed.RenderableEmbedMedia
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.color.ColorCompat
import com.lytefast.flexinput.R
import com.discord.simpleast.core.parser.Rule
import com.discord.stores.StoreMessageState
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.spans.BlockBackgroundSpan
import com.discord.utilities.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.BlockBackgroundNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BulletListNode
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.discord.widgets.chat.list.adapter.WidgetChatListItem
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemSticker
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import de.robv.android.xposed.XC_MethodHook
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.Collections
import java.util.WeakHashMap

/** Enables the legacy parser's message header/list rules for normal messages. */
internal class RichMessageRenderer : CorePlugin(Manifest("RichMessageRenderer")) {
    override val isHidden = true
    override val isRequired = true



    /**
     * Builders whose code block trailing newline was removed, awaiting a sibling node. Weak and
     * identity based so a builder that is never appended to is collected rather than retained.
     */
    private val pendingCodeBlockNewlines: MutableSet<SpannableStringBuilder> =
        Collections.newSetFromMap(WeakHashMap<SpannableStringBuilder, Boolean>())

    override fun start(context: Context) {
        // configureParser must run before any parser is built, and it must not be able to take the
        // view patches down with it: setFinalField can throw on a hidden-API change, and everything
        // after it in this list would then never run
        runCatching { patchListItemRule() }
        runCatching { patchBoldRendering() }
        runCatching { patchHeaderRendering() }
        runCatching { configureParser() }
            .onFailure { logger.error("Failed to install the markdown parsers", it) }
        runCatching { patchMessageLayout() }
            .onFailure { logger.error("Failed to patch the message layout", it) }
        runCatching { patchMediaRowIdentity() }
            .onFailure { logger.error("Failed to patch media row identity", it) }
        runCatching { patchEmbedRendering() }
            .onFailure { logger.error("Failed to patch embed rendering", it) }
        runCatching { patchImageLinkSuppression() }
        runCatching { patchCodeBlockPadding() }
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
     * BlockBackgroundNode appends a trailing newline, which StaticLayout lays out as a second,
     * empty line. Measured on device: "code\n" is 2 lines totalling 101px, against 54px once the
     * newline is gone. No span can shrink that line. AbsoluteSizeSpan and RelativeSizeSpan over the
     * newline leave it at 48px either way, because the line covers a zero-length range, and
     * LineHeightSpan.chooseHeight is never invoked for it. Only deleting the newline collapses it.
     *
     * Deleting it outright was tried before and glued the next node onto the last line of code,
     * since siblings append after this returns. So the newline is removed here and restored lazily
     * if anything is appended afterwards, which also stops it being the trailing character.
     */
    private fun patchCodeBlockPadding() {
        runCatching {
            val renderMethod = BlockBackgroundNode::class.java.getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                BasicRenderContext::class.java,
            )
            Patcher.addPatch(renderMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val builder = param.args[0] as? SpannableStringBuilder ?: return
                    val context = (param.args[1] as? BasicRenderContext)?.context ?: return
                    val end = builder.length
                    if (end < 1 || builder[end - 1] != '\n') return

                    // Match the padding span this block just added, not one from an earlier node
                    val span = builder.getSpans(0, end, VerticalPaddingSpan::class.java)
                        .lastOrNull { builder.getSpanEnd(it) == end } ?: return
                    val start = builder.getSpanStart(span)
                    if (start < 0 || start >= end) return

                    // Keep the lead above the code, drop the padding under it
                    builder.removeSpan(span)
                    builder.setSpan(VerticalPaddingSpan(dpToPx(context, 4), 0), start, end, SPAN_FLAGS)

                    // The spans stay anchored to the code, and the background keeps painting
                    // because its end still lands on the last line of code once this is gone
                    builder.delete(end - 1, end)
                    pendingCodeBlockNewlines.add(builder)
                }
            })
        }

        // Restore the separator before any later node renders, so the next node cannot run onto
        // the code's last line. Verified on device: without this the text reads "codeafter".
        // Node.render is the single dispatch point every node goes through
        runCatching {
            val nodeRender = Class.forName("com.discord.simpleast.core.node.Node").getDeclaredMethod(
                "render",
                SpannableStringBuilder::class.java,
                Any::class.java,
            )
            Patcher.addPatch(nodeRender, PreHook { param ->
                val builder = param.args[0] as? SpannableStringBuilder ?: return@PreHook
                if (!pendingCodeBlockNewlines.remove(builder)) return@PreHook
                val len = builder.length
                if (len > 0 && builder[len - 1] != '\n') builder.append("\n")
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
            runCatching { applySpacing(itemView, entry) }
                .onFailure { logger.error("Failed to apply message spacing", it) }
        }

        // The 24dp typing indicator spacer sits at position 0 in the reverse layout even when nobody
        // is typing, creating a large empty gap above the chat input box. Shrink it down.
        runCatching {
            patcher.after<WidgetChatListItem>(
                "onConfigure",
                Int::class.javaPrimitiveType!!,
                ChatListEntry::class.java,
            ) { param ->
                val holder = param.thisObject as WidgetChatListItem
                val entry = param.args[1] as? ChatListEntry ?: return@after
                if (entry.javaClass.simpleName == "SpacerEntry") {
                    val lp = holder.itemView.layoutParams ?: return@after
                    val target = dp(holder.itemView, 16)
                    if (lp.height != target) {
                        lp.height = target
                        holder.itemView.layoutParams = lp
                    }
                }
            }
        }
    }

    /**
     * Media rows are laid out by the stock adapter. The only thing wrong with them is identity:
     * DiffUtil decides whether two rows are the same item by comparing getKey(), and EmbedEntry and
     * AttachmentEntry build that key from the type and the message id alone. Two pictures on one
     * message therefore share a key, so the adapter reuses an already bound holder for a different
     * row and a picture surfaces under the wrong message. StickerEntry does not have this problem
     * because it folds the sticker id into its key, which is the shape restored here.
     *
     * Fixing the key is enough on its own. Drawing the pictures by hand, dropping the stock rows and
     * rewriting their margins was what made images appear in the wrong place to begin with.
     */
    private fun patchMediaRowIdentity() {
        runCatching {
            val embedKey = EmbedEntry::class.java.getDeclaredMethod("getKey")
            Patcher.addPatch(embedKey, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val entry = param.thisObject as? EmbedEntry ?: return
                    param.result = "${param.result}|${entry.embedIndex}"
                }
            })
        }.onFailure { logger.error("Failed to patch embed row identity", it) }

        runCatching {
            val attachmentKey = AttachmentEntry::class.java.getDeclaredMethod("getKey")
            Patcher.addPatch(attachmentKey, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val entry = param.thisObject as? AttachmentEntry ?: return
                    param.result = "${param.result}|${entry.embedIndex}"
                }
            })
        }.onFailure { logger.error("Failed to patch attachment row identity", it) }
    }


    /** True when the message still produces embed or attachment rows of its own. */
    private fun hasTrailingRows(message: Message): Boolean {
        if (!message.attachments.isNullOrEmpty()) return true
        if (!message.embeds.isNullOrEmpty()) return true
        if (!message.stickers.isNullOrEmpty()) return true
        return false
    }

    /**
     * Spacing must not depend on the row's position. RecyclerView does not rebind a row when other
     * rows are inserted above it, so a position derived padding stays frozen at its bind time value
     * and the gap silently goes stale. The 16dp spacer entry already separates the newest row from
     * the input box, so no position special case is needed here.
     */
    private fun applySpacing(itemView: View, entry: MessageEntry) {
        val message = entry.message
        val isGroupStart = !entry.isMinimal()
        // A non-minimal row opens a new author group and gets the wider lead; continuations stay tight
        val top = if (isGroupStart) dp(itemView, 4) else dp(itemView, 1)
        // Media, embed and sticker rows sit directly under the row and carry the gap themselves
        val bottom = when {
            hasTrailingRows(message) -> 0
            isGroupStart -> dp(itemView, 4)
            else -> dp(itemView, 1)
        }
        itemView.setPadding(itemView.paddingLeft, top, itemView.paddingRight, bottom)

        // A picture-only message still lays out its text view. ConstraintLayout collapses a GONE
        // view in place rather than removing it, so its vertical margins keep reserving a blank
        // line under the username and the picture below reads as a separate message
        val textId = Utils.getResId("chat_list_adapter_item_text", "id")
        if (textId == 0) return
        val textView = itemView.findViewById<TextView>(textId) ?: return
        if (textView.visibility != View.GONE) return
        (textView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (lp.topMargin != 0 || lp.bottomMargin != 0) {
                lp.topMargin = 0
                lp.bottomMargin = 0
                textView.layoutParams = lp
            }
        }
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
                    val url = fUrl.get(param.thisObject) as? String ?: return
                    if (!isImageLink(url)) return
                    val mask = fMask.get(param.thisObject) as? String
                    // A bare image link renders as the picture alone. A markdown link whose label
                    // is just the url is the same thing written differently, so drop its text too.
                    // A real label is left alone, since that text is the point of writing it
                    if (mask == null || mask == url) {
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
                val embed = param.args[0] as? MessageEmbed ?: return@after
                val media = param.result as? RenderableEmbedMedia
                if (media != null) {
                    val w = media.b
                    val h = media.c
                    if (w == null || w <= 0 || h == null || h <= 0) {
                        param.result = RenderableEmbedMedia(media.a, 400, 300)
                    }
                } else {
                    val imgUrl = embed.f()?.c() ?: embed.h()?.c()
                    if (!imgUrl.isNullOrBlank()) {
                        val w = embed.f()?.d() ?: embed.h()?.d() ?: 400
                        val h = embed.f()?.a() ?: embed.h()?.a() ?: 300
                        param.result = RenderableEmbedMedia(imgUrl, w, h)
                    }
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
            // The embed is its own row directly under the header row, so any margin here reads as
            // a blank line. The card keeps the gap above the picture instead
            zeroVerticalMargins(holder.itemView)

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
                val imageContainer = getBoundField(binding, "s") as? View
                imageView?.adjustViewBounds = true

                zeroVerticalMargins(cardView, imageContainer, mediaView, imageView)
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
                // Ensure bot embed image is visible
                val imgUrl = embed.f()?.c() ?: embed.h()?.c()
                if (!imgUrl.isNullOrBlank()) {
                    imageContainer?.visibility = View.VISIBLE
                    imageView?.visibility = View.VISIBLE
                    imageView?.adjustViewBounds = true
                    contentView?.visibility = View.VISIBLE
                }
            }
        }
        val fAttachBinding = runCatching {
            WidgetChatListAdapterItemAttachment::class.java.getDeclaredField("binding").apply {
                isAccessible = true
            }
        }.getOrNull()

        patcher.after<WidgetChatListAdapterItemAttachment>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemAttachment
            holder.itemView.setPadding(0, 0, 0, 0)
            zeroVerticalMargins(holder.itemView)
            if (fAttachBinding != null) {
                val b = fAttachBinding.get(holder) ?: return@after
                zeroVerticalMargins(
                    getBoundField(b, "h") as? View,
                    getBoundField(b, "d") as? View,
                )
            }
        }
        patcher.after<WidgetChatListAdapterItemSticker>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemSticker
            holder.itemView.setPadding(0, 0, 0, 0)
            zeroVerticalMargins(holder.itemView)
            val stickerId = Utils.getResId("chat_list_adapter_item_sticker", "id")
            if (stickerId != 0) {
                zeroVerticalMargins(holder.itemView.findViewById<View>(stickerId))
            }
        }
    }

    /**
     * Strips the vertical margins the chat row layouts put around media. The stock layouts stack a
     * gap on the row, the card and the image, which reads as a blank line once the rows are part of
     * the message above them.
     */
    private fun zeroVerticalMargins(vararg views: View?) {
        views.forEach { view ->
            if (view == null) return@forEach
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (params.topMargin == 0 && params.bottomMargin == 0) return@forEach
            params.topMargin = 0
            params.bottomMargin = 0
            view.layoutParams = params
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
