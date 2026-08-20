package com.legs.appsforaa.utils

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Adds the system's bottom inset to this view's existing bottom padding.
 *
 * Activities draw edge-to-edge (`WindowCompat.setDecorFitsSystemWindows(window, false)`), so
 * without this the last item sits underneath the gesture bar or a three-button navbar. The
 * view's original padding is captured once and the inset added on top of it, so repeated inset
 * dispatches — which do happen on rotation and on IME show/hide — cannot accumulate.
 */
fun View.applyBottomInsetPadding() {
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bottom = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ).bottom
        view.updatePadding(bottom = initialBottom + bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Adds the system's top inset to this view's existing top padding. Same accumulation guard as
 * [applyBottomInsetPadding].
 */
fun View.applyTopInsetPadding() {
    val initialTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val top = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ).top
        view.updatePadding(top = initialTop + top)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
