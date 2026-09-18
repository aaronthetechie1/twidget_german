package com.tjg.twidget.ui

import android.view.ViewGroup

/** SESL8 uses the toolbar inside CollapsingToolbarLayout. */
internal object SeslToolbarCompatibility {
    fun applyTopInset(root: android.view.View, top: Int) = false
    fun install(root: ViewGroup) = Unit
}
