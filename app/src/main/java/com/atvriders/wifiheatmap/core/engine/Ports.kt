package com.atvriders.wifiheatmap.core.engine

import com.atvriders.wifiheatmap.core.model.PositionFix
import com.atvriders.wifiheatmap.core.model.PositionedSample
import com.atvriders.wifiheatmap.core.model.SignalSnapshot
import com.atvriders.wifiheatmap.core.wifi.ThrottleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ports implemented by the Android layer (`sources/`, `data/`). Everything behind
 * them is pure JVM so the engine is fully unit-testable with fakes.
 */
interface SignalSource {
    val snapshots: Flow<SignalSnapshot>
    val throttle: StateFlow<ThrottleState>
    fun start()
    fun stop()
}

interface PositionSource {
    val fixes: Flow<PositionFix>
    fun start()
    fun stop()
}

interface SampleSink {
    suspend fun appendSamples(surveyId: Long, batch: List<PositionedSample>)

    /** Removes a finalized tap segment's samples (undo). */
    suspend fun deleteSegment(surveyId: Long, segmentIndex: Int)
}
