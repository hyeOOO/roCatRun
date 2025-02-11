package com.eeos.rocatrun.presentation

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import com.eeos.rocatrun.R
import com.eeos.rocatrun.component.CircularItemGauge
import com.eeos.rocatrun.receiver.SensorUpdateReceiver
import com.eeos.rocatrun.service.LocationForegroundService
import com.eeos.rocatrun.util.FormatUtils
import com.eeos.rocatrun.viewmodel.BossHealthRepository
import com.eeos.rocatrun.viewmodel.GameViewModel
import com.eeos.rocatrun.viewmodel.MultiUserScreen
import com.eeos.rocatrun.viewmodel.MultiUserViewModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class RunningActivity : ComponentActivity(), SensorEventListener {
    private val gameViewModel: GameViewModel by viewModels()
    private val multiUserViewModel: MultiUserViewModel by viewModels()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private var stepCounter: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var formatUtils = FormatUtils()

    // GPX 관련 변수
    private val locationList = mutableListOf<Location>()
    // 기존의 단순 값 대신 (timestamp, value) 형태로 저장하여 동기화에 활용
    private val heartRateData = mutableListOf<Pair<Long, Int>>()
    private val cadenceData = mutableListOf<Pair<Long, Int>>()
    // 기존 paceList는 그대로 사용하되, 필요하면 timestamp와 함께 저장하는 방식으로 개선 가능
    private val paceList = mutableListOf<Double>()

    // 상태 변수들
    private var totalDistance by mutableDoubleStateOf(0.0)
    private var speed by mutableDoubleStateOf(0.0)
    private var elapsedTime by mutableLongStateOf(0L)
    private var averagePace by mutableDoubleStateOf(0.0)
    private var heartRate by mutableStateOf("---")
    private var averageHeartRate by mutableDoubleStateOf(0.0)
    private var heartRateSum = 0
    private var heartRateCount = 0

    // 걸음 센서 관련 변수
    private var initialStepCount: Int = 0
    private var lastStepCount: Int = 0
    private var lastStepTimestamp = 0L
    private var stepCount = 0  // 누적 걸음 수

    // 절전 모드
    private lateinit var wakeLock: PowerManager.WakeLock

    private var startTime = 0L
    private var isRunning = false
    private var lastLocation: Location? = null

    private val handler = Handler(Looper.getMainLooper())

    // LaunchedEffect에서 데이터 측정 함수 실행을 위한 변수
    private var startTrackingRequested by mutableStateOf(false)

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                elapsedTime = System.currentTimeMillis() - startTime
                averagePace = if (totalDistance > 0) {
                    val paceInSeconds = elapsedTime / 1000.0 / totalDistance
                    paceInSeconds / 60
                } else 0.0

                // 데이터 전송 (아이템 사용 여부에 따라 분기)
                val itemUsed = gameViewModel.itemUsedSignal.value
                Log.d("itemUsedCheck", "체크 : $itemUsed")
                if (itemUsed) {
                    sendDataToPhone(itemUsed = true)
                } else {
                    sendDataToPhone()
                }

                handler.postDelayed(this, 1000)
            }
        }
    }

    // 결과창 노출을 위한 변수
    private var showStats by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val gameViewModel: GameViewModel by viewModels()
            val multiUserViewModel: MultiUserViewModel by viewModels()
            RunningApp(gameViewModel, multiUserViewModel)
        }
        observeStartTrackingState()

        // 절전 모드 방지를 위한 WakeLock 설정
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunningApp::Wakelock")
        wakeLock.acquire(10 * 60 * 1000L)

        // 포그라운드 서비스 시작
        startForegroundService()
        scheduleSensorUpdates()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    if (location.accuracy < 15) {
                        updateLocation(location)
                    } else {
                        Log.d("GPS", "정확도 부족: ${location.accuracy}m, 해당 데이터 무시")
                    }
                }
            }
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepCounter?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("StepCounter", "Step counter sensor registered successfully.")
        } ?: Log.w("StepCounter", "No step counter sensor available.")

        requestPermissions()

        // 게임 종료 이벤트 구독: RunningActivity의 lifecycleScope를 사용하여 구독
        lifecycleScope.launchWhenResumed {
            multiUserViewModel.gameEndEventFlow.collect { gameEnded ->
                if (gameEnded) {
                    // 게임 종료 시 센서 및 위치 업데이트 등 정리 작업 수행
                    gameViewModel.stopFeverTimeEffects()
                    delay(300)
                    stopTracking()
                    // 결과 화면(ResultActivity)으로 전환
                    navigateToResultActivity(this@RunningActivity)
                    Log.d("RunningActivity", "게임 종료 이벤트 수신, 결과 화면으로 전환")
                }
            }
        }
    }


    // 결과 화면으로 전환하는 함수
    private fun navigateToResultActivity(context: Context) {
        val intent = Intent(context, ResultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(intent)
    }

    private fun observeStartTrackingState() {
        handler.post(object : Runnable {
            override fun run() {
                if (startTrackingRequested && !isRunning) {
                    startTracking()
                    startTrackingRequested = false
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    // 포그라운드 서비스 시작
    private fun startForegroundService() {
        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        startForegroundService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        registerHeartRateSensor()
    }

    override fun onPause() {
        super.onPause()
        if (wakeLock.isHeld) {
            Log.e("WakeLock", "WakeLock 확인")
            wakeLock.release()
        }
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
    private fun registerHeartRateSensor() {
        heartRateSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d("HeartRate", "Heart rate sensor registered successfully.")
        } ?: Log.w("HeartRate", "No heart rate sensor available.")
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        if (permissions.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, 0)
        }
    }

    private var lastDistanceUpdate = 0.0  // 마지막 게이지 업데이트 시점

    // 위치 업데이트 후 거리 계산
    private fun updateLocation(location: Location) {
        lastLocation?.let {
            val distanceMoved = it.distanceTo(location) / 1000  // km 단위
            if (distanceMoved > 0.002) {  // 2m 이상 이동한 경우만 반영
                totalDistance += distanceMoved
                speed = location.speed * 3.6

                // 게이지 증가: 1m 이상 이동 시 갱신(750m를 이동하게 변경 예정)
                if (totalDistance - lastDistanceUpdate >= 0.001) {
                    val isFeverTime = gameViewModel.feverTimeActive.value
                    val gaugeIncrement = if (isFeverTime) 2 else 1
                    gameViewModel.increaseItemGauge(gaugeIncrement)
                    lastDistanceUpdate = totalDistance
                    if (gameViewModel.itemGaugeValue.value == 100) {
                        gameViewModel.handleGaugeFull(this)
                    }
                }
            }
        }
        lastLocation = location
        locationList.add(location)
        paceList.add(averagePace)
    }

    // 운동 시작
    private fun startTracking() {
        if (isRunning) return
        resetTrackingData()
        cadenceData.clear()
        heartRateData.clear()

        isRunning = true
        startTime = System.currentTimeMillis()
        handler.post(updateRunnable)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        )
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(2.0f)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun resetTrackingData() {
        totalDistance = 0.0
        elapsedTime = 0L
        averagePace = 0.0
        speed = 0.0
        heartRateSum = 0
        heartRateCount = 0
        averageHeartRate = 0.0
        heartRate = "--"
        lastLocation = null
        initialStepCount = 0
        lastStepCount = 0
        lastStepTimestamp = 0L
        stepCount = 0

        // 새 운동 시작 시 이전 데이터 초기화
        locationList.clear()
        heartRateData.clear()
        cadenceData.clear()
        paceList.clear()
    }

    private fun stopTracking() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val totalItemUsage = gameViewModel.totalItemUsageCount.value

        if (heartRateCount > 0) {
            averageHeartRate = heartRateSum.toDouble() / heartRateCount
        }

        val averageCadence = calculateAverageCadence()
        Log.d("FinalStats", "Average Cadence: $averageCadence steps/min")
        sensorManager.unregisterListener(this)

        Log.d("Stats",
            "Elapsed Time: ${formatUtils.formatTime(elapsedTime)}, Distance: $totalDistance km, Avg Pace: $averagePace min/km, Avg Heart Rate: ${"%.1f".format(averageHeartRate)} bpm")

        showStats = true
        sendFinalResultToPhone(totalItemUsage)
        createAndSendGpxFile()
    }

    // 최종 결과를 모바일로 전송
    private fun sendFinalResultToPhone(totalItemUsage: Int) {
        val dataMapRequest = PutDataMapRequest.create("/final_result_data").apply {
            dataMap.putDouble("distance", totalDistance)
            dataMap.putLong("time", elapsedTime)
            dataMap.putDouble("averagePace", averagePace)
            dataMap.putDouble("averageHeartRate", averageHeartRate)
            dataMap.putInt("totalItemUsage", totalItemUsage)
        }.asPutDataRequest().setUrgent()

        Log.d("Final Data 전송", "총 아이템 사용 횟수: $totalItemUsage")
        Wearable.getDataClient(this).putDataItem(dataMapRequest)
            .addOnSuccessListener { Log.d("RunningActivity", "Final result data sent successfully") }
            .addOnFailureListener { e -> Log.e("RunningActivity", "Failed to send final result data", e) }
    }

    // GPX 파일 생성 및 전송
    private fun createAndSendGpxFile() {
        val gpxString = createGpxString()
        val gpxBytes = gpxString.toByteArray(Charsets.UTF_8)
        val asset = Asset.createFromBytes(gpxBytes)

        val putDataMapReq = PutDataMapRequest.create("/gpx_data").apply {
            dataMap.putAsset("gpx_file", asset)
            dataMap.putLong("timestamp", System.currentTimeMillis())
        }

        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(putDataReq)
            .addOnSuccessListener { Log.d("RunningActivity", "GPX data sent successfully") }
            .addOnFailureListener { e -> Log.e("RunningActivity", "Failed to send GPX data", e) }
    }

    // 헬퍼 함수: 특정 timestamp 이하의 마지막 센서 값을 찾는다.
    private fun getNearestSensorValue(data: List<Pair<Long, Int>>, targetTime: Long, default: Int): Int {
        var result = default
        for ((time, value) in data) {
            if (time <= targetTime) {
                result = value
            } else {
                break
            }
        }
        return result
    }

    // GPX 파일 생성 (위치 데이터를 기준으로, 각 포인트에 가장 가까운 센서 데이터를 매칭)
    private fun createGpxString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.KOREA)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")

        val gpxBuilder = StringBuilder()
        gpxBuilder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        gpxBuilder.append("<gpx version=\"1.1\" creator=\"RocatRun Wear App\">\n")
        gpxBuilder.append("  <trk>\n")
        gpxBuilder.append("    <name>RocatRun Activity</name>\n")
        gpxBuilder.append("    <trkseg>\n")

        // 각 위치에 대해, 해당 위치의 timestamp와 가장 근접한 심박수와 케이던스 값을 찾는다.
        for (location in locationList) {
            val locTime = location.time
            val hr = getNearestSensorValue(heartRateData, locTime, 0)
            val cad = getNearestSensorValue(cadenceData, locTime, 0)
            // pace는 paceList의 마지막 값(또는 0.0)으로 처리 (필요시 별도 센서 데이터 저장 방식 적용)
            val pace = if (paceList.isNotEmpty()) paceList.last() else 0.0

            gpxBuilder.append("      <trkpt lat=\"${location.latitude}\" lon=\"${location.longitude}\">\n")
            gpxBuilder.append("        <ele>${location.altitude}</ele>\n")
            gpxBuilder.append("        <extensions>\n")
            gpxBuilder.append("          <gpxtpx:TrackPointExtension>\n")
            gpxBuilder.append("            <gpxtpx:hr>$hr</gpxtpx:hr>\n")
            gpxBuilder.append("            <gpxtpx:pace>$pace</gpxtpx:pace>\n")
            gpxBuilder.append("            <gpxtpx:cad>$cad</gpxtpx:cad>\n")
            gpxBuilder.append("          </gpxtpx:TrackPointExtension>\n")
            gpxBuilder.append("        </extensions>\n")
            gpxBuilder.append("        <time>${sdf.format(Date(locTime))}</time>\n")
            gpxBuilder.append("      </trkpt>\n")
        }

        gpxBuilder.append("    </trkseg>\n")
        gpxBuilder.append("  </trk>\n")
        gpxBuilder.append("</gpx>")
        return gpxBuilder.toString()
    }

    @Composable
    fun RunningApp(gameViewModel: GameViewModel, multiUserViewModel: MultiUserViewModel) {
        val activity = LocalContext.current as? RunningActivity
        var isCountdownFinished by remember { mutableStateOf(false) }
        var countdownValue by remember { mutableIntStateOf(5) }

        LaunchedEffect(Unit) {
            while (countdownValue > 0) {
                delay(1000)
                countdownValue -= 1
            }
            isCountdownFinished = true
            activity?.startTrackingRequested = true
        }

        if (isCountdownFinished) {
            WatchAppUI(gameViewModel, multiUserViewModel)
        } else {
            CountdownScreen(countdownValue)
        }
    }

    @Composable
    fun CountdownScreen(countdownValue: Int) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = countdownValue.toString(),
                color = Color.White,
                fontSize = 80.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily(Font(R.font.neodgm))
            )
        }
    }

    @Composable
    fun WatchAppUI(gameViewModel: GameViewModel, multiUserViewModel: MultiUserViewModel) {
        val pagerState = rememberPagerState(pageCount = { 4 })
        if (showStats) {
            ShowStatsScreen(gameViewModel)
        } else {
            HorizontalPager(state = pagerState) { page ->
                when (page) {
                    0 -> CircularLayout(gameViewModel)
                    1 -> ControlButtons { stopTracking() }
                    2 -> Box(modifier = Modifier.fillMaxSize()) {
                        GameScreen(gameViewModel, multiUserViewModel)
                    }
                    3 -> Box(modifier = Modifier.fillMaxSize()) {
                        MultiUserScreen(multiUserViewModel,gameViewModel)
                    }
                }
            }
        }
    }

    @Composable
    fun CircularLayout(gameViewModel: GameViewModel) {
        val itemGaugeValue by gameViewModel.itemGaugeValue.collectAsState()
        val bossGaugeValue by gameViewModel.bossGaugeValue.collectAsState()
        // BossHealthRepository의 최대 체력 구독 (최초 값이 0이라면 기본값 10000 사용)
        val maxBossHealth by BossHealthRepository.maxBossHealth.collectAsState()
        val effectiveMaxBossHealth = if (maxBossHealth == 0) 10000 else maxBossHealth
        val maxGaugeValue = 100

        val itemProgress by animateFloatAsState(
            targetValue = itemGaugeValue.toFloat() / maxGaugeValue,
            animationSpec = tween(durationMillis = 500)
        )
        val bossProgress by animateFloatAsState(
            targetValue = bossGaugeValue.toFloat() / effectiveMaxBossHealth,
            animationSpec = tween(durationMillis = 500)
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val spacing = maxWidth * 0.04f
            CircularItemGauge(itemProgress = itemProgress, bossProgress = bossProgress, Modifier.size(200.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "페이스",
                        color = Color(0xFF00FFCC),
                        fontSize = 12.sp,
                        fontFamily = FontFamily(Font(R.font.neodgm))
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = formatUtils.formatPace(averagePace),
                        color = Color(0xFFFFFFFF),
                        fontSize = 25.sp,
                        fontFamily = FontFamily(Font(R.font.neodgm))
                    )
                }
                Spacer(modifier = Modifier.height(spacing))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = formatUtils.formatTime(elapsedTime),
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily(Font(R.font.neodgm))
                    )
                }
                Spacer(modifier = Modifier.height(spacing * 1f))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "거리",
                            color = Color(0xFF36DBEB),
                            fontSize = 12.sp,
                            fontFamily = FontFamily(Font(R.font.neodgm))
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(color = Color.White, fontSize = 20.sp)) {
                                    append("%.2f".format(totalDistance))
                                }
                                withStyle(style = SpanStyle(color = Color.White, fontSize = 16.sp)) {
                                    append("km")
                                }
                            },
                            fontFamily = FontFamily(Font(R.font.neodgm)),
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "심박수",
                            color = Color(0xFFF20089),
                            fontSize = 12.sp,
                            fontFamily = FontFamily(Font(R.font.neodgm))
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = buildAnnotatedString {
                                withStyle(style = SpanStyle(color = Color.White, fontSize = 20.sp)) {
                                    append(heartRate)
                                }
                                withStyle(style = SpanStyle(color = Color.White, fontSize = 16.sp)) {
                                    append("bpm")
                                }
                            },
                            fontFamily = FontFamily(Font(R.font.neodgm)),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun ControlButtons(stopTracking: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = { startTracking() }) {
                Text("시작", fontFamily = FontFamily(Font(R.font.neodgm)))
            }
            Button(onClick = { stopTracking() }) {
                Text("종료", fontFamily = FontFamily(Font(R.font.neodgm)))
            }
        }
    }

    // 폰에 데이터 전송
    private fun sendDataToPhone(itemUsed: Boolean = false) {
        if (itemUsed) {
            val itemUsedRequest = PutDataMapRequest.create("/use_item").apply {
                dataMap.putBoolean("itemUsed", true)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(this).putDataItem(itemUsedRequest)
                .addOnSuccessListener { Log.d("RunningActivity", "아이템 사용 신호 성공적으로 보냄: $itemUsed") }
                .addOnFailureListener { e -> Log.e("RunningActivity", "아이템 사용 신호 보내지 못하였음", e) }
        } else {
            val dataMapRequest = PutDataMapRequest.create("/running_data").apply {
                dataMap.putDouble("pace", averagePace)
                dataMap.putDouble("distance", totalDistance)
                dataMap.putLong("time", elapsedTime)
                dataMap.putString("heartRate", heartRate)
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest()
            Log.d("데이터 전송 함수", "데이터 형태 - Pace : $averagePace distance : $totalDistance, time : $elapsedTime, heartRate: $heartRate")
            dataMapRequest.setUrgent()
            Wearable.getDataClient(this).putDataItem(dataMapRequest)
                .addOnSuccessListener { Log.d("RunningActivity", "Data sent successfully") }
                .addOnFailureListener { e -> Log.e("RunningActivity", "Failed to send data", e) }
        }
    }

    @Composable
    fun ShowStatsScreen(gameViewModel: GameViewModel) {
        val totalItemUsageCount by gameViewModel.totalItemUsageCount.collectAsState()
        val averageCadence = calculateAverageCadence()
        val statsData = listOf(
            "총 시간: ${formatUtils.formatTime(elapsedTime)}",
            "총 거리: ${"%.2f".format(totalDistance)} km",
            "평균 페이스: ${"%.2f".format(averagePace)} min/km",
            "평균 심박수: ${"%.1f".format(averageHeartRate)} bpm",
            "평균 케이던스: $averageCadence spm",
            "총 발걸음수: $stepCount",
            "총 아이템 사용 횟수 : $totalItemUsageCount"
        )

        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(statsData) { text ->
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )
            }
            item {
                Button(
                    onClick = {
                        showStats = false
                        resetTrackingData()
                    },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Confirm")
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        when (event?.sensor?.type) {
            Sensor.TYPE_HEART_RATE -> {
                val newHeartRate = event.values[0].toInt()
                Log.d("심박수", "심박수: $newHeartRate")
                if (newHeartRate > 0) {
                    heartRate = newHeartRate.toString()
                    heartRateSum += newHeartRate
                    heartRateCount++
                    // 센서 이벤트 발생 시의 현재 시간으로 저장
                    heartRateData.add(Pair(System.currentTimeMillis(), newHeartRate))
                    Log.d("추가", "심박수 추가")
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                // STEP_COUNTER 센서는 부팅 후 누적 걸음 수를 제공함
                val currentSteps = event.values[0].toInt()
                Log.d("StepCounter", "걸음수 : $currentSteps")
                val currentTime = System.currentTimeMillis()
                Log.d("StepCounter", "Received step counter: $currentSteps at $currentTime")
                // 최초 이벤트에서 기준값 저장
                if (initialStepCount == 0) {
                    initialStepCount = currentSteps
                    Log.d("StepCounter", "체크 : $initialStepCount, $currentSteps")
                }
                // 실제 걸음 수는 (현재 센서 값 - 초기 기준값)
                stepCount = currentSteps - initialStepCount
                Log.d("StepCounter", "Total steps: $stepCount, $currentSteps,$initialStepCount")

                // 선택: cadence 계산 (이전 이벤트와의 차이를 사용)
                val stepsDelta = currentSteps - lastStepCount
                val timeDelta = currentTime - lastStepTimestamp
                if (stepsDelta > 0 && timeDelta > 0) {
                    updateCadence(stepsDelta, timeDelta)
                }
                lastStepCount = currentSteps
                lastStepTimestamp = currentTime
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // STEP_DETECTOR는 한 걸음당 1 이벤트를 발생시키므로,
                // 만약 STEP_COUNTER 센서가 없다면 대체로 사용 가능
                stepCount += 1
                Log.d("StepDetector", "Step detected, total steps: $stepCount")
            }
        }
    }

    // updateStepCount는 STEP_DETECTOR의 경우에만 사용
    private fun updateStepCount(stepsDelta: Int) {
        stepCount += stepsDelta
        Log.d("StepCounter", "Total steps (from detector): $stepCount")
    }

    // cadence 계산: 분당 걸음수 = (걸음 증가량) / (시간 간격(분))
    private fun updateCadence(stepsDelta: Int, timeDelta: Long) {
        val cadence = (stepsDelta.toFloat() / (timeDelta / 60000f)).roundToInt()
        cadenceData.add(Pair(System.currentTimeMillis(), cadence))
        Log.d("StepCounter", "Current cadence: $cadence steps/min")
    }

    private fun calculateAverageCadence(): Int {
        return if (cadenceData.isNotEmpty()) {
            cadenceData.map { it.second }.average().roundToInt()
        } else {
            0
        }
    }

    // 알람 설정 (센서 업데이트용)
    private fun scheduleSensorUpdates() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, SensorUpdateReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        alarmManager.setRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 60000,
            60000,
            pendingIntent
        )
        Log.d("AlarmManager", "Alarm scheduled for sensor updates.")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
