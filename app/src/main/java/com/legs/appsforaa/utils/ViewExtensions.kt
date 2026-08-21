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

/**
 * Adds both the top and bottom system insets to this view's existing padding.
 *
 * **Use this instead of calling [applyTopInsetPadding] and [applyBottomInsetPadding] on the same
 * view.** A view has room for exactly one `OnApplyWindowInsetsListener`, so the second call
 * replaces the first and its padding is silently never applied — which looked like "insets don't
 * work on a ScrollView" and was really the top listener being overwritten by the bottom one.
 *
 * Needed on scrolling containers in particular: a `ScrollView` does not pass the inset dispatch
 * down to its child, so the listener has to sit on the container and the container needs
 * `android:clipToPadding="false"` for the bottom padding not to clip scrolled content.
 */
fun View.applyVerticalInsetPadding() {
    val initialTop = paddingTop
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(top = initialTop + bars.top, bottom = initialBottom + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
