package com.example.runwiththewind.wear

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.LocationAvailability
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var exerciseService: ExerciseService? = null
    private var isBound by mutableStateOf(false)

    private var screenState by mutableStateOf(ScreenState.ACTIVITIES)
    private var summaryData by mutableStateOf<RunSummary?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ExerciseService.LocalBinder
            exerciseService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            exerciseService = null
            isBound = false
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && screenState == ScreenState.TRACKING) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && screenState == ScreenState.TRACKING) {
            performTogglePause()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun performTogglePause() {
        val service = exerciseService ?: return
        val update = service.exerciseState.value
        val state = update?.exerciseStateInfo?.state
        if (state?.isPaused == true) {
            service.resumeExercise()
        } else {
            service.pauseExercise()
        }
    }

    private fun performFinish() {
        val service = exerciseService ?: return
        val update = service.exerciseState.value ?: return

        val elapsed = if (service.startTimeMillis > 0) System.currentTimeMillis() - service.startTimeMillis else 0L

        val checkpoint = update.activeDurationCheckpoint
        val state = update.exerciseStateInfo.state
        val moving = if (checkpoint != null && state == ExerciseState.ACTIVE) {
            val delta = System.currentTimeMillis() - checkpoint.time.toEpochMilli()
            checkpoint.activeDuration.toMillis() + delta
        } else {
            checkpoint?.activeDuration?.toMillis() ?: 0L
        }

        val distance = service.totalDistance.value

        summaryData = RunSummary(elapsed, moving, distance)
        service.stopExercise()
        screenState = ScreenState.SUMMARY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, ExerciseService::class.java)
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)

        setContent {
            val context = LocalContext.current
            var permissionsGranted by remember {
                mutableStateOf(checkAllPermissions(context))
            }
            var gpsEnabled by remember {
                mutableStateOf(isGpsEnabled(context))
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        permissionsGranted = checkAllPermissions(context)
                        gpsEnabled = isGpsEnabled(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            AppScaffold {
                if (isBound && exerciseService != null) {
                    val serviceStatus by exerciseService!!.serviceStatus.collectAsState()
                    val locationAvailability by exerciseService!!.locationAvailability.collectAsState()

                    BackHandler(enabled = screenState != ScreenState.ACTIVITIES) {
                        when (screenState) {
                            ScreenState.RUN_PREPARE -> screenState = ScreenState.ACTIVITIES
                            ScreenState.SUMMARY -> screenState = ScreenState.ACTIVITIES
                            ScreenState.TRACKING -> {
                                // Stay on tracking or handle confirmation
                            }
                            else -> {}
                        }
                    }

                    when (screenState) {
                        ScreenState.ACTIVITIES -> ActivitiesScreen {
                            screenState = ScreenState.RUN_PREPARE
                        }
                        ScreenState.RUN_PREPARE -> RunPrepareScreen(
                            permissionsGranted = permissionsGranted,
                            gpsEnabled = gpsEnabled,
                            locationAvailability = locationAvailability,
                            serviceStatus = serviceStatus,
                            onStart = {
                                exerciseService?.startExercise()
                            },
                            onRefreshStatus = {
                                permissionsGranted = checkAllPermissions(context)
                                gpsEnabled = isGpsEnabled(context)
                            }
                        )
                        ScreenState.TRACKING -> WorkoutScreen(
                            service = exerciseService!!,
                            onFinish = {
                                performFinish()
                            }
                        )
                        ScreenState.SUMMARY -> SummaryScreen(
                            summary = summaryData!!,
                            onRecord = {
                                screenState = ScreenState.ACTIVITIES
                            }
                        )
                    }

                    // Automatically switch to tracking if service becomes active
                    LaunchedEffect(serviceStatus) {
                        if (serviceStatus is ServiceStatus.Active && screenState == ScreenState.RUN_PREPARE) {
                            screenState = ScreenState.TRACKING
                        }
                    }

                    // Prepare exercise when entering RUN_PREPARE
                    LaunchedEffect(screenState, permissionsGranted, gpsEnabled) {
                        if (screenState == ScreenState.RUN_PREPARE && permissionsGranted && gpsEnabled) {
                            if (serviceStatus is ServiceStatus.Idle) {
                                exerciseService?.prepareExercise()
                            }
                        }
                    }
                } else {
                    LoadingScreen()
                }
            }
        }
    }

    private fun checkAllPermissions(context: Context): Boolean {
        return arrayOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        ).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isGpsEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }
}

enum class ScreenState {
    ACTIVITIES, RUN_PREPARE, TRACKING, SUMMARY
}

