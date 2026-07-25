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
    // Hero
    // --------------------------------------------------------
private fun buildHero(context: Context): LinearLayout {

    return LinearLayout(context).apply {

        orientation = LinearLayout.VERTICAL

        setPadding(
            20.dp(context),
            22.dp(context),
            20.dp(context),
            20.dp(context)
        )

        addView(
            TextView(context).apply {
                text = "🎬 StreamHubOne"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(SettingsTheme.TEXT_PRIMARY)
            }
        )

        addView(
            TextView(context).apply {
                text = "READY TO STREAM"
                textSize = 11f
                setTextColor(SettingsTheme.TEXT_SECONDARY)
                setPadding(0, 6.dp(context), 0, 18.dp(context))
            }
        )

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        chipRow.addView(
    SettingsWidgets.heroChip(
        context,
        "🎥",
        "720p",
        SettingsTheme.BLUE
    ) {
        // TODO Phase 3
    }.apply {
        layoutParams = chipMargin(context)
    }
)

        chipRow.addView(
            SettingsWidgets.hSpacer(context,8)
        )

        chipRow.addView(
    SettingsWidgets.heroChip(
        context,
        "🇮🇳",
        if (Settings.onlyHindiProviders()) "Hindi ON" else "Hindi OFF",
        SettingsTheme.ORANGE
    ) {
        // TODO
    }.apply {
        layoutParams = chipMargin(context)
    }
)
        addView(chipRow)

        addView(SettingsWidgets.vSpacer(context,8))

        val chipRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
       chipRow2.addView(
        SettingsWidgets.heroChip(
    context,
    "⚡",
    "${SettingsStats.concurrency()} Threads",
    SettingsTheme.PURPLE
) {
    // TODO
}.apply {
    layoutParams = chipMargin(context)
}

        chipRow2.addView(
            SettingsWidgets.hSpacer(context,8)
        )
      chipRow2.addView(
        SettingsWidgets.heroChip(
    context,
    "🎬",
    "${SettingsStats.providerCount()} Sources",
    SettingsTheme.GREEN
) {
    // TODO
}.apply {
    layoutParams = chipMargin(context)
}

        addView(chipRow2)
    }
}

//controll panel 
  
    // --------------------------------------------------------

private fun buildQuickControls(
    context: Context
): LinearLayout {

    return LinearLayout(context).apply {

        orientation = LinearLayout.HORIZONTAL

        setPadding(
            14.dp(context),
            0,
            14.dp(context),
            18.dp(context)
        )

        addView(
            SettingsWidgets.controlTile(
                context,
                "🎥",
                "Quality",
                "720p",
                SettingsTheme.BLUE
            )
        )

        addView(
            SettingsWidgets.controlTile(
                context,
                "⚡",
                "Threads",
                SettingsStats.concurrency().toString(),
                SettingsTheme.ORANGE
            )
        )
    }
}

    // Stats
    // --------------------------------------------------------

    
    //Chip margin //--------------------------------------------------------

private fun chipMargin(context: Context): LinearLayout.LayoutParams {
    return LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        marginEnd = 8.dp(context)
    }
}

    // Optional Refresh
    // --------------------------------------------------------

    fun refresh(
    parent: LinearLayout,
    context: Context
) {

    parent.removeAllViews()

    parent.addView(buildHero(context))

    parent.addView(
        SettingsWidgets.vSpacer(context,16)
    )

    parent.addView(
        buildQuickControls(context)
    )
}
}