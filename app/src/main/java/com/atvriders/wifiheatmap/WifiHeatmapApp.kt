package com.atvriders.wifiheatmap

import android.app.Application
import com.atvriders.wifiheatmap.di.AppContainer

/** Application entry point. Owns the [AppContainer] (manual dependency injection). */
class WifiHeatmapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
