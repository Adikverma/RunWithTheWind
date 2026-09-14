package com.example.runwiththewind.wear

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _locationAvailability = MutableStateFlow(LocationAvailability.UNKNOWN)
    val locationAvailability = _locationAvailability.asStateFlow()

    private val _totalDistance = MutableStateFlow(0.0)
    val totalDistance = _totalDistance.asStateFlow()

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
        if (!isGpsEnabled()) {
            _locationAvailability.value = LocationAvailability.NO_GNSS
            return
        }

        // Avoid re-preparing if already active or preparing
        if (_isRecording.value || _serviceStatus.value is ServiceStatus.Starting) return

        val config = WarmUpConfig(
            ExerciseType.RUNNING,
            setOf(DataType.HEART_RATE_BPM, DataType.LOCATION)
        )

        exerciseClient.setUpdateCallback(exerciseUpdateCallback)
        
        serviceScope.launch {
            try {
                _serviceStatus.value = ServiceStatus.Starting
                exerciseClient.prepareExerciseAsync(config).get()
                // Only set to acquiring if we haven't acquired yet
                if (_locationAvailability.value.id == LocationAvailability.UNKNOWN.id || 
                    _locationAvailability.value.id == LocationAvailability.NO_GNSS.id) {
                    _locationAvailability.value = LocationAvailability.ACQUIRING
                }
            } catch (e: Exception) {
                _serviceStatus.value = ServiceStatus.Error("Failed to prepare sensors: ${e.message}")
            } finally {
                if (_serviceStatus.value is ServiceStatus.Starting) {
                    _serviceStatus.value = ServiceStatus.Idle
                }
            }
        }
    }

    fun startExercise() {
        if (_isRecording.value) return

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

        exerciseClient.setUpdateCallback(exerciseUpdateCallback)
        
        serviceScope.launch {
            try {
                // Using ListenableFuture from startExerciseAsync
                exerciseClient.startExerciseAsync(config).get()
                _serviceStatus.value = ServiceStatus.Active
            } catch (e: Exception) {
                _serviceStatus.value = ServiceStatus.Error("Exercise failed to start: ${e.message}")
                stopExercise()
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

    fun stopExercise() {
        if (!_isRecording.value && _serviceStatus.value == ServiceStatus.Idle) return

        exerciseClient.endExerciseAsync()
        _isRecording.value = false
        _serviceStatus.value = ServiceStatus.Idle
        _locationAvailability.value = LocationAvailability.UNKNOWN
        _exerciseState.value = null
        _startTimeMillis = 0L
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        handoffDataToPhone()
        stopSelf()
    }

    private val exerciseUpdateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            _exerciseState.value = update
            _workoutHistory.add(update)
            update.latestMetrics.getData(DataType.DISTANCE_TOTAL)?.let {
                _totalDistance.value = it.total
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
            .setSmallIcon(android.R.drawable.ic_media_play)
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
