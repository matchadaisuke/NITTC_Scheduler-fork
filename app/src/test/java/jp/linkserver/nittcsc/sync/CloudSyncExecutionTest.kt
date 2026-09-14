package jp.linkserver.nittcsc.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudSyncExecutionTest {
    @Test
    fun authenticationFailuresRequireUserAction() {
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-9)
        )
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-15)
        )
        assertEquals(
            CloudSyncDisposition.PERMANENT_FAILURE,
            cloudSyncDispositionForMegaError(-26)
        )
    }

    @Test
    fun networkAndUnknownFailuresAreRetried() {
        assertEquals(CloudSyncDisposition.RETRY, cloudSyncDispositionForMegaError(-3))
        assertEquals(CloudSyncDisposition.RETRY, cloudSyncDispositionForMegaError(null))
    }

    @Test
    fun retryPolicyAllowsAtMostFiveRetriesAfterInitialAttempt() {
        assertEquals(true, shouldRetryCloudSync(0))
        assertEquals(true, shouldRetryCloudSync(4))
        assertEquals(false, shouldRetryCloudSync(5))
        assertEquals(false, shouldRetryCloudSync(6))
    }

    @Test
    fun dirtyTimestampIsMonotonicWithinSameMillisecond() {
        assertEquals(1_001L, nextCloudDirtyTimestamp(nowMillis = 1_000L, previousMillis = 1_000L))
        assertEquals(2_000L, nextCloudDirtyTimestamp(nowMillis = 2_000L, previousMillis = 1_000L))
    }

    @Test
    fun newerRemoteSnapshotWinsConflict() {
        assertEquals(
            CloudSnapshotChoice.REMOTE,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 1_000L,
                remoteUpdatedAt = 2_000L
            )
        )
    }

    @Test
    fun newerLocalSnapshotWinsConflict() {
        assertEquals(
            CloudSnapshotChoice.LOCAL,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 2_000L,
                remoteUpdatedAt = 1_000L
            )
        )
    }

    @Test
    fun equalTimestampKeepsUnsyncedLocalSnapshot() {
        assertEquals(
            CloudSnapshotChoice.LOCAL,
            chooseCloudSnapshot(
                localDirty = true,
                localUpdatedAt = 2_000L,
                remoteUpdatedAt = 2_000L
            )
        )
    }

    @Test
    fun cleanLocalAcceptsRemoteSnapshot() {
        assertEquals(
            CloudSnapshotChoice.REMOTE,
            chooseCloudSnapshot(
                localDirty = false,
                localUpdatedAt = 0L,
                remoteUpdatedAt = 1L
            )
        )
    }

    @Test
    fun cloudEnvelopeRoundTripPreservesVerifiedPayloads() {
        val envelope = buildCloudEnvelope(
            updatedAt = 123L,
            deviceId = "device-a",
            commitId = "commit-a",
            payload = JSONObject().put("schema", "nittc-scheduler").put("version", 16),
            syncPayload = JSONObject().put("syncProtocolVersion", 3).put("changedLessons", "value")
        )

        val parsed = requireNotNull(parseCloudRemote(envelope))
        assertEquals(123L, parsed.updatedAt)
        assertEquals("device-a", parsed.deviceId)
        assertEquals("commit-a", parsed.commitId)
        assertEquals(16, JSONObject(parsed.payload).getInt("version"))
        assertEquals("value", parsed.syncPayload?.getString("changedLessons"))
    }

    @Test
    fun corruptedCloudEnvelopeIsRejected() {
        val envelope = buildCloudEnvelope(
            updatedAt = 123L,
            deviceId = "device-a",
            commitId = "commit-a",
            payload = JSONObject().put("schema", "nittc-scheduler").put("version", 16),
            syncPayload = JSONObject().put("syncProtocolVersion", 3)
        )
        val corrupted = JSONObject(envelope).apply {
            getJSONObject("payload").put("version", 15)
        }.toString()

        assertNull(parseCloudRemote(corrupted))
    }

    @Test
    fun versionTwoCloudEnvelopeRemainsReadable() {
        val legacy = JSONObject()
            .put("schema", CLOUD_ENVELOPE_SCHEMA)
            .put("version", 2)
            .put("updatedAt", 10L)
            .put("deviceId", "old-device")
            .put("payload", JSONObject().put("schema", "nittc-scheduler").put("version", 16))
            .toString()

        val parsed = requireNotNull(parseCloudRemote(legacy))
        assertEquals(10L, parsed.updatedAt)
        assertNull(parsed.syncPayload)
    }

    @Test
    fun unknownFutureCloudEnvelopeIsRejected() {
        val future = JSONObject(buildCloudEnvelope(
            updatedAt = 123L,
            deviceId = "device-a",
            commitId = "commit-a",
            payload = JSONObject().put("schema", "nittc-scheduler").put("version", 16),
            syncPayload = JSONObject().put("syncProtocolVersion", 3)
        )).put("version", CURRENT_CLOUD_ENVELOPE_VERSION + 1).toString()

        assertNull(parseCloudRemote(future))
    }
}
