package com.tjg.twidget.env

import com.tjg.twidget.BuildConfig

object BuildVars {
    val DISTRIBUTION: Distribution = Distribution.GITHUB

    /** The separate debug app is updated locally through Android Studio or ADB. */
    val IN_APP_UPDATES: Boolean = !BuildConfig.DEBUG
}
