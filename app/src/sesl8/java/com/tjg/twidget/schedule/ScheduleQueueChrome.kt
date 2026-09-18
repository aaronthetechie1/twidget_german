package com.tjg.twidget.schedule

import android.view.View
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.widget.RoundedNestedScrollView
import dev.oneuiproject.oneui.widget.RoundedTabLayout

/** SESL8 keeps the existing tab and action bar layout. */
internal class ScheduleQueueChrome(
    toolbar: ToolbarLayout, root: View, fab: FloatingActionButton, tabs: RoundedTabLayout,
    selection: BottomNavigationView, trash: BottomNavigationView, scroll: RoundedNestedScrollView,
) {
    val floating = false
    val contentBottomInset = 0
    fun updateSelectionCount(count: Int) = Unit
    fun updateInsets(inset: Int) = Unit
    fun setSwitcherVisible(visible: Boolean) = Unit
    fun dispose() = Unit
}
