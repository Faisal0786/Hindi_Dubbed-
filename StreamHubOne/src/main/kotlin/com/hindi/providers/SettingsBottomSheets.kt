package com.hindi.providers

import android.content.Context
import android.app.AlertDialog
import android.widget.Switch
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog

internal object SettingsBottomSheets {

    fun showQuality(context: Context) {
        QualitySheet.show(context)
    }

fun showHindi(context: Context) {

    val toggle = Switch(context).apply {
        text = "Show Hindi Providers Only"
        isChecked = Settings.onlyHindiProviders()
    }

    AlertDialog.Builder(context)
        .setTitle("🇮🇳 Hindi Providers")
        .setMessage("Only Hindi-supported providers will be used while scraping.")
        .setView(toggle)
        .setPositiveButton("Save") { dialog, _ ->
            Settings.setHindiProvidersOnly(toggle.isChecked)
            dialog.dismiss()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

    fun showThreads(context: Context) {
    val values = (1..20).map { it.toString() }.toTypedArray()
    var selected = (Settings.getConcurrency() - 1).coerceIn(0, 19)

    android.app.AlertDialog.Builder(context)
        .setTitle("Concurrent Threads")
        .setSingleChoiceItems(values, selected) { _, which ->
            selected = which
        }
        .setPositiveButton("Save") { dialog, _ ->
            Settings.setConcurrency(selected + 1)
            dialog.dismiss()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

    fun showProviders(context: Context) {
        Toast.makeText(context, "Providers Manager (Coming Soon)", Toast.LENGTH_SHORT).show()
    }

    fun showCloudflare(context: Context) {
        Toast.makeText(context, "Cloudflare Manager (Coming Soon)", Toast.LENGTH_SHORT).show()
    }

    fun showTokens(context: Context) {
        Toast.makeText(context, "Token Manager (Coming Soon)", Toast.LENGTH_SHORT).show()
    }
}