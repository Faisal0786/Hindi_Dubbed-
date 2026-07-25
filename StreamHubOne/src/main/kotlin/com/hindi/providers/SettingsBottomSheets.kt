package com.hindi.providers

import android.content.Context
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog

internal object SettingsBottomSheets {

    fun showQuality(context: Context) {
        QualitySheet.show(context)
    }

    fun showThreads(context: Context) {
        Toast.makeText(context, "Threads Selector (Coming Soon)", Toast.LENGTH_SHORT).show()
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