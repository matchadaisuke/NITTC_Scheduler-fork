package jp.linkserver.nittcsc.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.InvalidationTracker
import jp.linkserver.nittcsc.data.AppDatabase
import jp.linkserver.nittcsc.data.SchedulerRepository
import jp.linkserver.nittcsc.data.UiDesignPreferences
import jp.linkserver.nittcsc.reminder.NotificationRebuildCoordinator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone MEGA-backed sync.
 *
 * The user only signs in to MEGA. We store MEGA's session token (not the account password),
 * keep one JSON file under /NITTC Scheduler/, and synchronize that file against the local DB.
 * MEGA SDK classes are accessed through reflection so ordinary source builds still compile when
 * the SDK AAR has not been vendored yet; runtime setup reports a clear error in that case.
 */
object CloudFileSyncManager {
    private const val PREFS = "mega_sync"
    private const val KEY_SESSION = "session"
    private const val KEY_EMAIL = "email"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_LAST_REMOTE_UPDATED_AT = "last_remote_updated_at"
    private const val KEY_LOCAL_DIRTY = "local_dirty"
    private const val KEY_LOCAL_DIRTY_AT = "local_dirty_at"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_LAST_SUCCESS_AT = "last_success_at"

    private const val REMOTE_FOLDER_NAME = "NITTC Scheduler"
    private const val REMOTE_FILE_NAME = "scheduler-sync.json"
    private const val USER_AGENT = "NITTC-Scheduler/MEGA-Sync"
    private const val TAG = "MegaSync"
    private const val SOFT_SYNC_MIN_INTERVAL_MS = 30_000L
    private const val NOTIFICATION_SYNC_TIMEOUT_MS = 10_000L
    private const val NOTIFICATION_SYNC_FRESH_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val runtimeMutex = Mutex()
    private val dirtyStateLock = Any()
    private val applyingRemote = AtomicBoolean(false)
    private var databaseObserver: InvalidationTracker.Observer? = null

    @Volatile
    private var runtime: MegaRuntime? = null

    private val _state = MutableStateFlow(CloudSyncState())

    /** UIが同期中・最終成功時刻・直近の結果を直感的に表示するための状態。 */
    val state: StateFlow<CloudSyncState> = _state.asStateFlow()

    private val activeSyncCount = AtomicInteger(0)
    private val lastSuccessElapsed = AtomicLong(-1L)
    private val softSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val preNotificationLock = Any()
    private var preNotificationJob: Deferred<CloudSyncExecutionResult>? = null

    private val watchedTables = arrayOf(
        "settings",
        "day_types",
        "long_breaks",
        "lessons",
        "cancelled_lessons",
        "changed_lessons",
        "lesson_notes",
        "exam_day_schedules",
        "exam_lessons",
        "lesson_notification_exclusions",
        "tasks",
        "plans"
    )

    fun start(context: Context) {
        val appContext = context.applicationContext
        registerDatabaseObserver(appContext)
        refreshState(appContext)
        if (!isConfigured(appContext)) return
        CloudFileSyncWorker.schedule(appContext)
        // 起動時は WorkManager 任せにせず、アプリ内で即座に（失敗時は何度か）同期する。
        syncSoon(appContext, "app_start", minIntervalMs = 0L)
    }

    /** 保存済みの最終成功時刻・結果文をUI状態へ反映する。 */
    fun refreshState(context: Context) {
        val appContext = context.applicationContext
        val preferences = prefs(appContext)
        _state.update {
            it.copy(
                lastSuccessAt = preferences.getLong(KEY_LAST_SUCCESS_AT, 0L),
                message = preferences.getString(KEY_LAST_STATUS, null).orEmpty()
            )
        }
    }

