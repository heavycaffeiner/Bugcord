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
import android.widget.LinearLayout
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
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemAttachment
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemSticker
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListItem
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.StickerEntry
import de.robv.android.xposed.XC_MethodHook
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.Collections
import java.util.WeakHashMap

/** Enables the legacy parser's message header/list rules for normal messages. */
internal class RichMessageRenderer : CorePlugin(Manifest("RichMessageRenderer")) {
    override val isHidden = true
    override val isRequired = true

    /** Identifies the container holding the rows inlined into a message row. */
    private val mediaContainerId = View.generateViewId()

    /**
     * Keys of the entries a message row has drawn itself. A standalone row is only collapsed once
     * its key is in here, so media is never hidden without a visible copy existing. Bounded, since
     * a channel's history is unbounded and only recently bound rows can still be on screen.
     */
    private val inlinedKeys: MutableSet<String> =
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>) = size > 256
        })

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
        runCatching { patchMediaMessageGrouping() }
            .onFailure { logger.error("Failed to patch media message grouping", it) }
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
     * empty line under the code. Measured on device: "code\n" is 2 lines totalling 101px against
     * 54px once the newline is gone. No span can shrink that line, because it covers a zero-length
     * range and LineHeightSpan.chooseHeight is never invoked for it.
     *
     * Deleting it outright glued the next node onto the last line of code, since siblings append
     * after this returns, so it is restored lazily if anything follows. The block's own padding is
     * left as the theme set it.
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
                    val end = builder.length
                    if (end < 1 || builder[end - 1] != '\n') return


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

    /**
     * A picture, sticker or embed message is emitted as two entries: a MessageEntry holding the
     * avatar, username and timestamp, and a separate row holding the media. With no text the first
     * row is a bare header, so one message costs two messages worth of height.
     *
     * Draw the media inside the message row instead. The views are produced by the stock holder for
     * that entry, constructed once per message row and asked to configure itself, so every embed,
     * attachment and sticker is bound by Discord's own code rather than rebuilt here. The trailing
     * rows are then dropped, since the message row already shows them.
     */
    private fun patchMessageLayout() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemMessage
            val position = param.args[0] as? Int ?: return@after
            val entry = param.args[1] as? MessageEntry ?: return@after
            val itemView = holder.itemView as? ConstraintLayout ?: return@after
            runCatching { inlineMedia(holder, itemView, entry, position) }
                .onFailure { logger.error("Failed to inline the message media", it) }
            runCatching { applyRowGap(itemView) }
                .onFailure { logger.error("Failed to apply the message row gap", it) }
        }
    }

    /**
     * The stock layout declares a top padding on the row but no bottom padding, because whatever
     * follows a message supplies its own lead. The inlined media sits inside the row now, so the
     * row itself has to close the group.
     */
    private fun applyRowGap(itemView: View) {
        val bottom = dp(itemView, 4)
        if (itemView.paddingBottom == bottom) return
        itemView.setPadding(itemView.paddingLeft, itemView.paddingTop, itemView.paddingRight, bottom)
    }

    /**
     * Attaches the rows the message owns underneath its text, inside the same row. Returns without
     * touching the container when the message owns nothing, so a plain text row is untouched.
     */
    private fun inlineMedia(
        holder: WidgetChatListAdapterItemMessage,
        itemView: ConstraintLayout,
        entry: MessageEntry,
        position: Int,
    ) {
        val adapter = holder.adapter ?: return
        val entries = trailingEntries(adapter, entry, position)
        val container = itemView.findViewById<LinearLayout>(mediaContainerId)
            ?: if (entries.isEmpty()) return else createMediaContainer(itemView) ?: return

        if (entries.isEmpty()) {
            if (container.childCount > 0) container.removeAllViews()
            container.visibility = View.GONE
            container.tag = null
            return
        }
        container.visibility = View.VISIBLE

        // Rebuild whenever the row's contents change identity, so a recycled row can never keep a
        // holder bound to another message. The keys already carry the entry index and the message id
        val key = entries.joinToString("|") { it.key }
        if (container.tag == key) return

        container.removeAllViews()
        entries.forEach { child ->
            val hosted = hostHolder(child, adapter) ?: return@forEach
            hosted.itemView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            container.addView(hosted.itemView)
            runCatching { hosted.onConfigure(child.type, child) }
                .onFailure { logger.error("Failed to configure an inlined media row", it) }
            inlinedKeys.add(child.key)
        }
        container.tag = key
    }

    /** The stock holder that draws [entry], or null when the entry is not a media row. */
    private fun hostHolder(
        entry: ChatListEntry,
        adapter: WidgetChatListAdapter,
    ): WidgetChatListItem? = when (entry) {
        is EmbedEntry -> WidgetChatListAdapterItemEmbed(adapter)
        is AttachmentEntry -> WidgetChatListAdapterItemAttachment(adapter)
        is StickerEntry -> WidgetChatListAdapterItemSticker(adapter)
        else -> null
    }

    /**
     * Collapses a standalone media row whose entry a message row already drew. A row is only
     * collapsed once its key is known to be inlined, so a failure to inline leaves the stock row
     * visible rather than hiding the media entirely.
     */
    private fun collapseInlinedRow(holder: WidgetChatListItem?, entry: ChatListEntry?) {
        val itemView = holder?.itemView ?: return
        val key = entry?.key ?: return
        // The hosted copy lives inside a message row, so it has a parent the recycler does not own
        val hosted = itemView.parent is LinearLayout
        val collapse = !hosted && key in inlinedKeys

        val params = itemView.layoutParams ?: return
        val height = if (collapse) 0 else ViewGroup.LayoutParams.WRAP_CONTENT
        if (params.height == height) return
        params.height = height
        itemView.layoutParams = params
    }

    /**
     * The rows this message owns, taken from the list the model already built, so nothing is
     * reconstructed here.
     *
     * The chat list runs with setReverseLayout(true) and the list is stored newest first, so the
     * rows the model appended after a message sit at lower indices than the message itself. They
     * are collected walking backwards and returned in the order the model produced them.
     */
    private fun trailingEntries(
        adapter: WidgetChatListAdapter,
        entry: MessageEntry,
        position: Int,
    ): List<ChatListEntry> {
        val list = adapter.data?.list ?: return emptyList()
        if (position !in list.indices || list[position] !== entry) return emptyList()

        val messageId = entry.message.id
        val owned = ArrayDeque<ChatListEntry>()

        for (index in position - 1 downTo 0) {
            val candidate = list[index]
            val ownerId = when (candidate) {
                is EmbedEntry -> candidate.message.id
                is AttachmentEntry -> candidate.message.id
                is StickerEntry -> candidate.message.id
                else -> break
            }
            if (ownerId != messageId) break
            owned.addFirst(candidate)
        }
        return owned
    }

    /**
     * Hosts the inlined rows, aligned to the chat guideline so the media lines up with the message
     * text rather than the avatar.
     */
    private fun createMediaContainer(root: ConstraintLayout): LinearLayout? {
        val guidelineId = Utils.getResId("uikit_chat_guideline", "id")
        val textId = Utils.getResId("chat_list_adapter_item_text", "id")
        if (guidelineId == 0 || textId == 0) return null

        return LinearLayout(root.context).apply {
            id = mediaContainerId
            orientation = LinearLayout.VERTICAL
            layoutParams = ConstraintLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                startToStart = guidelineId
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToBottom = textId
            }
            root.addView(this)
        }
    }

    /**
     * A message carrying media renders as two messages: the picture ends up under a second avatar
     * and username instead of joining the one above it.
     *
     * shouldConcatMessage decides whether a message keeps the previous message's header, and it
     * refuses in two ways once media is involved. It requires the last row added to be a message,
     * reactions or embed row, so a trailing AttachmentEntry or StickerEntry ends the group, and it
     * rejects the previous message outright when it hasAttachments or hasEmbeds. Media therefore
     * always breaks the group, even though the rows belong to the message above them.
     *
     * Author, timestamp window, mention, thread, system message and concat count checks are all
     * left to run. Only the media rejection is lifted, which is what the desktop client does.
     */
    private fun patchMediaMessageGrouping() {
        runCatching {
            val companion = Class.forName(
                "com.discord.widgets.chat.list.model.WidgetChatListModelMessages\$Companion"
            )
            val itemsClass = Class.forName(
                "com.discord.widgets.chat.list.model.WidgetChatListModelMessages\$Items"
            )
            val shouldConcat = companion.getDeclaredMethod(
                "shouldConcatMessage",
                itemsClass,
                Message::class.java,
                Message::class.java,
            ).apply { isAccessible = true }

            Patcher.addPatch(shouldConcat, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result == true) return
                    val message = param.args[1] as? Message ?: return
                    val previous = param.args[2] as? Message ?: return
                    // Only the media rejection is lifted; every other rule still decides
                    if (!hasTrailingRows(previous)) return
                    param.result = groupsWithPrevious(message, previous)
                }
            })
        }.onFailure { logger.error("Failed to patch media message grouping", it) }
    }

    /**
     * The concat rules that still apply once the media rejection is lifted, mirroring the checks
     * the stock implementation runs for a text message.
     */
    private fun groupsWithPrevious(message: Message, previous: Message): Boolean = runCatching {
        if (previous.isSystemMessage() || message.hasThread() || previous.hasThread()) return false
        val type = message.type ?: 0
        if (type != 0 && type != -1) return false

        val author = message.author?.id ?: return false
        if (previous.author?.id != author) return false
        if (message.isWebhook() && previous.author?.username != message.author?.username) return false
        if (!message.mentions.isNullOrEmpty()) return false

        // The stock window between two grouped messages
        val sent = message.timestamp?.g() ?: 0
        val previousSent = previous.timestamp?.g() ?: 0
        sent - previousSent < GROUPING_WINDOW_MS
    }.getOrDefault(false)

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

        // The message row draws these rows itself now, so the standalone copies collapse to nothing.
        // They stay in the list, because that list is where the message row reads them from
        runCatching {
            patcher.after<WidgetChatListAdapterItemEmbed>(
                "onConfigure",
                Int::class.javaPrimitiveType!!,
                ChatListEntry::class.java,
            ) { param ->
                collapseInlinedRow(param.thisObject as? WidgetChatListItem, param.args[1] as? ChatListEntry)
            }
            patcher.after<WidgetChatListAdapterItemAttachment>(
                "onConfigure",
                Int::class.javaPrimitiveType!!,
                ChatListEntry::class.java,
            ) { param ->
                collapseInlinedRow(param.thisObject as? WidgetChatListItem, param.args[1] as? ChatListEntry)
            }
            patcher.after<WidgetChatListAdapterItemSticker>(
                "onConfigure",
                Int::class.javaPrimitiveType!!,
                ChatListEntry::class.java,
            ) { param ->
                collapseInlinedRow(param.thisObject as? WidgetChatListItem, param.args[1] as? ChatListEntry)
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

                // The card's own 5dp bottom margin is the gap under the embed, so only the
                // stacked top margins are collapsed here
                zeroTopMargins(cardView, imageContainer, mediaView, imageView)
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

    /** Collapses stacked top margins while leaving the view's own bottom gap intact. */
    private fun zeroTopMargins(vararg views: View?) {
        views.forEach { view ->
            if (view == null) return@forEach
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (params.topMargin == 0) return@forEach
            params.topMargin = 0
            view.layoutParams = params
        }
    }

    private fun getBoundField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    private fun dp(view: View, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    private fun dpToPx(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private companion object {
        const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** The stock window two messages must fall inside to share a header. */
        const val GROUPING_WINDOW_MS = 0x668a0L
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
