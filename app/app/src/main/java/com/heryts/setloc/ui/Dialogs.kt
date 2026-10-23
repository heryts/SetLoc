package com.heryts.setloc.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.heryts.setloc.BuildConfig
import com.heryts.setloc.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.LayoutInflater

internal fun Context.showAboutDialog() {
    val view = LayoutInflater.from(this).inflate(R.layout.about, null)
    view.findViewById<TextView>(R.id.design_about_title).text = getString(R.string.app_name)
    view.findViewById<TextView>(R.id.design_about_version).text = BuildConfig.VERSION_NAME
    view.findViewById<TextView>(R.id.design_about_info).text = getString(R.string.about_info)
    MaterialAlertDialogBuilder(this).setView(view).show()
}

internal fun AlertDialog.dismissSafe() {
    try { if (isShowing) dismiss() } catch (_: Exception) {}
}

internal fun Window.attachTouchRouting(target: Window, movable: Boolean, onDrag: () -> Unit = {}) {
    val original = callback
    var mode = 0
    var lastX = 0f
    var lastY = 0f
    callback = object : Window.Callback by original {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                mode = 0
                val panel = decorView.findViewById<View>(androidx.appcompat.R.id.parentPanel)
                val title = decorView.findViewById<View>(androidx.appcompat.R.id.topPanel)
                if (panel == null) {
                    mode = 1
                } else if (!ev.isInside(panel)) {
                    mode = 1
                } else if (movable && title != null && title.visibility == View.VISIBLE && title.height > 0 && ev.isInside(title)) {
                    mode = 2
                    lastX = ev.rawX
                    lastY = ev.rawY
                    onDrag()
                }
            }
            if (mode == 2) {
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) {
                    val dx = (ev.rawX - lastX).toInt()
                    val dy = (ev.rawY - lastY).toInt()
                    lastX += dx
                    lastY += dy
                    moveBy(dx, dy)
                }
                return true
            }
            if (mode == 0) return original.dispatchTouchEvent(ev)
            val a = IntArray(2)
            val d = IntArray(2)
            target.decorView.getLocationOnScreen(a)
            decorView.getLocationOnScreen(d)
            val copy = MotionEvent.obtain(ev)
            copy.offsetLocation((d[0] - a[0]).toFloat(), (d[1] - a[1]).toFloat())
            target.decorView.dispatchTouchEvent(copy)
            copy.recycle()
            return true
        }
    }
}

private fun MotionEvent.isInside(v: View): Boolean {
    val loc = IntArray(2)
    v.getLocationInWindow(loc)
    return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
}

private fun Window.moveBy(dx: Int, dy: Int) {
    val lp = attributes
    val dm = decorView.resources.displayMetrics
    val w = decorView.width
    val h = decorView.height
    val baseX = if ((lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL) (dm.widthPixels - w) / 2 else 0
    val baseY = if ((lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL) (dm.heightPixels - h) / 2 else 0
    lp.x = (baseX + lp.x + dx).coerceIn(0, maxOf(0, dm.widthPixels - w)) - baseX
    lp.y = (baseY + lp.y + dy).coerceIn(0, maxOf(0, dm.heightPixels - h)) - baseY
    attributes = lp
}
