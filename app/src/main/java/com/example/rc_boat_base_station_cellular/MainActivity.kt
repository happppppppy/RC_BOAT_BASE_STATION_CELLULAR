package com.example.rc_boat_base_station_cellular // Change this to your actual package name

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// --- Data class for Telemetry ---
data class TelemetryData(
    val boatVoltage: String = "-.-- V",
    val boatTacho: String = "---- RPM",
    val phoneBattery: String = "--%",
    val phoneGps: String = "No Fix",
    val phoneSignal: String = "--",
    val phoneNetworkType: String = "Unknown",
    val phoneHeading: String = "---°"
)

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BaseStationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BaseStationScreen()
                }
            }
        }
    }
}

// --- ViewModel ---
class BaseStationViewModel(application: Application) : AndroidViewModel(application) {

    // --- State Flows for UI ---
    private val _mqttStatus = MutableStateFlow("Disconnected")
    val mqttStatus = _mqttStatus.asStateFlow()

    private val _webRtcStatus = MutableStateFlow("Idle")
    val webRtcStatus = _webRtcStatus.asStateFlow()

    private val _telemetryData = MutableStateFlow(TelemetryData())
    val telemetryData = _telemetryData.asStateFlow()

    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    val videoTrack = _videoTrack.asStateFlow()

    private val _isGatewayOnline = MutableStateFlow(false)
    val isGatewayOnline = _isGatewayOnline.asStateFlow()


    // --- MQTT Handling ---
    private val brokerHost = BuildConfig.MQTT_BROKER_HOST
    private val brokerPort = 8883
    private val mqttUsername = BuildConfig.MQTT_USERNAME
    private val mqttPassword = BuildConfig.MQTT_PASSWORD
    private var mqttClient: Mqtt5AsyncClient? = null
    private var commandPublishJob: Job? = null
    private var mqttHeartbeatJob: Job? = null
    private var gatewayHeartbeatMonitorJob: Job? = null
    private val lastGatewayHeartbeatTime = AtomicLong(0)

    // --- Control State ---
    private var throttle = 0f
    private var steering = 0f

