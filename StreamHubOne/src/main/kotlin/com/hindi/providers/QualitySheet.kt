package com.hindi.providers

import android.app.AlertDialog
import android.content.Context

internal object QualitySheet {

    fun show(
    context: Context,
    onSaved: (() -> Unit)? = null
) {

        val items = arrayOf(
            "Auto",
            "4K Only",
            "1080p Only",
            "720p Only",
            "480p Only"
        )

        var selected = Settings.getQualityMode().ordinal

        AlertDialog.Builder(context)
            .setTitle("Video Quality")
            .setSingleChoiceItems(items, selected) { _, which ->
                selected = which
            }
            ..setPositiveButton("Save") { dialog, _ ->
    Settings.setQualityMode(
        Settings.QualityMode.values()[selected]
    )
    onSaved?.invoke()
    dialog.dismiss()
}
            .setNegativeButton("Cancel", null)
            .show()
    }
}