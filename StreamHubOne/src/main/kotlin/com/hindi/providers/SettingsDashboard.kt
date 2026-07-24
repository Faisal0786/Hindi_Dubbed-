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

        val card = SettingsWidgets.cardContainer(context)

        card.addView(buildHeader(context))
        card.addView(buildStatsRow(context))
        card.addView(buildStatusRow(context))

        SettingsWidgets.fadeInSlide(card)

        return card
    }

    // --------------------------------------------------------
    // Header
    // --------------------------------------------------------

    private fun buildHeader(context: Context): LinearLayout {

        return LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                20.dp(context),
                18.dp(context),
                20.dp(context),
                10.dp(context)
            )

            addView(
                SettingsWidgets.accentBar(
                    context,
                    SettingsTheme.ACCENT_START,
                    SettingsTheme.ACCENT_END
                )
            )

            addView(
                LinearLayout(context).apply {

                    orientation = LinearLayout.VERTICAL

                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )

                    addView(
                        TextView(context).apply {

                            text = "Plugin Overview"

                            textSize = 18f

                            setTypeface(null, Typeface.BOLD)

                            setTextColor(SettingsTheme.TEXT_PRIMARY)
                        }
                    )

                    addView(
                        TextView(context).apply {

                            text =
                                "Live statistics for providers and settings"

                            textSize = 12f

                            setTextColor(
                                SettingsTheme.TEXT_SECONDARY
                            )
                        }
                    )
                }
            )
        }
    }

    // --------------------------------------------------------
    // Stats
    // --------------------------------------------------------

    private fun buildStatsRow(
        context: Context
    ): LinearLayout {

        return LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            setPadding(
                14.dp(context),
                8.dp(context),
                14.dp(context),
                8.dp(context)
            )

            addView(
                SettingsWidgets.statTile(
                    context,
                    SettingsStats.providerCount().toString(),
                    "Providers",
                    SettingsTheme.BLUE
                )
            )

            addView(
                SettingsWidgets.statTile(
                    context,
                    SettingsStats.enabledProviderCount().toString(),
                    "Enabled",
                    SettingsTheme.GREEN
                )
            )

            addView(
                SettingsWidgets.statTile(
                    context,
                    SettingsStats.addonCount().toString(),
                    "Addons",
                    SettingsTheme.PURPLE
                )
            )

            addView(
                SettingsWidgets.statTile(
                    context,
                    SettingsStats.concurrency().toString(),
                    "Threads",
                    SettingsTheme.ORANGE
                )
            )
        }
    }
    // --------------------------------------------------------
    // Status
    // --------------------------------------------------------

    private fun buildStatusRow(
        context: Context
    ): LinearLayout {

        val row = LinearLayout(context).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL

            setPadding(
                18.dp(context),
                8.dp(context),
                18.dp(context),
                18.dp(context)
            )
        }

        val tokenText =
            "${SettingsStats.tokenCount()}/3 Tokens"

        val cookieText =
            "${SettingsStats.cookieCount()}/${Settings.getCloudflareBypassDomains().size} Cookies"

        val tokenColor =
            if (SettingsStats.tokenCount() > 0)
                SettingsTheme.SUCCESS
            else
                SettingsTheme.WARNING

        val cookieColor =
            if (SettingsStats.cookieCount() > 0)
                SettingsTheme.SUCCESS
            else
                SettingsTheme.WARNING

        row.addView(
            SettingsWidgets.statusChip(
                context,
                tokenText,
                tokenColor
            )
        )

        row.addView(
            SettingsWidgets.hSpacer(
                context,
                10
            )
        )

        row.addView(
            SettingsWidgets.statusChip(
                context,
                cookieText,
                cookieColor
            )
        )

        return row
    }

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