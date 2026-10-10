package com.example.runwiththewind.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.example.runwiththewind.wear.data.ActivityRecorder
import com.example.runwiththewind.wear.data.RecordingSession
import com.example.runwiththewind.wear.data.Sample
import com.example.runwiththewind.wear.data.SportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

@Immutable
data class WorkoutUiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val activeMillis: Long = 0L,
    val elapsedMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val currentPace: Double = 0.0,
    val avgPace: Double = 0.0,
    val splitPace: Double = 0.0,
    val heartRate: Double = 0.0,
    val elevationGain: Double = 0.0
)

class ExerciseService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()

    private val recorder by lazy {
        ActivityRecorder(filesDir, (application as MainApplication).database.activityDao())
    }
    private var session: RecordingSession? = null

    private val healthServicesClient by lazy { HealthServices.getClient(this) }
    private val exerciseClient by lazy { healthServicesClient.exerciseClient }

    private val _exerciseState = MutableStateFlow<ExerciseUpdate?>(null)
    val exerciseState = _exerciseState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState = _uiState.asStateFlow()

    private val _serviceStatus = MutableStateFlow<ServiceStatus>(ServiceStatus.Idle)
    val serviceStatus = _serviceStatus.asStateFlow()

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing = _isPreparing.asStateFlow()

    private val _locationAvailability = MutableStateFlow(LocationAvailability.UNKNOWN)
    val locationAvailability = _locationAvailability.asStateFlow()

    private val _totalDistance = MutableStateFlow(0.0)
    val totalDistance = _totalDistance.asStateFlow()

    private val _heartRate = MutableStateFlow(0.0)
    val heartRate = _heartRate.asStateFlow()

    private val _elevationGain = MutableStateFlow(0.0)
    val elevationGain = _elevationGain.asStateFlow()

    private val _currentPace = MutableStateFlow(0.0)
    val currentPace = _currentPace.asStateFlow()

    private val _averagePace = MutableStateFlow(0.0)
    val averagePace = _averagePace.asStateFlow()

    private val _currentSplitDistance = MutableStateFlow(0.0)
    val currentSplitDistance = _currentSplitDistance.asStateFlow()

    private val _currentSplitPace = MutableStateFlow(0.0)
    val currentSplitPace = _currentSplitPace.asStateFlow()

    private val _currentSplitIndex = MutableStateFlow(1)
    val currentSplitIndex = _currentSplitIndex.asStateFlow()

    private val _activeDurationMillis = MutableStateFlow(0L)
    val activeDurationMillis = _activeDurationMillis.asStateFlow()

    private val _elapsedDurationMillis = MutableStateFlow(0L)
    val elapsedDurationMillis = _elapsedDurationMillis.asStateFlow()

    private var splitStartMovingMillis = 0L
    private var splitStartDistance = 0.0
    private var lastSplitIndex = 1

    private var cumulativeDistanceMeters = 0.0
    private var lastValidActiveMillis = 0L
    private var tickJob: Job? = null
    private var latestFix: Sample? = null
    private var latestSpeed: Float? = null

    private var _startTimeMillis = 0L
    val startTimeMillis: Long get() = _startTimeMillis

    private val exerciseConfig by lazy {
        ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(
                setOf(
                    DataType.HEART_RATE_BPM,
                    DataType.LOCATION,
                    DataType.SPEED,
                    DataType.ELEVATION_GAIN_TOTAL,
                    DataType.DISTANCE_TOTAL
                )
            )
            .setIsGpsEnabled(true)
            .build()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseService = this@ExerciseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            lifecycleMutex.withLock {
                recorder.recoverInterrupted(activeId = session?.id)
                recorder.pruneOld()
                endStaleHealthExercise()
            }
        }
    }

    @Suppress("RestrictedApi")
    private suspend fun endStaleHealthExercise() {
        val info = runCatching { exerciseClient.getCurrentExerciseInfoAsync().await() }.getOrNull() ?: return
        if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
            runCatching { exerciseClient.endExerciseAsync().await() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
        try {
            exerciseClient.clearUpdateCallbackAsync(exerciseUpdateCallback)
        } catch (e: Exception) {
            Log.w(TAG, "clearUpdateCallbackAsync failed", e)
        }
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopExercise()
    }

    private fun updateUiState() {
        _uiState.value = WorkoutUiState(
            isRecording = _isRecording.value,
            isPaused = _isPaused.value,
            activeMillis = _activeDurationMillis.value,
            elapsedMillis = _elapsedDurationMillis.value,
            distanceMeters = cumulativeDistanceMeters,
            currentPace = _currentPace.value,
            avgPace = _averagePace.value,
            splitPace = _currentSplitPace.value,
            heartRate = _heartRate.value,
            elevationGain = _elevationGain.value
        )
    }

    fun prepareExercise() {
        serviceScope.launch {
            lifecycleMutex.withLock {
                if (session != null || _isPreparing.value) return@withLock
                if (!checkAllPermissions(this@ExerciseService)) return@withLock
                if (!isGpsEnabled()) {
                    _locationAvailability.value = LocationAvailability.NO_GNSS
                    return@withLock
                }
                exerciseClient.setUpdateCallback(exerciseUpdateCallback)
                _isPreparing.value = true
                runCatching {
                    exerciseClient.prepareExerciseAsync(
                        WarmUpConfig(ExerciseType.RUNNING, setOf(DataType.HEART_RATE_BPM, DataType.LOCATION))
                    ).await()
                }.onFailure { Log.w(TAG, "prepare failed", it) }
                _isPreparing.value = false
            }
        }
    }

    fun startExercise() {
        serviceScope.launch { lifecycleMutex.withLock { beginRun() } }
    }

    fun stopExercise() {
        serviceScope.launch { lifecycleMutex.withLock { endRun(discard = false) } }
    }

    private suspend fun beginRun() {
        if (session != null) return
        if (!checkAllPermissions(this)) {
            _serviceStatus.value = ServiceStatus.Error("Permissions missing")
            return
        }
        if (!isGpsEnabled()) {
            _serviceStatus.value = ServiceStatus.Error("GPS is disabled")
            return
        }

        val startMs = System.currentTimeMillis()
        val rec = recorder.start(startMs, serviceScope, SportType.RUN)
        try {
            startService(Intent(this, ExerciseService::class.java))
            startForeground(NOTIFICATION_ID, createNotification(), foregroundServiceType())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            rec.discard()
            _serviceStatus.value = ServiceStatus.Error("Could not start service")
            return
        }

        session = rec
        resetMetrics(startMs)
        _isRecording.value = true
        _isPaused.value = false
        _serviceStatus.value = ServiceStatus.Starting
        updateUiState()
        exerciseClient.setUpdateCallback(exerciseUpdateCallback)

        try {
            exerciseClient.startExerciseAsync(exerciseConfig).await()
            _serviceStatus.value = ServiceStatus.Active
            startTicker()
        } catch (e: Exception) {
            Log.e(TAG, "startExerciseAsync failed", e)
            endRun(discard = true, error = "Exercise failed to start")
        }
    }

    private suspend fun endRun(discard: Boolean, error: String? = null) {
        val rec = session ?: return
        session = null
        stopTicker()

        val moving = _activeDurationMillis.value
        val elapsed = _elapsedDurationMillis.value
        val distance = cumulativeDistanceMeters
        val pace = _averagePace.value
        val elev = _elevationGain.value
        val hr = _heartRate.value.takeIf { it > 0 }

        _isRecording.value = false
        _isPaused.value = false
        _exerciseState.value = null
        _locationAvailability.value = LocationAvailability.UNKNOWN
        resetMetrics(0L)
        updateUiState()

        runCatching { exerciseClient.endExerciseAsync().await() }
            .onFailure { Log.w(TAG, "endExerciseAsync failed", it) }

        if (discard) {
            rec.discard()
        } else {
            rec.finish {
                copy(
                    endEpochMs = System.currentTimeMillis(),
                    movingMs = moving,
                    elapsedMs = elapsed,
                    distanceM = distance,
                    avgPace = pace,
                    avgHr = hr,
                    elevationGainM = elev
                )
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        _serviceStatus.value = error?.let { ServiceStatus.Error(it) } ?: ServiceStatus.Idle
        stopSelf()
    }

    fun pauseExercise() {
        if (!_isRecording.value) return
        serviceScope.launch {
            try {
                exerciseClient.pauseExerciseAsync().await()
                _isPaused.value = true
                _currentPace.value = 0.0
                updateUiState()
            } catch (e: Exception) {
                Log.w(TAG, "pauseExerciseAsync failed", e)
            }
        }
    }

    fun resumeExercise() {
        if (!_isRecording.value) return
        serviceScope.launch {
            try {
                exerciseClient.resumeExerciseAsync().await()
                _isPaused.value = false
                updateUiState()
            } catch (e: Exception) {
                Log.w(TAG, "resumeExerciseAsync failed", e)
            }
        }
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = serviceScope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (_isRecording.value && _startTimeMillis > 0L) {
            _elapsedDurationMillis.value = (now - _startTimeMillis).coerceAtLeast(0L)
        }
        recomputeActiveTime(now)
        updateUiState()

        val fix = latestFix?.takeIf { now - it.t < 5_000L }
        session?.add(
            Sample(
                t = now,
                lat = fix?.lat,
                lon = fix?.lon,
                alt = fix?.alt,
                hr = _heartRate.value.takeIf { it > 0 }?.toInt(),
                dist = cumulativeDistanceMeters,
                spd = latestSpeed,
                mv = _activeDurationMillis.value,
                eg = _elevationGain.value
            )
        )
    }

    private fun recomputeActiveTime(now: Long) {
        if (!_isRecording.value || _startTimeMillis == 0L) return

        val update = _exerciseState.value ?: return
        val checkpoint = update.activeDurationCheckpoint ?: return
        val state = update.exerciseStateInfo.state

        val activeMillis = if (state == ExerciseState.ACTIVE) {
            val delta = (now - checkpoint.time.toEpochMilli()).coerceAtLeast(0L)
            (checkpoint.activeDuration.toMillis() + delta).coerceAtLeast(0L)
        } else {
            checkpoint.activeDuration.toMillis().coerceAtLeast(0L)
        }

        if (activeMillis > 0L) {
            lastValidActiveMillis = activeMillis
        }
        _activeDurationMillis.value = activeMillis

        if (activeMillis > 0 && cumulativeDistanceMeters >= 5.0) {
            val rawAvgPace = (activeMillis / 60000.0) / (cumulativeDistanceMeters / 1000.0)
            _averagePace.value = if (rawAvgPace <= 15.0) rawAvgPace else 0.0
        } else {
            _averagePace.value = 0.0
        }
    }

    private fun resetMetrics(startMs: Long) {
        _startTimeMillis = startMs
        cumulativeDistanceMeters = 0.0
        lastValidActiveMillis = 0L
        _activeDurationMillis.value = 0L
        _elapsedDurationMillis.value = 0L
        _currentPace.value = 0.0
        _averagePace.value = 0.0
        _currentSplitPace.value = 0.0
        _currentSplitDistance.value = 0.0
        _currentSplitIndex.value = 1
        _heartRate.value = 0.0
        _elevationGain.value = 0.0
        _totalDistance.value = 0.0
        splitStartMovingMillis = 0L
        splitStartDistance = 0.0
        lastSplitIndex = 1
        latestFix = null
        latestSpeed = null
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            serviceScope.launch { handleUpdate(update) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onRegistered() {}

        override fun onRegistrationFailed(throwable: Throwable) {}

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (dataType == DataType.LOCATION && availability is LocationAvailability) {
                _locationAvailability.value = availability
            }
        }
    }

    private fun handleUpdate(update: ExerciseUpdate) {
        _exerciseState.value = update

        val checkpoint = update.activeDurationCheckpoint
        val exerciseState = update.exerciseStateInfo.state
        _isPaused.value = exerciseState.isPaused

        val now = System.currentTimeMillis()
        if (_startTimeMillis > 0L) {
            _elapsedDurationMillis.value = (now - _startTimeMillis).coerceAtLeast(0L)
        }
        val activeMillis = if (checkpoint != null) {
            val calculated = if (exerciseState == ExerciseState.ACTIVE) {
                val delta = now - checkpoint.time.toEpochMilli()
                (checkpoint.activeDuration.toMillis() + delta).coerceAtLeast(0L)
            } else {
                checkpoint.activeDuration.toMillis().coerceAtLeast(0L)
            }
            if (calculated > 0L) lastValidActiveMillis = calculated
            calculated
        } else {
            lastValidActiveMillis
        }

        update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let { distTotalPoint ->
            if (distTotalPoint.total > cumulativeDistanceMeters) {
                cumulativeDistanceMeters = distTotalPoint.total
            }
        }

        _totalDistance.value = cumulativeDistanceMeters

        update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { hrVal ->
            _heartRate.value = hrVal
        }

        update.latestMetrics.getData(DataType.ELEVATION_GAIN_TOTAL)?.let { elevPoint ->
            _elevationGain.value = elevPoint.total
        }

        val speedFromHs = update.latestMetrics.getData(DataType.SPEED).lastOrNull()?.value
        if (exerciseState.isPaused) {
            _currentPace.value = 0.0
        } else if (speedFromHs != null && speedFromHs in 1.111..8.333) {
            val paceVal = (1000.0 / speedFromHs) / 60.0
            _currentPace.value = if (paceVal <= 15.0) paceVal else 0.0
        } else if (speedFromHs != null && speedFromHs < 1.111) {
            _currentPace.value = 0.0
        }

        val splitIdx = (cumulativeDistanceMeters / 1000.0).toInt() + 1
        if (splitIdx > lastSplitIndex) {
            lastSplitIndex = splitIdx
            splitStartMovingMillis = activeMillis
            splitStartDistance = (splitIdx - 1) * 1000.0
        }

        _currentSplitIndex.value = splitIdx
        val currentSplitDistMeters = (cumulativeDistanceMeters - splitStartDistance).coerceAtLeast(0.0)
        _currentSplitDistance.value = currentSplitDistMeters

        val currentSplitActiveMillis = (activeMillis - splitStartMovingMillis).coerceAtLeast(0L)
        if (currentSplitActiveMillis > 0 && currentSplitDistMeters >= 15.0) {
            val splitPaceVal = (currentSplitActiveMillis / 60000.0) / (currentSplitDistMeters / 1000.0)
            _currentSplitPace.value = if (splitPaceVal <= 15.0) splitPaceVal else 0.0
        } else {
            _currentSplitPace.value = _currentPace.value
        }

        updateUiState()

        update.latestMetrics.getData(DataType.LOCATION).lastOrNull()?.let { p ->
            val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            latestFix = Sample(
                t = p.getTimeInstant(boot).toEpochMilli(),
                lat = p.value.latitude,
                lon = p.value.longitude,
                alt = p.value.altitude.takeUnless { it.isNaN() }
            )
        }
        latestSpeed = speedFromHs?.toFloat()
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running Exercise Session",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Run with the Wind")
            .setContentText("Recording your run...")
            .setSmallIcon(R.drawable.ic_run_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        try {
            val ongoingActivity = OngoingActivity.Builder(
                this,
                NOTIFICATION_ID,
                builder
            )
                .setTouchIntent(pendingIntent)
                .setStatus(Status.Builder().addTemplate("Running").build())
                .build()

            ongoingActivity.apply(this)
        } catch (e: Exception) {
            Log.w(TAG, "OngoingActivity build failed", e)
        }

        return builder.build()
    }

    companion object {
        private const val TAG = "ExerciseService"
        private const val CHANNEL_ID = "exercise_channel"
        private const val NOTIFICATION_ID = 1001
    }
}

sealed class ServiceStatus {
    object Idle : ServiceStatus()
    object Starting : ServiceStatus()
    object Active : ServiceStatus()
    data class Error(val message: String) : ServiceStatus()
}