    // --- WebRTC ---
    private val webRtcLock = Any()
    private val eglBase = EglBase.create()
    val eglBaseContext: EglBase.Context = eglBase.eglBaseContext
    private var controlDataChannel: DataChannel? = null
    private var telemetryDataChannel: DataChannel? = null
    private var isDataChannelOpen = false
    private val iceCandidateBuffer = mutableListOf<IceCandidate>()


    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(application).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }
    private var peerConnection: PeerConnection? = null

    init {
        connectMqtt()
    }

    fun connectMqtt() {
        _mqttStatus.value = "Connecting..."
        mqttClient = MqttClient.builder()
            .useMqttVersion5()
            .identifier("BaseStation-${UUID.randomUUID()}")
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .sslWithDefaultConfig()
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                viewModelScope.launch {
                    _mqttStatus.value = "Connected"
                    subscribeToTopics()
                    startMqttHeartbeat()
                    startGatewayHeartbeatMonitor()
                }
            }
            .addDisconnectedListener {
                viewModelScope.launch {
                    _mqttStatus.value = "Disconnected"
                    isDataChannelOpen = false
                    commandPublishJob?.cancel()
                    mqttHeartbeatJob?.cancel()
                    gatewayHeartbeatMonitorJob?.cancel()
                    _isGatewayOnline.value = false
                    cleanupWebRTC()
                }
            }
            .buildAsync()

        mqttClient?.connectWith()
            ?.simpleAuth()
            ?.username(mqttUsername)
            ?.password(mqttPassword.toByteArray())
            ?.applySimpleAuth()
            ?.send()
            ?.whenComplete { _, throwable: Throwable? ->
                viewModelScope.launch {
                    if (throwable != null) {
                        _mqttStatus.value = "Failed: ${throwable.message}"
                    }
                }
            }
    }

    private fun updateTelemetry(key: String, payload: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentData = _telemetryData.value
            _telemetryData.value = when (key) {
                "stm32:voltage" -> currentData.copy(boatVoltage = "$payload V")
                "stm32:tacho" -> currentData.copy(boatTacho = "$payload RPM")
                "phone:battery" -> currentData.copy(phoneBattery = "$payload%")
                "phone:signal" -> currentData.copy(phoneSignal = "Level: $payload/4")
                "phone:network_type" -> currentData.copy(phoneNetworkType = payload)
                "phone:gps" -> currentData.copy(phoneGps = payload)
                "phone:compass" -> currentData.copy(phoneHeading = "$payload°")
                else -> currentData
            }
        }
    }

    private fun startMqttHeartbeat() {
        mqttHeartbeatJob?.cancel()
        mqttHeartbeatJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (mqttClient?.state?.isConnected == true) {
                    mqttClient?.publishWith()?.topic("rcboat/heartbeat/base_to_boat")?.payload("thump".toByteArray())?.send()
                }
                delay(500)
            }
        }
    }

    private fun startGatewayHeartbeatMonitor() {
        gatewayHeartbeatMonitorJob?.cancel()
        gatewayHeartbeatMonitorJob = viewModelScope.launch {
            while(true) {
                val timeSinceLastBeat = System.currentTimeMillis() - lastGatewayHeartbeatTime.get()
                _isGatewayOnline.value = timeSinceLastBeat < 2000 // 2 second timeout
                delay(500)
            }
        }
    }

    private fun startCommandPublishing() {
        commandPublishJob?.cancel()
        commandPublishJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val power = (kotlin.math.abs(throttle) * 100).toInt()
                val direction = if (throttle > 0.1) 1 else if (throttle < -0.1) -1 else 0
                val angle = (steering * 45).toInt()

                val motorCommand = "M$power,$direction\n"
                val rudderCommand = "R$angle\n"

                sendDataChannelCommand(motorCommand)
                sendDataChannelCommand(rudderCommand)

                delay(100)
            }
        }
    }

    private fun sendDataChannelCommand(command: String) {
        if (isDataChannelOpen) {
            val buffer = ByteBuffer.wrap(command.toByteArray(StandardCharsets.UTF_8))
            controlDataChannel?.send(DataChannel.Buffer(buffer, false))
        }
    }

    fun updateThrottle(value: Float) { throttle = value }
    fun updateSteering(value: Float) { steering = value }

    private fun subscribeToTopics() {
        // Signaling Topic
        mqttClient?.subscribeWith()
            ?.topicFilter("rcboat/signaling/boat_to_base")
            ?.callback { publish: Mqtt5Publish ->
                if (publish.payload.isPresent) {
                    val message = StandardCharsets.UTF_8.decode(publish.payload.get()).toString()
                    Log.d("WebRTC_Signaling", "Received message: $message")
                    val json = JSONObject(message)
                    synchronized(webRtcLock) {
                        when {
                            json.has("type") && json.getString("type") == "bye" -> {
                                cleanupWebRTC()
                            }
                            json.has("type") && json.getString("type") == "busy" -> {
                                Log.w("WebRTC_Signaling", "Gateway is busy, cleaning up.")
                                cleanupWebRTC()
                            }
                            json.has("sdp") -> {
                                val sdp = json.getString("sdp")
                                val type = SessionDescription.Type.fromCanonicalForm(json.getString("type").lowercase())
                                if (type == SessionDescription.Type.ANSWER) {
                                    Log.d("WebRTC_Signaling", "Received ANSWER, setting remote description.")
                                    peerConnection?.setRemoteDescription(SdpObserverAdapter(), SessionDescription(type, sdp))
                                    iceCandidateBuffer.forEach {
                                        Log.d("WebRTC_Signaling", "Adding buffered ICE candidate.")
                                        peerConnection?.addIceCandidate(it)
                                    }
                                    iceCandidateBuffer.clear()
                                }
                            }
                            json.has("candidate") -> {
                                val candidate = IceCandidate(
                                    json.getString("sdpMid"),
                                    json.getInt("sdpMLineIndex"),
                                    json.getString("candidate")
                                )
                                if (peerConnection?.remoteDescription == null) {
                                    Log.d("WebRTC_Signaling", "Buffering ICE candidate, remote description not set yet.")
                                    iceCandidateBuffer.add(candidate)
                                } else {
                                    peerConnection?.addIceCandidate(candidate)
                                }
                            }
                        }
                    }
                }
            }
            ?.send()

        // Gateway Heartbeat Topic
        mqttClient?.subscribeWith()
            ?.topicFilter("rcboat/heartbeat/boat_to_base")
            ?.callback {
                lastGatewayHeartbeatTime.set(System.currentTimeMillis())
            }
            ?.send()
    }

    private fun sendSignalingMessage(message: JSONObject) {
        Log.d("WebRTC_Signaling", "Sending message: $message")
        if (mqttClient?.state?.isConnected == true) {
            mqttClient?.publishWith()?.topic("rcboat/signaling/base_to_boat")?.payload(message.toString().toByteArray())?.send()
        }
    }

    fun initiateWebRTC() {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(webRtcLock) {
                if (peerConnection != null) {
                    Log.w("WebRTC", "initiateWebRTC called but connection already exists.")
                    return@launch
                }
                _webRtcStatus.value = "Initiating..."

                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("turn:global.turn.twilio.com:3478?transport=udp")
                        .setUsername(BuildConfig.TURN_USERNAME)
                        .setPassword(BuildConfig.TURN_PASSWORD)
                        .createIceServer()
                )
                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

                peerConnection =
                    peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate?) {
                            candidate?.let {
                                val json = JSONObject().apply {
                                    put("candidate", it.sdp)
                                    put("sdpMid", it.sdpMid)
                                    put("sdpMLineIndex", it.sdpMLineIndex)
                                }
                                sendSignalingMessage(json)
                            }
                        }

                        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                            receiver?.track()?.let { track ->
                                if (track is VideoTrack) {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _videoTrack.value = track
                                    }
                                }
                            }
                        }

                        override fun onDataChannel(dataChannel: DataChannel?) {
                            telemetryDataChannel = dataChannel
                            telemetryDataChannel?.registerObserver(object : DataChannel.Observer {
                                override fun onBufferedAmountChange(p0: Long) {}
                                override fun onStateChange() {}
                                override fun onMessage(buffer: DataChannel.Buffer?) {
                                    buffer?.let {
                                        val data = ByteArray(it.data.remaining())
                                        it.data.get(data)
                                        val message = String(data, StandardCharsets.UTF_8)
                                        val parts = message.split(":", limit = 2)
                                        if (parts.size == 2) {
                                            updateTelemetry(parts[0], parts[1])
                                        }
                                    }
                                }
                            })
                        }

                        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                            viewModelScope.launch(Dispatchers.Main) {
                                _webRtcStatus.value = newState?.name ?: "UNKNOWN"
                                if (newState == PeerConnection.PeerConnectionState.FAILED || newState == PeerConnection.PeerConnectionState.CLOSED) {
                                    cleanupWebRTC()
                                }
                            }
                        }

                        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                            Log.d("BaseStation", "ICE Connection State: ${newState?.name}")
                        }

                        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                        override fun onIceConnectionReceivingChange(p0: Boolean) {}
                        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                        override fun onAddStream(p0: MediaStream?) {}
                        override fun onRemoveStream(p0: MediaStream?) {}
                        override fun onRenegotiationNeeded() {}
                    })

                peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
                peerConnection?.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))

                controlDataChannel = peerConnection?.createDataChannel("control", DataChannel.Init())
                controlDataChannel?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(p0: Long) {}
                    override fun onStateChange() {
                        isDataChannelOpen = controlDataChannel?.state() == DataChannel.State.OPEN
                        if (isDataChannelOpen) {
                            startCommandPublishing()
                        }
                    }

                    override fun onMessage(p0: DataChannel.Buffer?) {}
                })


                peerConnection?.createOffer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                sdp?.let {
                                    val json = JSONObject().apply {
                                        put("type", it.type.canonicalForm())
                                        put("sdp", it.description)
                                    }
                                    sendSignalingMessage(json)
                                }
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e("WebRTC_Signaling", "Failed to set local description: $error")
                            }
                        }, sdp)
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRTC_Signaling", "Failed to create offer: $error")
                    }
                }, MediaConstraints())
            }
        }
    }


    private fun cleanupWebRTC() {
        synchronized(webRtcLock) {
            Log.d("WebRTC_Signaling", "Cleaning up WebRTC connection...")
            commandPublishJob?.cancel()
            peerConnection?.close()
            peerConnection = null
            iceCandidateBuffer.clear()
            isDataChannelOpen = false
            viewModelScope.launch(Dispatchers.Main) {
                _videoTrack.value = null
                _webRtcStatus.value = "Idle"
            }
        }
    }

    private fun disconnectMqtt() {
        sendSignalingMessage(JSONObject().put("type", "bye"))
        cleanupWebRTC()
        mqttClient?.disconnect()
    }

    override fun onCleared() {
        disconnectMqtt()
        eglBase.release()
        peerConnectionFactory.dispose()
        super.onCleared()
    }
}

