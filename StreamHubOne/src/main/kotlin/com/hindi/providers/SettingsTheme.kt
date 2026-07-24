package com.hindi.providers

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue

/**
 * All color tokens, drawable factories, and dp conversion helpers.
 * Everything here is internal — only Settings* files in this package use it.
 */
internal object SettingsTheme {

    // ── Color palette ────────────────────────────────────────
    val BG_DARK        = Color.parseColor("#0D0F14")
    val BG_CARD        = Color.parseColor("#13161E")
    val ACCENT_START   = Color.parseColor("#6C63FF")
    val ACCENT_END     = Color.parseColor("#A855F7")
    val TEXT_PRIMARY   = Color.parseColor("#F0F2FF")
    val TEXT_SECONDARY = Color.parseColor("#7B82A0")
    val SWITCH_ON      = Color.parseColor("#6C63FF")
    val SWITCH_OFF     = Color.parseColor("#2A2D3E")
    val DIVIDER_COLOR  = Color.parseColor("#1F2235")
    val DANGER_COLOR   = Color.parseColor("#FF4E6A")

// ── Android 12 Design Tokens ─────────────────────────────

val BG_SURFACE      = Color.parseColor("#09090B")
val BG_ELEVATED     = Color.parseColor("#10131A")
val BG_SECONDARY    = Color.parseColor("#161922")

val CARD_BORDER     = Color.parseColor("#2B3147")
val CARD_PRESSED    = Color.parseColor("#323A54")

val PURPLE          = Color.parseColor("#7C3AED")
val PURPLE_LIGHT    = Color.parseColor("#8B5CF6")

val BLUE            = Color.parseColor("#2563EB")
val BLUE_LIGHT      = Color.parseColor("#3B82F6")

val GREEN           = Color.parseColor("#22C55E")
val ORANGE          = Color.parseColor("#F59E0B")
val RED             = Color.parseColor("#EF4444")

val CHIP_BG         = Color.parseColor("#1B2132")
val CHIP_BORDER     = Color.parseColor("#36415F")

val SUCCESS         = Color.parseColor("#22C55E")
val WARNING         = Color.parseColor("#F59E0B")
val ERROR           = Color.parseColor("#EF4444")
val INFO            = Color.parseColor("#3B82F6")

    // ── Drawable factories ───────────────────────────────────

    fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    fun colorDrawable(color: Int) = ColorDrawable(color)

    fun stateDrawable(context: Context) = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            GradientDrawable().apply { setColor(Color.parseColor("#2A2D45")) }
        )
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply {
                setColor(Color.parseColor("#1F2235"))
                setStroke(2, ACCENT_START)
            }
        )
        addState(
            intArrayOf(),
            GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        )
    }

    fun verticalGradient(top: Int, bottom: Int, radius: Float = 99f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
            .apply { cornerRadius = radius }

    fun pill(bgColor: Int, borderColor: Int, radius: Float = 99f) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColor)
            setStroke(1, borderColor)
        }

    fun inputBackground(context: Context) = GradientDrawable().apply {
        cornerRadius = 10f.dp(context)
        setColor(Color.parseColor("#0D1117"))
        setStroke(1, Color.parseColor("#2E2850"))
    }

// ── Radius Tokens ────────────────────────────────────────

const val RADIUS_SMALL = 12f
const val RADIUS_CARD = 22f
const val RADIUS_CHIP = 99f
const val RADIUS_DIALOG = 28f

// ── Alpha ────────────────────────────────────────────────

const val DISABLED_ALPHA = 0.45f
const val SECONDARY_ALPHA = 0.72f

// ── Typography ───────────────────────────────────────────

const val TITLE_SIZE = 22f
const val HEADER_SIZE = 16f
const val BODY_SIZE = 14f
const val CAPTION_SIZE = 12f
const val OVERLINE_SIZE = 11f

    // ── dp helpers ───────────────────────────────────────────

    fun Int.dp(context: Context): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, toFloat(), context.resources.displayMetrics
        ).toInt()

    fun Float.dp(context: Context): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, this, context.resources.displayMetrics
        )
}
