package com.hindi.providers

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.hindi.providers.SettingsTheme.dp
import android.view.View
import android.graphics.drawable.GradientDrawable


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

card.addView(buildHero(context, card))
card.addView(SettingsWidgets.vSpacer(context, 16))
card.addView(buildQuickControls(context, card))

SettingsWidgets.fadeInSlide(card)

return card

        }

    // --------------------------------------------------------

private fun qualityText(): String {
    return when (Settings.getQualityMode()) {
        Settings.QualityMode.AUTO -> "Auto"
        Settings.QualityMode.Q4K -> "4K"
        Settings.QualityMode.Q1080 -> "1080p"
        Settings.QualityMode.Q720 -> "720p"
        Settings.QualityMode.Q480 -> "480p"
    }
}

    // Hero
    // --------------------------------------------------------
private fun buildHero(
    context: Context,
    parent: LinearLayout
): LinearLayout {

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
                qualityText(),
                SettingsTheme.BLUE
            ) {
                SettingsBottomSheets.showQuality(context) {
    refresh(parent, context)
}
            }.apply {
                layoutParams = chipMargin(context)
            }
        )

        chipRow.addView(SettingsWidgets.hSpacer(context, 8))

        chipRow.addView(
            SettingsWidgets.heroChip(
                context,
                "🇮🇳",
                if (Settings.onlyHindiProviders()) "Hindi ON" else "Hindi OFF",
                SettingsTheme.ORANGE
            ) {
                SettingsBottomSheets.showHindi(context) {
    refresh(parent, context)
}
            }.apply {
                layoutParams = chipMargin(context)
            }
        )

        addView(chipRow)

        addView(SettingsWidgets.vSpacer(context, 8))

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
        )

        chipRow2.addView(SettingsWidgets.hSpacer(context, 8))

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
        )

        addView(chipRow2)

        addView(SettingsWidgets.vSpacer(context, 18))

        addView(
            TextView(context).apply {
                text = "Performance"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(SettingsTheme.TEXT_PRIMARY)
            }
        )

        addView(SettingsWidgets.vSpacer(context, 8))

        addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    8.dp(context)
                )

                background = GradientDrawable().apply {
                    cornerRadius = 99f
                    setColor(SettingsTheme.BG_SECONDARY)
                }
            }
        )

        addView(
            TextView(context).apply {
                text = "Balanced • ${SettingsStats.concurrency()} Threads"
                textSize = 11f
                gravity = Gravity.END
                setPadding(0, 8.dp(context), 0, 0)
                setTextColor(SettingsTheme.TEXT_SECONDARY)
            }
        )
    }
}
//controll panel 
  
    // --------------------------------------------------------

private fun buildQuickControls(
    context: Context,
    parent: LinearLayout
): LinearLayout {

    fun tile(
        icon: String,
        title: String,
        value: String,
        color: Int
    ): LinearLayout {

        return SettingsWidgets.controlTile(
            context,
            icon,
            title,
            value,
            color
        ).apply {

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 8.dp(context)
                bottomMargin = 8.dp(context)
            }
        }
    }

    return LinearLayout(context).apply {

        orientation = LinearLayout.VERTICAL

        setPadding(
            14.dp(context),
            0,
            14.dp(context),
            18.dp(context)
        )

        addView(
            LinearLayout(context).apply {

                orientation = LinearLayout.HORIZONTAL

                addView(
                    SettingsWidgets.controlTile(
    context,
    "🎥",
    "Quality",
    qualityText(),
    SettingsTheme.BLUE
) {
    SettingsBottomSheets.showQuality(context) {
    refresh(parent, context)
}
}.apply {
    layoutParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        marginEnd = 8.dp(context)
        bottomMargin = 8.dp(context)
    }
}
                )

                addView(
                    tile(
                        "⚡",
                        "Threads",
                        SettingsStats.concurrency().toString(),
                        SettingsTheme.ORANGE
                    )
                )
            }
        )

        addView(
            LinearLayout(context).apply {

                orientation = LinearLayout.HORIZONTAL

                addView(
                    tile(
                        "☁",
                        "Cloudflare",
                        SettingsStats.cookieCount().toString(),
                        SettingsTheme.GREEN
                    )
                )

                addView(
                    tile(
                        "🔑",
                        "Tokens",
                        SettingsStats.tokenCount().toString(),
                        SettingsTheme.PURPLE
                    )
                )
            }
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

    parent.addView(buildHero(context, parent))

    parent.addView(
        SettingsWidgets.vSpacer(context,16)
    )

    parent.addView(
    buildQuickControls(context, parent)
)
}
}