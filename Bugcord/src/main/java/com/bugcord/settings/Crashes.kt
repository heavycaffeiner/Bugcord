/*
 * This file is part of Bugcord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */
package com.bugcord.settings

import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.bugcord.Constants
import com.bugcord.Utils
import com.bugcord.fragments.SettingsPage
import com.bugcord.utils.DimenUtils
import com.bugcord.utils.MDUtils
import com.bugcord.views.DangerButton
import com.lytefast.flexinput.R
import java.io.File
import java.util.*


private data class CrashLog(val timestamp: String, val stacktrace: String, var times: Int)

class Crashes : SettingsPage() {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Crash Logs")

        val context = view.context
        val padding = DimenUtils.defaultPadding
        val p = padding / 2

        val dir = File(Constants.CRASHLOGS_PATH)
        val files = dir.listFiles()
        val hasCrashes = files != null && files.isNotEmpty()

        addHeaderButton("Open Crashlog Folder", R.e.ic_open_in_new_white_24dp) {
            if (!dir.exists() && !dir.mkdir()) {
                Utils.showToast("Failed to create crashlogs directory!", true)
            } else {
                Utils.launchFileExplorer(dir)
            }
            true
        }
        headerBar.menu.add("Clear Crashes")
            .setIcon(ContextCompat.getDrawable(context, R.e.ic_delete_24dp))
            .setEnabled(hasCrashes)
            .setOnMenuItemClickListener {
                files?.forEach { it.delete() }
                reRender()
                true
            }


        val crashes = getCrashes()
        if (crashes == null || crashes.isEmpty()) {
            TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).run {
                isAllCaps = false
                text = "Woah, no crashes :O"
                typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_semibold)
                gravity = Gravity.CENTER

                linearLayout.addView(this)
            }
            DangerButton(context).run {
                text = "LET'S CHANGE THAT"
                setPadding(p, p, p, p)
                setOnClickListener {
                    throw RuntimeException("You fool...")
                }
                linearLayout.addView(this)
            }
        } else {
            TextView(context, null, 0, R.i.UiKit_Settings_Item_SubText).run {
                text = "Hint: Crashlogs are accesible via your file explorer at Bugcord/crashlogs"
                typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_medium)
                gravity = Gravity.CENTER
                linearLayout.addView(this)
            }
            crashes.values.forEach { (timestamp, stacktrace, times) ->
                TextView(context, null, 0, R.i.UiKit_Settings_Item_Header).run {
                    var title = timestamp
                    if (times > 1)
                        title += " ($times)"
                    text = title
                    typeface = ResourcesCompat.getFont(context, Constants.Fonts.whitney_semibold)
                    linearLayout.addView(this)
                }
                TextView(context).run {
                    text = MDUtils.renderCodeBlock(context, SpannableStringBuilder(), null, stacktrace)
                    setOnClickListener {
                        Utils.setClipboard("CrashLog-$timestamp", stacktrace)
                        Utils.showToast("Copied to clipboard")
                    }
                    linearLayout.addView(this)
                }
            }
        }
    }

    private fun getCrashes(): Map<Int, CrashLog>? {
        val folder = File(Constants.CRASHLOGS_PATH)
        val files = folder.listFiles()?.apply {
            sortByDescending { it.lastModified() }
        } ?: return null

        val res = LinkedHashMap<Int, CrashLog>()
        for (file in files) {
            if (!file.isFile) continue
            val content = file.readText()
            val hashCode = content.hashCode()
            res.computeIfAbsent(hashCode) {
                CrashLog(
                    timestamp = file.name.replace(".txt", "").replace("_".toRegex(), ":"),
                    stacktrace = content,
                    times = 0
                )
            }.times++
        }
        return res
    }
}