    /**
     * 画面復帰・起動などの「そろそろ同期したい」タイミング用。多重実行を避け、
     * 直近の成功から [minIntervalMs] 以内なら何もしない。失敗時は [syncWithRetry] が再試行する。
     */
    fun syncSoon(context: Context, reason: String, minIntervalMs: Long = SOFT_SYNC_MIN_INTERVAL_MS) {
        val appContext = context.applicationContext
        if (!isConfigured(appContext)) return
        val last = lastSuccessElapsed.get()
        if (minIntervalMs > 0L && last >= 0L && SystemClock.elapsedRealtime() - last < minIntervalMs) return
        if (!softSyncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncWithRetry(appContext, reason)
            } finally {
                softSyncRunning.set(false)
            }
        }
    }

    /**
     * アプリがバックグラウンドへ移る/終了するときに、未送信の変更があれば送る。
     * プロセスが先に終了しても取りこぼさないよう、WorkManager（expedited）にも積む。
     */
    fun syncOnLeave(context: Context) {
        val appContext = context.applicationContext
        if (!isConfigured(appContext)) return
        val dirty = prefs(appContext).getBoolean(KEY_LOCAL_DIRTY, false)
        if (!dirty) return
        CloudFileSyncWorker.enqueueImmediate(appContext)
        if (!softSyncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncWithRetry(appContext, "app_leave", maxAttempts = 2)
            } finally {
                softSyncRunning.set(false)
            }
        }
    }

    /**
     * 通知を出す「直前」に最新データへ追従するための同期。
     * - 未設定・オフライン・直近に同期済みなら即座に戻る（通知を遅らせない）
     * - 同期自体は呼び出し元と切り離して走らせ、待つのは最大 [timeoutMs] まで
     *   （コールドスタート直後のMEGA再接続が長引いても同期は継続する）
     * - 例外は一切投げない。失敗しても通知は必ず出す。
     */
    suspend fun syncBeforeNotification(
        context: Context,
        reason: String,
        timeoutMs: Long = NOTIFICATION_SYNC_TIMEOUT_MS
    ) {
        val appContext = context.applicationContext
        try {
            if (!isConfigured(appContext)) return
            val last = lastSuccessElapsed.get()
            if (last >= 0L && SystemClock.elapsedRealtime() - last < NOTIFICATION_SYNC_FRESH_MS) return
            if (!isNetworkAvailable(appContext)) return
            val job = synchronized(preNotificationLock) {
                preNotificationJob?.takeIf { it.isActive } ?: scope.async {
                    syncWithRetry(appContext, reason, maxAttempts = 2)
                }.also { preNotificationJob = it }
            }
            val finished = withTimeoutOrNull(timeoutMs) { job.await() }
            if (finished == null) {
                Log.w(TAG, "pre-notification sync still running after ${timeoutMs}ms reason=$reason")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "pre-notification sync failed reason=$reason", error)
        }
    }

    /**
     * 失敗しても諦めず、間隔を空けて [maxAttempts] 回まで同期する。
     * オフラインなら試行せず、接続回復時に WorkManager が実行するよう積む。
     * 認証切れなど利用者の操作が要る失敗（PERMANENT_FAILURE）では再試行しない。
     */
    internal suspend fun syncWithRetry(
        context: Context,
        reason: String,
        preferRemote: Boolean = false,
        maxAttempts: Int = CLOUD_SYNC_IN_PROCESS_MAX_ATTEMPTS
    ): CloudSyncExecutionResult {
        val appContext = context.applicationContext
        if (!isConfigured(appContext)) {
            return CloudSyncExecutionResult(CloudSyncDisposition.SUCCESS, "MEGA同期は未設定です")
        }
        if (!isNetworkAvailable(appContext)) {
            CloudFileSyncWorker.enqueueImmediate(appContext)
            return syncResult(
                appContext,
                CloudSyncDisposition.RETRY,
                "オフラインです。接続が回復したら自動で同期します"
            )
        }
        var last: CloudSyncExecutionResult? = null
        for (attempt in 0 until maxAttempts.coerceAtLeast(1)) {
            val result = executeSync(appContext, preferRemote)
            last = result
            Log.i(TAG, "sync attempt=${attempt + 1}/$maxAttempts reason=$reason disposition=${result.disposition}")
            if (result.disposition != CloudSyncDisposition.RETRY) return result
            if (attempt < maxAttempts - 1) delay(cloudSyncRetryDelayMillis(attempt))
        }
        // アプリ内の再試行を使い切った。回線回復や端末スリープ後に備え、WorkManagerへ引き継ぐ。
        CloudFileSyncWorker.enqueueImmediate(appContext)
        return last ?: CloudSyncExecutionResult(CloudSyncDisposition.RETRY, "同期できませんでした")
    }

    fun isNetworkAvailable(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    fun isConfigured(context: Context): Boolean =
        prefs(context).getString(KEY_SESSION, null).isNullOrBlank().not()

    fun linkedEmail(context: Context): String? =
        prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun sdkAvailable(): Boolean = runCatching {
        Class.forName("nz.mega.sdk.MegaApiAndroid", false, CloudFileSyncManager::class.java.classLoader)
        true
    }.getOrDefault(false)

    fun lastStatus(context: Context): String =
        prefs(context).getString(KEY_LAST_STATUS, null)
            ?: if (isConfigured(context)) "MEGAに接続済み" else "未設定"

    /**
     * Log in without persisting the password. If [pin] is supplied, MEGA's MFA login endpoint
     * is used. A successful login persists only dumpSession() and the account email.
     */
    suspend fun login(
        context: Context,
        email: String,
        password: String,
        pin: String? = null
    ): String {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return setStatus(appContext, "MEGA同期はAndroid 9以降で利用できます")
        }
        if (!sdkAvailable()) {
            return setStatus(appContext, "MEGA SDKがアプリに組み込まれていません")
        }
        val normalizedEmail = email.trim().lowercase()
        if (normalizedEmail.isBlank() || password.isBlank()) {
            return setStatus(appContext, "MEGAのメールアドレスとパスワードを入力してください")
        }

        val loginFailure = runtimeMutex.withLock {
            val candidate = runCatching { MegaRuntime(appContext) }
                .getOrElse {
                    return@withLock "MEGA SDKの初期化に失敗しました: ${it.userMessage()}"
                }
            try {
                candidate.login(normalizedEmail, password, pin?.trim()?.takeIf { it.isNotEmpty() })
                candidate.fetchNodes()
                val session = candidate.dumpSession()
                require(session.isNotBlank()) { "MEGAセッションを取得できませんでした" }
                candidate.observeNodeChanges {
                    CloudFileSyncWorker.enqueueImmediate(appContext)
                }
                val sessionStored = prefs(appContext).edit()
                    .putString(KEY_SESSION, session)
                    .putString(KEY_EMAIL, normalizedEmail)
                    .putLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)
                    .putBoolean(KEY_LOCAL_DIRTY, true)
                    .putLong(KEY_LOCAL_DIRTY_AT, System.currentTimeMillis())
                    .commit()
                check(sessionStored) { "MEGAセッションを端末へ保存できませんでした" }
                val previousRuntime = runtime
                runtime = candidate
                previousRuntime?.close()
                CloudFileSyncWorker.schedule(appContext)
                registerDatabaseObserver(appContext)
                null
            } catch (timeout: TimeoutCancellationException) {
                candidate.close()
                "MEGAへのログインがタイムアウトしました。通信状況を確認して再度お試しください"
            } catch (cancelled: CancellationException) {
                candidate.close()
                throw cancelled
            } catch (error: Throwable) {
                candidate.close()
                when ((error as? MegaOperationException)?.code) {
                    -26 -> "2段階認証が有効です。認証コードを入力して再度ログインしてください"
                    -9 -> "MEGAのメールアドレスまたはパスワードが正しくありません"
                    else -> "MEGAへのログインに失敗しました: ${error.userMessage()}"
                }
            }
        }
        if (loginFailure != null) return setStatus(appContext, loginFailure)
        // Never acquire syncMutex while runtimeMutex is held. Background restore takes them
        // in the opposite order and would otherwise deadlock with an interactive login.
        return syncNow(appContext, preferRemote = true)
    }

    fun disconnect(context: Context) {
        val appContext = context.applicationContext
        val previousDeviceId = prefs(appContext).getString(KEY_DEVICE_ID, null)
        val active = runtime
        runtime = null
        scope.launch { runCatching { active?.logout() } }
        active?.close()
        prefs(appContext).edit().clear().commit()
        previousDeviceId?.let { prefs(appContext).edit().putString(KEY_DEVICE_ID, it).commit() }
        CloudFileSyncWorker.cancel(appContext)
    }

    fun requestImmediateSync(context: Context) {
        val appContext = context.applicationContext
        markLocalDirty(appContext)
        scheduleImmediatePush(appContext)
    }

    /**
     * Requests a near-term two-way sync without declaring that local scheduler data changed.
     * Use this for lifecycle/side-effect triggers such as notification delivery. Marking the
     * database dirty here would incorrectly make an unrelated notification look like a local
     * data edit and could bias conflict resolution toward an unnecessary upload.
     */
    fun requestSync(context: Context) {
        val appContext = context.applicationContext
        if (!isConfigured(appContext)) return
        CloudFileSyncWorker.enqueueImmediate(appContext)
    }

    /** 手動同期。失敗しても自動で数回やり直し、最終結果の文言を返す。 */
    suspend fun syncNow(context: Context, preferRemote: Boolean = false): String =
        syncWithRetry(context.applicationContext, "manual", preferRemote).message

    internal suspend fun syncForBackground(context: Context): CloudSyncExecutionResult =
        syncWithRetryInProcess(context.applicationContext)

    // WorkManager 側にも独自のバックオフがあるため、ここでの再試行は短く留める。
    private suspend fun syncWithRetryInProcess(context: Context): CloudSyncExecutionResult {
        var last: CloudSyncExecutionResult? = null
        for (attempt in 0 until CLOUD_SYNC_BACKGROUND_ATTEMPTS) {
            val result = executeSync(context, preferRemote = false)
            last = result
            if (result.disposition != CloudSyncDisposition.RETRY) return result
            if (attempt < CLOUD_SYNC_BACKGROUND_ATTEMPTS - 1) delay(cloudSyncRetryDelayMillis(attempt))
        }
        return last!!
    }

    private suspend fun executeSync(
        context: Context,
        preferRemote: Boolean
    ): CloudSyncExecutionResult {
        val appContext = context.applicationContext
        beginSyncState()
        try {
            val result = try {
                executeSyncLocked(appContext, preferRemote)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // 想定外の例外でプロセスごと落とさず、再試行可能な失敗として扱う。
                failureResult(appContext, "同期中に予期しないエラーが発生しました", error)
            }
            if (result.recreateRuntime) {
                // 通信断などで壊れたMEGAセッションを使い回さず、次回は復元からやり直す。
                syncMutex.withLock { invalidateRuntime() }
            }
            return result
        } finally {
            endSyncState()
        }
    }

    private fun beginSyncState() {
        activeSyncCount.incrementAndGet()
        _state.update { it.copy(isSyncing = true) }
    }

    private fun endSyncState() {
        if (activeSyncCount.decrementAndGet() <= 0) {
            activeSyncCount.set(0)
            _state.update { it.copy(isSyncing = false) }
        }
    }

    private fun invalidateRuntime() {
        val active = runtime
        runtime = null
        runCatching { active?.close() }
    }

    private suspend fun executeSyncLocked(
        appContext: Context,
        preferRemote: Boolean
    ): CloudSyncExecutionResult {
        return syncMutex.withLock {
            Log.i(TAG, "sync started preferRemote=$preferRemote")
            if (!isConfigured(appContext)) {
                return@withLock syncResult(
                    appContext,
                    CloudSyncDisposition.SUCCESS,
                    "MEGA同期は未設定です"
                )
            }
            val mega = try {
                ensureRuntime(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                return@withLock failureResult(
                    appContext,
                    "MEGAセッションの復元に失敗しました",
                    error
                )
            }
            val preferences = prefs(appContext)
            val localDeviceId = deviceId(appContext)

            val folder = captureSyncFailure { mega.ensureFolder(REMOTE_FOLDER_NAME) }.getOrElse {
                return@withLock failureResult(appContext, "MEGA同期フォルダを開けません", it)
            }
            val remoteNode = captureSyncFailure {
                mega.getChild(folder, REMOTE_FILE_NAME)
            }.getOrElse {
                return@withLock failureResult(
                    appContext,
                    "MEGA上の同期ファイルを確認できません",
                    it
                )
            }
            val remoteText = if (remoteNode != null) {
                captureSyncFailure { mega.downloadText(remoteNode) }.getOrElse {
                    return@withLock failureResult(
                        appContext,
                        "MEGAから同期ファイルを取得できません",
                        it
                    )
                }
            } else {
                null
            }
            val remote = remoteText?.let(::parseCloudRemote)
            if (!remoteText.isNullOrBlank() && remote == null) {
                return@withLock syncResult(
                    appContext,
                    CloudSyncDisposition.PERMANENT_FAILURE,
                    "MEGA上の同期ファイル形式を読み取れません"
                )
            }

            val dirty = preferences.getBoolean(KEY_LOCAL_DIRTY, false)
            val dirtyAt = preferences.getLong(KEY_LOCAL_DIRTY_AT, 0L)
            val lastRemote = preferences.getLong(KEY_LAST_REMOTE_UPDATED_AT, 0L)
            val remoteChanged = remote != null &&
                remote.updatedAt > lastRemote &&
                remote.deviceId != localDeviceId
            val snapshotChoice = remote?.let {
                chooseCloudSnapshot(
                    localDirty = dirty,
                    localUpdatedAt = dirtyAt,
                    remoteUpdatedAt = it.updatedAt
                )
            }
            var mergedRemoteChanges = false
            if (!preferRemote && dirty && remoteChanged && remote.syncPayload != null) {
                val mergedPayload = captureSyncFailure {
                    val localPayload = repository(appContext).exportSyncPayload()
                    SyncPayloadCoordinator().buildMergedPayload(
                        localPayload = localPayload,
                        remotePayload = remote.syncPayload,
                        remoteDeviceId = remote.deviceId,
                        resolutions = emptyMap()
                    )
                }.getOrElse {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.PERMANENT_FAILURE,
                        "MEGA同期データの競合を解決できません: ${it.userMessage()}"
                    )
                }
                val applied = try {
                    applyingRemote.set(true)
                    captureSyncFailure {
                        repository(appContext).applySyncPayload(mergedPayload)
                    }
                } finally {
                    applyingRemote.set(false)
                }
                if (applied.isFailure) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.PERMANENT_FAILURE,
                        "MEGA同期データの統合に失敗しました: ${applied.exceptionOrNull().userMessage()}"
                    )
                }
                NotificationRebuildCoordinator.rebuild(appContext, "cloud_sync_merge")
                mergedRemoteChanges = true
                Log.i(TAG, "concurrent cloud edits merged by dataset metadata")
            }
            val shouldPull = remote != null && remote.payload.isNotBlank() && (
                preferRemote ||
                    (remoteChanged && !mergedRemoteChanges &&
                        snapshotChoice == CloudSnapshotChoice.REMOTE)
            )

            if (remote != null && dirty && remote.deviceId != localDeviceId) {
                Log.i(
                    TAG,
                    "snapshot conflict resolved choice=$snapshotChoice " +
                        "localUpdatedAt=$dirtyAt remoteUpdatedAt=${remote.updatedAt} " +
                        "lastRemoteUpdatedAt=$lastRemote"
                )
            }

            if (shouldPull) {
                val imported = try {
                    applyingRemote.set(true)
                    captureSyncFailure {
                        val repository = repository(appContext)
                        repository.importAllData(remote.payload, requireSettings = true)
                        // Version 3 keeps per-dataset metadata beside the full backup payload.
                        // Restore it after legacy import timestamps have been generated.
                        remote.syncPayload?.let { repository.applySyncPayload(it) }
                    }
                } finally {
                    applyingRemote.set(false)
                }
                if (imported.isFailure) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.PERMANENT_FAILURE,
                        "MEGA同期データの適用に失敗しました: ${imported.exceptionOrNull().userMessage()}"
                    )
                }
                NotificationRebuildCoordinator.rebuild(appContext, "cloud_sync_pull")
                val (stateStored, changedDuringPull) = synchronized(dirtyStateLock) {
                    val latestDirtyAt = preferences.getLong(KEY_LOCAL_DIRTY_AT, 0L)
                    val changed = latestDirtyAt != dirtyAt
                    val editor = preferences.edit().putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt)
                    if (!changed) {
                        editor
                            .putBoolean(KEY_LOCAL_DIRTY, false)
                            .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                    }
                    editor.commit() to changed
                }
                if (!stateStored || changedDuringPull) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.RETRY,
                        if (stateStored) {
                            "同期中に新しい変更を検出したため再同期します"
                        } else {
                            "MEGAからの同期後、同期状態を端末へ保存できませんでした"
                        }
                    )
                }
                return@withLock syncResult(
                    appContext,
                    CloudSyncDisposition.SUCCESS,
                    "MEGAから同期しました"
                )
            }

            if (remote == null || dirty || mergedRemoteChanges) {
                val payloads = captureSyncFailure {
                    val repository = repository(appContext)
                    JSONObject(repository.exportAllData()) to repository.exportSyncPayload()
                }.getOrElse {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.PERMANENT_FAILURE,
                        "同期データの作成に失敗しました: ${it.userMessage()}"
                    )
                }
                val updatedAt = maxOf(System.currentTimeMillis(), (remote?.updatedAt ?: 0L) + 1L)
                val commitId = UUID.randomUUID().toString()
                val envelope = buildCloudEnvelope(
                    updatedAt = updatedAt,
                    deviceId = localDeviceId,
                    commitId = commitId,
                    payload = payloads.first,
                    syncPayload = payloads.second
                )
                val uploaded = captureSyncFailure {
                    mega.uploadText(folder, REMOTE_FILE_NAME, envelope)
                }
                if (uploaded.isFailure) {
                    return@withLock failureResult(
                        appContext,
                        "MEGAへのアップロードに失敗しました",
                        uploaded.exceptionOrNull()
                    )
                }
                val verified = captureSyncFailure {
                    val uploadedNode = mega.getChild(folder, REMOTE_FILE_NAME)
                        ?: error("アップロードした同期ファイルが見つかりません")
                    val uploadedRemote = parseCloudRemote(mega.downloadText(uploadedNode))
                        ?: error("アップロードした同期ファイルの検証に失敗しました")
                    check(uploadedRemote.commitId == commitId) {
                        "別端末の更新と競合したため、再同期が必要です"
                    }
                }
                if (verified.isFailure) {
                    return@withLock failureResult(
                        appContext,
                        "MEGAへのアップロードを確認できません",
                        verified.exceptionOrNull()
                    )
                }
                val (stateStored, changedDuringUpload) = synchronized(dirtyStateLock) {
                    val latestDirtyAt = preferences.getLong(KEY_LOCAL_DIRTY_AT, 0L)
                    val changed = latestDirtyAt != dirtyAt
                    val stateEditor = preferences.edit()
                        .putLong(KEY_LAST_REMOTE_UPDATED_AT, updatedAt)
                    if (!changed) {
                        stateEditor
                            .putBoolean(KEY_LOCAL_DIRTY, false)
                            .putLong(KEY_LOCAL_DIRTY_AT, 0L)
                    }
                    stateEditor.commit() to changed
                }
                if (!stateStored) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.RETRY,
                        "MEGAへの同期後、同期状態を端末へ保存できませんでした"
                    )
                }
                if (changedDuringUpload) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.RETRY,
                        "同期中に新しい変更を検出したため再同期します"
                    )
                }
                return@withLock syncResult(
                    appContext,
                    CloudSyncDisposition.SUCCESS,
                    "MEGAへ同期しました"
                )
            }

            if (remote.updatedAt > lastRemote) {
                if (!preferences.edit().putLong(KEY_LAST_REMOTE_UPDATED_AT, remote.updatedAt).commit()) {
                    return@withLock syncResult(
                        appContext,
                        CloudSyncDisposition.RETRY,
                        "同期状態を端末へ保存できませんでした"
                    )
                }
            }
            syncResult(appContext, CloudSyncDisposition.SUCCESS, "MEGA同期済み")
        }
    }

    private suspend fun ensureRuntime(context: Context): MegaRuntime {
        runtime?.let { return it }
        return runtimeMutex.withLock {
            runtime?.let { return@withLock it }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                throw CloudSyncPermanentException("MEGA同期はAndroid 9以降で利用できます")
            }
            if (!sdkAvailable()) {
                throw CloudSyncPermanentException("MEGA SDKがアプリに組み込まれていません")
            }
            val session = prefs(context).getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }
                ?: throw CloudSyncPermanentException("MEGA同期は未設定です")
            val created = captureSyncFailure {
                MegaRuntime(context).also { api ->
                    api.fastLogin(session)
                    api.fetchNodes()
                    api.observeNodeChanges {
                        CloudFileSyncWorker.enqueueImmediate(context)
                    }
                }
            }.getOrElse { error ->
                if (error is TimeoutCancellationException) {
                    // CancellationException のまま投げると同期全体が「キャンセル」扱いになり再試行されない。
                    throw java.io.IOException("MEGAへの接続がタイムアウトしました", error)
                }
                if ((error as? MegaOperationException)?.code == -15) {
                    throw CloudSyncPermanentException(
                        "MEGAセッションの有効期限が切れました。再ログインしてください",
                        error
                    )
                }
                throw error
            }
            runtime = created
            created
        }
    }

    private fun registerDatabaseObserver(context: Context) {
        if (databaseObserver != null) return
        val db = AppDatabase.getInstance(context)
        val observer = object : InvalidationTracker.Observer(watchedTables) {
            override fun onInvalidated(tables: Set<String>) {
                if (applyingRemote.get()) return
                markLocalDirty(context)
                scheduleImmediatePush(context)
            }
        }
        db.invalidationTracker.addObserver(observer)
        databaseObserver = observer
    }

    private fun markLocalDirty(context: Context) {
        if (!isConfigured(context)) return
        val preferences = prefs(context)
        val stored = synchronized(dirtyStateLock) {
            val previousDirtyAt = preferences.getLong(KEY_LOCAL_DIRTY_AT, 0L)
            val dirtyAt = nextCloudDirtyTimestamp(System.currentTimeMillis(), previousDirtyAt)
            preferences.edit()
                .putBoolean(KEY_LOCAL_DIRTY, true)
                .putLong(KEY_LOCAL_DIRTY_AT, dirtyAt)
                .commit()
        }
        if (!stored) Log.w(TAG, "local dirty state could not be persisted")
    }

    private fun scheduleImmediatePush(context: Context) {
        if (!isConfigured(context)) return
        CloudFileSyncWorker.enqueueImmediate(context)
    }

    private fun repository(context: Context): SchedulerRepository = SchedulerRepository(
        AppDatabase.getInstance(context),
        UiDesignPreferences(context)
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun deviceId(context: Context): String {
        val preferences = prefs(context)
        return preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(KEY_DEVICE_ID, it).commit()) {
                "MEGA同期の端末IDを保存できませんでした"
            }
        }
    }

    private fun failureResult(
        context: Context,
        prefix: String,
        error: Throwable?
    ): CloudSyncExecutionResult {
        val unwrapped = error.unwrapReflectionException()
        val disposition = when (unwrapped) {
            is CloudSyncPermanentException -> CloudSyncDisposition.PERMANENT_FAILURE
            is MegaOperationException -> cloudSyncDispositionForMegaError(unwrapped.code)
            else -> CloudSyncDisposition.RETRY
        }
        val message = if (unwrapped is CloudSyncPermanentException) {
            unwrapped.message.orEmpty().ifBlank { prefix }
        } else {
            "$prefix: ${unwrapped.userMessage()}"
        }
        return syncResult(
            context,
            disposition,
            message,
            recreateRuntime = disposition == CloudSyncDisposition.RETRY
        )
    }

    private fun syncResult(
        context: Context,
        disposition: CloudSyncDisposition,
        message: String,
        recreateRuntime: Boolean = false
    ): CloudSyncExecutionResult {
        setStatus(context, message)
        if (disposition == CloudSyncDisposition.SUCCESS) {
            val now = System.currentTimeMillis()
            prefs(context).edit().putLong(KEY_LAST_SUCCESS_AT, now).apply()
            lastSuccessElapsed.set(SystemClock.elapsedRealtime())
            _state.update { it.copy(lastSuccessAt = now, message = message, lastFailed = false) }
        } else {
            _state.update { it.copy(message = message, lastFailed = true) }
        }
        if (disposition == CloudSyncDisposition.SUCCESS) {
            Log.i(TAG, "sync completed disposition=$disposition message=$message")
        } else {
            Log.w(TAG, "sync completed disposition=$disposition message=$message")
        }
        return CloudSyncExecutionResult(disposition, message, recreateRuntime)
    }

    private fun setStatus(context: Context, value: String): String {
        if (!prefs(context).edit().putString(KEY_LAST_STATUS, value).commit()) {
            Log.w(TAG, "sync status could not be persisted")
        }
        return value
    }

}

