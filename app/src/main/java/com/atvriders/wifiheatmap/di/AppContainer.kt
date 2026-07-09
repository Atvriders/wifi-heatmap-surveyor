package com.atvriders.wifiheatmap.di

import android.content.Context
import com.atvriders.wifiheatmap.data.FloorPlanImageStore
import com.atvriders.wifiheatmap.data.SettingsStore
import com.atvriders.wifiheatmap.data.SurveyRepository
import com.atvriders.wifiheatmap.data.db.AppDatabase

/**
 * Manual dependency container, created once by [com.atvriders.wifiheatmap.WifiHeatmapApp].
 */
class AppContainer(val appContext: Context) {
    val database: AppDatabase by lazy { AppDatabase.build(appContext) }
    val repository: SurveyRepository by lazy { SurveyRepository(database) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val floorPlans: FloorPlanImageStore by lazy { FloorPlanImageStore(appContext) }
}
