package com.hindi.providers

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.hindi.providers.SettingsTheme.dp
import android.graphics.Typeface
import android.view.Gravity

/**
 * Small, reusable UI building blocks shared across all Settings card files.
 */
internal object SettingsWidgets {

    // ── Pill button ──────────────────────────────────────────

    fun pillBtn(
        context: Context, label: String,
        textColor: Int, bgColor: Int, borderColor: Int,
        onClick: () -> Unit
    ) = TextView(context).apply {
        text = label; textSize = 11f
        setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(textColor)
        setPadding(12.dp(context), 6.dp(context), 12.dp(context), 6.dp(context))
        background = GradientDrawable().apply {
            cornerRadius = SettingsTheme.RADIUS_CHIP.dp(context); setColor(bgColor); setStroke(1, borderColor)
        }
        isClickable = true; isFocusable = true; isFocusableInTouchMode = false
        setOnClickListener {
            animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
                animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()
            onClick()
        }
    }

    // ── Divider ──────────────────────────────────────────────

    fun divider(context: Context) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.setMargins(20.dp(context), 0, 20.dp(context), 0) }
        setBackgroundColor(SettingsTheme.DIVIDER_COLOR)
    }

    // ── Horizontal spacer ────────────────────────────────────

    fun hSpacer(context: Context, widthDp: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(widthDp.dp(context), 1)
    }

    // ── Expand / collapse animation ──────────────────────────

    fun animateExpand(view: View, expand: Boolean, onCollapsed: (() -> Unit)? = null) {
        if (expand) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(220).start()
        } else {
            view.animate().alpha(0f).setDuration(160).withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                onCollapsed?.invoke()
            }.start()
        }
    }

    // ── Entrance animation for cards ─────────────────────────

    fun fadeInSlide(view: View) {
        view.alpha       = 0f
        view.translationY = 20f
        view.animate()
            .alpha(1f).translationY(0f)
            .setDuration(300).setInterpolator(DecelerateInterpolator()).start()
    }

    // ── Accent bar (left colour strip used in card headers) ──

    fun accentBar(context: Context, top: Int, bottom: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(3.dp(context), 18.dp(context))
            .also { it.marginEnd = 12.dp(context) }
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = 99f }
    }

    // ── Danger pill shortcut ─────────────────────────────────

    fun dangerBtn(context: Context, label: String, onClick: () -> Unit) = pillBtn(
        context, label,
        SettingsTheme.DANGER_COLOR,
        SettingsTheme.DANGER_BG
SettingsTheme.DANGER_BORDER
        onClick
    )
// stat Tile

fun statTile(
    context: Context,
    value: String,
    label: String,
    accent: Int
): LinearLayout {

    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER

        layoutParams = LinearLayout.LayoutParams(
            0,
            82.dp(context),
            1f
        ).also {
            it.marginStart = 6.dp(context)
            it.marginEnd = 6.dp(context)
        }

        background = SettingsTheme.roundRect(
            SettingsTheme.BG_SECONDARY,
            SettingsTheme.RADIUS_CARD.dp(context)
        )

        addView(TextView(context).apply {
            text = value
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(accent)
        })

        addView(TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(SettingsTheme.TEXT_SECONDARY)
            setPadding(0, 4.dp(context), 0, 0)
        })
    }
}


//Sectione Title

fun sectionTitle(
    context: Context,
    text: String
): TextView {

    return TextView(context).apply {

        this.text = text

        textSize = SettingsTheme.HEADER_SIZE

        setTypeface(null, Typeface.BOLD)

        setTextColor(SettingsTheme.TEXT_PRIMARY)

        setPadding(
            22.dp(context),
            8.dp(context),
            22.dp(context),
            12.dp(context)
        )
    }
}

//status Chip

fun statusChip(
    context: Context,
    text: String,
    color: Int
): TextView {

    return TextView(context).apply {

        this.text = text

        textSize = 10f

        gravity = Gravity.CENTER

        setTypeface(null, Typeface.BOLD)

        setTextColor(color)

        setPadding(
            10.dp(context),
            4.dp(context),
            10.dp(context),
            4.dp(context)
        )

        background = SettingsTheme.pill(
            SettingsTheme.CHIP_BG,
            color
        )
    }
}

    // ── Styled switch ────────────────────────────────────────
    // Single place for all switch tint boilerplate.
    // trackOnColor defaults to SWITCH_ON; pass a different color for custom tints.

    fun styledSwitch(
        context: Context,
        checked: Boolean,
        trackOnColor: Int = SettingsTheme.SWITCH_ON
    ) = Switch(context).apply {
        isChecked = checked
        isClickable = false; isFocusable = false; isFocusableInTouchMode = false
        thumbTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(android.graphics.Color.WHITE, Color.parseColor("#9099B8"))
        )
        trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(trackOnColor, SettingsTheme.SWITCH_OFF)
        )
    }

    // ── Card container ───────────────────────────────────────
    // Standard card: vertical LinearLayout with side margins, rounded bg, elevation.

    fun cardContainer(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val m = 16.dp(context)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(m, 0, m, m) }
        background = SettingsTheme.roundRect(SettingsTheme.BG_CARD, SettingsTheme.RADIUS_CARD.dp(context))
        elevation = 4f
    }
}