// --- UI: Main Screen ---
@Composable
fun BaseStationScreen(viewModel: BaseStationViewModel = viewModel()) {
    val mqttStatus by viewModel.mqttStatus.collectAsState()
    val webRtcStatus by viewModel.webRtcStatus.collectAsState()
    val telemetryData by viewModel.telemetryData.collectAsState()
    val videoTrack by viewModel.videoTrack.collectAsState()
    val isGatewayOnline by viewModel.isGatewayOnline.collectAsState()
    val isConnected = webRtcStatus == "CONNECTED"

    Box(modifier = Modifier.fillMaxSize()) {
        VideoView(
            videoTrack = videoTrack,
            eglBaseContext = viewModel.eglBaseContext,
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Base Station", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Column(horizontalAlignment = Alignment.End) {
                    Text("MQTT: $mqttStatus", color = if (mqttStatus == "Connected") Color.Green else Color.LightGray, fontSize = 14.sp)
                    Text("WebRTC: $webRtcStatus", color = if (isConnected) Color.Green else Color.LightGray, fontSize = 14.sp)
                    Text("Gateway: ${if(isGatewayOnline) "Online" else "Offline"}", color = if (isGatewayOnline) Color.Green else Color.Red, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.initiateWebRTC() },
                enabled = isGatewayOnline && !isConnected && webRtcStatus == "Idle",
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Connect to Boat")
            }


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ThrottleSlider(onMove = { viewModel.updateThrottle(it) }, enabled = isConnected)
                TelemetryDashboard(data = telemetryData)
                SteeringSlider(onMove = { viewModel.updateSteering(it) }, enabled = isConnected)
            }
        }
    }
}


