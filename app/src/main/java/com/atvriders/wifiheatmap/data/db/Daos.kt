package com.atvriders.wifiheatmap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {

    @Query(
        """SELECT s.*, (SELECT COUNT(*) FROM samples WHERE surveyId = s.id) AS sampleCount
           FROM surveys s ORDER BY s.createdAtMs DESC"""
    )
    fun observeSurveys(): Flow<List<SurveyWithCount>>

    @Query("SELECT * FROM surveys WHERE id = :id")
    suspend fun getSurvey(id: Long): SurveyEntity?

    @Insert
    suspend fun insertSurvey(s: SurveyEntity): Long

    @Query("UPDATE surveys SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE surveys SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM surveys WHERE id = :id")
    suspend fun deleteSurvey(id: Long)

    @Query("SELECT * FROM surveys WHERE status = 'ACTIVE' ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun latestActiveSurvey(): SurveyEntity?

    @Insert
    suspend fun insertFloorPlan(p: FloorPlanEntity): Long

    @Query("SELECT * FROM floor_plans WHERE id = :id")
    suspend fun getFloorPlan(id: Long): FloorPlanEntity?

    @Query("DELETE FROM floor_plans WHERE id = :id")
    suspend fun deleteFloorPlan(id: Long)

    @Query(
        """UPDATE floor_plans SET metersPerPixel = :metersPerPixel, calAx = :ax, calAy = :ay,
           calBx = :bx, calBy = :by, calDistanceM = :distanceM WHERE id = :id"""
    )
    suspend fun updateCalibration(
        id: Long, metersPerPixel: Double?, ax: Double?, ay: Double?,
        bx: Double?, by: Double?, distanceM: Double?,
    )
}

@Dao
interface SampleDao {

    @Insert
    suspend fun insertSamples(samples: List<SampleEntity>): List<Long>

    @Insert
    suspend fun insertReadings(readings: List<ReadingEntity>)

    @Transaction
    suspend fun insertBatch(samples: List<SampleEntity>, readingsPerSample: List<List<ReadingEntity>>) {
        val ids = insertSamples(samples)
        val all = ArrayList<ReadingEntity>(readingsPerSample.sumOf { it.size })
        for (i in samples.indices) {
            readingsPerSample[i].mapTo(all) { it.copy(sampleId = ids[i]) }
        }
        insertReadings(all)
    }

    @Query("DELETE FROM samples WHERE surveyId = :surveyId AND segmentIndex = :segmentIndex")
    suspend fun deleteSegment(surveyId: Long, segmentIndex: Int)

    @Query("SELECT COUNT(*) FROM samples WHERE surveyId = :surveyId")
    suspend fun sampleCount(surveyId: Long): Int

    @Query(
        """SELECT ssid, COUNT(DISTINCT bssid) AS bssidCount, MAX(rssiDbm) AS maxRssi,
           COUNT(*) AS readingCount
           FROM readings WHERE surveyId = :surveyId AND ssid != ''
           GROUP BY ssid ORDER BY maxRssi DESC"""
    )
    fun observeSsidSummaries(surveyId: Long): Flow<List<SsidSummaryRow>>

    @Query(
        """SELECT bssid, MIN(frequencyMhz) AS frequencyMhz, MIN(band) AS band,
           MAX(rssiDbm) AS maxRssi, COUNT(*) AS readingCount
           FROM readings WHERE surveyId = :surveyId AND ssid = :ssid
           GROUP BY bssid ORDER BY maxRssi DESC"""
    )
    suspend fun bssidsForSsid(surveyId: Long, ssid: String): List<BssidRow>

    @Query(
        """SELECT s.x AS x, s.y AS y, MAX(r.rssiDbm) AS rssi
           FROM samples s JOIN readings r ON r.sampleId = s.id
           WHERE s.surveyId = :surveyId
             AND (:ssid IS NULL OR r.ssid = :ssid)
             AND (:band IS NULL OR r.band = :band)
             AND (:bssid IS NULL OR r.bssid = :bssid)
           GROUP BY s.id"""
    )
    suspend fun heatPoints(surveyId: Long, ssid: String?, band: Int?, bssid: String?): List<HeatPointRow>

    @Query(
        """SELECT s.id AS sampleId, s.timestampMs, s.x, s.y, s.segmentIndex,
           s.latitude, s.longitude, s.accuracyM,
           r.ssid, r.bssid, r.rssiDbm, r.frequencyMhz, r.band, r.isConnected
           FROM samples s JOIN readings r ON r.sampleId = s.id
           WHERE s.surveyId = :surveyId ORDER BY s.timestampMs, s.id"""
    )
    suspend fun joinedReadings(surveyId: Long): List<JoinedReadingRow>

    @Query(
        """SELECT MIN(x) AS minX, MIN(y) AS minY, MAX(x) AS maxX, MAX(y) AS maxY
           FROM samples WHERE surveyId = :surveyId"""
    )
    suspend fun bounds(surveyId: Long): BoundsRow?

    @Query("SELECT MAX(segmentIndex) FROM samples WHERE surveyId = :surveyId")
    suspend fun maxSegmentIndex(surveyId: Long): Int?
}
