package com.atvriders.wifiheatmap.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.atvriders.wifiheatmap.data.db.SurveyWithCount
import com.atvriders.wifiheatmap.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home list state + survey management actions. */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val surveys: StateFlow<List<SurveyWithCount>> =
        container.repository.surveyDao.observeSurveys()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(surveyId: Long) {
        viewModelScope.launch {
            val dao = container.repository.surveyDao
            // Capture the floor plan before the survey row (and its FK) goes away.
            val planId = dao.getSurvey(surveyId)?.floorPlanId
            val plan = planId?.let { dao.getFloorPlan(it) }
            dao.deleteSurvey(surveyId)
            if (planId != null) {
                dao.deleteFloorPlan(planId)
                plan?.imagePath?.let { path ->
                    withContext(Dispatchers.IO) { container.floorPlans.delete(path) }
                }
            }
        }
    }

    fun rename(surveyId: Long, name: String) {
        viewModelScope.launch { container.repository.surveyDao.rename(surveyId, name) }
    }
}