// --- UI: Video View ---
@Composable
fun VideoView(videoTrack: VideoTrack?, eglBaseContext: EglBase.Context?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val surfaceViewRenderer = remember { SurfaceViewRenderer(context) }

    DisposableEffect(key1 = Unit) {
        if (eglBaseContext != null) {
            surfaceViewRenderer.init(eglBaseContext, null)
            surfaceViewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            surfaceViewRenderer.setEnableHardwareScaler(true)
            surfaceViewRenderer.setZOrderMediaOverlay(true)
        }
        onDispose {
            surfaceViewRenderer.release()
        }
    }

    DisposableEffect(videoTrack) {
        videoTrack?.addSink(surfaceViewRenderer)
        onDispose {
            videoTrack?.removeSink(surfaceViewRenderer)
        }
    }

    if (videoTrack != null) {
        AndroidView({ surfaceViewRenderer }, modifier = modifier)
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Nothing here, just a black background
        }
    }
}

@Composable
fun TelemetryDashboard(data: TelemetryData) {
    Card(
        modifier = Modifier.width(250.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Live Telemetry", style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            TelemetryRow("Boat Voltage", data.boatVoltage)
            TelemetryRow("Boat Tacho", data.boatTacho)
            Divider(modifier = Modifier.padding(vertical = 4.dp), color = Color.Gray)
            TelemetryRow("Phone Battery", data.phoneBattery)
            TelemetryRow("Phone Signal", data.phoneSignal)
            TelemetryRow("Phone Network", data.phoneNetworkType)
            TelemetryRow("Phone Heading", data.phoneHeading)
            TelemetryRow("Phone GPS", data.phoneGps)
        }
    }
}

@Composable
fun ThrottleSlider(modifier: Modifier = Modifier, enabled: Boolean, onMove: (Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    val sliderHeight = 250.dp
    val sliderWidth = 80.dp
    val density = LocalDensity.current

    val trackWidthPx = with(density) { 20.dp.toPx() }
    val thumbHeightPx = with(density) { 40.dp.toPx() }
    val trackCornerRadiusPx = with(density) { 10.dp.toPx() }
    val thumbCornerRadiusPx = with(density) { 5.dp.toPx() }
    val thumbColor = if (enabled) Color.DarkGray else Color.Gray.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .height(sliderHeight)
            .width(sliderWidth)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { },
                    onDragEnd = {
                        sliderPosition = 0f
                        onMove(0f)
                    },
                    onDragCancel = {
                        sliderPosition = 0f
                        onMove(0f)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val newY = sliderPosition + dragAmount.y
                        val maxHeightPx = size.height / 2f
                        sliderPosition = newY.coerceIn(-maxHeightPx, maxHeightPx)
                        onMove(-sliderPosition / maxHeightPx)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                color = Color.LightGray.copy(alpha = 0.5f),
                topLeft = Offset(center.x - trackWidthPx / 2, 0f),
                size = Size(trackWidthPx, size.height),
                cornerRadius = CornerRadius(trackCornerRadiusPx)
            )
            drawRoundRect(
                color = thumbColor,
                topLeft = Offset(center.x - (thumbHeightPx / 2), center.y + sliderPosition - (thumbHeightPx / 2)),
                size = Size(thumbHeightPx, thumbHeightPx),
                cornerRadius = CornerRadius(thumbCornerRadiusPx)
            )
        }
    }
}

@Composable
fun SteeringSlider(modifier: Modifier = Modifier, enabled: Boolean, onMove: (Float) -> Unit) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Steering: ${(sliderPosition * 45).toInt()}°", color = Color.White)
        Slider(
            value = sliderPosition,
            enabled = enabled,
            onValueChange = {
                sliderPosition = it
                onMove(it)
            },
            onValueChangeFinished = {
                sliderPosition = 0f
                onMove(0f)
            },
            valueRange = -1f..1f,
            modifier = modifier.width(200.dp)
        )
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
        Text(value, fontSize = 14.sp, color = Color.White, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
fun BaseStationTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = Color(0xFF3F51B5),
        secondary = Color(0xFF03A9F4),
        tertiary = Color(0xFF009688)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

