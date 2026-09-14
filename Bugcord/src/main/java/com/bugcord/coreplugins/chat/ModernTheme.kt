/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2025 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.bugcord.coreplugins.chat

import android.content.Context
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.Patcher
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import de.robv.android.xposed.XC_MethodHook

/**
 * Repaints the legacy dark theme with Discord 344013's palette.
 *
 * Both builds derive every surface from the same small set of greys, so the mapping is done on the
 * resolved color value rather than on resource ids: any legacy grey that comes out of a theme or
 * resource lookup is swapped for its modern counterpart, alpha preserved. That covers attributes,
 * raw colors, and the alpha variants of each token in one table.
 */
internal class ModernTheme : CorePlugin(Manifest("ModernTheme")) {
    override val isHidden = true
    override val isRequired = true

    override fun start(context: Context) {
        patchColorCompat()
        patchTypedArray()
        patchResources()
    }

    /** Covers code paths that resolve theme attributes, which is most of the Discord UI. */
    private fun patchColorCompat() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val color = param.result as? Int ?: return
                param.result = remap(color)
            }
        }
        for (name in arrayOf("getThemedColor", "getColor")) {
            for (method in ColorCompat::class.java.declaredMethods) {
                if (method.name == name && method.returnType == Int::class.javaPrimitiveType) {
                    runCatching { Patcher.addPatch(method, hook) }
                }
            }
        }
    }

    /**
     * Covers colors read straight out of inflated XML. A `background` attribute pointing at a color
     * never reaches any getColor call: the resource system turns it into a ColorDrawable, so that
     * drawable has to be repainted too.
     */
    private fun patchTypedArray() {
        runCatching {
            val getColor = TypedArray::class.java.getDeclaredMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            Patcher.addPatch(getColor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val color = param.result as? Int ?: return
                    param.result = remap(color)
                }
            })
        }

        runCatching {
            val getDrawable = TypedArray::class.java.getDeclaredMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
            )
            Patcher.addPatch(getDrawable, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = remapDrawable(param.result)
                }
            })
        }
    }

    /** Covers direct resource lookups, including the ColorDrawable the theme builds for a view. */
    private fun patchResources() {
        val colorHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val color = param.result as? Int ?: return
                param.result = remap(color)
            }
        }
        for (method in Resources::class.java.declaredMethods) {
            if (method.name == "getColor" && method.returnType == Int::class.javaPrimitiveType) {
                runCatching { Patcher.addPatch(method, colorHook) }
            }
        }

        for (method in Resources::class.java.declaredMethods) {
            if (method.name == "getDrawable" && Drawable::class.java.isAssignableFrom(method.returnType)) {
                runCatching {
                    Patcher.addPatch(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = remapDrawable(param.result)
                        }
                    })
                }
            }
        }
    }

    /** Repaints a flat color drawable, leaving every other drawable untouched. */
    private fun remapDrawable(drawable: Any?): Any? {
        val colorDrawable = drawable as? ColorDrawable ?: return drawable
        val replacement = remap(colorDrawable.color)
        if (replacement == colorDrawable.color) return drawable
        // Mutating the shared instance would leak into every other user of this resource
        return ColorDrawable(replacement)
    }

    /** Returns the modern equivalent of [color], or [color] itself when it is not a mapped token. */
    private fun remap(color: Int): Int {
        if (!isDarkTheme()) return color
        val replacement = PALETTE[color and RGB_MASK] ?: return color
        return (color and ALPHA_MASK) or replacement
    }

    private fun isDarkTheme(): Boolean = runCatching {
        // "dark" and "pureEvil" both use the dark palette; "light" must stay untouched
        StoreStream.getUserSettingsSystem().theme != "light"
    }.getOrDefault(true)

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    private companion object {
        const val RGB_MASK = 0x00FFFFFF
        const val ALPHA_MASK = 0xFF000000.toInt()

        /**
         * Legacy 126021 grey to Discord 344013 "Darker" grey.
         * Left column is the legacy `primary_dark_*` ramp, right column the modern palette.
         * Darker anchors the chat canvas on the tone Dark uses for its server rail, so every
         * surface drops one full step and the frame bottoms out at black.
         */
        val PALETTE: Map<Int, Int> = mapOf(
            // Surfaces
            0x36393F to 0x1E1F22, // background primary, chat area
            0x2F3136 to 0x181A1D, // background secondary, channel list
            0x292B2F to 0x131416, // background secondary alt, guild list
            0x202225 to 0x111214, // background tertiary
            0x18191C to 0x0C0D0F, // background floating
            0x040405 to 0x000000, // deepest background
            0x40444B to 0x27292D, // chat input, elevated controls
            0x4F545C to 0x4E5058, // modifier accent, muted interactive
            // Text. Pure white stays untouched: it also paints icons and active states
            0xDCDDDE to 0xDBDEE1, // text normal
            0xB9BBBE to 0xB5BAC1, // header secondary, interactive normal
            0xA3A6AA to 0x949BA4, // text muted
            0x8E9297 to 0x80848E, // channel default
            0x72767D to 0x6D6F78, // interactive muted text
        ).mapKeys { it.key and RGB_MASK }
    }
}
