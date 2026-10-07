package com.example.runwiththewind.wear

import android.Manifest
//noinspection SuspiciousImport
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import androidx.health.services.client.data.ExerciseLapSummary
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque

data class LocationSample(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

class ExerciseService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val healthServicesClient by lazy { HealthServices.getClient(this) }
    private val exerciseClient by lazy { healthServicesClient.exerciseClient }

    private val _exerciseState = MutableStateFlow<ExerciseUpdate?>(null)
    val exerciseState = _exerciseState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

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

    private var splitStartMovingMillis = 0L
    private var splitStartDistance = 0.0
    private var lastSplitIndex = 1

    private var cumulativeDistanceMeters = 0.0
    private var accumulatedGpsDistance = 0.0
    private var accumulatedIntervalDistance = 0.0
    private var lastValidActiveMillis = 0L

    private val locationSamples = ArrayDeque<LocationSample>()
    private var smoothedSpeedMps: Double = 0.0
    private var tickerJob: Job? = null

    private val _workoutHistory = mutableListOf<ExerciseUpdate>()
    val workoutHistory: List<ExerciseUpdate> get() = _workoutHistory

    private var _startTimeMillis = 0L
    val startTimeMillis: Long get() = _startTimeMillis

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseService = this@ExerciseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopExercise()
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
                exerciseClient.prepareExerciseAsync(config).get()
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
                DataType.PACE,
                DataType.SPEED,
                DataType.ELEVATION_GAIN,
                DataType.DISTANCE,
                DataType.DISTANCE_TOTAL
            ))
            .setIsGpsEnabled(true)
            .build()

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        try {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(),
                foregroundServiceType
            )
        } catch (e: Exception) {
            _serviceStatus.value = ServiceStatus.Error("Failed to start foreground service: ${e.message}")
            return
        }

        _isRecording.value = true
        _totalDistance.value = 0.0
        _startTimeMillis = System.currentTimeMillis()
        _serviceStatus.value = ServiceStatus.Starting
        cumulativeDistanceMeters = 0.0
        accumulatedGpsDistance = 0.0
        accumulatedIntervalDistance = 0.0
        lastValidActiveMillis = 0L
        _activeDurationMillis.value = 0L
        locationSamples.clear()
        smoothedSpeedMps = 0.0
        splitStartMovingMillis = 0L
        splitStartDistance = 0.0
        lastSplitIndex = 1
        _currentSplitIndex.value = 1
        _currentSplitDistance.value = 0.0
        _currentSplitPace.value = 0.0
        _currentPace.value = 0.0
        _averagePace.value = 0.0

        exerciseClient.setUpdateCallback(exerciseUpdateCallback)
        startTicker()
        
        serviceScope.launch {
            try {
                prepareJob?.join()
                exerciseClient.startExerciseAsync(config).get()
                _serviceStatus.value = ServiceStatus.Active
            } catch (e: Exception) {
                _serviceStatus.value = ServiceStatus.Error("Exercise failed to start: ${e.message}")
                stopExercise(keepError = true)
            }
        }
    }

    fun pauseExercise() {
        if (!_isRecording.value) return
        exerciseClient.pauseExerciseAsync()
    }

    fun resumeExercise() {
        if (!_isRecording.value) return
        exerciseClient.resumeExerciseAsync()
    }

    fun stopExercise(keepError: Boolean = false) {
        if (!_isRecording.value && _serviceStatus.value == ServiceStatus.Idle) return

        try {
            exerciseClient.endExerciseAsync()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        stopTicker()
        _isRecording.value = false
        if (!keepError) {
            _serviceStatus.value = ServiceStatus.Idle
        }
        _locationAvailability.value = LocationAvailability.UNKNOWN
        _exerciseState.value = null
        _startTimeMillis = 0L
        cumulativeDistanceMeters = 0.0
        accumulatedGpsDistance = 0.0
        accumulatedIntervalDistance = 0.0
        lastValidActiveMillis = 0L
        _activeDurationMillis.value = 0L
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        handoffDataToPhone()
        stopSelf()
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
        val update = _exerciseState.value ?: return
        val checkpoint = update.activeDurationCheckpoint ?: return
        val state = update.exerciseStateInfo.state
        val now = System.currentTimeMillis()

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

        // Average Pace - calculated and updated strictly once per second (single value per second)
        if (activeMillis > 0 && cumulativeDistanceMeters >= 5.0) {
            val rawAvgPace = (activeMillis / 60000.0) / (cumulativeDistanceMeters / 1000.0)
            _averagePace.value = if (rawAvgPace <= 15.0) rawAvgPace else 0.0
        } else {
            _averagePace.value = 0.0
        }
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            _exerciseState.value = update
            _workoutHistory.add(update)

            val checkpoint = update.activeDurationCheckpoint
            val exerciseState = update.exerciseStateInfo.state
            val now = System.currentTimeMillis()
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

            // Total Distance
            update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let { distTotalPoint ->
                if (distTotalPoint.total > cumulativeDistanceMeters) {
                    cumulativeDistanceMeters = distTotalPoint.total
                }
            }

            val distPoints = update.latestMetrics.getData(DataType.DISTANCE)
            if (distPoints.isNotEmpty()) {
                val sumIntervals = distPoints.sumOf { it.value }
                if (sumIntervals > 0.0) {
                    accumulatedIntervalDistance += sumIntervals
                    if (accumulatedIntervalDistance > cumulativeDistanceMeters) {
                        cumulativeDistanceMeters = accumulatedIntervalDistance
                    }
                }
            }

            // Heart Rate
            update.latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.let { hrVal ->
                _heartRate.value = hrVal
            }

            // Elevation Gain
            update.latestMetrics.getData(DataType.ELEVATION_GAIN).lastOrNull()?.let { elevPoint ->
                val v = try {
                    val method = elevPoint.javaClass.getMethod("getValue")
                    method.invoke(elevPoint) as? Double
                } catch (e: Exception) {
                    try {
                        val method = elevPoint.javaClass.getMethod("getTotal")
                        method.invoke(elevPoint) as? Double
                    } catch (e2: Exception) {
                        null
                    }
                } ?: 0.0
                _elevationGain.value = v
            }

            // Speed & Location (8-second window for Current Pace)
            val speedFromHs = try {
                update.latestMetrics.getData(DataType.SPEED).lastOrNull()?.value
            } catch (e: Exception) { null }

            update.latestMetrics.getData(DataType.LOCATION).lastOrNull()?.let { locPoint ->
                val lat = locPoint.value.latitude
                val lng = locPoint.value.longitude
                val accuracy = try {
                    val method = locPoint.javaClass.getMethod("getHorizontalAccuracy")
                    (method.invoke(locPoint) as? Float) ?: 5.0f
                } catch (e: Exception) { 5.0f }

                processLocationUpdate(lat, lng, accuracy, now)
            }

            if (accumulatedGpsDistance > cumulativeDistanceMeters) {
                cumulativeDistanceMeters = accumulatedGpsDistance
            }

            _totalDistance.value = cumulativeDistanceMeters

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

            // Current Pace (8-second rolling window)
            if (exerciseState.isPaused) {
                _currentPace.value = 0.0
                smoothedSpeedMps = 0.0
            } else {
                val windowSpeed = calculateWindowSpeed(locationSamples)
                val activeSpeed = when {
                    speedFromHs != null && speedFromHs in 1.111..8.333 -> speedFromHs
                    windowSpeed in 1.111..8.333 -> windowSpeed
                    else -> 0.0
                }

                if (activeSpeed < 1.111) {
                    smoothedSpeedMps = 0.0
                    _currentPace.value = 0.0
                } else {
                    smoothedSpeedMps = if (smoothedSpeedMps < 1.111) activeSpeed else (0.75 * smoothedSpeedMps + 0.25 * activeSpeed)
                    val paceVal = (1000.0 / smoothedSpeedMps) / 60.0
                    _currentPace.value = if (paceVal <= 15.0) paceVal else 0.0
                }
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

    private fun processLocationUpdate(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        timeMillis: Long
    ) {
        if (accuracy > 25.0f) return

        val lastSample = locationSamples.peekLast()
        if (lastSample != null) {
            val dtSec = (timeMillis - lastSample.timeMillis) / 1000.0
            if (dtSec in 0.2..30.0) {
                val distMeters = calculateDistanceBetween(
                    lastSample.latitude, lastSample.longitude,
                    latitude, longitude
                )
                val impliedSpeed = distMeters / dtSec

                if (impliedSpeed in 0.15..15.0) {
                    accumulatedGpsDistance += distMeters
                }
            }
        }

        val sample = LocationSample(timeMillis, latitude, longitude, accuracy)
        locationSamples.addLast(sample)

        val cutoff = timeMillis - 8_000L
        while (locationSamples.isNotEmpty() && locationSamples.first.timeMillis < cutoff) {
            locationSamples.removeFirst()
        }
    }

    private fun calculateWindowSpeed(samples: ArrayDeque<LocationSample>): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first
        val last = samples.last
        val dtSec = (last.timeMillis - first.timeMillis) / 1000.0
        if (dtSec < 1.5) return 0.0

        var windowDistance = 0.0
        var prev = first
        for (curr in samples) {
            if (curr != first) {
                windowDistance += calculateDistanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                prev = curr
            }
        }

        return windowDistance / dtSec
    }

    private fun calculateDistanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun handoffDataToPhone() {
        serviceScope.launch {
            try {
                val request = PutDataMapRequest.create("/workout_finish").apply {
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                    dataMap.putString("exercise_type", "RUNNING")
                    val summaryData = "Workout Summary: Distance, Heart Rate, Pace metrics successfully captured."
                    val asset = Asset.createFromBytes(summaryData.toByteArray())
                    dataMap.putAsset("workout_asset", asset)
                }.asPutDataRequest().setUrgent()

                Wearable.getDataClient(this@ExerciseService).putDataItem(request)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running Exercise Session",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Run with the Wind")
            .setContentText("Recording your run...")
            .setSmallIcon(R.drawable.run_with_wind)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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
    data class Error(val message: String) : ServiceStatus()
}
