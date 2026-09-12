package com.bugcord.coreplugins.decorations

import android.os.Bundle
import android.view.View
import com.bugcord.Utils
import com.bugcord.api.SettingsAPI
import com.bugcord.settings.SettingsDelegate
import com.bugcord.settings.delegate
import com.bugcord.utils.ViewUtils.addTo
import com.bugcord.widgets.BottomSheet
import com.discord.views.CheckedSetting

internal object DecorationsSettings {
    private val settings = SettingsAPI("Decorations")

    private val enableAvatarDecorationDelegate = settings.delegate("enableAvatarDecorations", true)
    val enableAvatarDecoration by enableAvatarDecorationDelegate
    private val enableGuildTagsDelegate = settings.delegate("enableGuildTags", true)
    val enableGuildTags by enableGuildTagsDelegate
    private val enableNameplatesDelegate = settings.delegate("enableNameplates", true)
    val enableNameplates by enableNameplatesDelegate

    class Sheet : BottomSheet() {
        override fun onViewCreated(view: View, bundle: Bundle?) {
            super.onViewCreated(view, bundle)

            createSetting("Show avatar decorations", enableAvatarDecorationDelegate).addTo(linearLayout)
            createSetting("Show nameplates", enableNameplatesDelegate).addTo(linearLayout)
            createSetting("Show server tags", enableGuildTagsDelegate).addTo(linearLayout)
        }

        private fun createSetting(description: String, delegate: SettingsDelegate<Boolean>): CheckedSetting {
            return Utils.createCheckedSetting(
                requireContext(),
                CheckedSetting.ViewType.SWITCH,
                description,
                null
            ).apply {
                var setting by delegate
                isChecked = setting
                setOnCheckedListener {
                    setting = !setting
                    Utils.promptRestart()
                }
            }
        }
    }
}
