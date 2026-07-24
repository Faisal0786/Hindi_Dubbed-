package com.hindi.providers

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.hindi.providers.SettingsTheme.dp

internal object SettingsDashboard {

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

                            text = "Providers • Tokens • Cloudflare • Addons"

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

        orientation = LinearLayout.VERTICAL

        setPadding(
            16.dp(context),
            12.dp(context),
            16.dp(context),
            12.dp(context)
        )

        // Row 1
        addView(
            LinearLayout(context).apply {

                orientation = LinearLayout.HORIZONTAL

                addView(
                    SettingsWidgets.statTile(
                        context,
                        SettingsStats.providerCount().toString(),
                        "Providers",
                        SettingsTheme.BLUE
                    )
                )

                addView(SettingsWidgets.hSpacer(context, 12))

                addView(
                    SettingsWidgets.statTile(
                        context,
                        SettingsStats.enabledProviderCount().toString(),
                        "Enabled",
                        SettingsTheme.GREEN
                    )
                )
            }
        )

        addView(SettingsWidgets.vSpacer(context, 12))

        // Row 2
        addView(
            LinearLayout(context).apply {

                orientation = LinearLayout.HORIZONTAL

                addView(
                    SettingsWidgets.statTile(
                        context,
                        SettingsStats.addonCount().toString(),
                        "Addons",
                        SettingsTheme.PURPLE
                    )
                )

                addView(SettingsWidgets.hSpacer(context, 12))

                addView(
                    SettingsWidgets.statTile(
                        context,
                        SettingsStats.concurrency().toString(),
                        "Threads",
                        SettingsTheme.ORANGE
                    )
                )
            }
        )
    }
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