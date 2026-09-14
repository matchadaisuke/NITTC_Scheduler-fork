package jp.linkserver.nittcsc.sync

import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.putCurrentSyncProtocolVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadCoordinatorTest {
    @Test
    fun conflictRequiresBothSidesToChangeAfterLastSync() {
        assertTrue(hasSyncConflict(false, 200, 300, 100))
        assertFalse(hasSyncConflict(false, 200, 100, 100))
        assertFalse(hasSyncConflict(false, 100, 300, 100))
    }

    @Test
    fun forcedConflictIgnoresTimestamps() {
        assertTrue(hasSyncConflict(true, 0, 0, 100))
    }

    @Test
    fun explicitResolutionWinsOverTimestamp() {
        val choice = resolveSyncChoice(
            datasetKey = SchedulerRepository.DATASET_TASKS,
            resolutions = mapOf(SchedulerRepository.DATASET_TASKS to SyncChoice.LOCAL),
            localUpdatedAt = 100,
            remoteUpdatedAt = 200
        )

        assertEquals(SyncChoice.LOCAL, choice)
    }

    @Test
    fun cancelledLessonsFollowLessonResolutionWhenNotExplicitlySelected() {
        val choice = resolveSyncChoice(
            datasetKey = SchedulerRepository.DATASET_CANCELLED_LESSONS,
            resolutions = mapOf(SchedulerRepository.DATASET_LESSONS to SyncChoice.REMOTE),
            localUpdatedAt = 300,
            remoteUpdatedAt = 100
        )

        assertEquals(SyncChoice.REMOTE, choice)
    }

    @Test
    fun newerTimestampIsDefaultResolution() {
        assertEquals(
            SyncChoice.REMOTE,
            resolveSyncChoice(SchedulerRepository.DATASET_PLANS, emptyMap(), 100, 200)
        )
        assertEquals(
            SyncChoice.LOCAL,
            resolveSyncChoice(SchedulerRepository.DATASET_PLANS, emptyMap(), 200, 200)
        )
    }

    @Test
    fun concurrentChangesInDifferentDatasetsAreBothPreserved() {
        fun payload(taskValue: String, taskAt: Long, planValue: String, planAt: Long) =
            JSONObject().putCurrentSyncProtocolVersion()
                .put("device", JSONObject().put("deviceId", "device"))
                .put(
                    "metadata",
                    JSONObject()
                        .put(
                            SchedulerRepository.DATASET_TASKS,
                            JSONObject().put("updatedAt", taskAt)
                        )
                        .put(
                            SchedulerRepository.DATASET_PLANS,
                            JSONObject().put("updatedAt", planAt)
                        )
                )
                .put(SchedulerRepository.DATASET_TASKS, taskValue)
                .put(SchedulerRepository.DATASET_PLANS, planValue)

        val merged = SyncPayloadCoordinator(nowProvider = { 1_000L }).buildMergedPayload(
            localPayload = payload("local-task", 300L, "old-plan", 100L),
            remotePayload = payload("old-task", 100L, "remote-plan", 300L),
            remoteDeviceId = "remote-device",
            resolutions = emptyMap()
        )

        assertEquals("local-task", merged.getString(SchedulerRepository.DATASET_TASKS))
        assertEquals("remote-plan", merged.getString(SchedulerRepository.DATASET_PLANS))
    }
}
