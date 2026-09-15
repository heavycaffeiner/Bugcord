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
import android.widget.TextView
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
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.lytefast.flexinput.R
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.spans.BlockBackgroundSpan
import com.discord.utilities.embed.EmbedResourceUtils
import com.discord.utilities.spans.VerticalPaddingSpan
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.BlockBackgroundNode
import com.discord.utilities.textprocessing.node.BasicRenderContext
import com.discord.utilities.textprocessing.node.BulletListNode
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
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
        runCatching { patchSmallImageScaling() }
            .onFailure { logger.error("Failed to patch small image scaling", it) }
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

    private fun patchMessageLayout() {
        patcher.after<WidgetChatListAdapterItemMessage>(
            "onConfigure",
            Int::class.javaPrimitiveType!!,
            ChatListEntry::class.java,
        ) { param ->
            val holder = param.thisObject as WidgetChatListAdapterItemMessage
            param.args[1] as? MessageEntry ?: return@after
            val itemView = holder.itemView ?: return@after
            runCatching { applyRowGap(itemView) }
                .onFailure { logger.error("Failed to apply the message row gap", it) }
        }
    }

    /**
     * Closes the gap under a message row. The stock layout declares a top padding but no bottom
     * padding, so consecutive messages sit tighter than they should.
     */
    private fun applyRowGap(itemView: View) {
        val bottom = dp(itemView, 4)
        if (itemView.paddingBottom == bottom) return
        itemView.setPadding(itemView.paddingLeft, itemView.paddingTop, itemView.paddingRight, bottom)
    }

    /**
     * Keeps an image that already fits the viewport at its own size. The stock implementation
     * upscales anything below half the maximum width, which blows a small picture up to the full
     * screen width.
     */
    private fun patchSmallImageScaling() {
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

                // Embed dimensions are density independent; convert before comparing to px bounds
                val density = resources.displayMetrics.density
                val naturalWidth = (width * density).toInt()
                val naturalHeight = (height * density).toInt()
                if (naturalWidth <= maxWidth && naturalHeight <= maxHeight) {
                    param.result = Pair(naturalWidth, naturalHeight)
                }
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
