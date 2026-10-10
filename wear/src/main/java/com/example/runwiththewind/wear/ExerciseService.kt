package com.example.runwiththewind.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.runwiththewind.wear.data.ActivityRecorder
import com.example.runwiththewind.wear.data.Sample
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database by lazy { (application as MainApplication).database }
    private val recorder by lazy { ActivityRecorder(filesDir, database.activityDao(), serviceScope) }

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
    private var prepareJob: Job? = null

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
    private var tickerJob: Job? = null

    private var _startTimeMillis = 0L
    val startTimeMillis: Long get() = _startTimeMillis

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseService = this@ExerciseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            recorder.recoverInterrupted()
            recorder.pruneOld()
            runCatching {
                exerciseClient.endExerciseAsync().await()
            }
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
            e.printStackTrace()
        }
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isRecording.value || _serviceStatus.value is ServiceStatus.Active) {
            stopExercise()
        }
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        if (!isGpsEnabled()) {
            _locationAvailability.value = LocationAvailability.NO_GNSS
            return
        }

        if (_isRecording.value || _serviceStatus.value is ServiceStatus.Starting || _isPreparing.value) return

        val config = WarmUpConfig(
            ExerciseType.RUNNING,
            setOf(DataType.HEART_RATE_BPM, DataType.LOCATION)
        )

        exerciseClient.setUpdateCallback(exerciseUpdateCallback)

        prepareJob = serviceScope.launch {
            _isPreparing.value = true
            try {
                exerciseClient.prepareExerciseAsync(config).await()
                if (_locationAvailability.value.id == LocationAvailability.UNKNOWN.id ||
                    _locationAvailability.value.id == LocationAvailability.NO_GNSS.id) {
                    _locationAvailability.value = LocationAvailability.ACQUIRING
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isPreparing.value = false
            }
        }
    }

    fun startExercise() {
        if (_isRecording.value || _serviceStatus.value is ServiceStatus.Starting) return

        startService(Intent(this, ExerciseService::class.java))

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            _serviceStatus.value = ServiceStatus.Error("Location permission missing")
            return
        }

        if (!isGpsEnabled()) {
            _serviceStatus.value = ServiceStatus.Error("GPS is disabled")
            return
        }

        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(setOf(
                DataType.HEART_RATE_BPM,
                DataType.LOCATION,
                DataType.SPEED,
                DataType.ELEVATION_GAIN_TOTAL,
                DataType.DISTANCE_TOTAL
            ))
            .setIsGpsEnabled(true)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        val notification = createNotification()
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
        } catch (e: Exception) {
            _serviceStatus.value = ServiceStatus.Error("Failed to start foreground service: ${e.message}")
            return
        }

        _isRecording.value = true
        _isPaused.value = false
        recorder.begin(_startTimeMillis)
        _totalDistance.value = 0.0
        _startTimeMillis = System.currentTimeMillis()
        _serviceStatus.value = ServiceStatus.Starting
        cumulativeDistanceMeters = 0.0
        lastValidActiveMillis = 0L
        _activeDurationMillis.value = 0L
        _elapsedDurationMillis.value = 0L
        splitStartMovingMillis = 0L
        splitStartDistance = 0.0
        lastSplitIndex = 1
        _currentSplitIndex.value = 1
        _currentSplitDistance.value = 0.0
        _currentSplitPace.value = 0.0
        _currentPace.value = 0.0
        _averagePace.value = 0.0

        updateUiState()

        exerciseClient.setUpdateCallback(exerciseUpdateCallback)
        startTicker()

        serviceScope.launch {
            try {
                prepareJob?.join()
                exerciseClient.startExerciseAsync(config).await()
                _serviceStatus.value = ServiceStatus.Active
            } catch (e: Exception) {
                _serviceStatus.value = ServiceStatus.Error("Exercise failed to start: ${e.message}")
                stopExercise(keepError = true)
            }
        }
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
                e.printStackTrace()
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
                e.printStackTrace()
            }
        }
    }
    fun stopExercise(keepError: Boolean = false) {
        if (!_isRecording.value && _serviceStatus.value == ServiceStatus.Idle) return
        if (_serviceStatus.value is ServiceStatus.Stopping) return

        _serviceStatus.value = ServiceStatus.Stopping

        val moving = _activeDurationMillis.value
        val elapsed = _elapsedDurationMillis.value
        val dist = cumulativeDistanceMeters
        val avgPace = _averagePace.value
        val elev = _elevationGain.value
        val finalHr = _heartRate.value.takeIf { it > 0 }

        _isRecording.value = false
        _isPaused.value = false
        if (!keepError) {
            _serviceStatus.value = ServiceStatus.Idle
        }
        _locationAvailability.value = LocationAvailability.UNKNOWN
        _exerciseState.value = null
        _startTimeMillis = 0L
        cumulativeDistanceMeters = 0.0
        lastValidActiveMillis = 0L
        _activeDurationMillis.value = 0L
        _elapsedDurationMillis.value = 0L
        updateUiState()

        serviceScope.launch {
            try {
                stopTicker()
                runCatching { exerciseClient.endExerciseAsync().await() }

                recorder.finish {
                    copy(
                        endEpochMs = System.currentTimeMillis(),
                        movingMs = moving,
                        elapsedMs = elapsed,
                        distanceM = dist,
                        avgPace = avgPace,
                        avgHr = finalHr,
                        elevationGainM = elev
                    )
                }
                recorder.pruneOld()

                handoffDataToPhone(
                    activeDurationMillis = moving,
                    distanceMeters = dist,
                    avgPace = avgPace,
                    heartRate = finalHr ?: 0.0,
                    elevationGain = elev
                )

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                e.printStackTrace()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (true) {
                updateMetricsTicker()
                delay(1000)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun updateMetricsTicker() {
        val now = System.currentTimeMillis()
        if (_isRecording.value && _startTimeMillis > 0L) {
            _elapsedDurationMillis.value = (now - _startTimeMillis).coerceAtLeast(0L)
        }

        val update = _exerciseState.value ?: run {
            updateUiState()
            return
        }
        val checkpoint = update.activeDurationCheckpoint ?: run {
            updateUiState()
            return
        }
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

        // Average Pace - calculated strictly once per second
        if (activeMillis > 0 && cumulativeDistanceMeters >= 5.0) {
            val rawAvgPace = (activeMillis / 60000.0) / (cumulativeDistanceMeters / 1000.0)
            _averagePace.value = if (rawAvgPace <= 15.0) rawAvgPace else 0.0
        } else {
            _averagePace.value = 0.0
        }

        updateUiState()
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
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

            // Total Distance (Health Services fused DISTANCE_TOTAL)
            update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let { distTotalPoint ->
                if (distTotalPoint.total > cumulativeDistanceMeters) {
                    cumulativeDistanceMeters = distTotalPoint.total
                }
            }

            _totalDistance.value = cumulativeDistanceMeters

            // Heart Rate
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { hrVal ->
                _heartRate.value = hrVal
            }

            // Elevation Gain Total
            update.latestMetrics.getData(DataType.ELEVATION_GAIN_TOTAL)?.let { elevPoint ->
                _elevationGain.value = elevPoint.total
            }

            // Speed & Current Pace
            val speedFromHs = update.latestMetrics.getData(DataType.SPEED).lastOrNull()?.value
            if (exerciseState.isPaused) {
                _currentPace.value = 0.0
            } else if (speedFromHs != null && speedFromHs in 1.111..8.333) {
                val paceVal = (1000.0 / speedFromHs) / 60.0
                _currentPace.value = if (paceVal <= 15.0) paceVal else 0.0
            } else if (speedFromHs != null && speedFromHs < 1.111) {
                _currentPace.value = 0.0
            }

            // 1KM Split Tracking
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

            val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            val hrValInt = _heartRate.value.takeIf { it > 0 }?.toInt()
            val speedVal = speedFromHs?.toFloat()
            update.latestMetrics.getData(DataType.LOCATION).forEach { p ->
                recorder.add(
                    Sample(
                        t = p.getTimeInstant(boot).toEpochMilli(),
                        lat = p.value.latitude,
                        lon = p.value.longitude,
                        alt = p.value.altitude.takeUnless { it.isNaN() },
                        hr = hrValInt,
                        dist = cumulativeDistanceMeters,
                        spd = speedVal
                    )
                )
            }
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

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private suspend fun handoffDataToPhone(
        activeDurationMillis: Long,
        distanceMeters: Double,
        avgPace: Double,
        heartRate: Double,
        elevationGain: Double
    ) {
        try {
            val request = PutDataMapRequest.create("/workout_finish").apply {
                dataMap.putLong("timestamp", System.currentTimeMillis())
                dataMap.putString("exercise_type", "RUNNING")
                dataMap.putLong("active_duration_ms", activeDurationMillis)
                dataMap.putDouble("distance_m", distanceMeters)
                dataMap.putDouble("avg_pace", avgPace)
                dataMap.putDouble("heart_rate", heartRate)
                dataMap.putDouble("elevation_gain", elevationGain)
                val summaryData = "Workout Summary: Distance=%.2f m, Duration=%d ms, AvgPace=%.2f, HR=%.1f, Elev=%.1f".format(
                    distanceMeters, activeDurationMillis, avgPace, heartRate, elevationGain
                )
                val asset = Asset.createFromBytes(summaryData.toByteArray())
                dataMap.putAsset("workout_asset", asset)
            }.asPutDataRequest().setUrgent()

            withContext(Dispatchers.IO) {
                Tasks.await(Wearable.getDataClient(this@ExerciseService).putDataItem(request))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            e.printStackTrace()
        }

        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "exercise_channel"
        private const val NOTIFICATION_ID = 1001
    }
}

sealed class ServiceStatus {
    object Idle : ServiceStatus()
    object Starting : ServiceStatus()
    object Active : ServiceStatus()
    object Stopping : ServiceStatus()
    data class Error(val message: String) : ServiceStatus()
}
