package com.example.hostossi

import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

object SnackbarUtils {

    fun showModernSnackbar(view: View, message: String, duration: Int = Snackbar.LENGTH_SHORT, anchorView: View? = null) {
        val snackbar = Snackbar.make(view, message, duration)
        
        anchorView?.let {
            snackbar.anchorView = it
        }

        // Stylize the snackbar
        val snackbarView = snackbar.view
        val context = view.context

        // Floating style: add margins
        val params = snackbarView.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            val margin = context.resources.getDimensionPixelSize(R.dimen.snackbar_margin).takeIf { it > 0 } ?: 48
            params.setMargins(margin, margin, margin, margin)
            snackbarView.layoutParams = params
        }

        // Background and shape
        snackbarView.background = ContextCompat.getDrawable(context, R.drawable.bg_modern_snackbar)
        
        // Elevation for depth
        snackbarView.elevation = 8f

        snackbar.show()
    }
}
