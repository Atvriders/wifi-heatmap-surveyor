package com.atvriders.wifiheatmap.ui.nav

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose destinations. */
@Serializable
data object HomeRoute

@Serializable
data object NewSurveyRoute

@Serializable
data class LiveRoute(val surveyId: Long)

@Serializable
data class AnalysisRoute(val surveyId: Long)

@Serializable
data object SettingsRoute
