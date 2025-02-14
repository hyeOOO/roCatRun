package com.eeos.rocatrun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat.startForeground
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.content.FileProvider
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.eeos.rocatrun.R
import com.eeos.rocatrun.game.GamePlay
import com.eeos.rocatrun.game.GamePlay.ResultData
import com.eeos.rocatrun.game.GamePlay.RunningData
import com.eeos.rocatrun.service.GamePlayService.Companion.gpxFileReceived
import com.eeos.rocatrun.service.GamePlayService.Companion.runningData
import com.eeos.rocatrun.service.MessageHandlerService.Companion.NOTIFICATION_ID
import com.eeos.rocatrun.socket.SocketHandler
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class GamePlayService : Service(), DataClient.OnDataChangedListener {

    companion object {
        private val _runningData = MutableLiveData<RunningData?>()
        val runningData: LiveData<RunningData?> = _runningData

        private val _resultData = MutableLiveData<ResultData?>()
        val resultData: LiveData<ResultData?> = _resultData

        private val _gpxFileReceived = MutableLiveData<Boolean>()
        val gpxFileReceived: LiveData<Boolean> = _gpxFileReceived

        private val _playerResults = MutableLiveData<List<GamePlay.PlayersResultData>>()
        val playerResults: LiveData<List<GamePlay.PlayersResultData>> = _playerResults

        private val _myResult = MutableLiveData<GamePlay.MyResultData?>()
        var myResult: LiveData<GamePlay.MyResultData?> = _myResult

        private val _myRank = MutableLiveData<Int>()
        val myRank: LiveData<Int> = _myRank

        // 모달 상태를 위한 LiveData 추가
        private val _modalState = MutableLiveData<ModalState>()
        val modalState: LiveData<ModalState> = _modalState
    }

    // ModalState sealed class 추가
    sealed class ModalState {
        object None : ModalState()
        object SingleWin : ModalState()
        object SingleLose : ModalState()
        object MultiWin : ModalState()
        object MultiLose : ModalState()
    }

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "GamePlayChannel"

    private lateinit var dataClient: DataClient
    private lateinit var wakeLock: PowerManager.WakeLock

    // 게임 오버 상태를 관리하는 변수 추가
    private var isGameOver by mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // WakeLock 설정
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RoCatRun::GamePlayServiceWakeLock"
        )
        wakeLock.acquire(3 * 60 * 60 * 1000L)

        // Wearable 데이터 클라이언트 초기화
        dataClient = Wearable.getDataClient(this)
        dataClient.addListener(this)  // 리스너 등록

        // Socket 리스너 설정
        setupSocketListeners()
    }

    // 웹소켓 수신
    private fun setupSocketListeners() {

        // 서버에서 playerDataUpdated 응답 받기
        SocketHandler.mSocket.on("playerDataUpdated") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val responseJson = args[0] as JSONObject
                val nickName = responseJson.optString("nickName", "unknown")
                val returnedDistance = responseJson.optDouble("distance", 0.0)
                val itemUseCount = responseJson.optInt("itemUseCount", 0)
                Log.d(
                    "Socket", "On - playerDataUpdated: " +
                            "nickName: $nickName, distance: $returnedDistance, itemUseCount: $itemUseCount"
                )

                // 업데이트된 playersData를 워치에 전송하기 위해 PutDataMapRequest 생성
                val putDataMapRequest = PutDataMapRequest.create("/players_data")
                putDataMapRequest.dataMap.apply {
                    putString("nickName", nickName)
                    putDouble("distance", returnedDistance)
                    putInt("itemUseCount", itemUseCount)
                }
                val request = putDataMapRequest.asPutDataRequest().setUrgent()
                dataClient.putDataItem(request)
                    .addOnSuccessListener { _ ->
                        Log.d("Wear", "플레이어들 데이터 전송 완료")
                    }
                    .addOnFailureListener { exception ->
                        Log.e("Wear", "플레이어들 데이터 전송 실패", exception)
                    }
            }
        }

        // 웹소켓 - 보스체력 이벤트 수신 -> 워치 송신
        SocketHandler.mSocket.on("gameStatusUpdated") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val responseJson = args[0] as JSONObject
                val bossHealth = responseJson.optInt("bossHealth", 10000)

                Log.d(
                    "Socket", "On - gameStatusUpdated: " +
                            "bossHealth: $bossHealth"
                )

                // 워치에 bossHealth 보내기
                val putDataMapRequest = PutDataMapRequest.create("/boss_health")
                putDataMapRequest.dataMap.apply {
                    putInt("bossHealth",bossHealth)
                }
                val request = putDataMapRequest.asPutDataRequest().setUrgent()
                dataClient.putDataItem(request)
                    .addOnSuccessListener { _ ->
                        Log.d("Wear", "보스체력 업데이트 송신")
                    }
                    .addOnFailureListener { exception ->
                        Log.e("Wear", "보스체력 업데이트 실패", exception)
                    }
            }
        }

        // 웹소켓 - 피버시작 이벤트 수신 -> 워치 송신
        SocketHandler.mSocket.on("feverTimeStarted") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val responseJson = args[0] as JSONObject
                val active = responseJson.optBoolean("active", false)
                val duration = responseJson.optInt("duration", 0)

                Log.d(
                    "Socket", "On - feverTimeStarted"
                )

                // 워치에 피버타임 시작 메세지 보내기
                val putDataMapRequest = PutDataMapRequest.create("/fever_start")
                putDataMapRequest.dataMap.apply {
                    putBoolean("feverStart", true)
                    putLong("timestamp", System.currentTimeMillis())

                }
                val request = putDataMapRequest.asPutDataRequest().setUrgent()
                dataClient.putDataItem(request)
                    .addOnSuccessListener { _ ->
                        Log.d("Wear", "피버타임 시작")
                    }
                    .addOnFailureListener { exception ->
                        Log.e("Wear", "피버타임 시작 실패", exception)
                    }
            }
        }

        // 웹소켓 - 피버종료 이벤트 수신 -> 워치 송신
        SocketHandler.mSocket.on("feverTimeEnded") {
            Log.d("Socket", "On - feverTimeEnded")

            // 워치에 피버타임 종료 메세지 보내기
            val putDataMapRequest = PutDataMapRequest.create("/fever_end")
            putDataMapRequest.dataMap.apply {
                putBoolean("feverEnd", true)
                putLong("timestamp", System.currentTimeMillis())
            }
            val request = putDataMapRequest.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
                .addOnSuccessListener { _ ->
                    Log.d("Wear", "피버타임 종료")
                }
                .addOnFailureListener { exception ->
                    Log.e("Wear", "피버타임 종료 실패", exception)
                }
        }

        // 웹소켓 - 게임종료 이벤트 수신 -> 워치 송신
        SocketHandler.mSocket.on("gameOver") {

            Log.d("Socket", "On - gameOver")
            isGameOver = true

            // 워치에 게임 종료 메세지 보내기
            val putDataMapRequest = PutDataMapRequest.create("/game_end")
            putDataMapRequest.dataMap.apply {
                putBoolean("gameEnd", true)
                putLong("timestamp", System.currentTimeMillis())
            }
            val request = putDataMapRequest.asPutDataRequest().setUrgent()
            dataClient.putDataItem(request)
                .addOnSuccessListener { _ ->
                    Log.d("Wear", "게임 종료")
                }
                .addOnFailureListener { exception ->
                    Log.e("Wear", "게임 종료 실패", exception)
                }
        }

        // 플레이어들 결과 공유 데이터 수신
        SocketHandler.mSocket.on("gameResult") { args ->
            if (args.isNotEmpty() && args[0] is JSONObject) {
                val responseJson = args[0] as JSONObject
                // 클리어/페일
                val cleared = responseJson.optBoolean("cleared", false)

                // myResult 파싱
                responseJson.optJSONObject("myResult")?.let { myResultJson ->
                    val result = GamePlay.MyResultData(
                        userId = myResultJson.optString("userId", "unknown"),
                        nickName = myResultJson.optString("nickName", "unknown"),
                        characterImage = myResultJson.optString("characterImage", ""),
                        runningTime = myResultJson.optLong("runningTime", 0),
                        totalDistance = myResultJson.optDouble("totalDistance", 0.0),
                        paceAvg = myResultJson.optDouble("paceAvg", 0.0),
                        heartRateAvg = myResultJson.optDouble("heartRateAvg", 0.0),
                        cadenceAvg = myResultJson.optDouble("cadenceAvg", 0.0),
                        calories = myResultJson.optInt("calories", 0),
                        itemUseCount = myResultJson.optInt("itemUseCount", 0),
                        rewardExp = myResultJson.optInt("rewardExp", 0),
                        rewardCoin = myResultJson.optInt("rewardCoin", 0)
                    )
                    _myResult.postValue(result)
                }

                // playerResults 파싱
                val playerResultsArray = responseJson.optJSONArray("playerResults")
                val newPlayerResults = mutableListOf<GamePlay.PlayersResultData>()

                if (playerResultsArray != null) {
                    for (i in 0 until playerResultsArray.length()) {
                        val playerObj = playerResultsArray.optJSONObject(i)
                        playerObj?.let {
                            val result = GamePlay.PlayersResultData(
                                userId = it.optString("userId", "unknown"),
                                nickname = it.optString("nickname", "unknown"),
                                characterImage = it.optString("characterImage", ""),
                                totalDistance = it.optDouble("totalDistance", 0.0),
                                itemUseCount = it.optInt("itemUseCount", 0),
                                rewardExp = it.optInt("rewardExp", 0),
                                rewardCoin = it.optInt("rewardCoin", 0)
                            )
                            newPlayerResults.add(result)
                        }
                    }

                    // 모달 상태 업데이트
                    _playerResults.value?.let { results ->
                        _modalState.postValue(
                            when {
                                cleared && results.size == 1 -> ModalState.SingleWin
                                cleared -> ModalState.MultiWin
                                results.size == 1 -> ModalState.SingleLose
                                else -> ModalState.MultiLose
                            }
                        )
                    }

                    Log.d(
                        "Socket",
                        "On - gameResult: cleared: $cleared, myResult: $_myResult"
                    )
                }
            }
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        dataClient.removeListener(this)

        // 다른 이벤트 리스너들도 해제
        SocketHandler.mSocket.off("playerDataUpdated")
        SocketHandler.mSocket.off("gameStatusUpdated")
        SocketHandler.mSocket.off("gameResult")
        SocketHandler.mSocket.off("gameOver")
        SocketHandler.mSocket.off("feverTimeStarted")
        SocketHandler.mSocket.off("feverTimeEnded")
    }

     override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                when (dataItem.uri.path) {
                    "/running_data" -> processRunningData(dataItem)
                    "/final_result_data" -> processResultData(dataItem)
                    "/use_item" -> processUseItem(dataItem)
                    "/gpx_data" -> processGpxData(dataItem)
                }
            }
        }
    }

    // 워치에서 실시간 러닝데이터 받아오는 함수
    private fun processRunningData(dataItem: DataItem) {
        // 게임 오버 상태면 데이터 전송하지 않음
        if (isGameOver) {
            Log.d("Wear", "게임 오버 상태 - 러닝 데이터 전송 중단")
            return
        }

        DataMapItem.fromDataItem(dataItem).dataMap.apply {
            _runningData.postValue(RunningData(  // LiveData 업데이트로 변경
                distance = getDouble("distance"),
                time = getLong("time")
            ))
        }

        Log.d("Wear","러닝 데이터 받는중 - $_runningData")

        // 웹소켓으로 전송
        _runningData.value?.let { data ->  // value로 접근하도록 수정
            updateRunDataSocket(data.distance)
        }
    }

    // 워치에서 게임 결과 받아오는 함수
    private fun processResultData(dataItem: DataItem) {
        DataMapItem.fromDataItem(dataItem).dataMap.apply {
            _resultData.postValue(ResultData(
                distance = getDouble("distance"),
                time = getLong("time"),
                averagePace = getDouble("averagePace"),
                averageHeartRate = getDouble("averageHeartRate")
//                averageCadence = getDouble("averageCadence")
            ))
        }

        Log.d("Wear", "게임 결과 데이터 수신 완료! - $_resultData")

        // 웹소켓으로 전송
        _resultData.value?.let { data ->  // value로 접근하도록 수정
            submitRunningResultSocket(
                data.time,
                data.distance,
                data.averagePace,
                data.averageHeartRate,
                0.0
            )
        }
    }

    // 워치에서 아이템 사용여부 받아오는 함수
    private fun processUseItem(dataItem: DataItem) {
        DataMapItem.fromDataItem(dataItem).dataMap.apply {

            // 공격 여부 워치에서 받기
            val itemUsed = getBoolean("itemUsed")
            Log.d("Wear", "아이템 사용 여부 받음: $itemUsed")
        }

        Log.d("Wear","공격")

        // 웹소켓 이벤트 송신 - useItem
        SocketHandler.mSocket.emit("useItem")
    }

    private fun processGpxData(dataItem: DataItem) {
        val asset = DataMapItem.fromDataItem(dataItem).dataMap.getAsset("gpx_file")
        asset?.let {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val response = Wearable.getDataClient(this@GamePlayService).getFdForAsset(it).await()
                    response.inputStream?.use { stream ->
                        val gpxContent = stream.bufferedReader().use { it.readText() }
                        saveGpxFile(gpxContent)
                        _gpxFileReceived.postValue(true)
                    }

                } catch (e: IOException) {
                    Log.e("GameMulti", "Error processing GPX data", e)
                }
            }
        }
    }

    private fun saveGpxFile(content: String) {
        try {
            val file = File(getExternalFilesDir(null), "activity_${System.currentTimeMillis()}.gpx")
            FileOutputStream(file).use { it.write(content.toByteArray()) }
            Log.d("GameMulti", "GPX file saved: ${file.absolutePath}")
        } catch (e: IOException) {
            Log.e("GameMulti", "Error saving GPX file", e)
        }
    }

    // 웹소켓 - 실시간 러닝데이터 송신
    private fun updateRunDataSocket(
        distance: Double)
    {
        // 전송할 JSON 생성: {"runningData": {"distance": 5.2, "runningTime": 123}}
        val runningDataPayload = JSONObject().apply {
            put("distance", distance)
        }
        val runDataJson = JSONObject().apply {
            put("runningData", runningDataPayload)
        }

        // updateRunningData 실시간 러닝 데이터 전송
        SocketHandler.mSocket.emit("updateRunningData", runDataJson)

        Log.d("Socket", "Emit - updateRunningData: $runDataJson")
    }

    // 웹소켓 - 게임 결과 데이터 송신
    private fun submitRunningResultSocket(
        runningTime: Long,
        totalDistance: Double,
        paceAvg: Double,
        heartRateAvg: Double,
        cadenceAvg: Double
    ){
        val runningResultJson = JSONObject().apply {
            put("runningTimeSec", runningTime)
            put("totalDistance", totalDistance)
            put("paceAvg", paceAvg)
            put("heartRateAvg", heartRateAvg)
            put("cadenceAvg", cadenceAvg)
        }

        // 본인 러닝 결과 전송
        SocketHandler.mSocket.emit("submitRunningResult", runningResultJson)
        Log.d("Socket", "Emit - submitRunningResult: $runningResultJson")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent: PendingIntent =
            Intent(this, GamePlay::class.java).let { notificationIntent ->
                PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("로캣런 게임중")
            .setContentText("게임이 실행 중입니다.")
            .setSmallIcon(R.mipmap.ic_rocatrun_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val name = "Game Play Service"
        val descriptionText = "Running game in progress"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