data class RunSummary(
    val elapsedTime: Long,
    val movingTime: Long,
    val distance: Double
)

@Composable
fun ActivitiesScreen(onRunClick: () -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(text = "Activities")
                }
            }
            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    onClick = onRunClick
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDescription = "Run",
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Run")
                    }
                }
            }
        }
    }
}

@Composable
fun RunPrepareScreen(
    permissionsGranted: Boolean,
    gpsEnabled: Boolean,
    locationAvailability: LocationAvailability,
    serviceStatus: ServiceStatus,
    onStart: () -> Unit,
    onRefreshStatus: () -> Unit
) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        onRefreshStatus()
    }

    val gpsColor = when {
        !gpsEnabled -> Color.Red
        locationAvailability == LocationAvailability.ACQUIRED_UNTETHERED ||
                locationAvailability == LocationAvailability.ACQUIRED_TETHERED -> Color.Green
        locationAvailability == LocationAvailability.ACQUIRING -> Color.Yellow
        else -> Color.Yellow // Default to searching if enabled but status unknown
    }

    val gpsIcon = when {
        !gpsEnabled -> Icons.Default.GpsOff
        locationAvailability == LocationAvailability.ACQUIRED_UNTETHERED ||
                locationAvailability == LocationAvailability.ACQUIRED_TETHERED -> Icons.Default.GpsFixed
        locationAvailability == LocationAvailability.ACQUIRING -> Icons.Default.GpsNotFixed
        else -> Icons.Default.GpsNotFixed
    }

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = gpsIcon,
                        contentDescription = "GPS Status",
                        tint = gpsColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "GPS Status",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(text = "Run")
                }
            }

            if (!gpsEnabled) {
                item {
                    Text(
                        text = "GPS is disabled. Please enable it in settings.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    onClick = {
                        // Refresh status before acting
                        onRefreshStatus()
                        
                        if (!permissionsGranted) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.BODY_SENSORS,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACTIVITY_RECOGNITION
                                )
                            )
                        } else if (!gpsEnabled) {
                            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            context.startActivity(intent)
                        } else {
                            onStart()
                        }
                    }
                ) {
                    Text(
                        text = when {
                            !permissionsGranted -> "Grant Permissions"
                            !gpsEnabled -> "Enable GPS"
                            serviceStatus is ServiceStatus.Starting -> "Starting..."
                            else -> "Start"
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun WorkoutScreen(
    service: ExerciseService,
    onFinish: (RunSummary) -> Unit
) {
    val isRecording by service.isRecording.collectAsState()
    val exerciseUpdate by service.exerciseState.collectAsState()
    val distance by service.totalDistance.collectAsState()

    val currentTime by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsedTime = if (service.startTimeMillis > 0) currentTime - service.startTimeMillis else 0L

    val exerciseState = exerciseUpdate?.exerciseStateInfo?.state ?: ExerciseState.ACTIVE

    val movingTime = remember(exerciseUpdate, currentTime) {
        val checkpoint = exerciseUpdate?.activeDurationCheckpoint
        if (checkpoint != null && exerciseState == ExerciseState.ACTIVE) {
            val delta = currentTime - checkpoint.time.toEpochMilli()
            checkpoint.activeDuration.toMillis() + delta
        } else {
            checkpoint?.activeDuration?.toMillis() ?: 0L
        }
    }

    val columnState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Tracking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            item {
                MetricDisplay("Elapsed", formatTime(elapsedTime))
            }
            item {
                MetricDisplay("Moving", formatTime(movingTime))
            }
            item {
                MetricDisplay("Distance", "%.2f m".format(distance))
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (exerciseState.isPaused) {
                        Button(
                            onClick = { service.resumeExercise() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Resume")
                        }
                    } else {
                        Button(
                            onClick = { service.pauseExercise() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pause")
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onFinish(RunSummary(elapsedTime, movingTime, distance))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Finish")
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryScreen(summary: RunSummary, onRecord: () -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = "Run Summary",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            item {
                MetricDisplay("Total Elapsed", formatTime(summary.elapsedTime))
            }
            item {
                MetricDisplay("Total Moving", formatTime(summary.movingTime))
            }
            item {
                MetricDisplay("Total Distance", "%.2f m".format(summary.distance))
            }
            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    onClick = onRecord
                ) {
                    Text("Record & Exit")
                }
            }
        }
    }
}

@Composable
fun MetricDisplay(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

fun formatTime(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
fun LoadingScreen() {
    val columnState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = contentPadding
        ) {
            item {
                Text(text = "Initializing service...", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
