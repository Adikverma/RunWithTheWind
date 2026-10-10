package com.example.runwiththewind.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) && screenState == ScreenState.TRACKING) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_STEM_PRIMARY) && screenState == ScreenState.TRACKING) {
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
        val moving = service.activeDurationMillis.value
        val distance = service.totalDistance.value
        val avgPace = service.averagePace.value

        summaryData = RunSummary(moving, distance, avgPace)
        service.stopExercise()
        screenState = ScreenState.SUMMARY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            !(isBound && exerciseService != null && elapsed >= 1500)
        }
        super.onCreate(savedInstanceState)

        val intent = Intent(this, ExerciseService::class.java)
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

            // Listen for system location provider changes (e.g. toggled via Quick Settings tray)
            DisposableEffect(context) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                            permissionsGranted = checkAllPermissions(context ?: return)
                            gpsEnabled = isGpsEnabled(context)
                        }
                    }
                }
                val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose {
                    try {
                        context.unregisterReceiver(receiver)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Periodic check while on RUN_PREPARE screen to handle quick settings / options tray toggles
            LaunchedEffect(screenState) {
                while (screenState == ScreenState.RUN_PREPARE) {
                    val newGps = isGpsEnabled(context)
                    val newPerms = checkAllPermissions(context)
                    if (newGps != gpsEnabled) gpsEnabled = newGps
                    if (newPerms != permissionsGranted) permissionsGranted = newPerms
                    delay(1000)
                }
            }

            AppScaffold {
                if (isBound && exerciseService != null) {
                    val serviceStatus by exerciseService!!.serviceStatus.collectAsStateWithLifecycle()
                    val locationAvailability by exerciseService!!.locationAvailability.collectAsStateWithLifecycle()

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
                            onTogglePause = {
                                performTogglePause()
                            },
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

                    // Prepare exercise on RUN_PREPARE screen when permissions/GPS are ready
                    LaunchedEffect(isBound, permissionsGranted, gpsEnabled, screenState) {
                        if (screenState == ScreenState.RUN_PREPARE && isBound && exerciseService != null && permissionsGranted && gpsEnabled) {
                            if (exerciseService!!.serviceStatus.value is ServiceStatus.Idle) {
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
    val movingTime: Long,
    val distance: Double,
    val averagePace: Double
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
                        .fillMaxWidth(0.9f)
                        .padding(top = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    onClick = onRunClick
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsRun,
                            contentDescription = "Run",
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Run", textAlign = TextAlign.Center)
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

    var isStarting by remember { mutableStateOf(false) }

    LaunchedEffect(permissionsGranted, gpsEnabled) {
        isStarting = false
    }

    LaunchedEffect(serviceStatus) {
        if (serviceStatus is ServiceStatus.Error) {
            isStarting = false
        }
    }

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
                        .padding(top = 5.dp, bottom = 1.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = gpsIcon,
                        contentDescription = "GPS Status",
                        tint = gpsColor,
                        modifier = Modifier.size(18.dp)
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
                        text = "GPS is disabled.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(3.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            if (serviceStatus is ServiceStatus.Error) {
                item {
                    Text(
                        text = serviceStatus.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(3.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(top = 8.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    enabled = !isStarting && serviceStatus !is ServiceStatus.Starting && serviceStatus !is ServiceStatus.Stopping,
                    onClick = {
                        // Refresh status before acting
                        onRefreshStatus()
                        
                        if (!permissionsGranted) {
                            permissionLauncher.launch(getRequestablePermissions())
                        } else if (!gpsEnabled) {
                            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            context.startActivity(intent)
                        } else {
                            isStarting = true
                            onStart()
                        }
                    }
                ) {
                    Text(
                        text = when {
                            !permissionsGranted -> "Grant Permissions"
                            !gpsEnabled -> "Enable GPS"
                            isStarting -> "Starting..."
                            else -> "Start"
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutScreen(
    service: ExerciseService,
    onTogglePause: () -> Unit,
    onFinish: () -> Unit
) {
    val verticalPagerState = rememberPagerState(pageCount = { 2 })

    VerticalPager(
        state = verticalPagerState,
        modifier = Modifier.fillMaxSize()
    ) { verticalPage ->
        if (verticalPage == 0) {
            MetricsSingleScreen(
                service = service,
                onTogglePause = onTogglePause
            )
        } else {
            ControlsScreen(
                service = service,
                onFinish = onFinish
            )
        }
    }
}

@Composable
fun MetricsSingleScreen(
    service: ExerciseService,
    onTogglePause: () -> Unit
) {
    val pageCount = 3
    var pageIndex by remember { mutableIntStateOf(0) }
    var accumulatedRotaryDelta by remember { mutableFloatStateOf(0f) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    val focusRequester = remember { FocusRequester() }
    val rotaryThreshold = 30f
    val swipeThresholdPx = with(LocalDensity.current) { 35.dp.toPx() }

    val uiState by service.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onTogglePause()
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccum = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount
                    },
                    onDragEnd = {
                        if (dragAccum <= -swipeThresholdPx) {
                            pageIndex = (pageIndex + 1) % pageCount
                        } else if (dragAccum >= swipeThresholdPx) {
                            pageIndex = (pageIndex - 1 + pageCount) % pageCount
                        }
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f }
                )
            }
            .onRotaryScrollEvent { event ->
                accumulatedRotaryDelta += event.verticalScrollPixels
                if (accumulatedRotaryDelta >= rotaryThreshold) {
                    pageIndex = (pageIndex + 1) % pageCount
                    accumulatedRotaryDelta = 0f
                    true
                } else if (accumulatedRotaryDelta <= -rotaryThreshold) {
                    pageIndex = (pageIndex - 1 + pageCount) % pageCount
                    accumulatedRotaryDelta = 0f
                    true
                } else {
                    true
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        when (pageIndex) {
            0 -> Screen1Overview(uiState)
            1 -> Screen2SplitInfo(uiState)
            2 -> Screen3HealthElevation(uiState)
        }

        if (uiState.isPaused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.5.dp, Color.Red, CircleShape)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(pageCount) { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == pageIndex) 6.dp else 4.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pageIndex)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
fun HeroMetricDisplay(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall.copy(
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun Screen1Overview(uiState: WorkoutUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HeroMetricDisplay("Distance [km]", formatDistance(uiState.distanceMeters))
        MetricDisplay("Moving Time", formatTime(uiState.activeMillis))
        MetricDisplay("Avg Pace [min/km]", formatPace(uiState.avgPace))
    }
}

@Composable
fun Screen2SplitInfo(uiState: WorkoutUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HeroMetricDisplay("Distance [km]", formatDistance(uiState.distanceMeters))
        MetricDisplay("Split Pace [min/km]", formatPace(uiState.splitPace))
        MetricDisplay("Pace [min/km]", formatPace(uiState.currentPace))
        MetricDisplay("Moving Time", formatTime(uiState.activeMillis))
    }
}

@Composable
fun Screen3HealthElevation(uiState: WorkoutUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HeroMetricDisplay(
            "Heart Rate",
            if (uiState.heartRate > 0) "%.0f BPM".format(uiState.heartRate) else "-- BPM"
        )
        MetricDisplay("Elevation Gain", "%.1f m".format(uiState.elevationGain))
        MetricDisplay("Elapsed Time", formatTime(uiState.elapsedMillis))
    }
}

@Composable
fun ControlsScreen(
    service: ExerciseService,
    onFinish: () -> Unit
) {
    val exerciseUpdate by service.exerciseState.collectAsStateWithLifecycle()
    val exerciseState = exerciseUpdate?.exerciseStateInfo?.state ?: ExerciseState.ACTIVE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Controls",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (exerciseState.isPaused) {
            Button(
                onClick = { service.resumeExercise() },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 4.dp)
            ) {
                Text("Resume", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Button(
                onClick = { service.pauseExercise() },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(vertical = 4.dp)
            ) {
                Text("Pause", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        Button(
            onClick = onFinish,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Finish", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    Text(text = "Run Summary")
                }
            }
            item {
                MetricDisplay("Distance Covered", "${formatDistance(summary.distance)} km")
            }
            item {
                MetricDisplay("Moving Time Taken", formatTime(summary.movingTime))
            }
            item {
                MetricDisplay("Average Pace", "${formatPace(summary.averagePace)} min/km")
            }
            item {
                Button(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(top = 12.dp)
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    onClick = onRecord
                ) {
                    Text("Done", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

fun formatDistance(meters: Double): String {
    return "%.2f".format(meters / 1000.0)
}

fun formatTime(millis: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun formatPace(paceMinPerKm: Double, maxCutoff: Double = 15.0): String {
    if (paceMinPerKm <= 0.0 || paceMinPerKm > maxCutoff || paceMinPerKm.isInfinite() || paceMinPerKm.isNaN()) return "--:--"
    val totalSeconds = Math.round(paceMinPerKm * 60.0).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds.coerceIn(0, 59))
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.run_with_wind),
            contentDescription = "Run with the Wind Logo",
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

fun getBodySensorsPermission(): String {
    return if (Build.VERSION.SDK_INT >= 36) {
        "android.permission.health.READ_HEART_RATE"
    } else {
        Manifest.permission.BODY_SENSORS
    }
}

fun checkAllPermissions(context: Context): Boolean {
    val required = arrayOf(
        getBodySensorsPermission(),
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION
    )
    return required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

fun getRequestablePermissions(): Array<String> {
    val list = mutableListOf(
        getBodySensorsPermission(),
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
        Manifest.permission.POST_NOTIFICATIONS
    )
    return list.toTypedArray()
}
