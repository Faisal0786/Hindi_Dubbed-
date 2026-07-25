package com.hindi.providers

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.hindi.providers.SettingsTheme.dp

internal object SettingsDashboard {

//dashboard refrence

private lateinit var providersValue: TextView
private lateinit var enabledValue: TextView
private lateinit var addonsValue: TextView
private lateinit var threadsValue: TextView

private lateinit var tokenChip: TextView
private lateinit var cookieChip: TextView

    fun buildDashboard(context: Context): LinearLayout {

        val card = SettingsWidgets.glassCard(context)

card.addView(buildHero(context))
card.addView(SettingsWidgets.vSpacer(context, 16))
card.addView(buildQuickControls(context))

SettingsWidgets.fadeInSlide(card)

return card

        }

    // --------------------------------------------------------
    // Header
    // --------------------------------------------------------

    
    // --------------------------------------------------------
    // Stats
    // --------------------------------------------------------

    
    // --------------------------------------------------------
    // Optional Refresh
    // --------------------------------------------------------

    fun refresh(
        parent: LinearLayout,
        context: Context
    ) {

        parent.removeAllViews()

        parent.addView(buildHeader(context))
        parent.addView(buildStatsRow(context))
        parent.addView(buildStatusRow(context))
    }
}