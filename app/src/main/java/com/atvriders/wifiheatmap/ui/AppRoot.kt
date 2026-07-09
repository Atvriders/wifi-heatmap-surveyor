package com.atvriders.wifiheatmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.atvriders.wifiheatmap.data.AppSettings
import com.atvriders.wifiheatmap.di.AppContainer
import com.atvriders.wifiheatmap.ui.analysis.AnalysisScreen
import com.atvriders.wifiheatmap.ui.home.HomeScreen
import com.atvriders.wifiheatmap.ui.live.LiveSurveyScreen
import com.atvriders.wifiheatmap.ui.nav.AnalysisRoute
import com.atvriders.wifiheatmap.ui.nav.HomeRoute
import com.atvriders.wifiheatmap.ui.nav.LiveRoute
import com.atvriders.wifiheatmap.ui.nav.NewSurveyRoute
import com.atvriders.wifiheatmap.ui.nav.SettingsRoute
import com.atvriders.wifiheatmap.ui.settings.SettingsScreen
import com.atvriders.wifiheatmap.ui.theme.ThemeMode
import com.atvriders.wifiheatmap.ui.theme.WifiHeatmapTheme
import com.atvriders.wifiheatmap.ui.wizard.WizardScreen

/** Root composable: theme + navigation graph. */
@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsState(initial = AppSettings())
    val themeMode = when (settings.themeMode) {
        "LIGHT" -> ThemeMode.LIGHT
        "DARK" -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    WifiHeatmapTheme(themeMode = themeMode, dynamicColor = true) {
        val nav = rememberNavController()
        NavHost(navController = nav, startDestination = HomeRoute) {

            composable<HomeRoute> {
                HomeScreen(
                    container = container,
                    onNewSurvey = { nav.navigate(NewSurveyRoute) },
                    onOpenSurvey = { surveyId, status ->
                        if (status == "ACTIVE") {
                            nav.navigate(LiveRoute(surveyId))
                        } else {
                            nav.navigate(AnalysisRoute(surveyId))
                        }
                    },
                    onSettings = { nav.navigate(SettingsRoute) },
                )
            }

            composable<NewSurveyRoute> {
                WizardScreen(
                    container = container,
                    onSurveyCreated = { surveyId ->
                        nav.navigate(LiveRoute(surveyId)) {
                            popUpTo<NewSurveyRoute> { inclusive = true }
                        }
                    },
                    onCancel = { nav.popBackStack() },
                )
            }

            composable<LiveRoute> { entry ->
                val route = entry.toRoute<LiveRoute>()
                LiveSurveyScreen(
                    container = container,
                    surveyId = route.surveyId,
                    onFinish = { surveyId ->
                        nav.navigate(AnalysisRoute(surveyId)) {
                            popUpTo<LiveRoute> { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable<AnalysisRoute> { entry ->
                val route = entry.toRoute<AnalysisRoute>()
                AnalysisScreen(
                    container = container,
                    surveyId = route.surveyId,
                    onResumeSurvey = { surveyId ->
                        // Pop the analysis entry so finishing the resumed survey can't
                        // land back on a stale AnalysisScreen.
                        nav.navigate(LiveRoute(surveyId)) {
                            popUpTo<AnalysisRoute> { inclusive = true }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                )
            }
        }
    }
}