private class MegaRuntime(
    private val context: Context
) {
    private val apiClass = Class.forName("nz.mega.sdk.MegaApiAndroid")
    private val requestListenerClass = Class.forName("nz.mega.sdk.MegaRequestListenerInterface")
    private val transferListenerClass = Class.forName("nz.mega.sdk.MegaTransferListenerInterface")
    private val globalListenerClass = Class.forName("nz.mega.sdk.MegaGlobalListenerInterface")
    private val api: Any
    private var globalListener: Any? = null

    init {
        val cache = File(context.filesDir, "mega-sdk-cache").apply { mkdirs() }
        api = apiClass
            .getConstructor(String::class.java, String::class.java, String::class.java)
            // MEGA SDK v10.19.0 keeps this legacy constructor argument but ignores it.
            .newInstance("", "NITTC-Scheduler/MEGA-Sync", cache.absolutePath)
    }

    suspend fun login(email: String, password: String, pin: String?) {
        val result = if (pin.isNullOrBlank()) {
            request("login", email, password)
        } else {
            request("multiFactorAuthLogin", email, password, pin)
        }
        result.requireOk("ログイン")
    }

    suspend fun fastLogin(session: String) {
        request("fastLogin", session).requireOk("セッション復元")
    }

    suspend fun fetchNodes() {
        request("fetchNodes").requireOk("ファイル一覧取得")
    }

    fun dumpSession(): String =
        invokeApi("dumpSession") as? String ?: ""

    suspend fun ensureFolder(name: String): Any {
        val root = invokeApi("getRootNode")
            ?: throw IllegalStateException("MEGAルートフォルダを取得できません")
        getChild(root, name)?.let { return it }
        val result = request("createFolder", name, root).also { it.requireOk("同期フォルダ作成") }
        val handle = result.request?.let { invokeOn(it, "getNodeHandle") as? Number }?.toLong() ?: -1L
        if (handle != -1L) {
            invokeApi("getNodeByHandle", handle)?.let { return it }
        }
        return getChild(root, name)
            ?: throw IllegalStateException("作成したMEGA同期フォルダを取得できません")
    }

    fun getChild(parent: Any, name: String): Any? =
        invokeApi("getChildNode", parent, name)

    suspend fun uploadText(parent: Any, fileName: String, text: String) {
        val dir = File(context.cacheDir, "mega-sync-upload").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(text)
        try {
            transfer("startUpload", file.absolutePath, parent, fileName, -1L, null, false, false, null).requireOk("アップロード")
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun downloadText(node: Any): String {
        val dir = File(context.cacheDir, "mega-sync-download-${UUID.randomUUID()}").apply { mkdirs() }
        val nodeName = (invokeOn(node, "getName") as? String).orEmpty().ifBlank { "scheduler-sync.json" }
        val destination = File(dir, nodeName)
        return try {
            transfer("startDownload", node, destination.absolutePath, null, null, false, null, 5, 1).requireOk("ダウンロード")
            destination.readText()
        } finally {
            runCatching { dir.deleteRecursively() }
        }
    }

    fun observeNodeChanges(onChanged: () -> Unit) {
        if (globalListener != null) return
        val listener = Proxy.newProxyInstance(
            globalListenerClass.classLoader,
            arrayOf(globalListenerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "onNodesUpdate" -> onChanged()
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "NittcMegaGlobalListener"
            }
            defaultReturn(method.returnType)
        }
        invokeApi("addGlobalListener", listener)
        globalListener = listener
    }

    suspend fun logout() {
        runCatching { request("logout").requireOk("ログアウト") }
    }

    fun close() {
        globalListener?.let { listener ->
            runCatching { invokeApi("removeGlobalListener", listener) }
        }
        globalListener = null
    }

    private suspend fun request(methodName: String, vararg args: Any?): RequestResult =
        withTimeout(60_000L) {
            val deferred = CompletableDeferred<RequestResult>()
            val listener = Proxy.newProxyInstance(
                requestListenerClass.classLoader,
                arrayOf(requestListenerClass)
            ) { proxy, method, callbackArgs ->
                when (method.name) {
                    "onRequestFinish" -> {
                        val request = callbackArgs?.getOrNull(1)
                        val error = callbackArgs?.getOrNull(2)
                        deferred.complete(
                            RequestResult(
                                code = error?.let { (invokeOn(it, "getErrorCode") as? Number)?.toInt() } ?: -1,
                                message = error?.let { invokeOn(it, "getErrorString") as? String },
                                request = request
                            )
                        )
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === callbackArgs?.getOrNull(0)
                    "toString" -> return@newProxyInstance "NittcMegaRequestListener"
                }
                defaultReturn(method.returnType)
            }
            invokeApi(methodName, *args, listener)
            deferred.await()
        }

    private suspend fun transfer(methodName: String, vararg args: Any?): TransferResult =
        withTimeout(120_000L) {
            val deferred = CompletableDeferred<TransferResult>()
            val listener = Proxy.newProxyInstance(
                transferListenerClass.classLoader,
                arrayOf(transferListenerClass)
            ) { proxy, method, callbackArgs ->
                when (method.name) {
                    "onTransferFinish" -> {
                        val error = callbackArgs?.getOrNull(2)
                        deferred.complete(
                            TransferResult(
                                code = error?.let { (invokeOn(it, "getErrorCode") as? Number)?.toInt() } ?: -1,
                                message = error?.let { invokeOn(it, "getErrorString") as? String }
                            )
                        )
                    }
                    "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                    "equals" -> return@newProxyInstance proxy === callbackArgs?.getOrNull(0)
                    "toString" -> return@newProxyInstance "NittcMegaTransferListener"
                }
                defaultReturn(method.returnType)
            }
            invokeApi(methodName, *args, listener)
            deferred.await()
        }

    private fun invokeApi(name: String, vararg args: Any?): Any? =
        invokeCompatible(api, name, args)

    private fun invokeOn(target: Any, name: String, vararg args: Any?): Any? =
        invokeCompatible(target, name, args)

    private fun invokeCompatible(target: Any, name: String, args: Array<out Any?>): Any? {
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        val method = methods.firstOrNull { candidate ->
            candidate.parameterTypes.zip(args).all { (parameter, argument) ->
                argument == null && !parameter.isPrimitive ||
                    argument != null && boxed(parameter).isAssignableFrom(argument.javaClass)
            }
        } ?: throw NoSuchMethodException(
            "${target.javaClass.name}.$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})"
        )
        return method.invoke(target, *args)
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> type
    }

    private data class RequestResult(
        val code: Int,
        val message: String?,
        val request: Any?
    ) {
        fun requireOk(operation: String) {
            if (code != 0) throw MegaOperationException(code, "$operation: ${message ?: "MEGA error $code"}")
        }
    }

    private data class TransferResult(
        val code: Int,
        val message: String?
    ) {
        fun requireOk(operation: String) {
            if (code != 0) throw MegaOperationException(code, "$operation: ${message ?: "MEGA error $code"}")
        }
    }
}

private class MegaOperationException(
    val code: Int,
    message: String
) : IllegalStateException(message)

private class CloudSyncPermanentException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private suspend inline fun <T> captureSyncFailure(
    crossinline block: suspend () -> T
): Result<T> = try {
    Result.success(block())
} catch (timeout: TimeoutCancellationException) {
    // withTimeout の期限切れは CancellationException の一種だが、同期の失敗として再試行対象にする。
    Result.failure(timeout)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun Throwable?.unwrapReflectionException(): Throwable? {
    var current = this
    while (current is java.lang.reflect.InvocationTargetException && current.targetException != null) {
        current = current.targetException
    }
    return current
}

private fun defaultReturn(type: Class<*>): Any? = when (type) {
    java.lang.Boolean.TYPE -> false
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Float.TYPE -> 0f
    java.lang.Double.TYPE -> 0.0
    java.lang.Character.TYPE -> '\u0000'
    else -> null
}

private fun Throwable?.userMessage(): String {
    if (this == null) return "不明なエラー"
    val target = unwrapReflectionException() ?: this
    if (target is TimeoutCancellationException) return "通信がタイムアウトしました"
    return target.message?.takeIf { it.isNotBlank() } ?: target.javaClass.simpleName
}
