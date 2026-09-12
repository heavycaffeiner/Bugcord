package com.bugcord.coreplugins.accountstanding

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.bugcord.Utils
import com.bugcord.entities.CorePlugin
import com.bugcord.patcher.*
import com.bugcord.utils.ViewUtils.addTo
import com.bugcord.utils.ViewUtils.findViewById
import com.discord.widgets.settings.account.WidgetSettingsAccount
import com.lytefast.flexinput.R

internal class AccountStanding : CorePlugin(Manifest("AccountStanding")) {
    init {
        manifest.description = "Adds account standing to Bugcord."
    }

    override fun start(context: Context) {
        patcher.after<WidgetSettingsAccount>("onViewBound", View::class.java) { (_, view: CoordinatorLayout) ->
            val layout = (view.getChildAt(1) as NestedScrollView).getChildAt(0) as LinearLayout
            val baseIndex = layout.indexOfChild(layout.findViewById<TextView>("settings_account_information_header"))
            val ctx = layout.context

            TextView(ctx, null, 0, R.i.UiKit_Settings_Item).apply {
                text = "Account Standing"
                setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    null,
                    ctx.getDrawable(R.e.ic_navigate_next_white_a60_24dp),
                    null
                )
                setOnClickListener {
                    Utils.openPageWithProxy(ctx, AccountStandingPage())
                }
            }.addTo(layout, baseIndex + 1)
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()
}

