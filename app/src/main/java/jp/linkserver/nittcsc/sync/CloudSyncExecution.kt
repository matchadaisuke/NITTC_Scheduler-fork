package jp.linkserver.nittcsc.sync

import org.json.JSONObject
import java.security.MessageDigest

internal enum class CloudSyncDisposition {
    SUCCESS,
    RETRY,
    PERMANENT_FAILURE
}

internal data class CloudSyncExecutionResult(
    val disposition: CloudSyncDisposition,
    val message: String
)

internal const val CLOUD_SYNC_MAX_RETRY_COUNT = 5

internal fun shouldRetryCloudSync(runAttemptCount: Int): Boolean =
    runAttemptCount < CLOUD_SYNC_MAX_RETRY_COUNT

internal fun cloudSyncDispositionForMegaError(code: Int?): CloudSyncDisposition =
    when (code) {
        // Authentication/session errors require user action; retrying in the background is wasteful.
        -9, -15, -26 -> CloudSyncDisposition.PERMANENT_FAILURE
        else -> CloudSyncDisposition.RETRY
    }

internal fun nextCloudDirtyTimestamp(nowMillis: Long, previousMillis: Long): Long =
    maxOf(nowMillis, previousMillis + 1L)

internal enum class CloudSnapshotChoice {
    LOCAL,
    REMOTE
}

internal const val CLOUD_ENVELOPE_SCHEMA = "nittc-scheduler-cloud-sync"
internal const val CURRENT_CLOUD_ENVELOPE_VERSION = 3

internal data class CloudRemoteSnapshot(
    val updatedAt: Long,
    val deviceId: String,
    val payload: String,
    val syncPayload: JSONObject?,
    val commitId: String?
)

internal fun buildCloudEnvelope(
    updatedAt: Long,
    deviceId: String,
    commitId: String,
    payload: JSONObject,
    syncPayload: JSONObject
): String {
    val payloadCopy = JSONObject(payload.toString())
    val syncPayloadCopy = JSONObject(syncPayload.toString())
    return JSONObject().apply {
        put("schema", CLOUD_ENVELOPE_SCHEMA)
        put("version", CURRENT_CLOUD_ENVELOPE_VERSION)
        put("updatedAt", updatedAt)
        put("deviceId", deviceId)
        put("commitId", commitId)
        put("payload", payloadCopy)
        put("payloadSha256", sha256(payloadCopy.toString()))
        put("syncPayload", syncPayloadCopy)
        put("syncPayloadSha256", sha256(syncPayloadCopy.toString()))
    }.toString(2)
}

internal fun parseCloudRemote(text: String): CloudRemoteSnapshot? = runCatching {
    val root = JSONObject(text)
    if (root.optString("schema") == CLOUD_ENVELOPE_SCHEMA) {
        val version = root.optInt("version", 2)
        val payload = root.optJSONObject("payload") ?: return@runCatching null
        val syncPayload = root.optJSONObject("syncPayload")
        val commitId = root.optString("commitId", "").takeIf { it.isNotBlank() }
        if (version > CURRENT_CLOUD_ENVELOPE_VERSION) return@runCatching null
        if (version >= CURRENT_CLOUD_ENVELOPE_VERSION) {
            if (
                commitId == null ||
                syncPayload == null ||
                root.optLong("updatedAt", 0L) <= 0L ||
                root.optString("deviceId", "").isBlank()
            ) {
                return@runCatching null
            }
            if (root.optString("payloadSha256") != sha256(payload.toString())) {
                return@runCatching null
            }
            if (root.optString("syncPayloadSha256") != sha256(syncPayload.toString())) {
                return@runCatching null
            }
        }
        CloudRemoteSnapshot(
            updatedAt = root.optLong("updatedAt", 0L),
            deviceId = root.optString("deviceId", ""),
            payload = payload.toString(),
            syncPayload = syncPayload?.let { JSONObject(it.toString()) },
            commitId = commitId
        )
    } else if (root.optString("schema") == "nittc-scheduler" || root.has("version")) {
        CloudRemoteSnapshot(
            updatedAt = 0L,
            deviceId = "legacy",
            payload = root.toString(),
            syncPayload = null,
            commitId = null
        )
    } else {
        null
    }
}.getOrNull()

private fun sha256(value: String): String {
    val digits = "0123456789abcdef"
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}

/**
 * Resolves a MEGA whole-snapshot conflict by update time.
 *
 * A remote snapshot is accepted only when it is strictly newer than the pending local change.
 * Equal timestamps deliberately keep LOCAL so an unsynced local edit is never discarded on a tie.
 */
internal fun chooseCloudSnapshot(
    localDirty: Boolean,
    localUpdatedAt: Long,
    remoteUpdatedAt: Long
): CloudSnapshotChoice {
    if (!localDirty) return CloudSnapshotChoice.REMOTE
    return if (remoteUpdatedAt > localUpdatedAt) {
        CloudSnapshotChoice.REMOTE
    } else {
        CloudSnapshotChoice.LOCAL
    }
}
