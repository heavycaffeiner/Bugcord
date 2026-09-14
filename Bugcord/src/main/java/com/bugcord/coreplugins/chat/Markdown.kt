/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.widget.EditText
import com.bugcord.entities.CorePlugin
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Field
import java.util.regex.Pattern

/** Styles inline Markdown in the legacy composer without changing its source text. */
internal class Markdown : CorePlugin(Manifest("Markdown")) {
    override fun start(context: Context) {
        try {
            val composerClass = Class.forName("com.discord.widgets.chat.input.WidgetChatInputEditText")
            val flexEditTextClass = Class.forName(
                "com.lytefast.flexinput.widget.FlexEditText",
                false,
                composerClass.classLoader,
            )
            val draftsClass = Class.forName(
                "com.discord.widgets.chat.input.MessageDraftsRepo",
                false,
                composerClass.classLoader,
            )
            val editTextField = composerClass.getDeclaredField("editText").apply {
                isAccessible = true
            }
            val constructor = composerClass.getDeclaredConstructor(flexEditTextClass, draftsClass)
            patcher.patch(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val editText = editTextField.get(param.thisObject) as? EditText ?: return
                    val watcher = ComposerWatcher()
                    editText.addTextChangedListener(watcher)
                    watcher.apply(editText.editableText)
                }
            })
        } catch (throwable: Throwable) {
            // A missing/changed Discord class must leave the stock composer untouched.
            logger.error("Unable to hook legacy Markdown composer", throwable)
            patcher.unpatchAll()
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private class ComposerWatcher : TextWatcher {
        private var applying = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable) {
            apply(s)
        }

        fun apply(editable: Editable) {
            if (applying) return
            applying = true
            try {
                editable.getSpans(0, editable.length, MarkdownSpan::class.java)
                    .forEach(editable::removeSpan)
                STYLE_PATTERNS.forEach { style ->
                    val matcher = style.pattern.matcher(editable)
                    while (matcher.find()) {
                        val start = matcher.start(style.group)
                        val end = matcher.end(style.group)
                        if (start < end && !isEscaped(editable, matcher.start())) {
                            editable.setSpan(MarkdownSpan(style.kind), start, end, SPAN_FLAGS)
                        }
                    }
                }
            } finally {
                applying = false
            }
        }

        private fun isEscaped(text: CharSequence, markerStart: Int): Boolean {
            var slashes = 0
            var index = markerStart - 1
            while (index >= 0 && text[index] == '\\') {
                slashes++
                index--
            }
            return slashes % 2 != 0
        }
    }

    private enum class Kind {
        BOLD,
        ITALIC,
        UNDERLINE,
        STRIKETHROUGH,
        CODE,
        SPOILER,
    }

    private class MarkdownSpan(
        private val kind: Kind,
    ) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(paint: TextPaint) {
            when (kind) {
                Kind.BOLD -> paint.typeface = Typeface.create(paint.typeface, Typeface.BOLD)
                Kind.ITALIC -> paint.typeface = Typeface.create(paint.typeface, Typeface.ITALIC)
                Kind.UNDERLINE -> paint.isUnderlineText = true
                Kind.STRIKETHROUGH -> paint.isStrikeThruText = true
                Kind.CODE -> {
                    paint.typeface = Typeface.MONOSPACE
                    paint.bgColor = CODE_BACKGROUND
                }
                Kind.SPOILER -> {
                    paint.bgColor = paint.color
                    paint.color = Color.TRANSPARENT
                }
            }
        }
    }

    private data class StylePattern(
        val pattern: Pattern,
        val kind: Kind,
        val group: Int = 1,
    )

    private companion object {
        private const val SPAN_FLAGS = 33 // Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private const val CODE_BACKGROUND = 0x33000000

        // These mirror the simple inline rules in Discord's legacy SimpleMarkdownRules.
        private val STYLE_PATTERNS = listOf(
            StylePattern(Pattern.compile("\\*\\*(?=\\S)([\\s\\S]*?\\S)\\*\\*(?!\\*)"), Kind.BOLD),
            StylePattern(Pattern.compile("__(?=\\S)([\\s\\S]*?\\S)__(?!_)(?!\\S)"), Kind.UNDERLINE),
            StylePattern(Pattern.compile("~~(?=\\S)([\\s\\S]*?\\S)~~"), Kind.STRIKETHROUGH),
            StylePattern(Pattern.compile("(?<!\\\\)_(?!_)(?=\\S)([^_\\n]*?\\S)_(?!_)"), Kind.ITALIC),
            StylePattern(Pattern.compile("(?<!\\\\)\\*(?!\\*)(?=\\S)([^*\\n]*?\\S)\\*(?!\\*)"), Kind.ITALIC),
            StylePattern(Pattern.compile("`([^`\\n]+)`"), Kind.CODE),
            StylePattern(Pattern.compile("\\|\\|(?=\\S)([\\s\\S]*?\\S)\\|\\|"), Kind.SPOILER),
        )
    }
}
