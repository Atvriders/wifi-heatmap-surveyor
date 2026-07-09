package com.atvriders.wifiheatmap.di

import android.content.Context

/**
 * Manual dependency container, created once by [com.atvriders.wifiheatmap.WifiHeatmapApp].
 * Dependencies (database, repositories, settings) are added as the layers land.
 */
class AppContainer(val appContext: Context)
