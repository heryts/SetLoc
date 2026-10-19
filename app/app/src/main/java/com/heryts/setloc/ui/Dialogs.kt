package com.heryts.setloc.ui

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater

internal fun Context.showAboutDialog() {
    val alertDialog = MaterialAlertDialogBuilder(this)
    LayoutInflater.from(this).inflate(R.layout.about,null).apply {
        val  tittle = findViewById<TextView>(R.id.design_about_title)
        val  version = findViewById<TextView>(R.id.design_about_version)
        val  info = findViewById<TextView>(R.id.design_about_info)
        tittle.text = getString(R.string.app_name)
        version.text = BuildConfig.VERSION_NAME
        info.text = getString(R.string.about_info)
    }.run {
        alertDialog.setView(this)
        alertDialog.show()
    }
}

internal fun Context.buildProgressDialog(message: String): AlertDialog {
    val pad = (20 * resources.displayMetrics.density).toInt()
    val row = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(pad, pad, pad, pad)
        addView(android.widget.ProgressBar(context))
        addView(android.widget.TextView(context).apply {
            text = message
            setPadding(pad, 0, 0, 0)
        })
    }
    return MaterialAlertDialogBuilder(this).setView(row).setCancelable(false).create().also {
        it.setCanceledOnTouchOutside(false)
        it.window?.apply {
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        }
    }
}

internal fun AlertDialog.dismissSafe() {
    try { if (isShowing) dismiss() } catch (_: Exception) {}
}
