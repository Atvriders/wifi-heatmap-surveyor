package com.atvriders.wifiheatmap.data

import com.atvriders.wifiheatmap.core.engine.SampleSink
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.data.db.AppDatabase
import com.atvriders.wifiheatmap.data.db.SurveyEntity
import com.atvriders.wifiheatmap.data.db.FloorPlanEntity

/**
 * Persistence facade over Room. Implements the engine's [SampleSink] port
 * (write-behind: the engine batches, this just persists the batches).
 */
class SurveyRepository(private val db: AppDatabase) : SampleSink {

    val surveyDao get() = db.surveyDao()
    val sampleDao get() = db.sampleDao()

    override suspend fun appendSamples(surveyId: Long, batch: List<PositionedSample>) {
        if (batch.isEmpty()) return
        db.sampleDao().insertBatch(
            samples = batch.map { it.toEntity(surveyId) },
            readingsPerSample = batch.map { s -> s.readings.map { it.toEntity(surveyId) } },
        )
    }

    override suspend fun deleteSegment(surveyId: Long, segmentIndex: Int) {
        db.sampleDao().deleteSegment(surveyId, segmentIndex)
    }

    suspend fun createSurvey(
        name: String,
        positioningMode: String,
        scanMode: String,
        floorPlan: FloorPlanEntity?,
        nowMs: Long,
    ): Long {
        val planId = floorPlan?.let { db.surveyDao().insertFloorPlan(it) }
        return db.surveyDao().insertSurvey(
            SurveyEntity(
                name = name,
                createdAtMs = nowMs,
                positioningMode = positioningMode,
                scanMode = scanMode,
                floorPlanId = planId,
                status = "ACTIVE",
            )
        )
    }

    /** Full survey rehydration for analysis/resume: all samples with their readings. */
    suspend fun loadSamples(surveyId: Long): List<PositionedSample> =
        regroupJoinedRows(db.sampleDao().joinedReadings(surveyId))
}
